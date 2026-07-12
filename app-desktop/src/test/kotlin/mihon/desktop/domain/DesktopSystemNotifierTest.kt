package mihon.desktop.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mihon.domain.task.NotificationEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class DesktopSystemNotifierTest {
    @Test
    fun `falls back to in-app notification when system delivery is unavailable`() = runTest {
        val inApp = DesktopNotificationService()
        val notification = async(start = CoroutineStart.UNDISPATCHED) { inApp.notifications.first() }
        val notifier = DesktopSystemNotifier(system = { false }, fallback = inApp)

        notifier.notify(NotificationEvent.Failure("library", "Update failed", "Retry from Library"))

        assertEquals("Update failed", notification.await().title)
        assertEquals("Retry from Library", notification.await().message)
    }
}
