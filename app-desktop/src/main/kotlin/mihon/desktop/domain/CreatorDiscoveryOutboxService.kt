package mihon.desktop.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mihon.desktop.DesktopRuntimeService
import tachiyomi.domain.creator.service.CreatorDiscoveryDeliveryResult
import tachiyomi.domain.creator.service.CreatorDiscoveryNotificationPort
import tachiyomi.domain.creator.service.CreatorDiscoveryOutboxWorker

/** Runtime owner for durable author-discovery notification delivery. */
class CreatorDiscoveryOutboxService(
    private val worker: CreatorDiscoveryOutboxWorker,
    private val scope: CoroutineScope,
    private val pollIntervalMillis: Long = 60_000L,
) : DesktopRuntimeService {
    private var job: Job? = null

    override fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                try {
                    worker.deliverPending()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The durable outbox remains pending and the next poll retries it.
                }
                delay(pollIntervalMillis)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }
}

fun desktopCreatorDiscoveryNotificationPort(
    notifier: DesktopSystemNotifier,
): CreatorDiscoveryNotificationPort = CreatorDiscoveryNotificationPort { discovery ->
    val posted = notifier.post(
        DesktopNotification(
            title = "New author work",
            message = discovery.title,
            creatorId = discovery.creatorId,
        ),
    )
    if (posted) CreatorDiscoveryDeliveryResult.Delivered else CreatorDiscoveryDeliveryResult.Unavailable(
        "notification unavailable",
    )
}
