package mihon.desktop.task

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.domain.error.AppError
import mihon.domain.task.BackgroundTask
import mihon.domain.task.TaskCheckpoint
import mihon.domain.task.TaskStatus
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
    val failure: String? = null,
    val failedUnits: List<String> = emptyList(),
)

class FileTaskCheckpointStore(
    private val file: Path,
    private val atomicMove: (Path, Path) -> Boolean = ::moveAtomically,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = locks.computeIfAbsent(file.toAbsolutePath().normalize()) { ReentrantLock() }
    private val diagnosticMessages = mutableListOf<String>()

    fun load(): List<StoredTask> = lock.withLock { loadUnlocked() }

    fun update(transform: (MutableList<StoredTask>) -> Unit) = lock.withLock {
        val tasks = loadUnlocked().toMutableList()
        transform(tasks)
        saveUnlocked(tasks)
    }

    fun diagnostics(): List<String> = lock.withLock { diagnosticMessages.toList() }

    private fun loadUnlocked(): List<StoredTask> {
        if (!Files.exists(file)) return emptyList()
        return try {
            json.decodeFromString(Files.readString(file))
        } catch (error: Exception) {
            val corrupt = file.resolveSibling("${file.fileName}.corrupt-${System.currentTimeMillis()}")
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
            if (!atomicMove(temporary, file)) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
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
    fun register(task: BackgroundTask): StoredTask {
        var result: StoredTask? = null
        store.update { tasks ->
            val existing = tasks.firstOrNull { it.task.idempotencyKey == task.idempotencyKey }
            result = existing ?: StoredTask(task).also { replacement ->
                tasks.removeAll { it.task.id == task.id && it.status in terminalStatuses }
                tasks += replacement
            }
        }
        return checkNotNull(result)
    }

    fun checkpoint(id: String, checkpoint: TaskCheckpoint): Boolean = transition(id) { current ->
        if (current.status !in setOf(TaskStatus.Pending, TaskStatus.Running)) current else current.copy(
            task = current.task.copy(checkpoint = checkpoint),
            status = TaskStatus.Running,
        )
    }

    fun cancel(id: String): Boolean = transition(id) { current ->
        if (current.status !in setOf(TaskStatus.Pending, TaskStatus.Running, TaskStatus.Failed)) current else current.copy(status = TaskStatus.Cancelled)
    }

    fun start(id: String): Boolean = transition(id) { current ->
        if (current.status !in setOf(TaskStatus.Pending, TaskStatus.Failed)) current else current.copy(
            status = TaskStatus.Running,
            failure = null,
        )
    }

    fun complete(id: String): Boolean = transition(id) { current ->
        if (current.status !in setOf(TaskStatus.Pending, TaskStatus.Running)) current else current.copy(status = TaskStatus.Completed)
    }

    fun fail(id: String, error: AppError): Boolean = transition(id) { current ->
        if (current.status !in setOf(TaskStatus.Pending, TaskStatus.Running)) current else current.copy(
            status = TaskStatus.Failed,
            failure = error::class.simpleName,
            failedUnits = (error as? AppError.PartialFailure)?.failedUnits?.map { it.unitId }.orEmpty(),
        )
    }

    fun pendingTasks(): List<BackgroundTask> = store.load().filter { it.status in setOf(TaskStatus.Pending, TaskStatus.Running, TaskStatus.Failed) }.map { it.task }
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

    private companion object {
        val terminalStatuses = setOf(TaskStatus.Completed, TaskStatus.Failed, TaskStatus.Cancelled)
    }
}
