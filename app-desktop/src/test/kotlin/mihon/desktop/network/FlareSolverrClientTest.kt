package mihon.desktop.network

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FlareSolverrClientTest {
    @Test
    fun `solved challenge preserves user agent and cookies`() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(
                MockResponse(
                    body =
                        """{"status":"ok","solution":{"userAgent":"desktop-agent","cookies":[{"name":"cf_clearance","value":"token","domain":"example.com"}]}}""",
                ),
            )

            val result =
                FlareSolverrClient(server.url("/").toString().removeSuffix("/"), OkHttpClient())
                    .solve("https://example.com")

            assertEquals("desktop-agent", result?.userAgent)
            assertEquals(listOf(FlareSolverrCookie("cf_clearance", "token", "example.com")), result?.cookies)
        } finally {
            server.close()
        }
    }

    @Test
    fun `failed challenge returns null`() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse(body = """{"status":"error"}"""))

            assertNull(
                FlareSolverrClient(server.url("/").toString().removeSuffix("/"), OkHttpClient())
                    .solve("https://example.com"),
            )
        } finally {
            server.close()
        }
    }
}
