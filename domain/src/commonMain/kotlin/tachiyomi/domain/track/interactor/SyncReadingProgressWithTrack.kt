package tachiyomi.domain.track.interactor

import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.DelayedTrackerSyncItem
import tachiyomi.domain.track.service.DelayedTrackerSyncPersistence
import tachiyomi.domain.track.service.DelayedTrackerSyncQueue
import tachiyomi.domain.track.service.DelayedTrackerSyncReport
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackerProviderResult
import tachiyomi.domain.track.service.TrackerProviderService
import tachiyomi.domain.track.service.TrackerProviderSession
import tachiyomi.domain.track.service.TrackerServiceRegistry

data class TrackerSyncRequest(
    val eventId: String,
    val mangaId: Long,
    val chapterNumber: Double,
    val trackerId: Long? = null,
    val attempt: Int = 0,
) {
    val idempotencyKey: String get() = "$eventId:${trackerId ?: "all"}"
}

fun interface TrackerSyncRetryScheduler {
    suspend fun schedule(request: TrackerSyncRequest)
}

fun interface ReadingProgressTrackSync {
    suspend fun sync(request: TrackerSyncRequest)
}

/** Consumes one durable queue row; [DelayedTrackerSyncItem.trackId] is the track row ID, not the tracker service ID. */
fun interface ReadingProgressTrackRetryConsumer {
    suspend fun drain(item: DelayedTrackerSyncItem): DelayedTrackerSyncReport
}

/** Shared equivalent of Android's chapter-completion tracker update policy. */
class SyncReadingProgressWithTrack(
    private val repository: TrackRepository,
    private val registry: TrackerServiceRegistry,
    private val retryScheduler: TrackerSyncRetryScheduler,
) : ReadingProgressTrackSync, ReadingProgressTrackRetryConsumer {
    override suspend fun sync(request: TrackerSyncRequest) {
        val durablePersistence = retryScheduler as? DelayedTrackerSyncPersistence
        val persistence = object : DelayedTrackerSyncPersistence {
            override suspend fun getItems() =
                durablePersistence?.getItems().orEmpty()

            override suspend fun upsertMax(item: DelayedTrackerSyncItem): DelayedTrackerSyncItem {
                if (durablePersistence != null) {
                    val current = durablePersistence.getItems().firstOrNull { it.trackId == item.trackId }
                    val attempt = if (current?.eventId == request.eventId) request.attempt + 1 else request.attempt
                    return durablePersistence.upsertMax(
                        item.copy(eventId = request.eventId, attempt = attempt),
                    )
                }
                retryScheduler.schedule(request.copy(trackerId = item.trackerId, attempt = request.attempt + 1))
                return item
            }

            override suspend fun removeUpTo(trackId: Long, lastChapterRead: Double) =
                durablePersistence?.removeUpTo(trackId, lastChapterRead) ?: false
        }
        queue(persistence).sync(
            repository.getTracksByMangaId(request.mangaId)
                .filter { request.trackerId == null || it.trackerId == request.trackerId },
            request.chapterNumber,
        )
    }

    override suspend fun drain(item: DelayedTrackerSyncItem): DelayedTrackerSyncReport {
        val durablePersistence = requireNotNull(retryScheduler as? DelayedTrackerSyncPersistence) {
            "Retry consumption requires durable tracker persistence"
        }
        var firstReadCaptured = false
        var consumedSnapshot: DelayedTrackerSyncItem? = null
        val persistence = object : DelayedTrackerSyncPersistence {
            override suspend fun getItems(): List<DelayedTrackerSyncItem> {
                val items = durablePersistence.getItems().filter { it.trackId == item.trackId }
                if (!firstReadCaptured) {
                    firstReadCaptured = true
                    consumedSnapshot = items.firstOrNull()
                }
                return items
            }

            override suspend fun upsertMax(candidate: DelayedTrackerSyncItem): DelayedTrackerSyncItem {
                val current = getItems().firstOrNull() ?: return candidate
                return durablePersistence.upsertMax(
                    candidate.copy(
                        eventId = current.eventId ?: item.eventId,
                        attempt = current.attempt,
                    ),
                )
            }

            override suspend fun removeUpTo(trackId: Long, lastChapterRead: Double) =
                durablePersistence.removeUpTo(trackId, lastChapterRead)
        }
        val report = queue(persistence).drain(repository::getTrackById)
        val remaining = persistence.getItems().firstOrNull()
        if (
            report.remaining > 0 &&
            remaining?.sameCheckpointAs(consumedSnapshot) == true
        ) {
            durablePersistence.upsertMax(remaining.copy(attempt = remaining.attempt + 1))
        }
        return report.copy(remaining = persistence.getItems().size)
    }

    private fun DelayedTrackerSyncItem.sameCheckpointAs(other: DelayedTrackerSyncItem?): Boolean =
        other != null &&
            trackId == other.trackId &&
            mangaId == other.mangaId &&
            trackerId == other.trackerId &&
            lastChapterRead == other.lastChapterRead &&
            eventId == other.eventId &&
            attempt == other.attempt

    private fun queue(persistence: DelayedTrackerSyncPersistence) = DelayedTrackerSyncQueue(
        persistence = persistence,
        session = { id ->
            registry.get(id)?.profile?.value?.let { TrackerProviderSession(id, it.loggedIn, it.username) }
        },
        execute = { providerRequest ->
            val service = requireNotNull(registry.get(providerRequest.track.trackerId))
            val result = if (service is TrackerProviderService) {
                service.execute(providerRequest)
            } else {
                TrackerProviderResult.Success(
                    service.update(
                        providerRequest.track,
                        TrackEdit(
                            lastChapterRead = providerRequest.edit.lastChapterRead,
                            didReadChapter = true,
                        ),
                    ),
                )
            }
            (result as? TrackerProviderResult.Success)?.track?.let { repository.insert(it) }
            result
        },
    )
}
