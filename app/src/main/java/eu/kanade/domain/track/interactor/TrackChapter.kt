package eu.kanade.domain.track.interactor

import android.content.Context
import eu.kanade.domain.track.service.DelayedTrackingUpdateJob
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.service.DelayedTrackerSyncQueue
import tachiyomi.domain.track.service.DelayedTrackerSyncReport

class TrackChapter(
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    @Suppress("UNUSED_PARAMETER") insertTrack: InsertTrack,
    private val delayedTrackingStore: DelayedTrackingStore,
    private val scheduleRetry: (Context) -> Unit = DelayedTrackingUpdateJob::setupTask,
) {

    suspend fun await(context: Context, mangaId: Long, chapterNumber: Double, setupJobOnFailure: Boolean = true) {
        withNonCancellableContext {
            val report = queue().sync(getTracks.await(mangaId), chapterNumber)
            if (setupJobOnFailure && report.queued > 0) {
                scheduleRetry(context)
            }
        }
    }

    suspend fun drainDelayed(): DelayedTrackerSyncReport = queue().drain(getTracks::awaitOne)

    suspend fun markRetryExhausted() = queue().markRetryExhausted()

    private fun queue() = DelayedTrackerSyncQueue(
        persistence = delayedTrackingStore,
        session = trackerManager::session,
        execute = trackerManager::execute,
    )
}
