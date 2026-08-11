package tachiyomi.domain.creator.service

import tachiyomi.domain.creator.model.ArchiveDiscovery
import tachiyomi.domain.creator.model.NotificationDeliveryState
import tachiyomi.domain.creator.model.NotificationOutboxItem
import tachiyomi.domain.creator.repository.CreatorArchiveRepository

fun interface CreatorDiscoveryNotificationPort {
    suspend fun deliver(discovery: ArchiveDiscovery): CreatorDiscoveryDeliveryResult
}

sealed interface CreatorDiscoveryDeliveryResult {
    data object Delivered : CreatorDiscoveryDeliveryResult

    data class Retry(val error: String) : CreatorDiscoveryDeliveryResult

    data class Unavailable(val error: String) : CreatorDiscoveryDeliveryResult
}

data class DeliveryUpdate(
    val outboxId: Long,
    val state: NotificationDeliveryState,
    val error: String?,
    val nextAttemptAt: Long?,
    val occurredAt: Long,
)

interface CreatorDiscoveryOutboxStore {
    suspend fun pending(now: Long, limit: Long): List<NotificationOutboxItem>

    suspend fun discovery(id: Long): ArchiveDiscovery?

    suspend fun update(update: DeliveryUpdate)
}

class RepositoryCreatorDiscoveryOutboxStore(
    private val repository: CreatorArchiveRepository,
) : CreatorDiscoveryOutboxStore {
    override suspend fun pending(now: Long, limit: Long) = repository.getPendingNotificationOutbox(now, limit)

    override suspend fun discovery(id: Long) = repository.getDiscovery(id)

    override suspend fun update(update: DeliveryUpdate) {
        repository.updateNotificationDelivery(
            outboxId = update.outboxId,
            state = update.state,
            error = update.error,
            nextAttemptAt = update.nextAttemptAt,
            occurredAt = update.occurredAt,
        )
    }
}

data class CreatorDiscoveryOutboxResult(
    val attempted: Int,
    val delivered: Int,
    val retrying: Int,
    val unavailable: Int,
)

/** Delivers committed discovery events without treating notification delivery as read state. */
class CreatorDiscoveryOutboxWorker(
    private val store: CreatorDiscoveryOutboxStore,
    private val deliveryPort: CreatorDiscoveryNotificationPort,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val batchSize: Long = 50L,
) {
    suspend fun deliverPending(): CreatorDiscoveryOutboxResult {
        val now = clock()
        var delivered = 0
        var retrying = 0
        var unavailable = 0
        val pending = store.pending(now, batchSize)
        pending.forEach { item ->
            if (item.state == NotificationDeliveryState.FAILED) {
                store.update(DeliveryUpdate(item.id, NotificationDeliveryState.PENDING, null, null, now))
            }
            val discovery = store.discovery(item.discoveryId)
            val result = if (discovery == null) {
                CreatorDiscoveryDeliveryResult.Unavailable("discovery target no longer exists")
            } else {
                runCatching { deliveryPort.deliver(discovery) }
                    .getOrElse { CreatorDiscoveryDeliveryResult.Retry(it.message ?: "notification delivery failed") }
            }
            val update = when (result) {
                CreatorDiscoveryDeliveryResult.Delivered -> {
                    delivered += 1
                    DeliveryUpdate(item.id, NotificationDeliveryState.DELIVERED, null, null, now)
                }
                is CreatorDiscoveryDeliveryResult.Retry -> {
                    retrying += 1
                    DeliveryUpdate(
                        item.id,
                        NotificationDeliveryState.FAILED,
                        result.error,
                        now + retryDelayMillis(item.attemptCount),
                        now,
                    )
                }
                is CreatorDiscoveryDeliveryResult.Unavailable -> {
                    unavailable += 1
                    DeliveryUpdate(item.id, NotificationDeliveryState.CANCELLED, result.error, null, now)
                }
            }
            store.update(update)
        }
        return CreatorDiscoveryOutboxResult(pending.size, delivered, retrying, unavailable)
    }

    private fun retryDelayMillis(attemptCount: Long): Long {
        val shift = attemptCount.coerceIn(0L, 5L).toInt()
        return (MIN_RETRY_MILLIS shl shift).coerceAtMost(MAX_RETRY_MILLIS)
    }

    private companion object {
        const val MIN_RETRY_MILLIS = 60_000L
        const val MAX_RETRY_MILLIS = 30 * 60_000L
    }
}
