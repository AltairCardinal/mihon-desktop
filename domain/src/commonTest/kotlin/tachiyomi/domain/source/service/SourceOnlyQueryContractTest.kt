package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import mihon.domain.error.AppError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** The same contract is compiled and executed on Android and Desktop JVM. */
class SourceOnlyQueryContractTest {
    @Test
    fun `source only and legacy queries preserve mode page query filters and continuation`() = runTest {
        for (legacy in listOf(false, true)) {
            val recording = RecordingSource()
            val source: Source = if (legacy) object : CatalogueSource, Source by recording {} else recording
            val service = SourceMangaSearchService()
            val filters = source.getFilterList()
            val popular = service.loadPage(source, 2, SourceMangaSearchRequest.Popular)
            assertEquals("/popular/2", popular.mangas.single().url)
            val latest = service.loadPage(source, 3, SourceMangaSearchRequest.Latest)
            assertEquals("/latest/3", latest.mangas.single().url)
            val results = service.searchAllPages(source, "作者 query", filters)
            assertEquals(listOf("/search/1", "/search/2"), results.map { it.url })
            assertEquals(listOf("popular:2", "latest:3", "search:1:作者 query", "search:2:作者 query"), recording.calls)
            assertSame(filters, recording.lastFilters)
        }
    }

    @Test
    fun `source only empty failure cancellation and generation use shared reducer contract`() = runTest {
        val service = SourceMangaSearchService()
        val source = RecordingSource()
        val old = SourcePageRequest(source.id, 1, 1, SourceQuery.Popular)
        val current = old.copy(generation = 2)
        val reducer = SourceQueryReducer()
        val loading = reducer.start(current)
        val oldResult = service.loadPageResult(source, old)
        assertSame(loading, reducer.reduce(loading, oldResult))
        source.empty = true
        assertInstanceOf(SourcePageResult.Empty::class.java, service.loadPageResult(source, current))
        source.failure = IllegalStateException("source failed")
        assertInstanceOf(SourcePageResult.Failure::class.java, service.loadPageResult(source, current))
        source.failure = CancellationException("cancelled")
        val cancelled = service.loadPageResult(source, current) as SourcePageResult.Failure
        assertEquals(AppError.Cancelled, cancelled.error)
        assertEquals(SourceRecoveryAction.None, cancelled.recoveryAction)
    }

    @Test
    fun `source only discovery follows language hidden and pinned policy`() {
        val source = RecordingSource()
        assertEquals(
            listOf(source),
            GlobalSearchSourcePolicy.select(listOf(source), setOf("en"), emptySet(), setOf("81")),
        )
        assertEquals(
            emptyList<Source>(),
            GlobalSearchSourcePolicy.select(listOf(source), setOf("ja"), emptySet(), setOf("81")),
        )
        assertEquals(
            emptyList<Source>(),
            GlobalSearchSourcePolicy.select(listOf(source), setOf("en"), setOf("81"), setOf("81")),
        )
        assertEquals(
            emptyList<Source>(),
            GlobalSearchSourcePolicy.select(listOf(source), setOf("en"), emptySet(), emptySet()),
        )
    }

    private class RecordingSource : Source {
        override val id = 81L
        override val name = "Source only query contract"
        override val lang = "en"
        override val supportsLatest = true
        val calls = mutableListOf<String>()
        var lastFilters: FilterList? = null
        var empty = false
        var failure: Throwable? = null
        override fun getFilterList() = FilterList(Filter.Header("Source filter"))
        override suspend fun getPopularManga(page: Int): MangasPage {
            calls += "popular:$page"
            return result("popular", page)
        }
        override suspend fun getLatestUpdates(page: Int): MangasPage {
            calls += "latest:$page"
            return result("latest", page)
        }
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            calls += "search:$page:$query"
            lastFilters = filters
            return result("search", page)
        }
        private fun result(mode: String, page: Int): MangasPage {
            failure?.let { throw it }
            return MangasPage(
                if (empty) {
                    emptyList()
                } else {
                    listOf(
                        SManga.create().apply {
                            url = "/$mode/$page"
                            title = "$mode $page"
                        },
                    )
                },
                page == 1,
            )
        }
    }
}
