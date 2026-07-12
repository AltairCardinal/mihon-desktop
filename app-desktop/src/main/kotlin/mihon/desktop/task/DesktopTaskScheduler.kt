package mihon.desktop.task

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.domain.task.BackgroundTask
import mihon.domain.task.TaskCheckpoint
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
private data class StoredTask(val task: BackgroundTask, val status: String = "pending")

class FileTaskCheckpointStore(private val file: Path) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<Pair<BackgroundTask, String>> = if (Files.exists(file)) {
        json.decodeFromString<List<StoredTask>>(Files.readString(file)).map { it.task to it.status }
    } else {
        emptyList()
    }

    fun save(tasks: List<Pair<BackgroundTask, String>>) {
        file.parent?.let(Files::createDirectories)
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(tasks.map { StoredTask(it.first, it.second) }))
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

class DesktopTaskScheduler(private val store: FileTaskCheckpointStore) {
    private val tasks = store.load().associate { it.first.id to it }.toMutableMap()

    fun register(task: BackgroundTask) {
        val existing = tasks[task.id]
        if (existing == null || existing.second == "completed") {
            tasks[task.id] = task to "pending"
            persist()
        }
    }

    fun checkpoint(id: String, checkpoint: TaskCheckpoint) {
        val current = tasks[id] ?: return
        tasks[id] = current.first.copy(checkpoint = checkpoint) to "pending"
        persist()
    }

    fun cancel(id: String): Boolean {
        val current = tasks[id] ?: return false
        tasks[id] = current.first to "cancelled"
        persist()
        return true
    }

    fun complete(id: String) {
        val current = tasks[id] ?: return
        tasks[id] = current.first to "completed"
        persist()
    }

    fun pendingTasks(): List<BackgroundTask> = tasks.values.filter { it.second == "pending" }.map { it.first }

    fun isCancelled(id: String): Boolean = tasks[id]?.second == "cancelled"

    private fun persist() = store.save(tasks.values.toList())
}
