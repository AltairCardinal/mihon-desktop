package mihon.desktop.task

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import mihon.domain.task.BackgroundTask
import mihon.domain.task.TaskCheckpoint
import mihon.domain.task.TaskStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DesktopTaskSchedulerIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `checkpoint and cancellation obey legal terminal transitions`() {
        val scheduler = scheduler()
        scheduler.register(task("one", "same"))
        scheduler.complete("one")

        assertFalse(scheduler.cancel("one"))
        assertFalse(scheduler.checkpoint("one", TaskCheckpoint("late", 2)))
        scheduler.register(task("again", "same"))
        assertTrue(scheduler.pendingTasks().isEmpty())
        assertEquals(TaskStatus.Completed, scheduler.snapshot("one")?.status)
    }

    @Test
    fun `idempotency key deduplicates pending registrations`() {
        val scheduler = scheduler()
        scheduler.register(task("first", "daily"))
        scheduler.register(task("second", "daily"))

        assertEquals(listOf("first"), scheduler.pendingTasks().map { it.id })
    }

    @Test
    fun `invalid progress and checkpoint are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TaskCheckpoint("x", -1, 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { TaskCheckpoint("x", 1, 1.1f) }
    }

    @Test
    fun `corrupt store is quarantined and startup remains available`() {
        val file = directory.resolve("tasks.json")
        Files.writeString(file, "{truncated")

        val store = FileTaskCheckpointStore(file)

        assertTrue(store.load().isEmpty())
        assertTrue(Files.list(directory).use { files -> files.anyMatch { it.fileName.toString().contains("corrupt") } })
        assertTrue(store.diagnostics().single().contains("corrupt"))
    }

    @Test
    fun `concurrent scheduler instances do not lose writers`() = runTest {
        val file = directory.resolve("tasks.json")
        (1..20).map { index ->
            async { DesktopTaskScheduler(FileTaskCheckpointStore(file)).register(task("id-$index", "key-$index")) }
        }.awaitAll()

        assertEquals(20, DesktopTaskScheduler(FileTaskCheckpointStore(file)).pendingTasks().size)
    }

    @Test
    fun `atomic move fallback replaces store and ignores stale temp`() {
        val file = directory.resolve("tasks.json")
        Files.writeString(directory.resolve("tasks.json.stale.tmp"), "bad")
        val scheduler = DesktopTaskScheduler(FileTaskCheckpointStore(file, atomicMove = { _, _ -> false }))

        scheduler.register(task("one", "one"))

        assertEquals(listOf("one"), DesktopTaskScheduler(FileTaskCheckpointStore(file)).pendingTasks().map { it.id })
    }

    private fun scheduler() = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("tasks.json")))
    private fun task(id: String, key: String) = BackgroundTask(id, key)
}
