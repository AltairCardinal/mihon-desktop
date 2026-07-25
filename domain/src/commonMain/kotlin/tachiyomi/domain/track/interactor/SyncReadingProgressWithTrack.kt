package tachiyomi.domain.track.interactor

import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.DelayedTrackerSyncItem
import tachiyomi.domain.track.service.DelayedTrackerSyncPersistence
import tachiyomi.domain.track.service.DelayedTrackerSyncQueue
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

/** Shared equivalent of Android's chapter-completion tracker update policy. */
class SyncReadingProgressWithTrack(
    private val repository: TrackRepository,
    private val registry: TrackerServiceRegistry,
    private val retryScheduler: TrackerSyncRetryScheduler,
) : ReadingProgressTrackSync {
    override suspend fun sync(request: TrackerSyncRequest) {
        val persistence = object : DelayedTrackerSyncPersistence {
            override suspend fun getItems() = emptyList<DelayedTrackerSyncItem>()
            override suspend fun upsertMax(item: DelayedTrackerSyncItem): DelayedTrackerSyncItem {
                retryScheduler.schedule(request.copy(trackerId = item.trackerId, attempt = request.attempt + 1))
                return item
            }
            override suspend fun removeUpTo(trackId: Long, lastChapterRead: Double) = false
        }
        DelayedTrackerSyncQueue(
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
        ).sync(
            repository.getTracksByMangaId(request.mangaId)
                .filter { request.trackerId == null || it.trackerId == request.trackerId },
            request.chapterNumber,
        )
    }
}
