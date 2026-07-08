package mihon.desktop.domain

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import mihon.desktop.domain.fakes.FakeChapterRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class LibraryUpdateCheckerTest {

    private val sourceId = 42L

    /** Stub source that returns a fixed chapter list for any manga. */
    private class StubSource(
        private val chapters: List<SChapter>,
        override val id: Long = 42L,
    ) : CatalogueSource {
        override val name = "Stub"
        override val lang = "en"
        override val supportsLatest = false
        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
            MangasPage(emptyList(), false)
        override fun getFilterList() = FilterList()
        override suspend fun getMangaDetails(manga: SManga) = SManga.create()
        override suspend fun getChapterList(manga: SManga) = chapters
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    private fun manga(id: Long = 1L, url: String = "/manga/test") =
        Manga.create().copy(id = id, url = url, source = sourceId, title = "Test", favorite = true)

    private fun sChapter(url: String, name: String = "Ch") =
        SChapter.create().apply { this.url = url; this.name = name }

    @Test
    fun `adds new chapters not already in DB`() = runBlocking<Unit> {
        val chapterRepo = FakeChapterRepository()
        // DB already has ch-1
        chapterRepo.addAll(listOf(
            Chapter.create().copy(mangaId = 1L, url = "/ch/1", name = "Ch 1"),
        ))

        val source = StubSource(listOf(
            sChapter("/ch/1", "Ch 1"),
            sChapter("/ch/2", "Ch 2"),
            sChapter("/ch/3", "Ch 3"),
        ))

        val checker = LibraryUpdateChecker(chapterRepo)
        val result = checker.checkForUpdates(manga(), source)

        assertEquals(2, result.newChapterCount)
        // DB should now have 3 chapters
        assertEquals(3, chapterRepo.getChapterByMangaId(1L).size)
    }

    @Test
    fun `returns zero when no new chapters`() = runBlocking<Unit> {
        val chapterRepo = FakeChapterRepository()
        chapterRepo.addAll(listOf(
            Chapter.create().copy(mangaId = 1L, url = "/ch/1", name = "Ch 1"),
        ))

        val source = StubSource(listOf(sChapter("/ch/1", "Ch 1")))

        val checker = LibraryUpdateChecker(chapterRepo)
        val result = checker.checkForUpdates(manga(), source)

        assertEquals(0, result.newChapterCount)
    }

    @Test
    fun `handles empty source chapter list`() = runBlocking<Unit> {
        val chapterRepo = FakeChapterRepository()
        chapterRepo.addAll(listOf(
            Chapter.create().copy(mangaId = 1L, url = "/ch/1", name = "Ch 1"),
        ))

        val source = StubSource(emptyList())
        val checker = LibraryUpdateChecker(chapterRepo)
        val result = checker.checkForUpdates(manga(), source)

        assertEquals(0, result.newChapterCount)
    }

    @Test
    fun `assigns correct sourceOrder to new chapters`() = runBlocking<Unit> {
        val chapterRepo = FakeChapterRepository()
        val source = StubSource(listOf(
            sChapter("/ch/1", "Ch 1"),
            sChapter("/ch/2", "Ch 2"),
        ))

        val checker = LibraryUpdateChecker(chapterRepo)
        checker.checkForUpdates(manga(), source)

        val chapters = chapterRepo.getChapterByMangaId(1L)
        assertEquals(2, chapters.size)
        val byUrl = chapters.associateBy { it.url }
        assertEquals(0L, byUrl["/ch/1"]?.sourceOrder)
        assertEquals(1L, byUrl["/ch/2"]?.sourceOrder)
    }

    @Test
    fun `recognizes chapter numbers from source chapter names during library update`() = runBlocking<Unit> {
        val chapterRepo = FakeChapterRepository()
        val source = StubSource(listOf(
            sChapter("/ch/16", "第16卷"),
            sChapter("/ch/22", "第22卷"),
        ))

        val checker = LibraryUpdateChecker(chapterRepo)
        checker.checkForUpdates(
            manga = manga().copy(title = "SOUL EATER噬魂者"),
            source = source,
        )

        assertEquals(listOf(16.0, 22.0), chapterRepo.getChapterByMangaId(1L).map { it.chapterNumber })
    }

    @Test
    fun `updates existing unrecognized chapter numbers during library update`() = runBlocking<Unit> {
        val chapterRepo = FakeChapterRepository()
        chapterRepo.addAll(
            listOf(
                Chapter.create().copy(mangaId = 1L, url = "/ch/16", name = "第16卷", chapterNumber = -1.0),
                Chapter.create().copy(mangaId = 1L, url = "/ch/22", name = "第22卷", chapterNumber = -1.0),
            ),
        )
        val source = StubSource(listOf(
            sChapter("/ch/16", "第16卷"),
            sChapter("/ch/22", "第22卷"),
        ))

        val checker = LibraryUpdateChecker(chapterRepo)
        checker.checkForUpdates(
            manga = manga().copy(title = "SOUL EATER噬魂者"),
            source = source,
        )

        assertEquals(listOf(16.0, 22.0), chapterRepo.getChapterByMangaId(1L).map { it.chapterNumber })
    }

    @Test
    fun `result includes inserted chapter models with correct URLs`() = runBlocking<Unit> {
        val chapterRepo = FakeChapterRepository()
        val source = StubSource(listOf(
            sChapter("/ch/1", "Ch 1"),
            sChapter("/ch/2", "Ch 2"),
        ))

        val checker = LibraryUpdateChecker(chapterRepo)
        val result = checker.checkForUpdates(manga(), source)

        assertEquals(2, result.newChapters.size)
        val urls = result.newChapters.map { it.url }.toSet()
        assert("/ch/1" in urls) { "Expected /ch/1 in newChapters" }
        assert("/ch/2" in urls) { "Expected /ch/2 in newChapters" }
    }

    @Test
    fun `result newChapters is empty when no new chapters`() = runBlocking<Unit> {
        val chapterRepo = FakeChapterRepository()
        chapterRepo.addAll(listOf(
            Chapter.create().copy(mangaId = 1L, url = "/ch/1", name = "Ch 1"),
        ))
        val source = StubSource(listOf(sChapter("/ch/1")))

        val checker = LibraryUpdateChecker(chapterRepo)
        val result = checker.checkForUpdates(manga(), source)

        assertEquals(0, result.newChapters.size)
    }
}
