package mihon.desktop.migration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mihon.desktop.task.DesktopTaskScheduler
import mihon.desktop.task.FileTaskCheckpointStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBatchMigrationControllerTest {
    @Test
    fun `queue persists waiting selection options failures and continues other items`() = runTest {
        val file = testFile()
        val executed = mutableListOf<Long>()
        val controller = controller(file) { mangaId, _ ->
            executed += mangaId
            if (mangaId == 2L) error("Source unavailable")
        }
        val queueId = controller.submit(listOf(BatchMigrationRequest(1, "One"), BatchMigrationRequest(2, "Two")))
        advanceUntilIdle()

        assertEquals(BatchMigrationItemStatus.WAITING_FOR_USER, controller.queue(queueId)!!.items[0].status)
        controller.selectTarget(queueId, 1, target("Target one"), BatchMigrationOptions(copyNotes = false))
        advanceUntilIdle()
        assertEquals(BatchMigrationItemStatus.SUCCESS, controller.queue(queueId)!!.items[0].status)
        assertEquals(BatchMigrationItemStatus.WAITING_FOR_USER, controller.queue(queueId)!!.items[1].status)

        controller.selectTarget(queueId, 2, target("Target two"), BatchMigrationOptions(replace = false))
        advanceUntilIdle()
        val failed = controller.queue(queueId)!!.items[1]
        assertEquals(BatchMigrationItemStatus.ERROR, failed.status)
        assertEquals("Source unavailable", failed.error)
        assertEquals(listOf(1L, 2L), executed)

        val restored = controller(file) { _, _ -> }
        restored.recover()
        advanceUntilIdle()
        assertTrue(restored.queues.value.containsKey(queueId))
        assertEquals(false, restored.queue(queueId)!!.items[0].options!!.copyNotes)
        assertEquals(false, restored.queue(queueId)!!.items[1].options!!.replace)
        assertEquals("Source unavailable", restored.queue(queueId)!!.items[1].error)
    }

    @Test
    fun `restart converts running to queued and resumes from checkpoint`() = runTest {
        val file = testFile()
        val first = controller(file) { _, _ -> }
        val queueId = first.submit(listOf(BatchMigrationRequest(7, "Seven")))
        advanceUntilIdle()
        first.selectTarget(queueId, 7, target("Seven target"), BatchMigrationOptions())
        first.markRunningForTest(queueId, 7)

        val executed = mutableListOf<Long>()
        val restored = controller(file) { mangaId, _ -> executed += mangaId }
        restored.recover()
        advanceUntilIdle()

        assertEquals(listOf(7L), executed)
        assertEquals(BatchMigrationItemStatus.SUCCESS, restored.queue(queueId)!!.items.single().status)
    }

    @Test
    fun `pause cancel item cancel all and retry failed are durable`() = runTest {
        val file = testFile()
        var shouldFail = true
        val controller = controller(file) { _, _ -> if (shouldFail) error("offline") }
        val queueId = controller.submit(listOf(BatchMigrationRequest(1, "One"), BatchMigrationRequest(2, "Two")))
        advanceUntilIdle()
        controller.cancelItem(queueId, 1)
        assertEquals(BatchMigrationItemStatus.CANCELLED, controller.queue(queueId)!!.items[0].status)

        controller.selectTarget(queueId, 2, target("Two"), BatchMigrationOptions())
        advanceUntilIdle()
        assertEquals(BatchMigrationItemStatus.ERROR, controller.queue(queueId)!!.items[1].status)
        shouldFail = false
        controller.retryItem(queueId, 2)
        advanceUntilIdle()
        assertEquals(BatchMigrationItemStatus.SUCCESS, controller.queue(queueId)!!.items[1].status)

        val afterRetryRestart = controller(file) { _, _ -> error("completed retry must not execute again") }
        afterRetryRestart.recover()
        advanceUntilIdle()
        assertEquals(BatchMigrationItemStatus.SUCCESS, afterRetryRestart.queue(queueId)!!.items[1].status)

        controller.pause(queueId)
        assertTrue(controller.queue(queueId)!!.paused)
        controller.resume(queueId)
        assertEquals(false, controller.queue(queueId)!!.paused)
        controller.cancelAll(queueId)
        assertTrue(controller.queue(queueId)!!.cancelled)

        val afterCancelRestart = controller(file) { _, _ -> error("cancelled batch must not execute after restart") }
        assertTrue(afterCancelRestart.queue(queueId)!!.cancelled)
        assertTrue(afterCancelRestart.queue(queueId)!!.items.all { it.status in setOf(BatchMigrationItemStatus.SUCCESS, BatchMigrationItemStatus.CANCELLED) })
    }

    private fun TestScope.controller(
        file: java.nio.file.Path,
        execute: suspend (Long, BatchMigrationTargetSelection) -> Unit,
    ) = DesktopBatchMigrationController(
        scheduler = DesktopTaskScheduler(FileTaskCheckpointStore(file)),
        executeMigration = execute,
        scope = this,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun target(title: String) = BatchMigrationTargetSelection(99, "/target", title)

    private fun testFile(): java.nio.file.Path {
        val directory = java.nio.file.Path.of(".test-tmp", "batch-${UUID.randomUUID()}")
        Files.createDirectories(directory)
        return directory.resolve("tasks.json")
    }
}
