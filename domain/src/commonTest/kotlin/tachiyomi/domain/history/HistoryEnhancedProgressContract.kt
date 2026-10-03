package tachiyomi.domain.history

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.interactor.SyncEnhancedChapterProgress
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

abstract class HistoryEnhancedProgressContract {
    @Test
    fun `binding only marks matching chapters read after a remote wait`() = runTest {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            fixture.progress.await(10, fixture.track) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        fixture.rows =
            fixture.rows.map {
                if (it.id ==
                    1L
                ) {
                    it.copy(lastPageRead = 17, bookmark = true, name = "New")
                } else {
                    it.copy(url = "/replaced")
                }
            }
        release.complete(Unit)
        job.join()
        assertEquals(listOf(1L), fixture.updates.map { it.id })
        val patch = fixture.updates.single()
        assertEquals(true, patch.read)
        assertNull(patch.lastPageRead)
        assertNull(patch.bookmark)
        assertNull(patch.name)
        assertNull(patch.dateFetch)
        assertEquals(2.0, fixture.saved?.lastChapterRead)
        assertEquals(fixture.track.status, fixture.saved?.status)
    }

    @Test
    fun `only continuous recognized local reading raises remote progress`() = runTest {
        val fixture = Fixture()
        fixture.rows =
            listOf(3, 1, 2).map { n ->
                Chapter.create().copy(
                    id = n.toLong(),
                    mangaId = 10,
                    url = "/$n",
                    chapterNumber = n.toDouble(),
                    read =
                    n != 2,
                )
            } +
            Chapter.create().copy(id = 99, mangaId = 10, read = true)
        var uploaded: Track? = null
        fixture.progress.await(10, fixture.track.copy(lastChapterRead = 0.0)) { uploaded = it }
        assertEquals(1.0, uploaded?.lastChapterRead)
        fixture.rows = fixture.rows.map { it.copy(read = true) }
        fixture.progress.await(10, fixture.track) { uploaded = it }
        assertEquals(3.0, uploaded?.lastChapterRead)
    }

    private class Fixture {
        var rows = (1..2).map {
            Chapter.create().copy(id = it.toLong(), mangaId = 10, url = "/$it", chapterNumber = it.toDouble())
        }
        var updates = emptyList<ChapterUpdate>()
        var saved: Track? = null
        val track = Track(0, 10, 6, 1, null, "Title", 2.0, 4, 2, 0.0, "/remote", 0, 0, false)
        val chapters = mockk<ChapterRepository>()
        val tracks = mockk<TrackRepository>()
        init {
            coEvery { chapters.getChapterByMangaId(10, false) } answers { rows }
            coEvery { chapters.updateAll(any()) } coAnswers { updates = firstArg() }
            coEvery { tracks.insert(any()) } coAnswers { saved = firstArg() }
        }
        val progress =
            SyncEnhancedChapterProgress(UpdateChapter(chapters), InsertTrack(tracks), GetChaptersByMangaId(chapters))
    }
}
