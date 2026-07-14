package mihon.desktop.migration

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.desktop.DesktopRuntimeService
import mihon.desktop.task.DesktopTaskScheduler
import mihon.domain.migration.BatchMigrationEvent
import mihon.domain.migration.BatchMigrationOrchestrator
import mihon.domain.migration.BatchMigrationWaitingForUserException
import mihon.domain.task.BackgroundTask
import mihon.domain.task.TaskCheckpoint
import mihon.domain.task.TaskStatus
import java.util.UUID

@Serializable
data class BatchMigrationRequest(val mangaId: Long, val title: String)

@Serializable
data class BatchMigrationOptions(
    val copyChapters: Boolean = true,
    val copyCategories: Boolean = true,
    val copyNotes: Boolean = true,
    val replace: Boolean = true,
)

@Serializable
data class BatchMigrationTargetSelection(
    val sourceId: Long,
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val status: Int = 0,
)

@Serializable
enum class BatchMigrationItemStatus { QUEUED, RUNNING, WAITING_FOR_USER, SUCCESS, ERROR, CANCELLED }

@Serializable
data class BatchMigrationItemState(
    val mangaId: Long,
    val title: String,
    val status: BatchMigrationItemStatus = BatchMigrationItemStatus.QUEUED,
    val error: String? = null,
    val target: BatchMigrationTargetSelection? = null,
    val options: BatchMigrationOptions? = null,
)

@Serializable
data class BatchMigrationQueue(
    val id: String,
    val items: List<BatchMigrationItemState>,
    val checkpoint: Int = 0,
    val paused: Boolean = false,
    val cancelled: Boolean = false,
) {
    val completedCount: Int get() = items.count { it.status in terminalItemStatuses }
    val progress: Float get() = if (items.isEmpty()) 1f else completedCount.toFloat() / items.size
}

