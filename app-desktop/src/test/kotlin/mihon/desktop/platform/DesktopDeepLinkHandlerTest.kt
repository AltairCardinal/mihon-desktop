package mihon.desktop.platform

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ResolvableSource
import eu.kanade.tachiyomi.source.online.UriType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.SaveSourceMangaForDetails
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.source.FakeDesktopSourceManager
import mihon.domain.platform.ExternalActionInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.NetworkToLocalManga

class DesktopDeepLinkHandlerTest {

    @Test
    fun `first resolvable catalogue source persists manga through existing details chain`() = runTest {
        val ignored = ResolvingSource(1, UriType.Unknown)
        val selected = ResolvingSource(2, UriType.Manga)
        val later = ResolvingSource(3, UriType.Manga)
        val fixture = fixture(ignored, selected, later)

        val target = fixture.handler.resolve(ExternalActionInput.Search("https://example.org/title/one"))

        val manga = assertInstanceOf(DesktopExternalActionTarget.Manga::class.java, target)
        assertEquals(2L, fixture.mangaRepository.get(manga.mangaId)?.source)
        assertEquals(0, selected.detailsCalls)
        assertEquals(0, selected.chapterListCalls)
        assertEquals(0, later.uriTypeCalls)
    }

    @Test
    fun `chapter URI persists source chapters and returns linked chapter id`() = runTest {
        val source = ResolvingSource(7, UriType.Chapter)
        val fixture = fixture(source)

        val target = fixture.handler.resolve(ExternalActionInput.Search("https://example.org/chapter/2"))

        val chapter = assertInstanceOf(DesktopExternalActionTarget.Chapter::class.java, target)
        assertEquals(chapter.mangaId, fixture.chapterRepository.getChapterById(chapter.chapterId)?.mangaId)
        assertEquals("/chapter/2", fixture.chapterRepository.getChapterById(chapter.chapterId)?.url)
        assertEquals(chapter, fixture.handler.resolve(ExternalActionInput.Search("https://example.org/chapter/2")))
        assertEquals(1, source.chapterListCalls)
    }

    @Test
    fun `unresolved source and source NoResults fall back to global search`() = runTest {
        val unknown = fixture(ResolvingSource(1, UriType.Unknown)).handler
            .resolve(ExternalActionInput.Search("unknown"))
        val noResults = fixture(ResolvingSource(2, UriType.Manga, mangaResult = null)).handler
            .resolve(ExternalActionInput.Search("missing"))

        assertEquals(DesktopExternalActionTarget.GlobalSearch("unknown"), unknown)
        assertEquals(DesktopExternalActionTarget.GlobalSearch("missing"), noResults)
    }

    @Test
    fun `add repository preserves validated parameter and parser rejection remains structured`() = runTest {
        val handler = fixture().handler

        assertEquals(
            DesktopExternalActionTarget.ExtensionRepo("https://example.org/index.min.json"),
            handler.resolve(ExternalActionInput.ViewUri(REPOSITORY_URI)),
        )
        assertInstanceOf(
            DesktopExternalActionTarget.Rejected::class.java,
            handler.resolve(ExternalActionInput.ViewUri("tachiyomi://add-repo?url=file%3A%2F%2Fsecret")),
        )
    }

    @Test
    fun `source exception becomes structured rejection`() = runTest {
        val source = ResolvingSource(9, UriType.Manga, failure = IllegalStateException("boom"))

        val result = fixture(source).handler.resolve(ExternalActionInput.Search("https://example.org/fail"))
        val rejected = assertInstanceOf(DesktopExternalActionTarget.Rejected::class.java, result)

        assertEquals(DesktopExternalActionTarget.Rejection.SourceResolutionFailed, rejected.reason)
        val cancelled = ResolvingSource(10, UriType.Manga, failure = CancellationException("cancelled"))
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { fixture(cancelled).handler.resolve(ExternalActionInput.Search("cancel")) }
        }
    }

    private fun fixture(vararg sources: CatalogueSource): Fixture {
        val mangaRepository = FakeMangaRepository()
        val chapterRepository = FakeChapterRepository()
        val save = SaveSourceMangaForDetails(NetworkToLocalManga(mangaRepository), mangaRepository, chapterRepository)
        return Fixture(
            DesktopDeepLinkHandler(FakeDesktopSourceManager(sources.toList()), save),
            mangaRepository,
            chapterRepository,
        )
    }

    private data class Fixture(
        val handler: DesktopDeepLinkHandler,
        val mangaRepository: FakeMangaRepository,
        val chapterRepository: FakeChapterRepository,
    )

    private class ResolvingSource(
        override val id: Long,
        private val uriType: UriType,
        private val mangaResult: SManga? = manga("/manga/one", "Resolved manga"),
        private val failure: Throwable? = null,
    ) : CatalogueSource, ResolvableSource {
        var uriTypeCalls = 0
        var detailsCalls = 0
        var chapterListCalls = 0
        override val name = "Source $id"
        override val lang = "en"
        override val supportsLatest = false

        override fun getUriType(uri: String): UriType {
            uriTypeCalls++
            failure?.let { throw it }
            return uriType
        }

        override suspend fun getManga(uri: String): SManga? = mangaResult
        override suspend fun getChapter(uri: String): SChapter? =
            chapter("/chapter/2", "Chapter 2")
        override suspend fun getMangaDetails(manga: SManga): SManga = (mangaResult ?: manga).also { detailsCalls++ }
        override suspend fun getChapterList(manga: SManga): List<SChapter> {
            chapterListCalls++
            return listOf(chapter("/chapter/1", "Chapter 1"), chapter("/chapter/2", "Chapter 2"))
        }
        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
            MangasPage(emptyList(), false)
        override fun getFilterList() = FilterList()
    }

    private companion object {
        const val REPOSITORY_URI =
            "tachiyomi://add-repo?url=https%3A%2F%2Fexample.org%2Findex.min.json"

        fun manga(url: String, title: String) = SManga.create().also { it.url = url; it.title = title }
        fun chapter(url: String, name: String) = SChapter.create().also { it.url = url; it.name = name }
    }
}
