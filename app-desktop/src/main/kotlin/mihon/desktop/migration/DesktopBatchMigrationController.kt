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
import kotlinx.coroutines.isActive
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
import tachiyomi.i18n.MR
import java.util.UUID

@Serializable
data class BatchMigrationRequest(val mangaId: Long, val title: String)

@Serializable
data class BatchMigrationOptions(
    val copyChapters: Boolean = true,
    val copyCategories: Boolean = true,
    val copyNotes: Boolean = true,
    val replace: Boolean = true,
    val copyCustomCover: Boolean = false,
    val removeDownloads: Boolean = false,
    val accepted: mihon.desktop.domain.AcceptedMigration? = null,
    val checkpointOwner: String? = null,
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
    val cleanupPending: Boolean = false,
)

@Serializable
data class BatchMigrationQueue(
    val id: String,
    val items: List<BatchMigrationItemState>,
    val checkpoint: Int = 0,
    val paused: Boolean = false,
    val cancelled: Boolean = false,
    val defaultOptions: BatchMigrationOptions = BatchMigrationOptions(),
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
    private val onCommittedCheckpoint: suspend (Long, BatchMigrationOptions) -> Unit = { _, _ -> },
    private val onInterruptedMigration: suspend (Long, BatchMigrationOptions) -> Unit = { _, _ -> },
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
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val jobLock = Any()
    private val stoppingJobs = mutableSetOf<Job>()

    fun submit(
        requests: List<BatchMigrationRequest>,
        defaultOptions: BatchMigrationOptions = BatchMigrationOptions(),
    ): String {
        require(requests.isNotEmpty()) { "A batch migration queue cannot be empty" }
        val id = "$TASK_PREFIX${UUID.randomUUID()}"
        val queue = BatchMigrationQueue(
            id = id,
            items = requests.map { BatchMigrationItemState(it.mangaId, it.title) },
            defaultOptions = defaultOptions,
        )
        scheduler.register(BackgroundTask(id, id, checkpoint = checkpoint(queue)))
        publish(queue)
        launch(queue.id)
        return id
    }

    fun queue(id: String): BatchMigrationQueue? = mutableQueues.value[id] ?: restore(id)

    fun recover() {
        scheduler.allTasks().filter { it.task.id.startsWith(TASK_PREFIX) }.forEach { stored ->
            var queue = decode(stored.task.checkpoint?.cursor) ?: return@forEach
            if (queue.items.all {
                    it.status in
                        setOf(BatchMigrationItemStatus.SUCCESS, BatchMigrationItemStatus.CANCELLED)
                }
            ) {
                return@forEach
            }
            if (stored.status == TaskStatus.Running ||
                queue.items.any { it.status == BatchMigrationItemStatus.RUNNING }
            ) {
                queue = queue.copy(
                    items = queue.items.map {
                        if (it.status ==
                            BatchMigrationItemStatus.RUNNING
                        ) {
                            it.copy(status = BatchMigrationItemStatus.QUEUED)
                        } else {
                            it
                        }
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
        updateItem(id, mangaId) {
            it.copy(status = BatchMigrationItemStatus.QUEUED, error = null, target = target, options = options)
        }
        resume(id)
    }

    fun pause(id: String) {
        jobs[id]?.cancel()
        scheduler.pause(id)
        update(id) { queue ->
            queue.copy(
                paused = true,
                items = queue.items.map {
                    if (it.status ==
                        BatchMigrationItemStatus.RUNNING
                    ) {
                        it.copy(status = BatchMigrationItemStatus.QUEUED)
                    } else {
                        it
                    }
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
            jobs[id]?.cancel()
            scheduler.pause(id)
        }
        updateItem(id, mangaId) { it.copy(status = BatchMigrationItemStatus.CANCELLED, error = null) }
        launch(id)
    }

    fun cancelAll(id: String) {
        jobs[id]?.cancel()
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

    /** Finishes only the original accepted operation, including on a cancelled task. */
    fun retryCleanup(id: String, mangaId: Long) {
        val item = queue(id)?.items?.singleOrNull { it.mangaId == mangaId } ?: return
        if (!item.cleanupPending || item.options?.accepted == null) return
        val originalOptions = item.options
        synchronized(jobLock) {
            val previous = jobs[id]
            if (previous?.isActive == true && !previous.isCancelled) return
            val next = scope.launch(dispatcher, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                previous?.join()
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        onInterruptedMigration(mangaId, originalOptions)
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    val message = MR.strings.desktop_migration_recovery_pending.localized()
                    try {
                        updateItem(id, mangaId) { it.copy(error = message, cleanupPending = true) }
                    } catch (
                        _: Exception,
                    ) { /* The original receipt and pending cursor remain authoritative. */ }
                } finally {
                    jobs.remove(id, kotlinx.coroutines.currentCoroutineContext()[Job])
                }
            }
            jobs[id] = next
            next.start()
        }
    }

    internal fun markRunningForTest(id: String, mangaId: Long) {
        updateItem(id, mangaId) { it.copy(status = BatchMigrationItemStatus.RUNNING) }
        scheduler.start(id)
    }

    override fun start() = recover()

    override fun stop() {
        val original = synchronized(jobLock) {
            jobs.values.toList().also { pending ->
                pending.filter(stoppingJobs::add).forEach { job ->
                    job.invokeOnCompletion { synchronized(jobLock) { stoppingJobs.remove(job) } }
                }
            }
        }
        original.forEach(Job::cancel)
    }

    override suspend fun awaitStopped() {
        val original = synchronized(jobLock) { (jobs.values + stoppingJobs).distinct() }
        original.forEach { it.join() }
        synchronized(jobLock) { stoppingJobs.removeAll(original.toSet()) }
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private fun launch(id: String) {
        val queue = queue(id) ?: return
        if (queue.paused || queue.cancelled) return
        synchronized(jobLock) {
            val previous = jobs[id]
            if (previous?.isActive == true && !previous.isCancelled) return
            val next = scope.launch(dispatcher, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                previous?.join()
                try {
                    run(id)
                } catch (
                    error: kotlinx.coroutines.CancellationException,
                ) {
                    throw error
                } catch (error: Exception) {
                    val current = queue(id) ?: return@launch
                    val failed = current.copy(
                        items = current.items.map { item ->
                            if (item.status ==
                                BatchMigrationItemStatus.RUNNING
                            ) {
                                item.copy(
                                    status = BatchMigrationItemStatus.ERROR,
                                    error = MR.strings.desktop_migration_checkpoint_failed.localized(),
                                )
                            } else {
                                item
                            }
                        },
                    )
                    try {
                        persist(failed)
                    } catch (_: Exception) {
                        publish(failed)
                    }
                } finally {
                    if (jobs.remove(id, kotlinx.coroutines.currentCoroutineContext()[Job])) {
                        val next = queue(id)
                        if (kotlinx.coroutines.currentCoroutineContext().isActive &&
                            next != null && !next.paused && !next.cancelled &&
                            next.items.none { it.status == BatchMigrationItemStatus.WAITING_FOR_USER } &&
                            next.items.getOrNull(next.checkpoint)?.status == BatchMigrationItemStatus.QUEUED
                        ) {
                            launch(id)
                        }
                    }
                }
            }
            jobs[id] = next
            next.start()
        }
    }

    fun attachAccepted(id: String, mangaId: Long, accepted: mihon.desktop.domain.AcceptedMigration) {
        check(accepted.checkpointOwner == id)
        val current = checkNotNull(queue(id))
        check(!current.paused && !current.cancelled)
        updateItem(id, mangaId) { item ->
            check(item.status == BatchMigrationItemStatus.RUNNING)
            item.copy(
                options = (item.options ?: current.defaultOptions).copy(accepted = accepted, checkpointOwner = id),
            )
        }
    }

    fun clearAccepted(id: String, mangaId: Long, operationId: String) {
        updateItem(id, mangaId) { item ->
            if (item.options?.accepted?.operationId ==
                operationId
            ) {
                item.copy(
                    status = if (item.status ==
                        BatchMigrationItemStatus.CANCELLED
                    ) {
                        item.status
                    } else {
                        BatchMigrationItemStatus.WAITING_FOR_USER
                    },
                    options = item.options.copy(accepted = null),
                    target = null,
                    error = null,
                    cleanupPending = false,
                )
            } else {
                item
            }
        }
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
                        throw BatchMigrationWaitingForUserException()
                    }
                    val options = (item.options ?: current.defaultOptions).copy(checkpointOwner = id)
                    updateItem(id, item.mangaId) {
                        it.copy(status = BatchMigrationItemStatus.RUNNING, error = null, options = options)
                    }
                    try {
                        executeMigration(item.mangaId, target, options)
                    } catch (
                        error: kotlinx.coroutines.CancellationException,
                    ) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            val original =
                                queue(id)?.items?.firstOrNull { it.mangaId == item.mangaId }?.options ?: options
                            try {
                                onInterruptedMigration(item.mangaId, original)
                            } catch (cleanup: Exception) {
                                error.addSuppressed(cleanup)
                                val feedbackMessage = MR.strings.desktop_migration_recovery_pending.localized()
                                try {
                                    updateItem(id, item.mangaId) {
                                        it.copy(
                                            error = feedbackMessage,
                                            cleanupPending = true,
                                        )
                                    }
                                } catch (feedback: Exception) {
                                    error.addSuppressed(feedback)
                                    queue(id)?.let { originalQueue ->
                                        publish(
                                            originalQueue.copy(
                                                items = originalQueue.items.map { currentItem ->
                                                    if (currentItem.mangaId == item.mangaId) {
                                                        currentItem.copy(error = feedbackMessage, cleanupPending = true)
                                                    } else {
                                                        currentItem
                                                    }
                                                },
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                        throw error
                    }
                }
            }
        }.collect { event ->
            when (event) {
                is BatchMigrationEvent.Succeeded -> {
                    update(id) { current ->
                        current.copy(
                            checkpoint = event.index + 1,
                            items = current.items.map { item ->
                                if (item.mangaId == event.item.mangaId &&
                                    item.status != BatchMigrationItemStatus.CANCELLED
                                ) {
                                    item.copy(status = BatchMigrationItemStatus.SUCCESS, error = null)
                                } else {
                                    item
                                }
                            },
                        )
                    }
                    val item = requireNotNull(queue(id)).items.first { it.mangaId == event.item.mangaId }
                    if (item.status ==
                        BatchMigrationItemStatus.SUCCESS
                    ) {
                        onCommittedCheckpoint(
                            item.mangaId,
                            item.options ?: requireNotNull(queue(id)).defaultOptions,
                        )
                    }
                }
                is BatchMigrationEvent.Failed -> {
                    updateItem(id, event.item.mangaId) {
                        it.copy(status = BatchMigrationItemStatus.ERROR, error = event.message)
                    }
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
    }

    private fun updateItem(id: String, mangaId: Long, transform: (BatchMigrationItemState) -> BatchMigrationItemState) {
        update(id) { queue -> queue.copy(items = queue.items.map { if (it.mangaId == mangaId) transform(it) else it }) }
    }

    /** Records a committed original item without changing a cancelled or paused task's lifecycle. */
    fun recordCommittedReceipt(receipt: mihon.domain.migration.MigrationReceipt) {
        val command = receipt.request
        val owner = requireNotNull(command.checkpointOwner)
        check(receipt.committed && receipt.filesComplete)
        val accepted = scheduler.transaction { tasks ->
            val index = tasks.indexOfFirst { it.task.id == owner && it.task.idempotencyKey == owner }
            check(index >= 0) { "Original migration task no longer exists" }
            val stored = tasks[index]
            val original = requireNotNull(decode(stored.task.checkpoint?.cursor))
            val itemIndex = original.items.indexOfFirst { it.mangaId == command.sourceMangaId }
            check(original.id == owner && itemIndex >= 0)
            val item = original.items[itemIndex]
            val capture = requireNotNull(item.options?.accepted)
            check(
                capture.operationId == command.operationId && capture.checkpointOwner == owner &&
                    capture.source.source == command.sourceId && capture.source.url == command.sourceUrl &&
                    item.target?.sourceId == command.targetSourceId && item.target.url == command.targetUrl,
            ) {
                "Original migration confirmation was replaced"
            }
            val completedItems = original.items.mapIndexed { i, current ->
                if (i ==
                    itemIndex
                ) {
                    current.copy(status = BatchMigrationItemStatus.SUCCESS, error = null, cleanupPending = false)
                } else {
                    current
                }
            }
            val completedPrefix = completedItems.takeWhile { it.status in terminalItemStatuses }.size
            val completed = original.copy(
                items = completedItems,
                checkpoint = maxOf(original.checkpoint, completedPrefix),
            )
            tasks[index] = stored.copy(task = stored.task.copy(checkpoint = checkpoint(completed)))
            completed
        }
        publish(accepted)
    }

    private fun update(id: String, transform: (BatchMigrationQueue) -> BatchMigrationQueue) {
        val current = checkNotNull(queue(id)) { "Unknown migration queue: $id" }
        persist(transform(current))
    }

    private fun persist(queue: BatchMigrationQueue) {
        val original = requireNotNull(scheduler.snapshot(queue.id)) { "Original migration task no longer exists" }
        check(original.task.idempotencyKey == queue.id) { "Original migration task was replaced" }
        val expected = checkpoint(queue)
        scheduler.replaceCheckpoint(queue.id, expected)
        val persisted = requireNotNull(scheduler.snapshot(queue.id))
        check(persisted.task.idempotencyKey == queue.id && persisted.task.checkpoint == expected) {
            "Original migration checkpoint was refused"
        }
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

    private fun decode(value: String?): BatchMigrationQueue? = value?.let {
        runCatching { json.decodeFromString<BatchMigrationQueue>(it) }.getOrNull()
    }

    companion object {
        const val TASK_PREFIX = "batch-migration:"

        fun checkpointAccepts(
            scheduler: DesktopTaskScheduler,
            receipt: mihon.domain.migration.MigrationReceipt,
        ): Boolean {
            val command = receipt.request
            val owner = command.checkpointOwner ?: return false
            val stored = scheduler.snapshot(owner) ?: return false
            if (stored.task.idempotencyKey != owner) return false
            val queue = stored.task.checkpoint?.cursor?.let {
                runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<BatchMigrationQueue>(it) }.getOrNull()
            } ?: return false
            val item = queue.items.singleOrNull { it.mangaId == command.sourceMangaId } ?: return false
            return receipt.committed && receipt.filesComplete && queue.id == owner &&
                item.status == BatchMigrationItemStatus.SUCCESS &&
                item.options?.accepted?.operationId == command.operationId &&
                item.options?.checkpointOwner == owner && item.target?.sourceId == command.targetSourceId &&
                item.target.url == command.targetUrl
        }
    }
}

private val terminalItemStatuses = setOf(
    BatchMigrationItemStatus.SUCCESS,
    BatchMigrationItemStatus.ERROR,
    BatchMigrationItemStatus.CANCELLED,
)
