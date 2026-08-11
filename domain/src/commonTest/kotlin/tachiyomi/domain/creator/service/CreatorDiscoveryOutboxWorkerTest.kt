package tachiyomi.domain.creator.service

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.creator.model.ArchiveDiscovery
import tachiyomi.domain.creator.model.DiscoveryKind
import tachiyomi.domain.creator.model.DiscoveryReadState
import tachiyomi.domain.creator.model.DiscoveryStateVector
import tachiyomi.domain.creator.model.NotificationDeliveryState
import tachiyomi.domain.creator.model.NotificationOutboxItem
import tachiyomi.domain.creator.model.ReviewDisposition
import tachiyomi.domain.creator.model.SourceWorkNaturalKey

class CreatorDiscoveryOutboxWorkerTest {

    @Test
    fun `delivered item is recorded exactly once`() = runTest {
        val store = FakeStore()
        val delivered = mutableListOf<Long>()
        val worker = CreatorDiscoveryOutboxWorker(
            store = store,
            deliveryPort = CreatorDiscoveryNotificationPort { discovery ->
                delivered += discovery.id
                CreatorDiscoveryDeliveryResult.Delivered
            },
            clock = { 1_000L },
        )

        assertEquals(CreatorDiscoveryOutboxResult(1, 1, 0, 0), worker.deliverPending())
        assertEquals(listOf(7L), delivered)
        assertEquals(NotificationDeliveryState.DELIVERED, store.updates.single().state)

        assertEquals(CreatorDiscoveryOutboxResult(0, 0, 0, 0), worker.deliverPending())
        assertEquals(listOf(7L), delivered)
    }

    @Test
    fun `retryable failure persists bounded retry without changing discovery read state`() = runTest {
        val store = FakeStore()
        val worker = CreatorDiscoveryOutboxWorker(
            store = store,
            deliveryPort = CreatorDiscoveryNotificationPort {
                CreatorDiscoveryDeliveryResult.Retry("offline")
            },
            clock = { 2_000L },
        )

        assertEquals(CreatorDiscoveryOutboxResult(1, 0, 1, 0), worker.deliverPending())
        val update = store.updates.single()
        assertEquals(NotificationDeliveryState.FAILED, update.state)
        assertEquals(62_000L, update.nextAttemptAt)
        assertEquals(DiscoveryReadState.UNSEEN, store.discovery.state.readState)
    }

    @Test
    fun `unavailable notifier cancels delivery while persistent feed remains unread`() = runTest {
        val store = FakeStore()
        val worker = CreatorDiscoveryOutboxWorker(
            store = store,
            deliveryPort = CreatorDiscoveryNotificationPort {
                CreatorDiscoveryDeliveryResult.Unavailable("permission denied")
            },
            clock = { 3_000L },
        )

        assertEquals(CreatorDiscoveryOutboxResult(1, 0, 0, 1), worker.deliverPending())
        assertEquals(NotificationDeliveryState.CANCELLED, store.updates.single().state)
        assertEquals(DiscoveryReadState.UNSEEN, store.discovery.state.readState)
    }

    @Test
    fun `due failed item reenters pending before a successful retry`() = runTest {
        val store = FakeStore(initialState = NotificationDeliveryState.FAILED)
        val worker = CreatorDiscoveryOutboxWorker(
            store = store,
            deliveryPort = CreatorDiscoveryNotificationPort { CreatorDiscoveryDeliveryResult.Delivered },
            clock = { 4_000L },
        )

        assertEquals(CreatorDiscoveryOutboxResult(1, 1, 0, 0), worker.deliverPending())
        assertEquals(
            listOf(NotificationDeliveryState.PENDING, NotificationDeliveryState.DELIVERED),
            store.updates.map(DeliveryUpdate::state),
        )
    }

    private class FakeStore(
        private val initialState: NotificationDeliveryState = NotificationDeliveryState.PENDING,
    ) : CreatorDiscoveryOutboxStore {
        val discovery = ArchiveDiscovery(
            id = 7L,
            creatorId = 11L,
            sourceWork = SourceWorkNaturalKey(13L, "/work"),
            title = "New work",
            kind = DiscoveryKind.NEW_WORK_CANDIDATE,
            reason = "verified",
            baselineGeneration = 1L,
            state = DiscoveryStateVector(
                DiscoveryReadState.UNSEEN,
                ReviewDisposition.PENDING,
                NotificationDeliveryState.PENDING,
            ),
            firstDiscoveredAt = 10L,
            lastModifiedAt = 10L,
        )
        private var pending = true
        val updates = mutableListOf<DeliveryUpdate>()

        override suspend fun pending(now: Long, limit: Long): List<NotificationOutboxItem> = if (pending) {
            listOf(
                NotificationOutboxItem(
                    id = 5L,
                    discoveryId = discovery.id,
                    channel = "DESKTOP",
                    idempotencyKey = "discovery-7",
                    attemptCount = 0L,
                    state = initialState,
                    lastError = null,
                    nextAttemptAt = null,
                    createdAt = 10L,
                    lastAttemptAt = null,
                    deliveredAt = null,
                ),
            )
        } else {
            emptyList()
        }

        override suspend fun discovery(id: Long): ArchiveDiscovery? = discovery.takeIf { it.id == id }

        override suspend fun update(update: DeliveryUpdate) {
            pending = false
            updates += update
        }
    }
}
