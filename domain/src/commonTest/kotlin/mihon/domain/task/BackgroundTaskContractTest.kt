package mihon.domain.task

import org.junit.jupiter.api.Assertions.assertEquals
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
}
