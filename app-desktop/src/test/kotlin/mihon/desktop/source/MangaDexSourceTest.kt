package mihon.desktop.source

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MangaDexSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: MangaDexSource

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        source = MangaDexSource(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true; explicitNulls = false },
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    // ── getPageList ─────────────────────────────────────────────────────────

    @Test
    fun `getPageList returns image URLs from data array`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse.Builder()
                .body(AT_HOME_NORMAL)
                .build(),
        )

        val pages = source.getPageList(chapterStub())

        assertEquals(3, pages.size)
        assertEquals("https://cdn.example.com/data/abc123/page1.jpg", pages[0].imageUrl)
        assertEquals("https://cdn.example.com/data/abc123/page3.jpg", pages[2].imageUrl)
    }

    @Test
    fun `getPageList falls back to dataSaver when data is empty`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse.Builder()
                .body(AT_HOME_DATA_SAVER_ONLY)
                .build(),
        )

        val pages = source.getPageList(chapterStub())

        assertEquals(2, pages.size)
        assertEquals("https://cdn.example.com/data-saver/xyz789/saver1.jpg", pages[0].imageUrl)
    }

    @Test
    fun `getPageList returns empty when both data and dataSaver are empty`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse.Builder()
                .body(AT_HOME_EMPTY)
                .build(),
        )

        val pages = source.getPageList(chapterStub())

        assertTrue(pages.isEmpty(), "No pages when both data and dataSaver are empty")
    }

    // ── getChapterList ──────────────────────────────────────────────────────

    @Test
    fun `getChapterList skips chapters with externalUrl`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse.Builder()
                .body(CHAPTER_FEED_WITH_EXTERNAL)
                .build(),
        )

        val chapters = source.getChapterList(
            SManga.create().apply { url = "/manga/test-id" },
        )

        assertEquals(1, chapters.size, "External-only chapters must be filtered out")
        assertEquals("Ch.1: Normal Chapter", chapters[0].name)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun chapterStub() = SChapter.create().apply { url = "/chapter/some-uuid" }

    companion object {
        val AT_HOME_NORMAL = """
            {
              "result": "ok",
              "baseUrl": "https://cdn.example.com",
              "chapter": {
                "hash": "abc123",
                "data": ["page1.jpg", "page2.jpg", "page3.jpg"],
                "dataSaver": ["s-page1.jpg"]
              }
            }
        """.trimIndent()

        val AT_HOME_DATA_SAVER_ONLY = """
            {
              "result": "ok",
              "baseUrl": "https://cdn.example.com",
              "chapter": {
                "hash": "xyz789",
                "data": [],
                "dataSaver": ["saver1.jpg", "saver2.jpg"]
              }
            }
        """.trimIndent()

        val AT_HOME_EMPTY = """
            {
              "result": "ok",
              "baseUrl": "https://cdn.example.com",
              "chapter": {
                "hash": "",
                "data": [],
                "dataSaver": []
              }
            }
        """.trimIndent()

        val CHAPTER_FEED_WITH_EXTERNAL = """
            {
              "result": "ok",
              "data": [
                {
                  "id": "ch-1",
                  "type": "chapter",
                  "attributes": {
                    "volume": null,
                    "chapter": "1",
                    "title": "Normal Chapter",
                    "externalUrl": null,
                    "pages": 20
                  },
                  "relationships": []
                },
                {
                  "id": "ch-2",
                  "type": "chapter",
                  "attributes": {
                    "volume": null,
                    "chapter": "2",
                    "title": "External Only",
                    "externalUrl": "https://mangaplus.shueisha.co.jp/viewer/123",
                    "pages": 0
                  },
                  "relationships": []
                }
              ],
              "total": 2,
              "limit": 96,
              "offset": 0
            }
        """.trimIndent()
    }
}