class DesktopBatchMigrationController(
    private val scheduler: DesktopTaskScheduler,
    private val executeMigration: suspend (Long, BatchMigrationTargetSelection, BatchMigrationOptions) -> Unit,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val orchestrator: BatchMigrationOrchestrator<BatchMigrationRequest> = BatchMigrationOrchestrator(),
) : DesktopRuntimeService {
    constructor(
        scheduler: DesktopTaskScheduler,
        executeMigration: suspend (Long, BatchMigrationTargetSelection) -> Unit,
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(scheduler, { id, target, _ -> executeMigration(id, target) }, scope, dispatcher)

    private val json = Json { ignoreUnknownKeys = true }
    private val mutableQueues = MutableStateFlow<Map<String, BatchMigrationQueue>>(emptyMap())
    val queues: StateFlow<Map<String, BatchMigrationQueue>> = mutableQueues.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()

    fun submit(requests: List<BatchMigrationRequest>): String {
        require(requests.isNotEmpty()) { "A batch migration queue cannot be empty" }
        val id = "$TASK_PREFIX${UUID.randomUUID()}"
        val queue = BatchMigrationQueue(id, requests.map { BatchMigrationItemState(it.mangaId, it.title) })
        scheduler.register(BackgroundTask(id, id, checkpoint = checkpoint(queue)))
        publish(queue)
        launch(queue.id)
        return id
    }

    fun queue(id: String): BatchMigrationQueue? = mutableQueues.value[id] ?: restore(id)

    fun recover() {
        scheduler.allTasks().filter { it.task.id.startsWith(TASK_PREFIX) }.forEach { stored ->
            var queue = decode(stored.task.checkpoint?.cursor) ?: return@forEach
            if (queue.items.all { it.status in setOf(BatchMigrationItemStatus.SUCCESS, BatchMigrationItemStatus.CANCELLED) }) {
                return@forEach
            }
            if (stored.status == TaskStatus.Running || queue.items.any { it.status == BatchMigrationItemStatus.RUNNING }) {
                queue = queue.copy(
                    items = queue.items.map {
                        if (it.status == BatchMigrationItemStatus.RUNNING) it.copy(status = BatchMigrationItemStatus.QUEUED) else it
                    },
                )
                scheduler.pause(queue.id)
                persist(queue)
            } else {
                publish(queue)
            }
            if (
                !queue.paused &&
                !queue.cancelled &&
                queue.items.any { it.status == BatchMigrationItemStatus.QUEUED } &&
                queue.items.none { it.status == BatchMigrationItemStatus.WAITING_FOR_USER }
            ) {
                launch(queue.id)
            }
        }
    }

    fun selectTarget(id: String, mangaId: Long, target: BatchMigrationTargetSelection, options: BatchMigrationOptions) {
        updateItem(id, mangaId) { it.copy(status = BatchMigrationItemStatus.QUEUED, error = null, target = target, options = options) }
        resume(id)
    }

    fun pause(id: String) {
        jobs.remove(id)?.cancel()
        scheduler.pause(id)
        update(id) { queue ->
            queue.copy(
                paused = true,
                items = queue.items.map {
                    if (it.status == BatchMigrationItemStatus.RUNNING) it.copy(status = BatchMigrationItemStatus.QUEUED) else it
                },
            )
        }
    }

    fun resume(id: String) {
        update(id) { it.copy(paused = false) }
        launch(id)
    }

    fun cancelItem(id: String, mangaId: Long) {
        if (queue(id)?.items?.firstOrNull { it.mangaId == mangaId }?.status == BatchMigrationItemStatus.RUNNING) {
            jobs.remove(id)?.cancel()
            scheduler.pause(id)
        }
        updateItem(id, mangaId) { it.copy(status = BatchMigrationItemStatus.CANCELLED, error = null) }
        launch(id)
    }

    fun cancelAll(id: String) {
        jobs.remove(id)?.cancel()
        scheduler.cancel(id)
        update(id) { queue ->
            queue.copy(
                cancelled = true,
                items = queue.items.map {
                    if (it.status in terminalItemStatuses) it else it.copy(status = BatchMigrationItemStatus.CANCELLED)
                },
            )
        }
    }

    fun retryItem(id: String, mangaId: Long) {
        scheduler.reopen(id)
        updateItem(id, mangaId) { it.copy(status = BatchMigrationItemStatus.QUEUED, error = null) }
        val queue = checkNotNull(queue(id))
        val index = queue.items.indexOfFirst { it.mangaId == mangaId }
        update(id) { it.copy(checkpoint = minOf(it.checkpoint, index), paused = false, cancelled = false) }
        launch(id)
    }

    internal fun markRunningForTest(id: String, mangaId: Long) {
        updateItem(id, mangaId) { it.copy(status = BatchMigrationItemStatus.RUNNING) }
        scheduler.start(id)
    }

    override fun start() = recover()

    override fun stop() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private fun launch(id: String) {
        val queue = queue(id) ?: return
        if (queue.paused || queue.cancelled || jobs[id]?.isActive == true) return
        jobs[id] = scope.launch(dispatcher) { run(id) }
    }

    private suspend fun run(id: String) {
        var queue = queue(id) ?: return
        if (!scheduler.start(id) && scheduler.snapshot(id)?.status == TaskStatus.Cancelled) return
        val requests = queue.items.map { BatchMigrationRequest(it.mangaId, it.title) }
        orchestrator.run(requests, queue.checkpoint) { request ->
            val current = checkNotNull(queue(id))
            val item = current.items.first { it.mangaId == request.mangaId }
            when (item.status) {
                BatchMigrationItemStatus.SUCCESS, BatchMigrationItemStatus.CANCELLED -> Unit
                else -> {
                    val target = item.target ?: run {
                        updateItem(id, item.mangaId) { it.copy(status = BatchMigrationItemStatus.WAITING_FOR_USER) }
                        throw BatchMigrationWaitingForUserException()
                    }
                    val options = item.options ?: BatchMigrationOptions()
                    updateItem(id, item.mangaId) { it.copy(status = BatchMigrationItemStatus.RUNNING, error = null) }
                    executeMigration(item.mangaId, target, options)
                }
            }
        }.collect { event ->
            when (event) {
                is BatchMigrationEvent.Succeeded -> {
                    updateItem(id, event.item.mangaId) { item ->
                        if (item.status == BatchMigrationItemStatus.CANCELLED) item else item.copy(status = BatchMigrationItemStatus.SUCCESS)
                    }
                    update(id) { it.copy(checkpoint = event.index + 1) }
                }
                is BatchMigrationEvent.Failed -> {
                    updateItem(id, event.item.mangaId) { it.copy(status = BatchMigrationItemStatus.ERROR, error = event.message) }
                    update(id) { it.copy(checkpoint = event.index + 1) }
                }
                is BatchMigrationEvent.WaitingForUser -> {
                    updateItem(id, event.item.mangaId) { it.copy(status = BatchMigrationItemStatus.WAITING_FOR_USER) }
                    scheduler.pause(id)
                }
                is BatchMigrationEvent.Completed -> {
                    update(id) { it.copy(checkpoint = event.nextIndex) }
                    if (event.nextIndex >= requests.size) scheduler.complete(id)
                }
            }
        }
        jobs.remove(id)
    }

    private fun updateItem(id: String, mangaId: Long, transform: (BatchMigrationItemState) -> BatchMigrationItemState) {
        update(id) { queue -> queue.copy(items = queue.items.map { if (it.mangaId == mangaId) transform(it) else it }) }
    }

    private fun update(id: String, transform: (BatchMigrationQueue) -> BatchMigrationQueue) {
        val current = checkNotNull(queue(id)) { "Unknown migration queue: $id" }
        persist(transform(current))
    }

    private fun persist(queue: BatchMigrationQueue) {
        scheduler.replaceCheckpoint(queue.id, checkpoint(queue))
        publish(queue)
    }

    private fun publish(queue: BatchMigrationQueue) {
        mutableQueues.value = mutableQueues.value + (queue.id to queue)
    }

    private fun restore(id: String): BatchMigrationQueue? =
        scheduler.snapshot(id)?.task?.checkpoint?.cursor?.let(::decode)?.also(::publish)

    private fun checkpoint(queue: BatchMigrationQueue) = TaskCheckpoint(
        cursor = json.encodeToString(queue),
        completedUnits = queue.completedCount,
        progress = queue.progress,
    )

    private fun decode(value: String?): BatchMigrationQueue? = value?.let { runCatching { json.decodeFromString<BatchMigrationQueue>(it) }.getOrNull() }

    companion object {
        const val TASK_PREFIX = "batch-migration:"
    }
}

private val terminalItemStatuses = setOf(
    BatchMigrationItemStatus.SUCCESS,
    BatchMigrationItemStatus.ERROR,
    BatchMigrationItemStatus.CANCELLED,
)
