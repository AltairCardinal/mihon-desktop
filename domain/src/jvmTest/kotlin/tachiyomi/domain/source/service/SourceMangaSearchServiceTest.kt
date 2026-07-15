package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class SourceMangaSearchServiceTest {

    @Test
    fun `shared page requests delegate popular latest and search to the existing source client`() = runBlocking {
        val source = RecordingSource()
        val service = SourceMangaSearchService()

        service.loadPageResult(source, request(1, SourceQuery.Popular))
        service.loadPageResult(source, request(2, SourceQuery.Latest))
        service.loadPageResult(source, request(3, SourceQuery.Search("author", source.getFilterList())))

        assertEquals(listOf("popular:1", "latest:2", "search:author:3"), source.calls)
    }

    @Test
    fun `first page without manga produces empty result`() = runBlocking {
        val result = SourceMangaSearchService().loadPageResult(
            RecordingSource(),
            request(1, SourceQuery.Popular),
        )

        assertInstanceOf(SourcePageResult.Empty::class.java, result)
    }

    @Test
    fun `successful page produces content with pagination metadata`() = runBlocking {
        val manga = smanga("/one", "One")
        val result = SourceMangaSearchService().loadPageResult(
            RecordingSource(popular = MangasPage(listOf(manga), true)),
            request(1, SourceQuery.Popular),
        ) as SourcePageResult.Content

        assertEquals(listOf(manga), result.items)
        assertTrue(result.hasNextPage)
    }

    @Test
    fun `403 maps to authentication and open login recovery`() = runBlocking {
        val result = failureFor(HttpException(403))

        assertInstanceOf(AppError.Authentication::class.java, result.error)
        assertEquals(SourceRecoveryAction.OpenLogin, result.recoveryAction)
    }

    @Test
    fun `429 and 500 map to retryable stable app errors`() = runBlocking {
        val rateLimited = failureFor(HttpException(429))
        val server = failureFor(HttpException(500))

        assertInstanceOf(AppError.RateLimited::class.java, rateLimited.error)
        assertEquals(SourceRecoveryAction.Retry, rateLimited.recoveryAction)
        assertEquals(500, (server.error as AppError.Server).statusCode)
        assertEquals(SourceRecoveryAction.Retry, server.recoveryAction)
    }

    @Test
    fun `malformed parser failure keeps the existing stable app error`() = runBlocking {
        val expected = AppError.MalformedData(IllegalArgumentException("broken json"))
        val result = failureFor(AppErrorException(expected))

        assertSame(expected, result.error)
        assertEquals(SourceRecoveryAction.Retry, result.recoveryAction)
    }

    @Test
    fun `IO failures map to network error with retry recovery`() = runBlocking {
        val failure = IOException("offline")

        val result = failureFor(failure)

        assertInstanceOf(AppError.Network::class.java, result.error)
        assertSame(failure, result.error.cause)
        assertEquals(SourceRecoveryAction.Retry, result.recoveryAction)
    }

    @Test
    fun `cancelled request produces cancelled without recovery`() = runBlocking {
        val result = failureFor(CancellationException("superseded"))

        assertEquals(AppError.Cancelled, result.error)
        assertEquals(SourceRecoveryAction.None, result.recoveryAction)
    }

    @Test
    fun `late old generation does not overwrite a newer query`() {
        val reducer = SourceQueryReducer()
        val current = reducer.start(request(page = 1, generation = 2, query = SourceQuery.Search("new", FilterList())))

        val stale = reducer.reduce(
            current,
            SourcePageResult.Content(
                request = request(page = 1, generation = 1, query = SourceQuery.Search("old", FilterList())),
                items = listOf(smanga("/old", "Old")),
                hasNextPage = false,
            ),
        )

        assertSame(current, stale)
    }

    @Test
    fun `later page failure preserves loaded items and attaches a page error`() {
        val reducer = SourceQueryReducer()
        val firstRequest = request(page = 1, generation = 7, query = SourceQuery.Popular)
        val firstItem = smanga("/first", "First")
        val content = reducer.reduce(
            reducer.start(firstRequest),
            SourcePageResult.Content(firstRequest, listOf(firstItem), hasNextPage = true),
        )
        val secondRequest = firstRequest.copy(page = 2)

        val loadingNextPage = reducer.start(secondRequest, content)
        val failed = reducer.reduce(
            loadingNextPage,
            SourcePageResult.Failure(
                secondRequest,
                AppError.Server(500),
                SourceRecoveryAction.Retry,
            ),
        ) as SourceQueryState.Content

        assertEquals(listOf(firstItem), failed.items)
        assertFalse(failed.isLoading)
        assertEquals(AppError.Server(500), failed.pageError?.error)
        assertEquals(SourceRecoveryAction.Retry, failed.pageError?.recoveryAction)
    }

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

    private class RecordingSource(
        private val popular: MangasPage = MangasPage(emptyList(), false),
        private val failure: Throwable? = null,
    ) : CatalogueSource {
        val calls = mutableListOf<String>()

        override val id = 99L
        override val name = "Recording"
        override val lang = "en"
        override val supportsLatest = true

        override suspend fun getPopularManga(page: Int): MangasPage {
            failure?.let { throw it }
            calls += "popular:$page"
            return popular
        }

        override suspend fun getLatestUpdates(page: Int): MangasPage {
            failure?.let { throw it }
            calls += "latest:$page"
            return MangasPage(emptyList(), false)
        }

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            failure?.let { throw it }
            calls += "search:$query:$page"
            return MangasPage(emptyList(), false)
        }

        override fun getFilterList() = FilterList()
        override suspend fun getMangaDetails(manga: SManga) = manga
        override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
        override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
    }

    private suspend fun failureFor(error: Throwable): SourcePageResult.Failure {
        return SourceMangaSearchService().loadPageResult(
            RecordingSource(failure = error),
            request(1, SourceQuery.Popular),
        ) as SourcePageResult.Failure
    }

    private fun request(
        page: Int,
        query: SourceQuery,
        generation: Long = 1,
    ) = SourcePageRequest(
        sourceId = 99L,
        page = page,
        generation = generation,
        query = query,
    )

    private fun smanga(url: String, title: String): SManga {
        return SManga.create().apply {
            this.url = url
            this.title = title
            initialized = true
        }
    }
}
