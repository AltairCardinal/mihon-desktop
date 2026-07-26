package mihon.desktop.task

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import mihon.domain.error.AppError
import mihon.domain.error.StoredAppError
import mihon.domain.error.toStoredAppError
import mihon.domain.task.BackgroundTask
import mihon.domain.task.BackgroundTaskLifecycle
import mihon.domain.task.TaskCheckpoint
import mihon.domain.task.TaskLifecycleEvent
import mihon.domain.task.TaskLifecycleOutcome
import mihon.domain.task.TaskOccurrence
import mihon.domain.task.TaskStatus
import mihon.desktop.platform.retryTransientAccessDenied
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Serializable
data class StoredTask(
    val task: BackgroundTask,
    val status: TaskStatus = TaskStatus.Pending,
    @Serializable(with = StoredAppErrorCompatSerializer::class)
    val failure: StoredAppError? = null,
    val failedUnits: List<String> = emptyList(),
    val workset: List<Long> = emptyList(),
    val worksetInitialized: Boolean = false,
    val completedUnitIds: Set<Long> = emptySet(),
)

object StoredAppErrorCompatSerializer : KSerializer<StoredAppError?> {
    override val descriptor: SerialDescriptor = StoredAppError.serializer().descriptor

    override fun deserialize(decoder: Decoder): StoredAppError? {
        require(decoder is JsonDecoder)
        return when (val element = decoder.decodeJsonElement()) {
            JsonNull -> null
            is JsonObject -> decoder.json.decodeFromJsonElement(StoredAppError.serializer(), element)
            is JsonPrimitive -> {
                require(element.isString) { "Stored task failure must be a string, object, or null" }
                StoredAppError(type = "Unknown", message = element.content)
            }
            else -> error("Stored task failure must be a string, object, or null")
        }
    }

    override fun serialize(encoder: Encoder, value: StoredAppError?) {
        require(encoder is JsonEncoder)
        val element = value?.let { encoder.json.encodeToJsonElement(StoredAppError.serializer(), it) } ?: JsonNull
        encoder.encodeJsonElement(element)
    }
}

