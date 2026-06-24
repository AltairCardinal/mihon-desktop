package mihon.desktop.smoke

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import mihon.desktop.test.http.testHttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Smoke tests for the reader HTTP API.
 *
 * These tests run the test server in-process. They must not depend on a
 * previously deployed `/Applications/Mihon Desktop.app`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReaderScenarioSmokeTestSuite {

    private lateinit var client: HttpClient
    private lateinit var server: ApplicationEngine
    private lateinit var baseUrl: String
    private var port = 8200

    @BeforeAll
    fun setup() {
        port = findAvailablePort()
        baseUrl = "http://localhost:$port/test"
        client = HttpClient(OkHttp)
        server = embeddedServer(Netty, port = port) {
            testHttpServer()
        }.start(wait = false)
    }

    @BeforeEach
    fun reset() = runBlocking {
        client.post("$baseUrl/reset")
        Unit
    }

    @AfterAll
    fun teardown() {
        client.close()
        server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }

    @Test
    fun `reader state endpoint returns closed state by default`() = runBlocking {
        val response = client.get("$baseUrl/reader/state")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"isOpen\": false"))
        assertTrue(body.contains("\"currentPage\": 0"))
        assertTrue(body.contains("\"totalPages\": 0"))
    }

    @Test
    fun `reader next page reports boundary when reader is closed`() = runBlocking {
        val response = client.post("$baseUrl/reader/next_page")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"success\":false"))
        assertTrue(body.contains("\"error\":\"Already at last page\""))
    }

    @Test
    fun `read chapter action opens reader state`() = runBlocking {
        val response = client.post("$baseUrl/action/read_chapter") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"mangaId":42,"chapterId":7,"chapterTitle":"Chapter 7: Start, Part A"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val state = client.get("$baseUrl/reader/state").bodyAsText()
        assertTrue(state.contains("\"isOpen\": true"))
        assertTrue(state.contains("\"currentChapterId\": 7"))
        assertTrue(state.contains("\"totalPages\": 20"))
        assertTrue(state.contains("\"chapterTitle\": \"Chapter 7: Start, Part A\""))
    }

    @Test
    fun `go to page updates current reader page`() = runBlocking {
        openReader()

        val response = client.post("$baseUrl/reader/go_to_page") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"page":5}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"success\":true"))
        assertTrue(body.contains("\"page\":5"))
    }

    @Test
    fun `go to page rejects out of range page`() = runBlocking {
        openReader()

        val response = client.post("$baseUrl/reader/go_to_page") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"page":99}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("\"success\":false"))
    }

    @Test
    fun `reader close closes state and records history`() = runBlocking {
        openReader()

        val response = client.post("$baseUrl/reader/close")

        assertEquals(HttpStatusCode.OK, response.status)
        val state = client.get("$baseUrl/reader/state").bodyAsText()
        assertTrue(state.contains("\"isOpen\": false"))
        assertTrue(client.get("$baseUrl/history").bodyAsText().contains("reader_close"))
    }

    private suspend fun openReader() {
        client.post("$baseUrl/action/read_chapter") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"mangaId":42,"chapterId":7,"chapterTitle":"Chapter 7"}""")
        }
    }

    private fun findAvailablePort(): Int {
        return (8200..8299).firstOrNull { candidate ->
            try {
                java.net.ServerSocket(candidate).use { true }
            } catch (_: Exception) {
                false
            }
        } ?: 8200
    }
}
