package mihon.desktop.migration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
    fun `target accepted while original waiting event is publishing starts once after that worker exits`() = runTest {
        for (stopAfterSelection in listOf(false, true)) {
            var executions = 0
            var selected = false
            val controller = DesktopBatchMigrationController(
                DesktopTaskScheduler(FileTaskCheckpointStore(testFile())),
                { _, _, _ -> executions++ },
                this,
                StandardTestDispatcher(testScheduler),
            )
            val observer = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
                controller.queues.collect { queues ->
                    val waiting = queues.values.singleOrNull()?.takeIf {
                        it.items.single().status == BatchMigrationItemStatus.WAITING_FOR_USER
                    }
                    if (!selected && waiting != null) {
                        selected = true
                        controller.selectTarget(waiting.id, 1, target("Target"), BatchMigrationOptions())
                        if (stopAfterSelection) controller.stop()
                    }
                }
            }
            try {
                val id = controller.submit(listOf(BatchMigrationRequest(1, "One")))
                advanceUntilIdle()
                assertTrue(selected)
                val expected = if (stopAfterSelection) 0 else 1
                assertEquals(expected, executions, "Accepted work continues once, but never after runtime stop")
                if (!stopAfterSelection) {
                    assertEquals(BatchMigrationItemStatus.SUCCESS, controller.queue(id)!!.items.single().status)
                }
                controller.awaitStopped()
            } finally {
                observer.cancel()
                controller.stop()
                controller.awaitStopped()
            }
        }
    }

    @Test
    fun `successful migration acknowledges only after original checkpoint is durable`() = runTest {
        val scheduler = DesktopTaskScheduler(FileTaskCheckpointStore(testFile()))
        val acknowledgements = mutableListOf<Long>()
        lateinit var queueId: String
        val controller = DesktopBatchMigrationController(
            scheduler,
            { _, _, _ -> },
            this,
            StandardTestDispatcher(testScheduler),
            onCommittedCheckpoint = { id, _ ->
                assertTrue(requireNotNull(scheduler.snapshot(queueId)?.task?.checkpoint?.cursor).contains("SUCCESS"))
                acknowledgements += id
            },
        )
        queueId = controller.submit(listOf(BatchMigrationRequest(1, "One")))
        advanceUntilIdle()
        controller.selectTarget(queueId, 1, target("Target"), BatchMigrationOptions())
        advanceUntilIdle()
        assertEquals(
            listOf(1L),
            acknowledgements,
            "SQL receipt must remain until this exact task checkpoint is accepted",
        )
    }

    @Test
    fun `checkpoint write refusal leaves visible retry instead of a running phantom`() = runTest {
        val file = testFile()
        val failures = mutableListOf<Throwable>()
        val store = FileTaskCheckpointStore(file, atomicMove = { from, to ->
            if (java.nio.file.Files.readString(
                    from,
                ).contains("SUCCESS")
            ) {
                throw java.io.IOException("Checkpoint refused")
            }
            java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            true
        })
        val scheduler = DesktopTaskScheduler(store)
        val separateScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() +
                StandardTestDispatcher(testScheduler) +
                kotlinx.coroutines.CoroutineExceptionHandler { _, e -> failures += e },
        )
        val controller =
            DesktopBatchMigrationController(scheduler, { _, _, _ ->
            }, separateScope, StandardTestDispatcher(testScheduler))
        try {
            val id = controller.submit(listOf(BatchMigrationRequest(1, "One")))
            advanceUntilIdle()
            controller.selectTarget(id, 1, target("Target"), BatchMigrationOptions())
            advanceUntilIdle()
            assertEquals(
                BatchMigrationItemStatus.ERROR,
                controller.queue(id)!!.items.single().status,
                "Failed checkpoint must be visible and retain original confirmation for retry",
            )
            assertTrue(failures.isEmpty(), "Storage refusal must not escape the owning job")
            assertTrue(!requireNotNull(scheduler.snapshot(id)?.task?.checkpoint?.cursor).contains("SUCCESS"))
        } finally {
            controller.close()
        }
    }

    @Test
    fun `resume waits for cancelled original migration before another worker can start`() = runTest {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var active = 0
        var maximum = 0
        val controller = DesktopBatchMigrationController(
            DesktopTaskScheduler(FileTaskCheckpointStore(testFile())),
            { _, _, _ ->
                active++
                maximum = maxOf(maximum, active)
                started.complete(Unit)
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                } finally {
                    active--
                }
            },
            this,
            StandardTestDispatcher(testScheduler),
        )
        val id = controller.submit(listOf(BatchMigrationRequest(1, "One")))
        advanceUntilIdle()
        controller.selectTarget(id, 1, target("Target"), BatchMigrationOptions())
        runCurrent()
        assertTrue(started.isCompleted)
        try {
            controller.pause(id)
            controller.resume(id)
            runCurrent()
            assertEquals(1, maximum, "Cancelled worker still owns its finite file and SQL session")
        } finally {
            release.complete(Unit)
            advanceUntilIdle()
            controller.stop()
        }
    }

    @Test
    fun `runtime stop awaits noncancellable original migration before database can close`() = runTest {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val controller = DesktopBatchMigrationController(
            DesktopTaskScheduler(FileTaskCheckpointStore(testFile())),
            { _, _, _ ->
                started.complete(Unit)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
            },
            this,
            StandardTestDispatcher(testScheduler),
        )
        val id = controller.submit(listOf(BatchMigrationRequest(1, "One")))
        advanceUntilIdle()
        controller.selectTarget(id, 1, target("Target"), BatchMigrationOptions())
        runCurrent()
        assertTrue(started.isCompleted)
        controller.stop()
        val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val waiter = launch {
            controller.awaitStopped()
            closed.complete(Unit)
        }
        try {
            runCurrent()
            assertTrue(
                !closed.isCompleted,
                "Runtime must retain the original worker until finite SQL and file cleanup returns",
            )
        } finally {
            release.complete(Unit)
            advanceUntilIdle()
            waiter.join()
        }
        assertTrue(closed.isCompleted)
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
        assertTrue(
            afterCancelRestart.queue(queueId)!!.items.all {
                it.status in
                    setOf(BatchMigrationItemStatus.SUCCESS, BatchMigrationItemStatus.CANCELLED)
            },
        )
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
