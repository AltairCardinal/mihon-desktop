package mihon.desktop.platform

import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import mihon.desktop.network.CF_CLEARANCE_COOKIE_NAME
import mihon.desktop.network.DesktopCloudflareCookieImportResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class DesktopNetworkHelperTest {

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
}
