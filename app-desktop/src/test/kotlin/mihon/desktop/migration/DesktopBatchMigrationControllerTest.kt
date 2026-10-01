package mihon.desktop.migration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mihon.desktop.DesktopAppRuntime
import mihon.desktop.DesktopRuntimeService
import mihon.desktop.task.DesktopTaskScheduler
import mihon.desktop.task.FileTaskCheckpointStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBatchMigrationControllerTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `runtime close and join waits for migration checkpoint without joining shared scope`() = runTest {
        val enteredWrite = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val moves = AtomicInteger()
        val file = directory.resolve("background-tasks.json")
        val store = FileTaskCheckpointStore(file) { source, target ->
            if (moves.incrementAndGet() == 2) {
                enteredWrite.countDown()
                check(releaseWrite.await(5, TimeUnit.SECONDS)) { "Checkpoint gate was not released" }
            }
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        }
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val runtimeScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = DesktopBatchMigrationController(
            DesktopTaskScheduler(store),
            { _, _: BatchMigrationTargetSelection -> },
            workerScope,
        )
        val runtime = migrationRuntime(controller, runtimeScope)
        try {
            runtime.start()
            advanceUntilIdle()
            val id = controller.submit(listOf(BatchMigrationRequest(1, "One")))
            withContext(Dispatchers.IO) { assertTrue(enteredWrite.await(5, TimeUnit.SECONDS)) }
            val closing = async { runtime.closeAndJoin() }
            runCurrent()
            assertFalse(closing.isCompleted, "Runtime shutdown must await the actual migration file write")
            releaseWrite.countDown()
            closing.await()
            assertTrue(workerScope.coroutineContext[Job]!!.isActive, "Only owned workers should be joined")
            assertTrue(FileTaskCheckpointStore(file).load().any { it.task.id == id })
        } finally {
            releaseWrite.countDown()
            runtime.closeAndJoin()
            workerScope.cancel()
            workerScope.coroutineContext[Job]!!.join()
        }
    }

    @Test
    fun `runtime shutdown tracks paused workers and their same queue replacements`() = runTest {
        val workerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runtimeScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val entered = List(3) { CompletableDeferred<Unit>() }
        val stopping = List(3) { CompletableDeferred<Unit>() }
        val release = List(3) { CompletableDeferred<Unit>() }
        var invocation = 0
        val controller = DesktopBatchMigrationController(
            DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("replacement-tasks.json"))),
            { _: Long, _: BatchMigrationTargetSelection ->
                val index = invocation++
                entered[index].complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        stopping[index].complete(Unit)
                        release[index].await()
                    }
                }
            },
            workerScope,
            StandardTestDispatcher(testScheduler),
        )
        val runtime = migrationRuntime(controller, runtimeScope)
        try {
            runtime.start()
            runCurrent()
            val id = controller.submit(listOf(BatchMigrationRequest(1, "One")))
            runCurrent()
            controller.selectTarget(id, 1, target("One"), BatchMigrationOptions())
            runCurrent()
            assertTrue(entered[0].isCompleted)
            controller.pause(id)
            runCurrent()
            controller.resume(id)
            runCurrent()
            assertTrue(entered[1].isCompleted)
            controller.pause(id)
            runCurrent()
            controller.resume(id)
            runCurrent()
            assertTrue(entered[2].isCompleted)
            release[0].complete(Unit)
            runCurrent()
            val closing = async { runtime.closeAndJoin() }
            runCurrent()
            assertTrue(stopping[2].isCompleted, "An old completion must not forget its same-ID replacement")
            release[2].complete(Unit)
            runCurrent()
            assertFalse(closing.isCompleted, "Shutdown must also await the worker detached by pause")
            release[1].complete(Unit)
            closing.await()
            assertTrue(workerScope.coroutineContext[Job]!!.isActive)
        } finally {
            release.forEach { it.complete(Unit) }
            runtime.closeAndJoin()
            workerScope.cancel()
            workerScope.coroutineContext[Job]!!.join()
        }
    }

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

    @Test
    fun `cancelling a paused queue persists a desktop cancelled terminal`() = runTest {
        val file = testFile()
        val controller = controller(file) { _, _ -> error("waiting queue must not execute") }
        val queueId = controller.submit(listOf(BatchMigrationRequest(1, "One")))
        advanceUntilIdle()
        assertEquals(BatchMigrationItemStatus.WAITING_FOR_USER, controller.queue(queueId)!!.items.single().status)

        controller.cancelAll(queueId)

        val persisted = DesktopTaskScheduler(FileTaskCheckpointStore(file))
        assertEquals(mihon.domain.task.TaskStatus.Cancelled, persisted.snapshot(queueId)?.status)
        assertTrue(persisted.pendingTasks().none { it.id == queueId })
        val restored = controller(file) { _, _ -> error("cancelled queue must not execute after restart") }
        restored.recover()
        advanceUntilIdle()
        assertTrue(restored.queue(queueId)!!.cancelled)
    }

    private fun migrationRuntime(controller: DesktopBatchMigrationController, scope: CoroutineScope): DesktopAppRuntime {
        val idle = object : DesktopRuntimeService {
            override fun start() = Unit
            override fun stop() = Unit
        }
        return DesktopAppRuntime(idle, idle, idle, batchMigrationController = controller, startupCleanup = {}, scope = scope)
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
