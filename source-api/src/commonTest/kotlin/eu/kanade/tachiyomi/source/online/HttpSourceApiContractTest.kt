package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Shared AEX-01 HTTP contract executed by every source-api test target. */
class HttpSourceApiContractTest {

    @Test
    fun `optional request and parser hooks fail explicitly by default`() {
        val source = MinimalHttpSource()
        assertThrows(UnsupportedOperationException::class.java) { source.callPopularRequest(1) }
        assertThrows(UnsupportedOperationException::class.java) { source.callPopularParse(emptyResponse()) }
        assertThrows(UnsupportedOperationException::class.java) { source.callSearchRequest(1, "query") }
        assertThrows(UnsupportedOperationException::class.java) { source.callSearchParse(emptyResponse()) }
        assertThrows(UnsupportedOperationException::class.java) { source.callLatestRequest(1) }
        assertThrows(UnsupportedOperationException::class.java) { source.callLatestParse(emptyResponse()) }
        assertThrows(UnsupportedOperationException::class.java) { source.callMangaDetailsParse(emptyResponse()) }
        assertThrows(UnsupportedOperationException::class.java) { source.callChapterListParse(emptyResponse()) }
        assertThrows(UnsupportedOperationException::class.java) { source.callPageListParse(emptyResponse()) }
        assertThrows(UnsupportedOperationException::class.java) { source.callImageUrlParse(emptyResponse()) }
    }

