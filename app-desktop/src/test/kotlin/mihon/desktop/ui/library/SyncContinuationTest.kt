package mihon.desktop.ui.library

import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.reader.ReaderNavigator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `library continuation chooses earlier unread chapter before old synchronized read chapter`() = runTest {
        val manga = Manga.create().copy(id = 10, source = 42, url = "/manga", chapterFlags = Manga.CHAPTER_SORTING_NUMBER)
        val chapters = FakeChapterRepository()
        chapters.addAll(
            listOf(
                Chapter.create().copy(id = 1, mangaId = 10, url = "/first", sourceOrder = 0, chapterNumber = 1.0, lastPageRead = 4),
                Chapter.create().copy(id = 2, mangaId = 10, url = "/second", sourceOrder = 1, chapterNumber = 2.0, read = true),
            ),
        )
        val model = LibraryScreenModel(
            getChaptersByMangaId = GetChaptersByMangaId(chapters),
            readingProgress = progress(ReadingResumePosition(2, 9, ReadingSyncSnapshot())),
        )

        val request = requireNotNull(model.continueReadingRequest(LibraryManga(manga, emptyList(), 2, 1, 0, 0, 0, 0)))
        assertEquals(1L, request.chapterId)
        assertEquals(4, request.initialPage)
        assertNull(request.resumeSnapshot)
    }

    @Test
    fun `detail continuation chooses earlier unread chapter and keeps synchronized state only for that chapter`() = runTest {
        val manga = Manga.create().copy(id = 10, source = 42, url = "/manga", chapterFlags = Manga.CHAPTER_SORTING_NUMBER)
        val first = Chapter.create().copy(
            id = 1,
            mangaId = 10,
            url = "/first",
            sourceOrder = 0,
            chapterNumber = 1.0,
            lastPageRead = 4,
        )
        val second = Chapter.create().copy(
            id = 2,
            mangaId = 10,
            url = "/second",
            sourceOrder = 1,
            chapterNumber = 2.0,
            read = true,
        )
        val snapshot = ReadingSyncSnapshot()
        val baseline = ReadingSyncSnapshot()
        val model = MangaDetailScreenModel(
            10,
            readingProgress = progress(
                ReadingResumePosition(2, 9, snapshot),
                listOf(
                    tachiyomi.domain.reader.model.ReaderOpenContext(manga, first, 4, baseline, false),
                    tachiyomi.domain.reader.model.ReaderOpenContext(manga, second, 0, baseline, false),
                ),
            ),
        )

        val oldResume = requireNotNull(model.continueReadingRequest(manga, listOf(first, second)))
        assertEquals(first.id, oldResume.chapterId)
        assertEquals(4, oldResume.initialPage)
        assertSame(baseline, oldResume.resumeSnapshot)

        val sameChapter = MangaDetailScreenModel(
            10,
            readingProgress = progress(
                ReadingResumePosition(1, 5, snapshot),
                listOf(
                    tachiyomi.domain.reader.model.ReaderOpenContext(manga, first, 5, snapshot, true),
                ),
            ),
        )
        val matchingResume = requireNotNull(sameChapter.continueReadingRequest(manga, listOf(first, second)))
        assertEquals(first.id, matchingResume.chapterId)
        assertEquals(5, matchingResume.initialPage)
        assertSame(snapshot, matchingResume.resumeSnapshot)
        assertEquals(second.id, model.readerRequest(manga, listOf(first, second), second)?.chapterId)
        assertEquals(0, model.readerRequest(manga, listOf(first, second), second)?.initialPage)
    }

    @Test
    fun `all read chapters do not fall back to synchronized resume`() = runTest {
        val manga = Manga.create().copy(id = 10, source = 42, url = "/manga")
        val chapters = FakeChapterRepository()
        chapters.addAll(listOf(Chapter.create().copy(id = 1, mangaId = 10, url = "/first", read = true)))
        val progress = progress(ReadingResumePosition(1, 2, ReadingSyncSnapshot()))
        val library = LibraryScreenModel(
            getChaptersByMangaId = GetChaptersByMangaId(chapters),
            readingProgress = progress,
        )
        val detail = MangaDetailScreenModel(10, readingProgress = progress)

        assertNull(library.continueReadingRequest(LibraryManga(manga, emptyList(), 1, 1, 0, 0, 0, 0)))
        assertNull(detail.continueReadingRequest(manga, listOf(Chapter.create().copy(id = 1, mangaId = 10, read = true))))
    }

    @Test
    fun `external unread chapter is skipped before choosing an internal unread target`() = runTest {
        val manga = Manga.create().copy(id = 10, source = 42, url = "/manga", chapterFlags = Manga.CHAPTER_SORTING_NUMBER)
        val external = Chapter.create().copy(
            id = 1,
            mangaId = 10,
            url = "external:https://example.com/first",
            sourceOrder = 0,
            chapterNumber = 1.0,
        )
        val internal = Chapter.create().copy(
            id = 2,
            mangaId = 10,
            url = "/second",
            sourceOrder = 1,
            chapterNumber = 2.0,
            lastPageRead = 3,
        )
        val chapters = FakeChapterRepository().apply { addAll(listOf(external, internal)) }
        val progress = progress(ReadingResumePosition(external.id, 9, ReadingSyncSnapshot()))
        val library = LibraryScreenModel(getChaptersByMangaId = GetChaptersByMangaId(chapters), readingProgress = progress)
        val baseline = ReadingSyncSnapshot()
        val detail = MangaDetailScreenModel(
            10,
            readingProgress = progress(
                ReadingResumePosition(external.id, 9, ReadingSyncSnapshot()),
                listOf(
                    tachiyomi.domain.reader.model.ReaderOpenContext(manga, internal, 3, baseline, false),
                ),
            ),
        )

        val libraryRequest = requireNotNull(
            library.continueReadingRequest(LibraryManga(manga, emptyList(), 2, 2, 2, 0, 0, 0)),
        )
        val detailRequest = requireNotNull(detail.continueReadingRequest(manga, listOf(external, internal)))
        assertEquals(internal.id, libraryRequest.chapterId)
        assertEquals(3, libraryRequest.initialPage)
        assertNull(libraryRequest.resumeSnapshot)
        assertEquals(listOf(internal.id), libraryRequest.chapters.map { it.id })
        assertNull(ReaderNavigator(libraryRequest.chapters, libraryRequest.currentChapterIndex).nextToRead)
        assertEquals(internal.id, detailRequest.chapterId)
        assertEquals(3, detailRequest.initialPage)
        assertSame(baseline, detailRequest.resumeSnapshot)
    }

    @Test
    fun `all external unread chapters leave no continue target and keep library feedback`() = runTest {
        val manga = Manga.create().copy(id = 10, source = 42, url = "/manga")
        val external = Chapter.create().copy(id = 1, mangaId = 10, url = "external:https://example.com/first")
        val chapters = FakeChapterRepository().apply { addAll(listOf(external)) }
        val library = LibraryScreenModel(getChaptersByMangaId = GetChaptersByMangaId(chapters))
        val detail = MangaDetailScreenModel(10)

        assertNull(library.continueReadingRequest(LibraryManga(manga, emptyList(), 1, 1, 1, 0, 0, 0)))
        assertEquals(tachiyomi.i18n.MR.strings.no_next_chapter.localized(), library.state.value.operationFeedback)
        assertNull(detail.continueReadingRequest(manga, listOf(external)))
    }

    @Test
    fun `library continuation resumes an unread target chapter and carries frozen snapshot`() = runTest {
        val manga = Manga.create().copy(id = 10, source = 42, url = "/manga")
        val chapters = FakeChapterRepository()
        chapters.addAll(listOf(Chapter.create().copy(id = 1, mangaId = 10, url = "/first")))
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
    fun `detail continuation keeps saved position while explicit read chapter selection restarts`() = runTest {
        val manga = Manga.create().copy(id = 10, source = 42, url = "/manga")
        val chapters = listOf(
            Chapter.create().copy(id = 1, mangaId = 10, url = "/first"),
            Chapter.create().copy(id = 2, mangaId = 10, url = "/second", read = true, lastPageRead = 7),
        )
        val snapshot = ReadingSyncSnapshot()
        val model = MangaDetailScreenModel(
            10,
            readingProgress = progress(
                ReadingResumePosition(1, 2, snapshot),
                listOf(
                    tachiyomi.domain.reader.model.ReaderOpenContext(manga, chapters[0], 2, snapshot, true),
                    tachiyomi.domain.reader.model.ReaderOpenContext(manga, chapters[1], 0, ReadingSyncSnapshot(), false),
                ),
            ),
        )
        val request = requireNotNull(model.continueReadingRequest(manga, chapters))
        assertEquals(1L, request.chapterId)
        assertEquals(2, request.initialPage)
        assertEquals(2L, model.readerRequest(manga, chapters, chapters[1])?.chapterId)
        assertEquals(0, model.readerRequest(manga, chapters, chapters[1])?.initialPage)
    }

    private fun progress(snapshot: ReadingSyncSnapshot) = progress(ReadingResumePosition(1, 2, snapshot))

    private fun progress(position: ReadingResumePosition, openings: List<tachiyomi.domain.reader.model.ReaderOpenContext> = emptyList()) = RecordReadingProgress(object : ReadingProgressRepository {
        override suspend fun openChapter(target: tachiyomi.domain.reader.model.ReaderChapterIdentity) = openings.firstOrNull { it.chapter.id == target.chapterId }
        override suspend fun record(event: ReadingProgressEvent) = Unit
        override suspend fun resumePosition(mangaId: Long) = position
    })
}
