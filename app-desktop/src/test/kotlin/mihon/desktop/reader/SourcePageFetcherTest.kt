package mihon.desktop.reader

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI

class SourcePageFetcherTest {

    private lateinit var server: MockWebServer

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun teardown() {
        server.close()
    }

    // ─── Fake source that exposes custom OkHttp client and headers ────────────

    private fun fakeSource(client: OkHttpClient, headers: Headers): CatalogueSource =
        object : CatalogueSource {
            override val id = 1L
            override val name = "FakeSource"
            override val lang = "en"
            override val supportsLatest = false
            override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
            override suspend fun getSearchManga(page: Int, q: String, f: FilterList) = MangasPage(emptyList(), false)
            override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
            override suspend fun getMangaDetails(manga: SManga) = manga
            override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
            override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
            override fun getFilterList() = FilterList()
            // Exposed via reflection by SourcePageFetcher
            fun getClient(): OkHttpClient = client
            fun getHeaders(): Headers = headers
        }

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `fetches image using source client and saves to file`() {
        val imageBytes = byteArrayOf(1, 2, 3, 4, 5)
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(imageBytes)).code(200).build())

        val source = fakeSource(OkHttpClient(), Headers.headersOf())
        val fetcher = SourcePageFetcher(source, OkHttpClient())

        val page = Page(index = 0, imageUrl = server.url("/image.jpg").toString())

        val result = runBlocking { fetcher.fetchToFile(page, tempDir) }

        assertNotNull(result)
        val file = File(URI(result!!))
        assertTrue(file.exists())
        assertTrue(file.readBytes().contentEquals(imageBytes))
    }

    @Test
    fun `sends source headers with image request`() {
        server.enqueue(MockResponse(body = "img"))

        val customHeaders = Headers.headersOf("Referer", "https://manhuagui.com/", "X-Custom", "test")
        val source = fakeSource(OkHttpClient(), customHeaders)
        val fetcher = SourcePageFetcher(source, OkHttpClient())

        val page = Page(index = 0, imageUrl = server.url("/page.jpg").toString())
        runBlocking { fetcher.fetchToFile(page, tempDir) }

        val recorded = server.takeRequest()
        assertEquals("https://manhuagui.com/", recorded.headers["Referer"])
        assertEquals("test", recorded.headers["X-Custom"])
    }

    @Test
    fun `uses fallback client when source has no getClient method`() {
        server.enqueue(MockResponse(body = "fallback"))

        val fallbackClient = OkHttpClient()
        val bareSource = object : CatalogueSource {
            override val id = 2L
            override val name = "Bare"
            override val lang = "en"
            override val supportsLatest = false
            override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)
            override suspend fun getSearchManga(page: Int, q: String, f: FilterList) = MangasPage(emptyList(), false)
            override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)
            override suspend fun getMangaDetails(manga: SManga) = manga
            override suspend fun getChapterList(manga: SManga) = emptyList<SChapter>()
            override suspend fun getPageList(chapter: SChapter) = emptyList<Page>()
            override fun getFilterList() = FilterList()
            // no getClient/getHeaders
        }

        val fetcher = SourcePageFetcher(bareSource, fallbackClient)
        assertEquals(fallbackClient, fetcher.client)

        val page = Page(index = 0, imageUrl = server.url("/img.jpg").toString())
        val result = runBlocking { fetcher.fetchToFile(page, tempDir) }
        assertNotNull(result)
    }

    @Test
    fun `returns null for page with no imageUrl`() {
        val source = fakeSource(OkHttpClient(), Headers.headersOf())
        val fetcher = SourcePageFetcher(source, OkHttpClient())
        val page = Page(index = 0) // no imageUrl

        val result = runBlocking { fetcher.fetchToFile(page, tempDir) }
        assertNull(result)
    }

    @Test
    fun `skips download if file already cached`() {
        val existingFile = File(tempDir, "page_0000.jpg")
        existingFile.writeBytes(byteArrayOf(1, 2, 3))

        val source = fakeSource(OkHttpClient(), Headers.headersOf())
        val fetcher = SourcePageFetcher(source, OkHttpClient())
        val page = Page(index = 0, imageUrl = server.url("/image.jpg").toString())

        val result = runBlocking { fetcher.fetchToFile(page, tempDir) }

        assertNotNull(result)
        assertEquals(0, server.requestCount, "Should not request a cached file")
    }

    @Test
    fun `returns null on HTTP error`() {
        server.enqueue(MockResponse(code = 403))

        val source = fakeSource(OkHttpClient(), Headers.headersOf())
        val fetcher = SourcePageFetcher(source, OkHttpClient())
        val page = Page(index = 0, imageUrl = server.url("/forbidden.jpg").toString())

        val result = runBlocking { fetcher.fetchToFile(page, tempDir) }
        assertNull(result, "Should return null on HTTP error")
    }
}
