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
import uy.kohesive.injekt.api.addSingleton
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

    @Test
    fun `actual Android TrackChapter caps fresh service progress and drains pending raw target`() = runTest {
        val original = track()
        val getTracks = mockk<GetTracks> { coEvery { await(7) } returns listOf(original) }
        val writes = mutableListOf<Double>()
        val persisted = mutableListOf<Track>()
        val tracker = mockk<eu.kanade.tachiyomi.data.track.Tracker>(relaxed = true) {
            io.mockk.every { id } returns 9L
            io.mockk.every { isLoggedIn } returns true
            io.mockk.every { name } returns "Test tracker"
            io.mockk.every { getReadingStatus() } returns 1L
            io.mockk.every { getCompletionStatus() } returns 2L
            io.mockk.every { getRereadingStatus() } returns -1L
            coEvery { refresh(any()) } answers
                {
                    firstArg<eu.kanade.tachiyomi.data.database.models.Track>().apply {
                        total_chapters = 10
                        last_chapter_read =
                            2.0
                    }
                }
            coEvery { update(any(), any()) } answers
                { firstArg<eu.kanade.tachiyomi.data.database.models.Track>().also { writes += it.last_chapter_read } }
        }
        val manager = TrackerManager(listOf(tracker), persist = { persisted += it })
        val store = DelayedTrackingStore(mutableMapOf())
        store.upsertMax(DelayedTrackerSyncItem(original.id, 7, 9, 50.0))
        val subject = TrackChapter(getTracks, manager, mockk(relaxed = true), store, scheduleRetry = {})
        subject.await(mockk<Context>(), 7, 50.0)
        assertEquals(listOf(10.0), writes)
        assertEquals(10.0, persisted.single().lastChapterRead)
        assertEquals(emptyList<DelayedTrackerSyncItem>(), store.getItems())
    }

    @Test
    fun `Android default persistence rejects failed SQL and keeps raw pending for update and fresh no op`() = runTest {
        val previous = uy.kohesive.injekt.Injekt
        uy.kohesive.injekt.Injekt =
            uy.kohesive.injekt.api.InjektScope(uy.kohesive.injekt.registry.default.DefaultRegistrar())
        try {
            val repository = mockk<tachiyomi.domain.track.repository.TrackRepository>()
            var rejected = true
            val saved = mutableListOf<Track>()
            coEvery { repository.insert(any()) } coAnswers {
                if (rejected) throw java.io.IOException("isolated SQL rejection")
                saved += firstArg<Track>()
            }
            uy.kohesive.injekt.Injekt.addSingleton(InsertTrack(repository))
            for (freshProgress in listOf(2.0, 60.0)) {
                val original = track()
                val getTracks = mockk<GetTracks> { coEvery { await(7) } returns listOf(original) }
                val writes = mutableListOf<Double>()
                val tracker = mockk<eu.kanade.tachiyomi.data.track.Tracker>(relaxed = true) {
                    io.mockk.every { id } returns 9L
                    io.mockk.every { isLoggedIn } returns true
                    io.mockk.every { getReadingStatus() } returns 1L
                    io.mockk.every { getCompletionStatus() } returns 2L
                    io.mockk.every { getRereadingStatus() } returns -1L
                    coEvery { refresh(any()) } answers {
                        firstArg<eu.kanade.tachiyomi.data.database.models.Track>().apply {
                            total_chapters = 10
                            last_chapter_read = freshProgress
                        }
                    }
                    coEvery { update(any(), any()) } answers {
                        firstArg<eu.kanade.tachiyomi.data.database.models.Track>().also {
                            writes += it.last_chapter_read
                        }
                    }
                }
                val manager = TrackerManager(listOf(tracker))
                val store = DelayedTrackingStore(mutableMapOf())
                val subject = TrackChapter(getTracks, manager, InsertTrack(repository), store, scheduleRetry = {})
                rejected = true
                subject.await(mockk<Context>(), 7, 50.0)
                assertEquals(
                    50.0,
                    store.getItems().singleOrNull()?.lastChapterRead,
                    "failed authoritative persistence must not report completion or discard retry",
                )
                rejected = false
                saved.clear()
                writes.clear()
                subject.await(mockk<Context>(), 7, 50.0)
                assertEquals(emptyList<DelayedTrackerSyncItem>(), store.getItems())
                assertEquals(1, saved.size, "successful update and fresh no-op each persist exactly once")
                assertEquals(if (freshProgress == 2.0) listOf(10.0) else emptyList<Double>(), writes)
                assertEquals(if (freshProgress == 2.0) 10.0 else 60.0, saved.single().lastChapterRead)
            }
        } finally {
            uy.kohesive.injekt.Injekt = previous
        }
    }

    private fun track() = Track(
        4, 7, 9, 10, null, "Manga", 2.0, 10, 1, 0.0, "", 0, 0, false,
    )
}
