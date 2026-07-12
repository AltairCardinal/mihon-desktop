package mihon.desktop.network

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mihon.desktop.source.MangaDexSource
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NetworkErrorContractTest {
    private val server = MockWebServer().also { it.start() }
    private val source = MangaDexSource(OkHttpClient(), Json { ignoreUnknownKeys = true }, server.url("/").toString().removeSuffix("/"), null)
    private val chapter = SChapter.create().apply { url = "/chapter/test" }

    @AfterEach fun close() = server.close()

    @Test fun `real source parses successful response`() = runTest {
        enqueue(200, """{"baseUrl":"https://img","chapter":{"hash":"abc","data":["1.jpg"],"dataSaver":[]}}""")
        assertEquals("https://img/data/abc/1.jpg", source.getPageList(chapter).single().imageUrl)
    }

    @Test fun `empty response maps to malformed data`() = assertError<AppError.MalformedData>(200, "")
    @Test fun `missing required data maps to malformed data`() = assertError<AppError.MalformedData>(200, """{"baseUrl":"x"}""")
    @Test fun `403 maps to authentication`() = assertError<AppError.Authentication>(403, "forbidden")
    @Test fun `429 preserves retry after`() = runTest {
        enqueue(429, "slow", "12")
        val thrown = assertThrows(AppErrorException::class.java) { kotlinx.coroutines.runBlocking { source.getPageList(chapter) } }
        assertEquals(12, assertInstanceOf(AppError.RateLimited::class.java, thrown.error).retryAfterSeconds)
    }
    @Test fun `500 maps to server error`() = assertError<AppError.Server>(500, "oops")
    @Test fun `malformed json maps to malformed data`() = assertError<AppError.MalformedData>(200, "not-json")

    @Test fun `empty full and saver page arrays preserve original empty page contract`() = runTest {
        enqueue(200, """{"baseUrl":"https://img","chapter":{"hash":"abc","data":[],"dataSaver":[]}}""")
        assertEquals(emptyList<eu.kanade.tachiyomi.source.model.Page>(), source.getPageList(chapter))
    }

    @Test fun `empty full pages with missing saver preserves original empty page contract`() = runTest {
        enqueue(200, """{"baseUrl":"https://img","chapter":{"hash":"abc","data":[]}}""")
        assertEquals(emptyList<eu.kanade.tachiyomi.source.model.Page>(), source.getPageList(chapter))
    }

    @Test fun `missing full page array maps to malformed data`() =
        assertError<AppError.MalformedData>(200, """{"baseUrl":"https://img","chapter":{"hash":"abc","dataSaver":[]}}""")

    private inline fun <reified T : AppError> assertError(code: Int, body: String) = runTest {
        enqueue(code, body)
        val thrown = assertThrows(AppErrorException::class.java) { kotlinx.coroutines.runBlocking { source.getPageList(chapter) } }
        assertInstanceOf(T::class.java, thrown.error)
    }

    private fun enqueue(code: Int, body: String, retryAfter: String? = null) {
        server.enqueue(MockResponse(code = code, headers = retryAfter?.let { Headers.headersOf("Retry-After", it) } ?: Headers.EMPTY, body = body))
    }
}
