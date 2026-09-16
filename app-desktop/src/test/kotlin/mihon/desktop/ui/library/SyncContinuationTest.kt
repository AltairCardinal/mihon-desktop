package mihon.desktop.ui.library

import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.fakes.FakeChapterRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.domain.reader.model.ReadingResumePosition
import tachiyomi.domain.reader.model.ReadingSyncSnapshot
import tachiyomi.domain.reader.repository.ReadingProgressRepository

class SyncContinuationTest {
    @Test
    fun `library continuation resumes a read chapter and carries frozen snapshot`() = runTest {
        val manga = Manga.create().copy(id = 10, source = 42, url = "/manga")
        val chapters = FakeChapterRepository()
        chapters.addAll(listOf(Chapter.create().copy(id = 1, mangaId = 10, url = "/first", read = true)))
        val snapshot = ReadingSyncSnapshot()
        val model = LibraryScreenModel(
            getChaptersByMangaId = GetChaptersByMangaId(chapters),
            readingProgress = progress(snapshot),
        )
        val request = requireNotNull(model.continueReadingRequest(LibraryManga(manga, emptyList(), 1, 1, 0, 0, 0, 0)))
        assertEquals(1L, request.chapterId)
        assertEquals(2, request.initialPage)
        assertSame(snapshot, request.resumeSnapshot)
    }

    @Test
    fun `detail continuation can reread while explicit chapter selection is unchanged`() = runTest {
        val manga = Manga.create().copy(id = 10, source = 42, url = "/manga")
        val chapters = listOf(
            Chapter.create().copy(id = 1, mangaId = 10, url = "/first", read = true),
            Chapter.create().copy(id = 2, mangaId = 10, url = "/second", read = true, lastPageRead = 7),
        )
        val model = MangaDetailScreenModel(10, readingProgress = progress(ReadingSyncSnapshot()))
        val request = requireNotNull(model.continueReadingRequest(manga, chapters))
        assertEquals(1L, request.chapterId)
        assertEquals(2, request.initialPage)
        assertEquals(2L, model.readerRequest(manga, chapters, chapters[1])?.chapterId)
        assertEquals(7, model.readerRequest(manga, chapters, chapters[1])?.initialPage)
    }

    private fun progress(snapshot: ReadingSyncSnapshot) = RecordReadingProgress(object : ReadingProgressRepository {
        override suspend fun record(event: ReadingProgressEvent) = Unit
        override suspend fun resumePosition(mangaId: Long) = ReadingResumePosition(1, 2, snapshot)
    })
}
