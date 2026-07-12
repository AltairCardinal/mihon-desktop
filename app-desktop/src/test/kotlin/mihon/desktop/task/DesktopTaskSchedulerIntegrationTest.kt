package mihon.desktop.task

import kotlinx.coroutines.test.runTest
import mihon.domain.task.BackgroundTask
import mihon.domain.task.TaskCheckpoint
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class DesktopTaskSchedulerIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `new scheduler restores unfinished checkpoint without repeating completed unit`() = runTest {
        val store = FileTaskCheckpointStore(directory.resolve("tasks.json"))
        val first = DesktopTaskScheduler(store)
        first.register(BackgroundTask("library", "library:daily"))
        first.checkpoint("library", TaskCheckpoint(cursor = "manga-2", completedUnits = 1))

        val restored = DesktopTaskScheduler(store)
        restored.register(BackgroundTask("library", "library:daily"))

        assertEquals("manga-2", restored.pendingTasks().single().checkpoint?.cursor)
        assertEquals(1, restored.pendingTasks().single().checkpoint?.completedUnits)
    }

    @Test
    fun `cancel persists and completed task is not restored`() = runTest {
        val file = directory.resolve("tasks.json")
        val scheduler = DesktopTaskScheduler(FileTaskCheckpointStore(file))
        scheduler.register(BackgroundTask("cancelled", "cancelled:key"))
        assertTrue(scheduler.cancel("cancelled"))
        scheduler.register(BackgroundTask("done", "done:key"))
        scheduler.complete("done")

        val restored = DesktopTaskScheduler(FileTaskCheckpointStore(file))
        assertTrue(restored.isCancelled("cancelled"))
        assertFalse(restored.pendingTasks().any { it.id == "done" })
    }
}
