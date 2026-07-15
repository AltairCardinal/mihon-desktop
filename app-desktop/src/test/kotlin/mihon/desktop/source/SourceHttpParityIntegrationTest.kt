package mihon.desktop.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.domain.error.AppError
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceMangaSearchService
import tachiyomi.domain.source.service.SourcePageRequest
import tachiyomi.domain.source.service.SourcePageResult
import tachiyomi.domain.source.service.SourceQuery
import tachiyomi.domain.source.service.SourceRecoveryAction

class SourceHttpParityIntegrationTest {

    @Test
    fun `real HttpSource parser returns shared content`() = withServer(
        MockResponse(body = SUCCESS_JSON),
    ) { source ->
        val result = load(source) as SourcePageResult.Content

        assertEquals(listOf("/alpha"), result.items.map { it.url })
        assertEquals(true, result.hasNextPage)
    }

    @Test
    fun `real HttpSource parser returns shared empty`() = withServer(
        MockResponse(body = EMPTY_JSON),
    ) { source ->
        assertInstanceOf(SourcePageResult.Empty::class.java, load(source))
    }

    @Test
    fun `real HttpSource 403 returns open login`() = withServer(
        MockResponse(code = 403, body = "forbidden"),
    ) { source ->
        val result = load(source) as SourcePageResult.Failure

        assertInstanceOf(AppError.Authentication::class.java, result.error)
        assertEquals(SourceRecoveryAction.OpenLogin, result.recoveryAction)
    }

    @Test
    fun `real HttpSource 429 returns retry`() = withServer(
        MockResponse(code = 429, body = "slow down"),
    ) { source ->
        val result = load(source) as SourcePageResult.Failure

        assertInstanceOf(AppError.RateLimited::class.java, result.error)
        assertEquals(SourceRecoveryAction.Retry, result.recoveryAction)
    }

    @Test
    fun `real HttpSource 500 returns retry`() = withServer(
        MockResponse(code = 500, body = "server error"),
    ) { source ->
        val result = load(source) as SourcePageResult.Failure

        assertEquals(500, (result.error as AppError.Server).statusCode)
        assertEquals(SourceRecoveryAction.Retry, result.recoveryAction)
    }

    @Test
    fun `real HttpSource malformed payload returns stable malformed error`() = withServer(
        MockResponse(body = "not-json"),
    ) { source ->
        val result = load(source) as SourcePageResult.Failure

        assertInstanceOf(AppError.MalformedData::class.java, result.error)
        assertEquals(SourceRecoveryAction.Retry, result.recoveryAction)
    }

    private fun load(source: JsonHttpSource): SourcePageResult = runBlocking {
        SourceMangaSearchService().loadPageResult(
            source,
            SourcePageRequest(
                sourceId = source.id,
                page = 1,
                generation = 1,
                query = SourceQuery.Search("alpha", source.getFilterList()),
            ),
        )
    }

    private fun withServer(response: MockResponse, block: (JsonHttpSource) -> Unit) {
        MockWebServer().use { server ->
            server.enqueue(response)
            server.start()
            block(JsonHttpSource(server.url("/").toString().removeSuffix("/")))
        }
    }

    private class JsonHttpSource(
        override val baseUrl: String,
    ) : HttpSource() {
        override val id = 77L
        override val name = "JSON"
        override val lang = "en"
        override val supportsLatest = true
        override val client = OkHttpClient()

        override fun popularMangaRequest(page: Int) = request("popular", page)
        override fun latestUpdatesRequest(page: Int) = request("latest", page)
        override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
            request("search", page, query)

        override fun popularMangaParse(response: Response) = parse(response)
        override fun latestUpdatesParse(response: Response) = parse(response)
        override fun searchMangaParse(response: Response) = parse(response)

        private fun request(kind: String, page: Int, query: String? = null): Request {
            val suffix = query?.let { "&query=$it" }.orEmpty()
            return Request.Builder().url("$baseUrl/$kind?page=$page$suffix").build()
        }

        private fun parse(response: Response): MangasPage {
            val root = Json.parseToJsonElement(response.body.string()).jsonObject
            val mangas = root.getValue("mangas").jsonArray.map { element ->
                val manga = element.jsonObject
                SManga.create().apply {
                    url = manga.getValue("url").jsonPrimitive.content
                    title = manga.getValue("title").jsonPrimitive.content
                    initialized = true
                }
            }
            return MangasPage(mangas, root.getValue("hasNextPage").jsonPrimitive.boolean)
        }

        override fun mangaDetailsParse(response: Response) = SManga.create()
        override fun chapterListParse(response: Response) = emptyList<SChapter>()
        override fun chapterPageParse(response: Response) = SChapter.create()
        override fun pageListParse(response: Response) = emptyList<Page>()
        override fun imageUrlParse(response: Response) = ""
    }

    private companion object {
        const val SUCCESS_JSON =
            """{"mangas":[{"url":"/alpha","title":"Alpha"}],"hasNextPage":true}"""
        const val EMPTY_JSON = """{"mangas":[],"hasNextPage":false}"""
    }
}
