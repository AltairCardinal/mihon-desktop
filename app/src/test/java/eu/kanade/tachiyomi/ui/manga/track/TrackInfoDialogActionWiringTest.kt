package eu.kanade.tachiyomi.ui.manga.track

import eu.kanade.tachiyomi.data.track.Tracker
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.TrackerProviderError
import tachiyomi.domain.track.service.TrackerProviderErrorKind
import tachiyomi.domain.track.service.TrackerProviderOperation
import tachiyomi.domain.track.service.TrackerProviderRequest
import tachiyomi.domain.track.service.TrackerProviderResult
import java.util.Collections
import java.util.TimeZone

class TrackInfoDialogActionWiringTest {
    @Test
    fun `production actions emit exact requests and report only failures`() = runTest {
        val requests = mutableListOf<TrackerProviderRequest>()
        val failures = mutableListOf<TrackerProviderError>()
        var result: TrackerProviderResult = TrackerProviderResult.Success(track)
        val actions = TrackInfoDialogActions(
            execute = {
                requests += it
                result
            },
            feedback = failures::add,
        )

        actions.setPrivate(track, true)
        actions.setStatus(track, 2)
        actions.setChapter(track, 3.0)
        actions.setScore(track, 9.0)
        actions.setStartDate(track, 11)
        actions.setFinishDate(track, 12)
        actions.removeDate(track, start = true)
        actions.removeDate(track, start = false)
        assertTrue(failures.isEmpty())

        val error = TrackerProviderError(TrackerProviderOperation.DELETE, TrackerProviderErrorKind.NETWORK)
        result = TrackerProviderResult.Failure(error)
        actions.delete(track)

        assertEquals(
            listOf(
                TrackerProviderRequest.Edit(track, TrackEdit(private = true)),
                TrackerProviderRequest.Edit(track, TrackEdit(status = 2)),
                TrackerProviderRequest.Edit(track, TrackEdit(lastChapterRead = 3.0, didReadChapter = false)),
                TrackerProviderRequest.Edit(track, TrackEdit(score = 9.0)),
                TrackerProviderRequest.Edit(track, TrackEdit(startDate = 11)),
                TrackerProviderRequest.Edit(track, TrackEdit(finishDate = 12)),
                TrackerProviderRequest.Edit(track, TrackEdit(startDate = 0)),
                TrackerProviderRequest.Edit(track, TrackEdit(finishDate = 0)),
                TrackerProviderRequest.Delete(track),
            ),
            requests,
        )
        assertEquals(listOf(error), failures)
    }

    @Test
    fun `production models delegate transformed values through actions`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val previousTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        try {
            val requests = Collections.synchronizedList(mutableListOf<TrackerProviderRequest>())
            val failures = Collections.synchronizedList(mutableListOf<TrackerProviderError>())
            val error = TrackerProviderError(TrackerProviderOperation.DELETE, TrackerProviderErrorKind.NETWORK)
            val actions = TrackInfoDialogActions(
                execute = {
                    requests += it
                    if (it is TrackerProviderRequest.Delete) {
                        TrackerProviderResult.Failure(error)
                    } else {
                        TrackerProviderResult.Success(it.track)
                    }
                },
                feedback = failures::add,
            )
            val tracker = mockk<Tracker>(relaxed = true) {
                every { id } returns 1
                every { getScoreList() } returns persistentListOf("0", "9")
                every { indexToScore(1) } returns 9.0
            }

            TrackInfoDialogHomeScreen.Model(2, 1, mockk<GetTracks>(), actions, startObservers = false)
                .togglePrivate(TrackItem(track, tracker))
            TrackStatusSelectorScreen.Model(track, tracker, actions).apply {
                setSelection(2)
                setStatus()
            }
            TrackChapterSelectorScreen.Model(track, tracker, actions).apply {
                setSelection(3)
                setChapter()
            }
            TrackScoreSelectorScreen.Model(track, tracker, actions).apply {
                setSelection("9")
                setScore()
            }
            TrackDateSelectorScreen.Model(track, tracker, start = true, actions = actions).setDate(100_000_000)
            TrackDateSelectorScreen.Model(track, tracker, start = false, actions = actions).setDate(200_000_000)
            TrackDateRemoverScreen.Model(track, tracker, start = true, actions = actions).removeDate()
            TrackDateRemoverScreen.Model(track, tracker, start = false, actions = actions).removeDate()
            TrackerRemoveScreen.Model(2, track, tracker, mockk<DeleteTrack>(), actions).deleteMangaFromService()

            advanceUntilIdle()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    while (requests.size < 9 || failures.size < 1) yield()
                }
            }
            val expected = setOf(
                TrackerProviderRequest.Edit(track, TrackEdit(private = true)),
                TrackerProviderRequest.Edit(track, TrackEdit(status = 2)),
                TrackerProviderRequest.Edit(track, TrackEdit(lastChapterRead = 3.0, didReadChapter = false)),
                TrackerProviderRequest.Edit(track, TrackEdit(score = 9.0)),
                TrackerProviderRequest.Edit(track, TrackEdit(startDate = 67_600_000)),
                TrackerProviderRequest.Edit(track, TrackEdit(finishDate = 167_600_000)),
                TrackerProviderRequest.Edit(track, TrackEdit(startDate = 0)),
                TrackerProviderRequest.Edit(track, TrackEdit(finishDate = 0)),
                TrackerProviderRequest.Delete(track),
            )
            assertEquals(9, requests.size)
            assertEquals(expected, requests.toSet())
            assertEquals(listOf(error), failures)
        } finally {
            TimeZone.setDefault(previousTimeZone)
            Dispatchers.resetMain()
        }
    }

    private val track = Track(1, 2, 1, 3, 4, "Manga", 1.0, 10, 5, 6.0, "", 7, 8, false)
}
