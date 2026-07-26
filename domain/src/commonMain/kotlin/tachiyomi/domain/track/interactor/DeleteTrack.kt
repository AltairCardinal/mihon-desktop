package tachiyomi.domain.track.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.repository.TrackRepository

class DeleteTrack(
    private val trackRepository: TrackRepository,
) {
    suspend fun awaitOrThrow(mangaId: Long, trackerId: Long) {
        trackRepository.delete(mangaId, trackerId)
    }

    suspend fun await(mangaId: Long, trackerId: Long) {
        try {
            awaitOrThrow(mangaId, trackerId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }
}
