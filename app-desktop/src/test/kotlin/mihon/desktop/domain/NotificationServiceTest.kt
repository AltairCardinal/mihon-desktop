package mihon.desktop.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class NotificationServiceTest {

    @Test
    fun `library notifications belong only to the banner while other tasks retain notifications`() = runTest {
        val fallback = DesktopNotificationService()
        val received = mutableListOf<DesktopNotification>()
        val collector = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            fallback.notifications.collect { received += it }
        }
        val system = mutableListOf<DesktopNotification>()
        val notifier = DesktopSystemNotifier({ system += it; false }, fallback)
        listOf(
            mihon.domain.task.NotificationEvent.Progress("library-update", "progress", .5f),
            mihon.domain.task.NotificationEvent.Success("library-update", "done", "done"),
            mihon.domain.task.NotificationEvent.Failure("library-update", "failed", "failed"),
            mihon.domain.task.NotificationEvent.Cancelled("library-update", "cancelled"),
        ).forEach(notifier::notify)
        assertEquals(emptyList<DesktopNotification>(), system)
        assertEquals(emptyList<DesktopNotification>(), received)
        notifier.notify(mihon.domain.task.NotificationEvent.Success("backup", "backup", "saved"))
        assertEquals(1, system.size)
        assertEquals(1, received.size)
        collector.cancel()
    }

    @Test
    fun `post emits a notification to the flow`() = runTest {
        val service = DesktopNotificationService()
        val notification = DesktopNotification(
            title = "Library updated",
            message = "3 new chapters found",
        )

        // Start collecting before posting so we don't miss the emission
        val deferred = async { service.notifications.first() }
        launch { service.post(notification) }

        val received = deferred.await()
        assertEquals(notification, received)
    }

    @Test
    fun `DesktopNotification data class equality works`() {
        val n1 = DesktopNotification(title = "A", message = "msg1")
        val n2 = DesktopNotification(title = "A", message = "msg1")
        assertEquals(n1, n2)
    }
}
