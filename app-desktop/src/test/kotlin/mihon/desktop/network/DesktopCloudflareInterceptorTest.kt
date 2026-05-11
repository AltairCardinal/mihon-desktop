package mihon.desktop.network

import eu.kanade.tachiyomi.network.DesktopCookieJar
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class DesktopCloudflareInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var cookieJar: DesktopCookieJar
    private lateinit var challengeManager: CloudflareChallengeManager
    private lateinit var interceptor: DesktopCloudflareInterceptor
    private lateinit var client: OkHttpClient

    private val cfChallengeHtml = """
        <html>
        <head><title>Just a moment...</title></head>
        <body>
            <div id="challenge-error-title">Enable JavaScript and cookies to continue</div>
        </body>
        </html>
    """.trimIndent()

    private val cfChallengeTextHtml = """
        <html>
        <body>
            <div id="challenge-error-text">Please turn JavaScript on</div>
        </body>
        </html>
    """.trimIndent()

    private val cfGeoBlockHtml = """
        <html>
        <body>
            <div id="cf-error-details">Sorry, you have been blocked</div>
        </body>
        </html>
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        cookieJar = DesktopCookieJar()
        challengeManager = CloudflareChallengeManager()
        interceptor = DesktopCloudflareInterceptor(cookieJar, challengeManager)
        client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(interceptor)
            .build()
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `normal 200 response is not intercepted`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("<html>OK</html>")
                .build(),
        )

        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
        assertEquals(200, response.code)
        response.close()
    }

    @Test
    fun `403 with cloudflare server and challenge title is intercepted`() {
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("Server", "cloudflare")
                .body(cfChallengeHtml)
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body("OK").build())

        val thread = Thread {
            Thread.sleep(100)
            val challenge = pollChallenge()
            if (challenge != null) {
                cookieJar.addManual(server.url("/"), "cf_clearance", "bypass_token")
                challenge.resolved = true
                challenge.latch.countDown()
            }
        }
        thread.start()

        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
        assertEquals(200, response.code)
        response.close()
        thread.join()
    }

    @Test
    fun `503 with cloudflare-nginx server and challenge text is intercepted`() {
        server.enqueue(
            MockResponse.Builder()
                .code(503)
                .addHeader("Server", "cloudflare-nginx")
                .body(cfChallengeTextHtml)
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body("OK").build())

        val thread = Thread {
            Thread.sleep(100)
            val challenge = pollChallenge()
            if (challenge != null) {
                cookieJar.addManual(server.url("/"), "cf_clearance", "token")
                challenge.resolved = true
                challenge.latch.countDown()
            }
        }
        thread.start()

        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
        assertEquals(200, response.code)
        response.close()
        thread.join()
    }

    @Test
    fun `403 with non-cloudflare server is not intercepted`() {
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("Server", "nginx")
                .body("<html>Forbidden</html>")
                .build(),
        )

        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
        assertEquals(403, response.code)
        response.close()
    }

    @Test
    fun `403 cloudflare geo-block without challenge element is not intercepted`() {
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("Server", "cloudflare")
                .body(cfGeoBlockHtml)
                .build(),
        )

        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()
        assertEquals(403, response.code)
        response.close()
    }

    @Test
    fun `unresolved challenge throws IOException`() {
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("Server", "cloudflare")
                .body(cfChallengeHtml)
                .build(),
        )

        val thread = Thread {
            Thread.sleep(100)
            val challenge = pollChallenge()
            challenge?.latch?.countDown() // countDown without resolving
        }
        thread.start()

        assertThrows<IOException> {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute()
        }
        thread.join()
    }

    @Test
    fun `challenge removes old cf_clearance cookie before emitting`() {
        val url = server.url("/")
        cookieJar.addManual(url, "cf_clearance", "old_value")

        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("Server", "cloudflare")
                .body(cfChallengeHtml)
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body("OK").build())

        val thread = Thread {
            Thread.sleep(100)
            val challenge = pollChallenge()
            if (challenge != null) {
                val cookies = cookieJar.get(url)
                assertTrue(cookies.none { it.name == "cf_clearance" })

                cookieJar.addManual(url, "cf_clearance", "new_value")
                challenge.resolved = true
                challenge.latch.countDown()
            }
        }
        thread.start()

        val response = client.newCall(Request.Builder().url(url).build()).execute()
        assertEquals(200, response.code)
        response.close()
        thread.join()
    }

    private fun pollChallenge(): CloudflareChallenge? {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 5000) {
            val challenge = challengeManager.tryReceive()
            if (challenge != null) return challenge
            Thread.sleep(10)
        }
        return null
    }
}