    @Test
    fun `zero offset image overload preserves the legacy single argument override`() = withServer { server ->
        server.enqueue(HttpTestResponse(body = "network"))
        var legacyCalls = 0
        val source = object : TestHttpSource(server.baseUrl) {
            override suspend fun getImage(page: Page): Response {
                legacyCalls++
                return Response.Builder()
                    .request(Request.Builder().url(page.imageUrl!!).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("legacy".toResponseBody())
                    .build()
            }
        }

        runBlocking {
            source.getImage(RecordingPage(source.baseUrl + "/image"), existingSize = 0L).use { response ->
                assertEquals("legacy", response.body.string())
            }
        }
        assertEquals(1, legacyCalls)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `getHomeUrl returns the source base url`() = withServer { server ->
        val source = TestHttpSource(server.baseUrl)

        assertEquals(source.baseUrl, source.getHomeUrl())
    }

    @Test
    fun `getImage sends existing range and reports resumed 206 progress`() = withServer { server ->
        server.enqueue(HttpTestResponse(code = 206, body = "abc"))
        val source = TestHttpSource(server.baseUrl)
        val page = RecordingPage(source.baseUrl + "/image")

        runBlocking {
            source.getImage(page, existingSize = 4L).use { response ->
                assertEquals(3L, response.body.contentLength())
                assertEquals("abc", response.body.string())
            }
        }

        assertTrue(server.requestArrived(5_000L))
        assertEquals("bytes=4-", server.lastRange)
        assertEquals(7L to 7L, page.updates.last().let { it.bytesRead to it.contentLength })
        assertTrue(page.updates.last().done)
    }

    @Test
    fun `getImage resets progress when server ignores range with 200`() = withServer { server ->
        server.enqueue(HttpTestResponse(code = 200, body = "abc"))
        val source = TestHttpSource(server.baseUrl)
        val page = RecordingPage(source.baseUrl + "/image")

        runBlocking {
            source.getImage(page, existingSize = 4L).use { response ->
                assertEquals(3L, response.body.contentLength())
                response.body.string()
            }
        }

        assertTrue(server.requestArrived(5_000L))
        assertEquals("bytes=4-", server.lastRange)
        assertEquals(3L to 3L, page.updates.last().let { it.bytesRead to it.contentLength })
        assertTrue(page.updates.last().done)
        assertTrue(page.updates.dropLast(1).all { it.bytesRead <= 3L })
    }

    @Test
    fun `getImage preserves an explicit range header`() = withServer { server ->
        server.enqueue(HttpTestResponse(code = 206, body = "abc"))
        val source = TestHttpSource(server.baseUrl, explicitRange = true)

        runBlocking {
            source.getImage(RecordingPage(source.baseUrl + "/image"), existingSize = 4L).use {
                it.body.string()
            }
        }

        assertTrue(server.requestArrived(5_000L))
        assertEquals("bytes=9-", server.lastRange)
    }

    @Test
    fun `getImage exposes 403 429 and 500 as HttpException`() = withServer { server ->
        listOf(403, 429, 500).forEach { code ->
            server.enqueue(HttpTestResponse(code = code, body = "error"))
            val source = TestHttpSource(server.baseUrl)

            val error = runCatching {
                runBlocking {
                    source.getImage(RecordingPage(source.baseUrl + "/image"))
                }
            }.exceptionOrNull()

            assertTrue(error is HttpException)
            assertEquals(code, (error as HttpException).code)
        }
    }

    @Test
    fun `getImage reports unknown length for chunked body`() = withServer { server ->
        server.enqueue(HttpTestResponse(body = "abc", chunked = true, chunkSize = 1))
        val source = TestHttpSource(server.baseUrl)
        val page = RecordingPage(source.baseUrl + "/image")

        runBlocking {
            source.getImage(page).use { response ->
                assertEquals(-1L, response.body.contentLength())
                response.body.string()
            }
        }

        assertEquals(-1L, page.updates.last().contentLength)
        assertTrue(page.updates.last().done)
    }

    @Test
    fun `getImage cancellation cancels the production okhttp call`() = withServer { server ->
        server.enqueue(
            HttpTestResponse(
                body = "x".repeat(32_768),
                headersDelayMillis = 5_000L,
                throttleBytes = 1L,
                throttlePeriodMillis = 100L,
            ),
        )
        val source = TestHttpSource(server.baseUrl, client = server.client)

        runBlocking {
            val request = async(Dispatchers.Default) {
                source.getImage(RecordingPage(source.baseUrl + "/image"))
            }
            assertTrue(server.requestArrived(5_000L))
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            assertTrue(server.cancelled)
        }
    }

    private fun withServer(block: (HttpSourceTestServer) -> Unit) {
        val server = HttpSourceTestServer()
        try {
            block(server)
        } finally {
            server.close()
        }
    }

    private class RecordingPage(url: String) : Page(0, url = url, imageUrl = url) {
        val updates = mutableListOf<ProgressUpdate>()

        override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
            updates += ProgressUpdate(bytesRead, contentLength, done)
            super.update(bytesRead, contentLength, done)
        }
    }

    private data class ProgressUpdate(
        val bytesRead: Long,
        val contentLength: Long,
        val done: Boolean,
    )

    private open class TestHttpSource(
        override val baseUrl: String,
        private val explicitRange: Boolean = false,
        override val client: okhttp3.OkHttpClient = okhttp3.OkHttpClient(),
    ) : HttpSource() {
        override val id = 0xAE0105L
        override val name = "AEX-01 HTTP"
        override val lang = "en"
        override val supportsLatest = true

        override fun popularMangaRequest(page: Int) = Request.Builder().url(baseUrl).build()
        override fun latestUpdatesRequest(page: Int) = Request.Builder().url(baseUrl).build()
        override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
            Request.Builder().url(baseUrl).build()

        override fun popularMangaParse(response: Response) = MangasPage(emptyList(), false)
        override fun latestUpdatesParse(response: Response) = MangasPage(emptyList(), false)
        override fun searchMangaParse(response: Response) = MangasPage(emptyList(), false)
        override fun mangaDetailsParse(response: Response) = SManga.create()
        override fun chapterListParse(response: Response) = emptyList<SChapter>()
        override fun chapterPageParse(response: Response) = SChapter.create()
        override fun pageListParse(response: Response) = emptyList<Page>()
        override fun imageUrlParse(response: Response) = ""

        override fun imageRequest(page: Page): Request = Request.Builder()
            .url(page.imageUrl!!)
            .apply { if (explicitRange) header("Range", "bytes=9-") }
            .build()
    }

    private class MinimalHttpSource : HttpSource() {
        override val baseUrl = "https://example.invalid"
        override val id = 0xAE0106L
        override val name = "AEX-01 minimal HTTP"
        override val lang = "en"
        override val supportsLatest = true

        override fun getFilterList() = FilterList()

        override fun chapterPageParse(response: Response) = SChapter.create()

        fun callPopularRequest(page: Int) = popularMangaRequest(page)
        fun callPopularParse(response: Response) = popularMangaParse(response)
        fun callSearchRequest(page: Int, query: String) = searchMangaRequest(page, query, FilterList())
        fun callSearchParse(response: Response) = searchMangaParse(response)
        fun callLatestRequest(page: Int) = latestUpdatesRequest(page)
        fun callLatestParse(response: Response) = latestUpdatesParse(response)
        fun callMangaDetailsParse(response: Response) = mangaDetailsParse(response)
        fun callChapterListParse(response: Response) = chapterListParse(response)
        fun callPageListParse(response: Response) = pageListParse(response)
        fun callImageUrlParse(response: Response) = imageUrlParse(response)
    }

    private fun emptyResponse(): Response = Response.Builder()
        .request(Request.Builder().url("https://example.invalid").build())
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .build()
}
