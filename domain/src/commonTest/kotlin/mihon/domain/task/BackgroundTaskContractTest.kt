package mihon.domain.task

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackgroundTaskContractTest {
    @Test
    fun `task contract carries constraints checkpoint and idempotency key`() {
        val task = BackgroundTask(
            id = "library-update",
            idempotencyKey = "library-update:42",
            constraints = setOf(TaskConstraint.NetworkConnected),
            checkpoint = TaskCheckpoint(cursor = "42", completedUnits = 3),
        )

        assertEquals("42", task.checkpoint?.cursor)
        assertTrue(TaskConstraint.NetworkConnected in task.constraints)
        assertEquals("library-update:42", task.idempotencyKey)
    }

    @Test
    fun `notification events cover every terminal task state`() {
        val events = listOf(
            NotificationEvent.Progress("id", "Updating", 0.5f),
            NotificationEvent.Success("id", "Updated", "Done"),
            NotificationEvent.Failure("id", "Update failed", "Retry"),
            NotificationEvent.Cancelled("id", "Update cancelled"),
        )

        assertEquals(4, events.size)
    }

    @Test
    fun `task states are explicit instead of stringly typed`() {
        assertEquals(
            setOf(
                TaskStatus.Pending,
                TaskStatus.Running,
                TaskStatus.Completed,
                TaskStatus.Failed,
                TaskStatus.Cancelled,
            ),
            TaskStatus.entries.toSet(),
        )
    }

    @Test
    fun `register creates one pending occurrence and only terminal tasks accept a new key`() {
        val cases = listOf(
            RegisterCase(null, TaskLifecycleOutcome.Applied, TaskStatus.Pending),
            RegisterCase(TaskStatus.Pending, TaskLifecycleOutcome.Rejected, TaskStatus.Pending),
            RegisterCase(TaskStatus.Running, TaskLifecycleOutcome.Rejected, TaskStatus.Running),
            RegisterCase(TaskStatus.Completed, TaskLifecycleOutcome.Applied, TaskStatus.Pending),
            RegisterCase(TaskStatus.Failed, TaskLifecycleOutcome.Applied, TaskStatus.Pending),
            RegisterCase(TaskStatus.Cancelled, TaskLifecycleOutcome.Applied, TaskStatus.Pending),
        )

        cases.forEach { case ->
            val current = case.currentStatus?.let(::occurrence)
            val nextTask = task(key = "library-update:new")
            val decision = BackgroundTaskLifecycle.reduce(current, TaskLifecycleEvent.Register(nextTask))

            assertEquals(case.outcome, decision.outcome, "register from ${case.currentStatus}")
            assertEquals(case.nextStatus, decision.occurrence?.status, "register from ${case.currentStatus}")
            if (case.outcome == TaskLifecycleOutcome.Applied) {
                assertEquals(nextTask, decision.occurrence?.task, "register from ${case.currentStatus}")
            } else {
                assertSame(current, decision.occurrence, "register from ${case.currentStatus}")
            }
        }
    }

    @Test
    fun `register with an existing idempotency key always returns the same occurrence`() {
        TaskStatus.entries.forEach { status ->
            val current = occurrence(status, key = "library-update:same")
            val replacement = task(key = "library-update:same", checkpoint = TaskCheckpoint(cursor = "replacement"))

            val decision = BackgroundTaskLifecycle.reduce(current, TaskLifecycleEvent.Register(replacement))

            assertEquals(TaskLifecycleOutcome.AlreadyApplied, decision.outcome, "duplicate key in $status")
            assertSame(current, decision.occurrence, "duplicate key in $status")
            assertEquals(null, decision.occurrence?.task?.checkpoint, "duplicate key must not rewrite $status")
        }
    }

    @Test
    fun `start has an explicit legal state matrix`() {
        val expected = mapOf(
            TaskStatus.Pending to (TaskLifecycleOutcome.Applied to TaskStatus.Running),
            TaskStatus.Running to (TaskLifecycleOutcome.AlreadyApplied to TaskStatus.Running),
            TaskStatus.Completed to (TaskLifecycleOutcome.Rejected to TaskStatus.Completed),
            TaskStatus.Failed to (TaskLifecycleOutcome.Rejected to TaskStatus.Failed),
            TaskStatus.Cancelled to (TaskLifecycleOutcome.Rejected to TaskStatus.Cancelled),
        )

        assertMatrix(TaskLifecycleEvent.Start, expected)
    }

    @Test
    fun `checkpoint is accepted only while running and preserves the occurrence identity`() {
        val checkpoint = TaskCheckpoint(cursor = "42", completedUnits = 3, progress = 0.5f)
        val expected = TaskStatus.entries.associateWith { status ->
            if (status == TaskStatus.Running) {
                TaskLifecycleOutcome.Applied to TaskStatus.Running
            } else {
                TaskLifecycleOutcome.Rejected to status
            }
        }

        TaskStatus.entries.forEach { status ->
            val current = occurrence(status)
            val decision = BackgroundTaskLifecycle.reduce(current, TaskLifecycleEvent.Checkpoint(checkpoint))
            val (outcome, nextStatus) = expected.getValue(status)

            assertEquals(outcome, decision.outcome, "checkpoint from $status")
            assertEquals(nextStatus, decision.occurrence?.status, "checkpoint from $status")
            assertEquals(
                if (status == TaskStatus.Running) checkpoint else null,
                decision.occurrence?.task?.checkpoint,
                "checkpoint from $status",
            )
            assertEquals(current.task.idempotencyKey, decision.occurrence?.task?.idempotencyKey)
        }
    }

    @Test
    fun `repeating the same checkpoint is idempotent`() {
        val checkpoint = TaskCheckpoint(cursor = "42", completedUnits = 3)
        val current = occurrence(TaskStatus.Running, checkpoint = checkpoint)

        val decision = BackgroundTaskLifecycle.reduce(current, TaskLifecycleEvent.Checkpoint(checkpoint))

        assertEquals(TaskLifecycleOutcome.AlreadyApplied, decision.outcome)
        assertSame(current, decision.occurrence)
    }

    @Test
    fun `complete fail and cancel have explicit legal state matrices`() {
        assertMatrix(
            TaskLifecycleEvent.Complete,
            mapOf(
                TaskStatus.Pending to (TaskLifecycleOutcome.Rejected to TaskStatus.Pending),
                TaskStatus.Running to (TaskLifecycleOutcome.Applied to TaskStatus.Completed),
                TaskStatus.Completed to (TaskLifecycleOutcome.Rejected to TaskStatus.Completed),
                TaskStatus.Failed to (TaskLifecycleOutcome.Rejected to TaskStatus.Failed),
                TaskStatus.Cancelled to (TaskLifecycleOutcome.Rejected to TaskStatus.Cancelled),
            ),
        )
        assertMatrix(
            TaskLifecycleEvent.Fail,
            mapOf(
                TaskStatus.Pending to (TaskLifecycleOutcome.Rejected to TaskStatus.Pending),
                TaskStatus.Running to (TaskLifecycleOutcome.Applied to TaskStatus.Failed),
                TaskStatus.Completed to (TaskLifecycleOutcome.Rejected to TaskStatus.Completed),
                TaskStatus.Failed to (TaskLifecycleOutcome.Rejected to TaskStatus.Failed),
                TaskStatus.Cancelled to (TaskLifecycleOutcome.Rejected to TaskStatus.Cancelled),
            ),
        )
        assertMatrix(
            TaskLifecycleEvent.Cancel,
            mapOf(
                TaskStatus.Pending to (TaskLifecycleOutcome.Rejected to TaskStatus.Pending),
                TaskStatus.Running to (TaskLifecycleOutcome.Applied to TaskStatus.Cancelled),
                TaskStatus.Completed to (TaskLifecycleOutcome.Rejected to TaskStatus.Completed),
                TaskStatus.Failed to (TaskLifecycleOutcome.Rejected to TaskStatus.Failed),
                TaskStatus.Cancelled to (TaskLifecycleOutcome.Rejected to TaskStatus.Cancelled),
            ),
        )
    }

    @Test
    fun `one terminal result cannot be repeated or rewritten`() {
        val terminalStatuses = listOf(TaskStatus.Completed, TaskStatus.Failed, TaskStatus.Cancelled)
        val events = listOf(
            TaskLifecycleEvent.Start,
            TaskLifecycleEvent.Checkpoint(TaskCheckpoint(cursor = "late")),
            TaskLifecycleEvent.Complete,
            TaskLifecycleEvent.Fail,
            TaskLifecycleEvent.Cancel,
        )

        terminalStatuses.forEach { status ->
            events.forEach { event ->
                val current = occurrence(status)
                val decision = BackgroundTaskLifecycle.reduce(current, event)

                assertEquals(TaskLifecycleOutcome.Rejected, decision.outcome, "$event from $status")
                assertSame(current, decision.occurrence, "$event from $status")
                assertEquals(TaskLifecycleRejection.InvalidTransition, decision.rejection, "$event from $status")
            }
        }
    }

    @Test
    fun `events other than register require an occurrence`() {
        val events = listOf(
            TaskLifecycleEvent.Start,
            TaskLifecycleEvent.Checkpoint(TaskCheckpoint(cursor = "missing")),
            TaskLifecycleEvent.Complete,
            TaskLifecycleEvent.Fail,
            TaskLifecycleEvent.Cancel,
        )

        events.forEach { event ->
            val decision = BackgroundTaskLifecycle.reduce(null, event)

            assertEquals(TaskLifecycleOutcome.Rejected, decision.outcome, event.toString())
            assertEquals(null, decision.occurrence, event.toString())
            assertEquals(TaskLifecycleRejection.MissingOccurrence, decision.rejection, event.toString())
        }
    }

    private fun assertMatrix(
        event: TaskLifecycleEvent,
        expected: Map<TaskStatus, Pair<TaskLifecycleOutcome, TaskStatus>>,
    ) {
        TaskStatus.entries.forEach { status ->
            val current = occurrence(status)
            val decision = BackgroundTaskLifecycle.reduce(current, event)
            val (outcome, nextStatus) = expected.getValue(status)

            assertEquals(outcome, decision.outcome, "$event from $status")
            assertEquals(nextStatus, decision.occurrence?.status, "$event from $status")
            if (outcome != TaskLifecycleOutcome.Applied) {
                assertSame(current, decision.occurrence, "$event from $status")
            }
        }
    }

    private fun occurrence(
        status: TaskStatus,
        key: String = "library-update:old",
        checkpoint: TaskCheckpoint? = null,
    ) = TaskOccurrence(task(key, checkpoint), status)

    private fun task(
        key: String,
        checkpoint: TaskCheckpoint? = null,
    ) = BackgroundTask(
        id = "library-update",
        idempotencyKey = key,
        constraints = setOf(TaskConstraint.NetworkConnected),
        checkpoint = checkpoint,
    )

    private data class RegisterCase(
        val currentStatus: TaskStatus?,
        val outcome: TaskLifecycleOutcome,
        val nextStatus: TaskStatus,
    )
}
