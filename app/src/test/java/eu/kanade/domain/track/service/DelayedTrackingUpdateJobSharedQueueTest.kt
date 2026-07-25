package eu.kanade.domain.track.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.service.DelayedTrackerSyncItem
import tachiyomi.domain.track.service.TrackerProviderRequest
import tachiyomi.domain.track.service.TrackerProviderResult
import tachiyomi.domain.track.service.TrackerProviderSession
import java.util.concurrent.TimeUnit

class DelayedTrackingUpdateJobSharedQueueTest {
    @Test
    fun `store round trips highest progress and failure evidence through shared queue shape`() = runTest {
        val values = mutableMapOf<String, Any>("4" to 8.0f)
        val store = DelayedTrackingStore(values)

        assertEquals(DelayedTrackerSyncItem(4, 0, 0, 8.0), store.getItems().single())
        store.upsertMax(DelayedTrackerSyncItem(4, 7, 9, 10.0, "NETWORK"))
        store.upsertMax(DelayedTrackerSyncItem(4, 7, 9, 9.0, "SERVER"))
        assertEquals(DelayedTrackerSyncItem(4, 7, 9, 10.0, "NETWORK"), store.getItems().single())
        store.removeUpTo(4, 9.0)
        assertEquals(10.0, store.getItems().single().lastChapterRead)
        store.removeUpTo(4, 10.0)
        assertEquals(emptyList<DelayedTrackerSyncItem>(), store.getItems())
    }

    @Test
    fun `production worker runner maps drain and exhaustion outcomes`() = runTest {
        var remaining = 0
        var drained = 0
        var exhausted = 0
        val runner = DelayedTrackingWorkerRunner(
            drain = {
                drained++
                tachiyomi.domain.track.service.DelayedTrackerSyncReport(1, 0, 0, remaining)
            },
            markRetryExhausted = { exhausted++ },
        )

        assertEquals(ListenableWorker.Result.success()::class, runner.run(0)::class)
        remaining = 1
        assertEquals(ListenableWorker.Result.retry()::class, runner.run(3)::class)
        assertEquals(ListenableWorker.Result.failure()::class, runner.run(4)::class)
        assertEquals(2, drained)
        assertEquals(1, exhausted)
    }

    @Test
    fun `setupTask uses connected unique replace and five minute exponential backoff`() {
        var capturedName = ""
        var capturedPolicy: ExistingWorkPolicy? = null
        val request = DelayedTrackingUpdateJob.setupTask { name, policy, work ->
            capturedName = name
            capturedPolicy = policy
            work
        }

        assertEquals("DelayedTrackingUpdate", capturedName)
        assertEquals(ExistingWorkPolicy.REPLACE, capturedPolicy)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(TimeUnit.MINUTES.toMillis(5), request.workSpec.backoffDelayDuration)
        assertEquals(true, DelayedTrackingUpdateJob.isRetryExhausted(4))
        assertEquals(false, DelayedTrackingUpdateJob.isRetryExhausted(3))
    }

    @Test
    fun `TrackChapter executes shared provider request and queues failed highest progress`() = runTest {
        val track = track()
        val getTracks = mockk<GetTracks> { coEvery { await(7) } returns listOf(track) }
        val manager = mockk<TrackerManager> {
            coEvery { session(9) } returns TrackerProviderSession(9, true)
            coEvery { execute(any()) } returns TrackerProviderResult.Failure(
                tachiyomi.domain.track.service.TrackerProviderError(
                    tachiyomi.domain.track.service.TrackerProviderOperation.EDIT,
                    tachiyomi.domain.track.service.TrackerProviderErrorKind.NETWORK,
                ),
            )
        }
        val values = mutableMapOf<String, Any>()
        val store = DelayedTrackingStore(values)
        var scheduled = 0
        val subject = TrackChapter(
            getTracks,
            manager,
            mockk<InsertTrack>(relaxed = true),
            store,
            scheduleRetry = { scheduled++ },
        )

        subject.await(mockk<Context>(), 7, 5.0)

        coVerify(exactly = 1) {
            manager.execute(
                match<TrackerProviderRequest.Edit> {
                    it.track.id == 4L && it.edit.lastChapterRead == 5.0 && it.edit.didReadChapter
                },
            )
        }
        assertEquals(5.0, store.getItems().single().lastChapterRead)
        assertEquals("NETWORK", store.getItems().single().failureReason)
        assertEquals(1, scheduled)

        coEvery { manager.execute(any()) } returns TrackerProviderResult.Success(track.copy(lastChapterRead = 6.0))
        subject.await(mockk<Context>(), 7, 6.0)
        assertEquals(emptyList<DelayedTrackerSyncItem>(), store.getItems())
        assertEquals(1, scheduled)
    }

    private fun track() = Track(
        4, 7, 9, 10, null, "Manga", 2.0, 10, 1, 0.0, "", 0, 0, false,
    )
}
