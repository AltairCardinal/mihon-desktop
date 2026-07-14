package tachiyomi.domain.track.interactor

import kotlinx.coroutines.CancellationException
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackEdit
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
        repository.getTracksByMangaId(request.mangaId)
            .asSequence()
            .filter { request.trackerId == null || it.trackerId == request.trackerId }
            .filter { it.lastChapterRead < request.chapterNumber }
            .forEach { track ->
                val service = registry.get(track.trackerId)
                    ?.takeIf { it.profile.value.loggedIn }
                    ?: return@forEach
                try {
                    val updated = service.update(track, TrackEdit(lastChapterRead = request.chapterNumber))
                    repository.insert(updated)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    retryScheduler.schedule(request.copy(trackerId = track.trackerId, attempt = request.attempt + 1))
                }
            }
    }
}