class FileTaskCheckpointStore(
    private val file: Path,
    private val atomicMove: (Path, Path) -> Boolean = ::moveAtomically,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = locks.computeIfAbsent(file.toAbsolutePath().normalize()) { ReentrantLock() }
    private val diagnosticMessages = mutableListOf<String>()

    fun load(): List<StoredTask> = lock.withLock { loadUnlocked() }

    fun <R> transaction(transform: (MutableList<StoredTask>) -> R): R = lock.withLock {
        val tasks = loadUnlocked().toMutableList()
        val result = transform(tasks)
        saveUnlocked(tasks)
        result
    }

    fun update(transform: (MutableList<StoredTask>) -> Unit) = transaction(transform)

    fun diagnostics(): List<String> = lock.withLock { diagnosticMessages.toList() }

    private fun loadUnlocked(): List<StoredTask> {
        if (!Files.exists(file)) return emptyList()
        return try {
            json.decodeFromString(Files.readString(file))
        } catch (error: Exception) {
            val corrupt = file.resolveSibling("${file.fileName}.corrupt-${UUID.randomUUID()}")
            runCatching { Files.move(file, corrupt, StandardCopyOption.REPLACE_EXISTING) }
            diagnosticMessages += "corrupt task store quarantined: ${error.message}"
            emptyList()
        }
    }

    private fun saveUnlocked(tasks: List<StoredTask>) {
        file.parent?.let(Files::createDirectories)
        val temporary = file.resolveSibling("${file.fileName}.${UUID.randomUUID()}.tmp")
        Files.writeString(temporary, json.encodeToString(tasks))
        try {
            replaceWithRetry(temporary)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun replaceWithRetry(temporary: Path) {
        retryTransientAccessDenied {
            if (!atomicMove(temporary, file)) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<Path, ReentrantLock>()

        private fun moveAtomically(source: Path, target: Path): Boolean = try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: AtomicMoveNotSupportedException) {
            false
        }
    }
}

class DesktopTaskScheduler(private val store: FileTaskCheckpointStore) {
    fun <R> transaction(transform: (MutableList<StoredTask>) -> R): R = store.transaction(transform)

    fun register(task: BackgroundTask): StoredTask {
        var result: StoredTask? = null
        store.update { tasks ->
            val existing = tasks.firstOrNull { it.task.idempotencyKey == task.idempotencyKey }
                ?: tasks.firstOrNull { it.task.id == task.id }
            val decision = BackgroundTaskLifecycle.reduce(
                existing?.toOccurrence(),
                TaskLifecycleEvent.Register(task),
            )
            result = when (decision.outcome) {
                TaskLifecycleOutcome.Applied -> {
                    val replacement = if (existing?.status == TaskStatus.Failed) {
                        StoredTask(
                            task = task,
                            workset = existing.workset,
                            worksetInitialized = existing.worksetInitialized,
                            completedUnitIds = existing.completedUnitIds,
                        )
                    } else {
                        StoredTask(task)
                    }
                    tasks.removeAll { it.task.id == task.id && it.status in terminalStatuses }
                    tasks += replacement
                    replacement
                }
                TaskLifecycleOutcome.AlreadyApplied,
                TaskLifecycleOutcome.Rejected,
                -> existing
            }
        }
        return checkNotNull(result)
    }

    fun checkpoint(id: String, checkpoint: TaskCheckpoint): Boolean = lifecycleTransition(
        id,
        TaskLifecycleEvent.Checkpoint(checkpoint),
    ) { current, occurrence ->
        current.copy(task = occurrence.task, status = occurrence.status)
    }

    fun replaceCheckpoint(id: String, checkpoint: TaskCheckpoint): Boolean = transition(id) { current ->
        current.copy(task = current.task.copy(checkpoint = checkpoint))
    }

    fun reopen(id: String): Boolean = transition(id) { current ->
        current.copy(status = TaskStatus.Pending, failure = null, failedUnits = emptyList())
    }

    fun setWorkset(id: String, workset: List<Long>): Boolean = transition(id) { current ->
        if (current.status !in setOf(TaskStatus.Pending, TaskStatus.Running) || current.worksetInitialized) {
            current
        } else {
            current.copy(workset = workset, worksetInitialized = true)
        }
    }

    fun completeUnit(id: String, unitId: Long, checkpoint: TaskCheckpoint): Boolean = lifecycleTransition(
        id,
        TaskLifecycleEvent.Checkpoint(checkpoint),
    ) { current, occurrence ->
        current.copy(
            task = occurrence.task,
            status = occurrence.status,
            completedUnitIds = current.completedUnitIds + unitId,
        )
    }

    fun cancel(id: String): Boolean = transition(id) { current ->
        if (current.status in setOf(TaskStatus.Pending, TaskStatus.Failed)) {
            current.copy(status = TaskStatus.Cancelled)
        } else {
            applyLifecycle(current, TaskLifecycleEvent.Cancel) { stored, occurrence ->
                stored.copy(status = occurrence.status)
            }
        }
    }

    fun cancelRunning(id: String): Boolean = lifecycleTransition(id, TaskLifecycleEvent.Cancel) { current, occurrence ->
        current.copy(status = occurrence.status)
    }

    fun pause(id: String): Boolean = transition(id) { current ->
        if (current.status != TaskStatus.Running) current else current.copy(status = TaskStatus.Pending)
    }

    fun start(id: String): Boolean = lifecycleTransition(id, TaskLifecycleEvent.Start) { current, occurrence ->
        current.copy(
            status = occurrence.status,
            failure = null,
            failedUnits = emptyList(),
        )
    }

    fun complete(id: String): Boolean = lifecycleTransition(id, TaskLifecycleEvent.Complete) { current, occurrence ->
        current.copy(status = occurrence.status)
    }

    fun fail(id: String, error: AppError): Boolean = lifecycleTransition(id, TaskLifecycleEvent.Fail) { current, occurrence ->
        current.copy(
            status = occurrence.status,
            failure = error.toStoredAppError(),
            failedUnits = (error as? AppError.PartialFailure)?.failedUnits?.map { it.unitId }.orEmpty(),
        )
    }

    fun pendingTasks(): List<BackgroundTask> = store.load().filter { it.status in setOf(TaskStatus.Pending, TaskStatus.Running, TaskStatus.Failed) }.map { it.task }
    fun allTasks(): List<StoredTask> = store.load()
    fun snapshot(id: String): StoredTask? = store.load().firstOrNull { it.task.id == id }
    fun isCancelled(id: String): Boolean = snapshot(id)?.status == TaskStatus.Cancelled

    private fun transition(id: String, change: (StoredTask) -> StoredTask): Boolean {
        var changed = false
        store.update { tasks ->
            val index = tasks.indexOfFirst { it.task.id == id }
            if (index >= 0) {
                val next = change(tasks[index])
                changed = next != tasks[index]
                tasks[index] = next
            }
        }
        return changed
    }

    private fun lifecycleTransition(
        id: String,
        event: TaskLifecycleEvent,
        change: (StoredTask, TaskOccurrence) -> StoredTask,
    ): Boolean = transition(id) { current -> applyLifecycle(current, event, change) }

    private fun applyLifecycle(
        current: StoredTask,
        event: TaskLifecycleEvent,
        change: (StoredTask, TaskOccurrence) -> StoredTask,
    ): StoredTask {
        val decision = BackgroundTaskLifecycle.reduce(current.toOccurrence(), event)
        return if (decision.outcome != TaskLifecycleOutcome.Applied) {
            current
        } else {
            change(current, checkNotNull(decision.occurrence))
        }
    }

    private fun StoredTask.toOccurrence() = TaskOccurrence(task = task, status = status)

    private companion object {
        val terminalStatuses = setOf(TaskStatus.Completed, TaskStatus.Failed, TaskStatus.Cancelled)
    }
}
