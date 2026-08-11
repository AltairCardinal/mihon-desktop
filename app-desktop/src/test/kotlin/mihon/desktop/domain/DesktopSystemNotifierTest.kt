package mihon.desktop.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mihon.domain.task.NotificationEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import tachiyomi.domain.creator.model.ArchiveDiscovery
import tachiyomi.domain.creator.model.DiscoveryKind
import tachiyomi.domain.creator.model.DiscoveryReadState
import tachiyomi.domain.creator.model.DiscoveryStateVector
import tachiyomi.domain.creator.model.NotificationDeliveryState
import tachiyomi.domain.creator.model.ReviewDisposition
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.service.CreatorDiscoveryDeliveryResult

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

    @Test
    fun `falls back when system adapter throws`() = runTest {
        val inApp = DesktopNotificationService()
        val notification = async(start = CoroutineStart.UNDISPATCHED) { inApp.notifications.first() }
        val notifier = DesktopSystemNotifier(system = { error("native unavailable") }, fallback = inApp)

        notifier.notify(NotificationEvent.Cancelled("library", "Cancelled"))

        assertEquals("Cancelled", notification.await().title)
    }

    @Test
    fun `author discovery fallback retains stable creator route`() = runTest {
        val inApp = DesktopNotificationService()
        val notification = async(start = CoroutineStart.UNDISPATCHED) { inApp.notifications.first() }
        val port = desktopCreatorDiscoveryNotificationPort(DesktopSystemNotifier({ false }, inApp))

        val result = port.deliver(
            ArchiveDiscovery(
                id = 1L,
                creatorId = 42L,
                sourceWork = SourceWorkNaturalKey(7L, "/new"),
                title = "New work",
                kind = DiscoveryKind.NEW_WORK_CANDIDATE,
                reason = "verified",
                baselineGeneration = 1L,
                state = DiscoveryStateVector(
                    DiscoveryReadState.UNSEEN,
                    ReviewDisposition.PENDING,
                    NotificationDeliveryState.PENDING,
                ),
                firstDiscoveredAt = 1L,
                lastModifiedAt = 1L,
            ),
        )

        assertEquals(CreatorDiscoveryDeliveryResult.Delivered, result)
        assertEquals(42L, notification.await().creatorId)
    }
}
