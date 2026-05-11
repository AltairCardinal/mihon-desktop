package mihon.desktop.network

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Stage 12.2 — FlareSolverrClient tests.
 *
 * Tests the FlareSolverr proxy client that automatically solves Cloudflare
 * challenges via a locally running FlareSolverr instance.
 */
class FlareSolverrClientTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() { server.close() }

    // ── solve() success path ───────────────────────────────────────────────

    @Test
    fun `solve returns FlareSolverrResult with cookies on success`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body(SOLVE_SUCCESS_JSON)
                .addHeader("Content-Type", "application/json")
                .build(),
        )

        val fsc = FlareSolverrClient(
            flareSolverrUrl = server.url("/").toString().trimEnd('/'),
            client = client,
        )

        val result = fsc.solve("https://example.com/manga")

        assertNotNull(result)
        assertEquals("Mozilla/5.0 (FlareSolverr)", result!!.userAgent)
        assertEquals(1, result.cookies.size)
        assertEquals("cf_clearance", result.cookies.first().name)
        assertEquals("abc123xyz", result.cookies.first().value)
        assertEquals(".example.com", result.cookies.first().domain)
    }

    @Test
    fun `solve sends correct request to FlareSolverr v1 endpoint`() = runBlocking {
        server.enqueue(MockResponse.Builder().body(SOLVE_SUCCESS_JSON).build())

        val fsc = FlareSolverrClient(
            flareSolverrUrl = server.url("/").toString().trimEnd('/'),
            client = client,
        )
        fsc.solve("https://example.com/manga")

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.endsWith("/v1"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("request.get"))
        assertTrue(body.contains("https://example.com/manga"))
    }

    @Test
    fun `solve returns null when FlareSolverr reports error status`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"status":"error","message":"Session error"}""")
                .build(),
        )

        val fsc = FlareSolverrClient(
            flareSolverrUrl = server.url("/").toString().trimEnd('/'),
            client = client,
        )
        val result = fsc.solve("https://example.com/manga")
        assertNull(result)
    }

    @Test
    fun `solve returns null when server is unreachable`() = runBlocking {
        // Use a port that nothing is listening on
        val fsc = FlareSolverrClient(
            flareSolverrUrl = "http://127.0.0.1:19999",
            client = OkHttpClient.Builder()
                .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )
        val result = fsc.solve("https://example.com/")
        assertNull(result)
    }

    @Test
    fun `solve returns null when response body is malformed`() = runBlocking {
        server.enqueue(MockResponse.Builder().body("not json").build())
        val fsc = FlareSolverrClient(
            flareSolverrUrl = server.url("/").toString().trimEnd('/'),
            client = client,
        )
        val result = fsc.solve("https://example.com/")
        assertNull(result)
    }

    // ── FlareSolverrResult data ────────────────────────────────────────────

    @Test
    fun `FlareSolverrResult cookie list can be empty`() {
        val result = FlareSolverrResult(userAgent = "UA", cookies = emptyList())
        assertTrue(result.cookies.isEmpty())
    }

    // ── FlareSolverrPreferences ────────────────────────────────────────────

    @Test
    fun `FlareSolverrPreferences defaults to disabled with localhost URL`() {
        val prefs = FlareSolverrPreferences()
        assertFalse(prefs.enabled)
        assertEquals("http://localhost:8191", prefs.url)
    }

    @Test
    fun `FlareSolverrPreferences can be toggled`() {
        val prefs = FlareSolverrPreferences()
        prefs.enabled = true
        assertTrue(prefs.enabled)
        prefs.url = "http://192.168.1.10:8191"
        assertEquals("http://192.168.1.10:8191", prefs.url)
    }

    companion object {
        private val SOLVE_SUCCESS_JSON = """
            {
                "status": "ok",
                "message": "",
                "solution": {
                    "url": "https://example.com/manga",
                    "status": 200,
                    "cookies": [
                        {
                            "name": "cf_clearance",
                            "value": "abc123xyz",
                            "domain": ".example.com",
                            "path": "/",
                            "expires": 9999999999.0,
                            "httpOnly": false,
                            "secure": false,
                            "sameSite": "None"
                        }
                    ],
                    "userAgent": "Mozilla/5.0 (FlareSolverr)",
                    "headers": {},
                    "response": "<html>solved</html>"
                }
            }
        """.trimIndent()
    }
}
