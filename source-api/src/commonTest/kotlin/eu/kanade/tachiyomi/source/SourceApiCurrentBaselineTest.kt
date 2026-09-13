package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaImpl
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rx.Observable

/**
 * AEX-00 production baseline for the source-api targets.
 *
 * This intentionally exercises the current public Source/CatalogueSource defaults and the
 * concrete model factories. It is not an implementation of the frozen 1.6 API; AEX-01 owns
 * external binary and compatibility-bridge coverage.
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class SourceApiCurrentBaselineTest {

    @Test
    fun `current Source default methods execute the legacy production bridge`() = runTest {
        val source = BaselineSource()
        val manga = SManga.create().apply {
            url = "/manga/baseline"
            title = "Baseline"
        }

        val details = source.getMangaDetails(manga)
        val chapters = source.getChapterList(manga)
        val pages = source.getPageList(chapters.single())

        assertEquals("Baseline updated", details.title)
        assertEquals("/chapter/1", chapters.single().url)
        assertEquals("https://image.example/page-1.jpg", pages.single().imageUrl)
        assertEquals(1, source.calls)
    }

    @Test
    fun `current CatalogueSource default methods preserve pagination and filters`() = runTest {
        val source = BaselineCatalogueSource()
        val popular = source.getPopularManga(page = 1)
        val searched = source.getSearchManga(
            page = 2,
            query = "baseline",
            filters = FilterList(Filter.Header("AEX-00")),
        )
        val latest = source.getLatestUpdates(page = 1)

        assertEquals(listOf("Popular 1"), popular.mangas.map(SManga::title))
        assertTrue(popular.hasNextPage)
        assertEquals(listOf("Search baseline 2"), searched.mangas.map(SManga::title))
        assertFalse(searched.hasNextPage)
        assertEquals(listOf("Latest 1"), latest.mangas.map(SManga::title))
        assertFalse(latest.hasNextPage)
        assertEquals(1, source.lastFiltersSize)
    }

    private class BaselineSource : Source {
        var calls = 0

        override val id = 0xAE000L
        override val name = "AEX-00 Source API baseline"

        @Suppress("DEPRECATION")
        override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.just(
            manga.copy().also { it.title = "${manga.title} updated" },
        )

        @Suppress("DEPRECATION")
        override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.just(
            listOf(
                SChapter.create().apply {
                    url = "/chapter/1"
                    name = "Chapter 1"
                },
            ),
        )

        @Suppress("DEPRECATION")
        override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.just(
            listOf(Page(index = 0, imageUrl = "https://image.example/page-1.jpg")),
        ).doOnNext { calls++ }
    }

    private class BaselineCatalogueSource : CatalogueSource {
        var lastFiltersSize = -1

        override val id = 0xAE001L
        override val name = "AEX-00 Catalogue baseline"
        override val lang = "en"
        override val supportsLatest = true

        override fun getFilterList(): FilterList = FilterList()

        @Suppress("DEPRECATION")
        override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.just(
            MangasPage(listOf(manga("Popular $page")), hasNextPage = page < 2),
        )

        @Suppress("DEPRECATION")
        override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
            lastFiltersSize = filters.size
            return Observable.just(MangasPage(listOf(manga("Search $query $page")), hasNextPage = false))
        }

        @Suppress("DEPRECATION")
        override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.just(
            MangasPage(listOf(manga("Latest $page")), hasNextPage = false),
        )

        private fun manga(title: String): SManga = SMangaImpl().apply {
            url = "/manga/${title.lowercase().replace(' ', '-')}"
            this.title = title
        }
    }
}
