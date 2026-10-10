package eu.kanade.domain.track.interactor

import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

class HistoryEnhancedProgressWiringTest {
    @Test
    fun `Android existing enhanced wrapper consumes shared read-only patch workflow`() = runTest {
        val chapters = mockk<ChapterRepository>()
        val tracks = mockk<TrackRepository>()
        val tracker = mockk<Tracker>(moreInterfaces = arrayOf(EnhancedTracker::class))
        val row = Chapter.create().copy(
            id = 1,
            mangaId = 10,
            url = "/1",
            chapterNumber = 1.0,
            bookmark = true,
            lastPageRead = 17,
        )
        coEvery { chapters.getChapterByMangaId(10, false) } returns listOf(row)
        var patch: ChapterUpdate? = null
        coEvery { chapters.updateAll(any()) } coAnswers { patch = firstArg<List<ChapterUpdate>>().single() }
        coEvery { tracks.insert(any()) } returns Unit
        coEvery { tracker.update(any(), false) } coAnswers { firstArg() }
        val progress =
            SyncChapterProgressWithTrack(UpdateChapter(chapters), InsertTrack(tracks), GetChaptersByMangaId(chapters))
        progress.await(10, Track(0, 10, 6, 1, null, "Title", 1.0, 4, 2, 0.0, "/remote", 0, 0, false), tracker)
        assertEquals(true, requireNotNull(patch).read)
        assertNull(requireNotNull(patch).bookmark)
        assertNull(requireNotNull(patch).lastPageRead)
    }
}
