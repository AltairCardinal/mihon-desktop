package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceMangaSearchServiceTest {

    @Test
    fun `searchAllPages reuses source search pagination until last page`() = runBlocking {
        val source = StubSource(
            pages = mapOf(
                1 to MangasPage(listOf(smanga("/fire-force", "炎炎消防队")), true),
                2 to MangasPage(listOf(smanga("/soul-eater", "噬魂师")), true),
                3 to MangasPage(listOf(smanga("/soul-eater-not", "噬魂师NOT")), false),
            ),
        )

        val results = SourceMangaSearchService().searchAllPages(
            source = source,
            query = "大久保笃",
            filters = source.getFilterList(),
        )

        assertEquals(listOf(1, 2, 3), source.requestedPages)
        assertEquals(
            listOf("/fire-force", "/soul-eater", "/soul-eater-not"),
            results.map { it.url },
        )
    }

    @Test
    fun `loadPage uses search when query is present`() = runBlocking {
        val source = StubSource(
            pages = mapOf(1 to MangasPage(listOf(smanga("/result", "Result")), false)),
        )

        val page = SourceMangaSearchService().loadPage(
            source = source,
            page = 1,
            request = SourceMangaSearchRequest.Search("作者", source.getFilterList()),
        )

        assertEquals(listOf(1), source.requestedPages)
        assertEquals("作者", source.requestedQueries.single())
        assertEquals("/result", page.mangas.single().url)
    }

    private class StubSource(
        private val pages: Map<Int, MangasPage>,
    ) : CatalogueSource {
        val requestedPages = mutableListOf<Int>()
        val requestedQueries = mutableListOf<String>()

        override val id: Long = 1L
        override val name: String = "Stub"
        override val lang: String = "zh"
        override val supportsLatest: Boolean = true

        override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            requestedPages += page
            requestedQueries += query
            return pages[page] ?: MangasPage(emptyList(), false)
        }
        override fun getFilterList() = FilterList()
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    private fun smanga(url: String, title: String): SManga {
        return SManga.create().apply {
            this.url = url
            this.title = title
            initialized = true
        }
    }
}
