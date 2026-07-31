package mihon.desktop.platform

import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import mihon.desktop.network.CF_CLEARANCE_COOKIE_NAME
import mihon.desktop.network.DesktopCloudflareCookieImportResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import mihon.desktop.settings.DesktopProxyRuntimeConfig
import mihon.desktop.settings.GlobalNetworkMode
import mihon.desktop.settings.PluginNetworkMode
import mihon.desktop.settings.DesktopAppPreferences
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.runBlocking

class DesktopNetworkHelperTest {

    @Test
    fun `system proxy selector enables JVM operating system proxy discovery`() {
        val key = "java.net.useSystemProxies"
        val previous = System.getProperty(key)
        try {
            System.clearProperty(key)

            desktopSystemProxySelector()

            assertEquals("true", System.getProperty(key))
        } finally {
            if (previous == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, previous)
            }
        }
    }

    @Test
    fun `client is configured with sensible defaults`() {
        val helper = DesktopNetworkHelper(cacheDir = createTempCacheDir())
        val client = helper.client
        assertNotNull(client)
        assertTrue(client is OkHttpClient)
    }

    @Test
    fun `client has connection timeout set`() {
        val helper = DesktopNetworkHelper(cacheDir = createTempCacheDir())
        assertTrue(helper.client.connectTimeoutMillis > 0)
    }

    @Test
    fun `client has read timeout set`() {
        val helper = DesktopNetworkHelper(cacheDir = createTempCacheDir())
        assertTrue(helper.client.readTimeoutMillis > 0)
    }

    @Test
    fun `client has cache configured`() {
        val cacheDir = createTempCacheDir()
        val helper = DesktopNetworkHelper(cacheDir = cacheDir)
        assertNotNull(helper.client.cache)
    }

    @Test
    fun `client uses configured HTTP proxy for extension and source requests`() {
        val helper = DesktopNetworkHelper(
            cacheDir = createTempCacheDir(),
            globalMode = GlobalNetworkMode.MANUAL,
            proxyConfig = DesktopProxyRuntimeConfig(Proxy.Type.HTTP, "127.0.0.1", 10808),
        )

        assertEquals(Proxy.Type.HTTP, helper.client.proxy?.type())
        assertEquals(InetSocketAddress("127.0.0.1", 10808), helper.client.proxy?.address())
        helper.close()
    }

    @Test
    fun `direct mode explicitly bypasses every proxy selector`() {
        val helper = DesktopNetworkHelper(
            cacheDir = createTempCacheDir(),
            globalMode = GlobalNetworkMode.DIRECT,
            systemProxySelector = FixedProxySelector(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9999))),
        )

        assertEquals(Proxy.NO_PROXY, helper.client.proxy)
        helper.close()
    }

    @Test
    fun `system mode delegates proxy choice per destination`() {
        val selector = FixedProxySelector(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 7777)))
        val helper = DesktopNetworkHelper(
            cacheDir = createTempCacheDir(),
            globalMode = GlobalNetworkMode.SYSTEM,
            systemProxySelector = selector,
        )

        assertEquals(selector, helper.client.proxySelector)
        assertEquals(null, helper.client.proxy)
        assertEquals(
            Proxy.Type.HTTP,
            helper.client.proxySelector.select(URI("https://example.org")).single().type(),
        )
        helper.close()
    }

    @Test
    fun `source client applies plugin override instead of global policy`() {
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore())
        val packageName = "pkg.manual"
        preferences.pluginNetworkMode(packageName).set(PluginNetworkMode.MANUAL)
        preferences.pluginProxyUrl(packageName).set("socks5://127.0.0.1:7890")
        val helper = DesktopNetworkHelper(
            cacheDir = createTempCacheDir(),
            globalMode = GlobalNetworkMode.DIRECT,
            appPreferences = preferences,
        )
        helper.bindSourceOwner { sourceId -> if (sourceId == 42L) packageName else null }

        val pluginClient = helper.clientForSource(42L)
        val selectedProxy = pluginClient.proxySelector.select(URI("https://example.org")).single()
        assertEquals(Proxy.Type.SOCKS, selectedProxy.type())
        assertEquals(InetSocketAddress("127.0.0.1", 7890), selectedProxy.address())
        assertEquals(Proxy.NO_PROXY, helper.client.proxy)
        helper.close()
    }

    @Test
    fun `managed plugin request observes exact host and actual route`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = "ok"))
            val preferenceNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
            val preferences = DesktopAppPreferences(DesktopPreferenceStore(preferenceNode))
            val packageName = "pkg.observed"
            val helper = DesktopNetworkHelper(
                cacheDir = createTempCacheDir(),
                globalMode = GlobalNetworkMode.DIRECT,
                appPreferences = preferences,
            )
            helper.bindSourceOwner { packageName }

            helper.clientForSource(7L)
                .newCall(Request.Builder().url(server.url("/secret/path?token=hidden")).build())
                .execute()
                .use { assertEquals(200, it.code) }

            assertEquals(setOf(server.hostName), preferences.pluginObservedDomains(packageName).get())
            val route = helper.routeObservations.value.last()
            assertEquals(packageName, route.scope)
            assertEquals(server.hostName, route.host)
            assertEquals(Proxy.Type.DIRECT, route.proxyType)
            helper.close()
            preferenceNode.removeNode()
        }
    }

    @Test
    fun `managed plugin observes redirect target without storing URL details`() {
        MockWebServer().use { redirectServer ->
            MockWebServer().use { targetServer ->
                redirectServer.start()
                targetServer.start()
                val targetUrl = targetServer.url("/redirected/secret?token=hidden")
                    .newBuilder()
                    .host("127.0.0.1")
                    .build()
                redirectServer.enqueue(
                    MockResponse(
                        code = 302,
                        headers = Headers.headersOf("Location", targetUrl.toString()),
                    ),
                )
                targetServer.enqueue(MockResponse(body = "ok"))
                val preferenceNode = Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")
                val preferences = DesktopAppPreferences(DesktopPreferenceStore(preferenceNode))
                val helper = DesktopNetworkHelper(
                    cacheDir = createTempCacheDir(),
                    globalMode = GlobalNetworkMode.DIRECT,
                    appPreferences = preferences,
                )
                helper.bindSourceOwner { "pkg.redirect" }

                helper.clientForSource(7L)
                    .newCall(Request.Builder().url(redirectServer.url("/start")).build())
                    .execute()
                    .use { assertEquals(200, it.code) }

                assertEquals(
                    setOf(redirectServer.hostName, "127.0.0.1"),
                    preferences.pluginObservedDomains("pkg.redirect").get(),
                )
                assertEquals(
                    setOf(redirectServer.hostName, "127.0.0.1"),
                    helper.routeObservations.value.map { it.host }.toSet(),
                )
                helper.close()
                preferenceNode.removeNode()
            }
        }
    }

    @Test
    fun `connection test uses its short diagnostic timeout`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .onResponseStart(SocketEffect.Stall)
                    .build(),
            )
            val helper = DesktopNetworkHelper(
                cacheDir = createTempCacheDir(),
                globalMode = GlobalNetworkMode.DIRECT,
                connectionTestTimeoutMillis = 100,
            )

            val result = helper.testConnection(server.url("/stalled").toString())

            assertFalse(result.successful)
            assertTrue(result.error.orEmpty().contains("timeout", ignoreCase = true))
            helper.close()
        }
    }

    @Test
    fun `connection test retries a transient timeout with a fresh diagnostic connection`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .onResponseStart(SocketEffect.Stall)
                    .build(),
            )
            server.enqueue(MockResponse(code = 204))
            val helper = DesktopNetworkHelper(
                cacheDir = createTempCacheDir(),
                globalMode = GlobalNetworkMode.DIRECT,
                connectionTestTimeoutMillis = 100,
            )

            val result = helper.testConnection(server.url("/diagnostic").toString())

            assertTrue(result.successful, result.error)
            assertEquals(204, result.statusCode)
            assertEquals(2, server.requestCount)
            helper.close()
        }
    }

    @Test
    fun `default client leaves content decoding to OkHttp and source specific configuration`() {
        val helper = DesktopNetworkHelper(cacheDir = createTempCacheDir())

        assertTrue(
            helper.client.networkInterceptors.none {
                it.javaClass.simpleName == "IgnoreGzipInterceptor" ||
                    it.javaClass.simpleName == "BrotliInterceptor"
            },
        )
        helper.close()
    }

    @Test
    fun `cookieJar is available`() {
        val helper = DesktopNetworkHelper(cacheDir = createTempCacheDir())
        assertNotNull(helper.cookieJar)
    }

    @Test
    fun `extension cookie port clears only valid source domains`() {
        val helper = DesktopNetworkHelper(
            cacheDir = createTempCacheDir(),
            cookieStorageFile = File(createTempCacheDir(), "cookies.json"),
        )
        val sourceUrl = "https://source.example/path".toHttpUrl()
        val otherUrl = "https://other.example/path".toHttpUrl()
        helper.cookieJar.saveFromResponse(
            sourceUrl,
            listOf(Cookie.Builder().name("source").value("one").domain(sourceUrl.host).build()),
        )
        helper.cookieJar.saveFromResponse(
            otherUrl,
            listOf(Cookie.Builder().name("other").value("two").domain(otherUrl.host).build()),
        )

        assertEquals(1, helper.clearCookies(listOf(TestHttpSource(sourceUrl.toString()), TestHttpSource("not a url"))))
        assertTrue(helper.cookieJar.loadForRequest(sourceUrl).isEmpty())
        assertEquals(listOf("other"), helper.cookieJar.loadForRequest(otherUrl).map { cookie: Cookie -> cookie.name })
        helper.close()
    }

    @Test
    fun `network maintenance port validates imports canonical host and clears all cookies`() {
        val helper = DesktopNetworkHelper(
            cacheDir = createTempCacheDir(),
            cookieStorageFile = File(createTempCacheDir(), "cookies.json"),
        )
        val imported = helper.importCloudflareCookie("例子.测试", "clearance-secret")
        assertEquals(
            DesktopCloudflareCookieImportResult.Imported("xn--fsqu00a.xn--0zwm56d"),
            imported,
        )
        val url = "https://xn--fsqu00a.xn--0zwm56d/".toHttpUrl()
        assertEquals(
            listOf(CF_CLEARANCE_COOKIE_NAME),
            helper.cookieJar.loadForRequest(url).map { cookie: Cookie -> cookie.name },
        )
        assertEquals(
            DesktopCloudflareCookieImportResult.InvalidDomain,
            helper.importCloudflareCookie("", "value"),
        )
        assertEquals(
            DesktopCloudflareCookieImportResult.InvalidValue,
            helper.importCloudflareCookie("example.com", ""),
        )

        helper.clearCookies()
        assertTrue(helper.cookieJar.loadForRequest(url).isEmpty())
        helper.close()
    }

    private fun createTempCacheDir(): File {
        return File(System.getProperty("java.io.tmpdir"), "mihon-test-cache-${System.nanoTime()}").apply {
            mkdirs()
            deleteOnExit()
        }
    }

    private class TestHttpSource(override val baseUrl: String) : HttpSource() {
        override val id: Long = baseUrl.hashCode().toLong()
        override val name: String = "Test"
        override val lang: String = "en"
        override val supportsLatest: Boolean = false
        override val client: OkHttpClient = OkHttpClient()

        override fun popularMangaRequest(page: Int): Request = error("not used")
        override fun popularMangaParse(response: Response): MangasPage = error("not used")
        override fun latestUpdatesRequest(page: Int): Request = error("not used")
        override fun latestUpdatesParse(response: Response): MangasPage = error("not used")
        override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = error("not used")
        override fun searchMangaParse(response: Response): MangasPage = error("not used")
        override fun mangaDetailsParse(response: Response): SManga = error("not used")
        override fun chapterListParse(response: Response): List<SChapter> = error("not used")
        override fun chapterPageParse(response: Response): SChapter = error("not used")
        override fun pageListParse(response: Response): List<Page> = error("not used")
        override fun imageUrlParse(response: Response): String = error("not used")
    }

    private class FixedProxySelector(private val proxy: Proxy) : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(proxy)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
    }
}
