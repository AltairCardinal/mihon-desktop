package mihon.desktop.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class NotificationServiceTest {

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
