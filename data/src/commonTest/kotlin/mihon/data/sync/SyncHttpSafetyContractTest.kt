package mihon.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import mihon.data.sync.http.SyncHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SyncHttpSafetyContractTest {
    @Test
    fun `short and empty HTTP bodies are complete responses rather than unexpected EOF`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val http = SyncHttpClient(OkHttpClient(), setOf(server.url("/").host), maxBodyBytes = 16)
            for (body in listOf("", "{}", "x".repeat(16))) {
                server.enqueue(MockResponse(body = body))
                val response = http.execute(http.request(server.url("/").toString(), "GET"))
                assertEquals(200, response.code)
                assertEquals(body, response.body.decodeToString())
            }
        }
    }

    @Test
    fun `response over the configured limit fails instead of returning truncated success`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = "x".repeat(17)))
            val http = SyncHttpClient(OkHttpClient(), setOf(server.url("/").host), maxBodyBytes = 16)
            val outcome = runCatching { http.execute(http.request(server.url("/").toString(), "GET")) }
            assertTrue(outcome.isFailure)
        }
    }

    @Test
    fun `chunked responses enforce the size limit while reading without a content length`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val http = SyncHttpClient(OkHttpClient(), setOf(server.url("/").host), maxBodyBytes = 16)
            for (size in listOf(16, 17)) {
                server.enqueue(MockResponse.Builder().chunkedBody("x".repeat(size), 3).build())
                val result = runCatching { http.execute(http.request(server.url("/chunked").toString(), "GET")) }
                assertEquals(size > 16, result.isFailure)
                if (size == 16) {
                    val response = result.getOrThrow()
                    assertNull(response.headers["content-length"])
                    assertEquals("chunked", response.headers["transfer-encoding"])
                    assertEquals("x".repeat(size), response.body.decodeToString())
                }
            }
        }
    }

    @Test
    fun `source cookies and explicit cookie headers never accompany sync credentials`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = "{}"))
            val cookieReads = AtomicInteger()
            val cookieWrites = AtomicInteger()
            val production = OkHttpClient.Builder().cookieJar(object : CookieJar {
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    cookieReads.incrementAndGet()
                    return listOf(Cookie.Builder().name("source").value("source-cookie").domain(url.host).build())
                }

                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    cookieWrites.incrementAndGet()
                }
            }).build()
            val http = SyncHttpClient(production, setOf(server.url("/").host))
            http.execute(
                http.request(
                    server.url("/sync").toString(),
                    "GET",
                    mapOf(
                        "Authorization" to "Bearer sync-access-test-value",
                        "Cookie" to "explicit=source-cookie",
                    ),
                ),
            )
            val request = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(request)
            assertEquals("Bearer sync-access-test-value", request!!.headers["Authorization"])
            assertNull(request.headers["Cookie"])
            assertEquals(0, cookieReads.get())
            assertEquals(0, cookieWrites.get())
        }
    }

    @Test
    fun `production application and network HTTP loggers cannot see sync secrets`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = "{\"access_token\":\"response-access-test-value\"}"))
            val lines = mutableListOf<String>()
            fun logger() = HttpLoggingInterceptor { line -> synchronized(lines) { lines += line } }
                .setLevel(HttpLoggingInterceptor.Level.BODY)
            val production = OkHttpClient.Builder()
                .addInterceptor(logger())
                .addNetworkInterceptor(logger())
                .build()
            val http = SyncHttpClient(production, setOf(server.url("/").host))
            val response = http.execute(
                http.request(
                    server.url("/sync").toString(),
                    "GET",
                    mapOf("Authorization" to "Bearer request-access-test-value"),
                ),
            )
            assertEquals(200, response.code)
            assertFalse(
                lines.any {
                    it.contains("request-access-test-value") ||
                        it.contains("response-access-test-value")
                },
            )
        }
    }

    @Test
    fun `sync requests actually use the injected production proxy selector and DNS`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = "{}"))
            val proxySelections = AtomicInteger()
            val dnsLookups = AtomicInteger()
            val production = OkHttpClient.Builder()
                .proxySelector(object : ProxySelector() {
                    override fun select(uri: URI): List<Proxy> {
                        proxySelections.incrementAndGet()
                        return listOf(Proxy.NO_PROXY)
                    }

                    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
                })
                .dns(
                    Dns {
                        dnsLookups.incrementAndGet()
                        listOf(InetAddress.getByName("127.0.0.1"))
                    },
                )
                .build()
            val http = SyncHttpClient(production, setOf("localhost"))
            val url = server.url("/sync").newBuilder().host("localhost").build()
            assertEquals(200, http.execute(http.request(url.toString(), "GET")).code)
            assertTrue(proxySelections.get() > 0)
            assertTrue(dnsLookups.get() > 0)
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `disallowed host is rejected before any production network request`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val http = SyncHttpClient(OkHttpClient(), setOf("api.github.com"))
            val outcome = runCatching { http.execute(http.request(server.url("/sync").toString(), "GET")) }
            assertTrue(outcome.isFailure)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `sync ignores the source disk cache for credential-bearing requests`(@TempDir directory: Path) = runTest {
        MockWebServer().use { server ->
            server.start()
            Cache(directory.toFile(), 1024L * 1024).use { sourceCache ->
                val headers = headersOf("Cache-Control", "public, max-age=600", "Vary", "Authorization")
                server.enqueue(MockResponse(headers = headers, body = "first"))
                server.enqueue(MockResponse(headers = headers, body = "second"))
                val production = OkHttpClient.Builder().cache(sourceCache).build()
                val http = SyncHttpClient(production, setOf(server.url("/").host))
                val request = http.request(
                    server.url("/sync").toString(),
                    "GET",
                    mapOf(
                        "Authorization" to "Bearer private-sync-test-value",
                    ),
                )
                assertEquals("first", http.execute(request).body.decodeToString())
                assertEquals("second", http.execute(request).body.decodeToString())
                assertEquals(2, server.requestCount)
                sourceCache.flush()
                assertFalse(sourceCache.urls().hasNext())
            }
        }
    }

    @Test
    fun `cancelling after response headers promptly cancels a delayed response body`() = runTest {
        MockWebServer().use { server ->
            server.start()
            // Deliver the first byte immediately so responseBodyStart really precedes the stall.
            server.enqueue(MockResponse.Builder().body("xy").throttleBody(1, 4, TimeUnit.SECONDS).build())
            val bodyReadStarted = CountDownLatch(1)
            val production = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) {
                    bodyReadStarted.countDown()
                }
            }).build()
            val http = SyncHttpClient(production, setOf(server.url("/").host))
            val pending = async(Dispatchers.IO) {
                http.execute(http.request(server.url("/delayed").toString(), "GET"))
            }
            assertTrue(bodyReadStarted.await(5, TimeUnit.SECONDS))
            val beforeCancel = System.nanoTime()
            pending.cancelAndJoin()
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beforeCancel)
            assertTrue(elapsedMillis < 1_500, "Cancellation waited $elapsedMillis ms for response body")
        }
    }

    @Test
    fun `response diagnostics never expose credential bearing headers or payload bytes`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse(
                    headers = headersOf(
                        "Set-Cookie",
                        "private=response-secret",
                        "X-Reflected-Token",
                        "response-secret",
                    ),
                    body = "response-secret",
                ),
            )
            val http = SyncHttpClient(OkHttpClient(), setOf(server.url("/").host))
            val response = http.execute(http.request(server.url("/sync").toString(), "GET"))
            assertEquals("response-secret", response.body.decodeToString())
            assertFalse(response.toString().contains("response-secret"))
            assertFalse(response.toString().contains(response.body.joinToString()))
        }
    }

    @Test
    fun `production response body exceptions are converted to safe HTTP failures`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = "{}"))
            val production = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) {
                    throw IllegalStateException("synthetic-response-secret")
                }
            }).build()
            val http = SyncHttpClient(production, setOf(server.url("/").host))
            val result = runCatching { http.execute(http.request(server.url("/sync").toString(), "GET")) }
            assertTrue(result.isFailure)
            assertFalse(result.exceptionOrNull()!!.stackTraceToString().contains("synthetic-response-secret"))
        }
    }
}
