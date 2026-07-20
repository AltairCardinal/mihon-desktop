package mihon.desktop.source

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mihon.desktop.extension.SourceCallResult
import mihon.desktop.extension.safeSourceCall
import mihon.desktop.reader.SourcePageFetcher
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.util.Collections

class MangaDexSourceTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `getChapterList does not filter MangaDex all source to English only`() = runTest {
        val port = freePort()
        var requestedLimit: String? = null
        val server = embeddedServer(Netty, port = port) {
            routing {
                get("/manga/manga-id/feed") {
                    requestedLimit = call.request.queryParameters["limit"]
                    val hasLanguageFilter = !call.request.queryParameters.getAll("translatedLanguage[]").isNullOrEmpty()
                    if (hasLanguageFilter) {
                        call.respondText(chapterFeedJson(emptyList()), ContentType.Application.Json)
                    } else {
                        call.respondText(
                            chapterFeedJson(
                                listOf(
                                    ChapterFixture(id = "chapter-ja", translatedLanguage = "ja", number = "2"),
                                    ChapterFixture(id = "chapter-en", translatedLanguage = "en", number = "1"),
                                ),
                            ),
                            ContentType.Application.Json,
                        )
                    }
                }
            }
        }.start(wait = false)

        try {
            val source = source("http://localhost:$port")
            val manga = SManga.create().apply { url = "/manga/manga-id" }

            val chapters = source.getChapterList(manga)

            assertEquals("500", requestedLimit)
            assertEquals(2, chapters.size)
            assertEquals(listOf("/chapter/chapter-ja", "/chapter/chapter-en"), chapters.map { it.url })
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        }
    }

    @Test
    fun `getChapterList keeps external chapters for desktop browser routing`() = runTest {
        val port = freePort()
        val server = embeddedServer(Netty, port = port) {
            routing {
                get("/manga/manga-id/feed") {
                    call.respondText(
                        chapterFeedJson(
                            listOf(
                                ChapterFixture(
                                    id = "external-chapter",
                                    translatedLanguage = "en",
                                    number = "1",
                                    externalUrl = "https://kodansha.us/chapter/1",
                                ),
                            ),
                        ),
                        ContentType.Application.Json,
                    )
                }
            }
        }.start(wait = false)

        try {
            val source = source("http://localhost:$port")
            val manga = SManga.create().apply { url = "/manga/manga-id" }

            val chapters = source.getChapterList(manga)

            assertEquals(listOf("external:https://kodansha.us/chapter/1"), chapters.map { it.url })
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        }
    }

    @Test
    fun `getChapterList falls back to browser fetch when MangaDex rejects OkHttp as unsupported browser`() = runTest {
        val port = freePort()
        val expectedFeed = chapterFeedJson(
            listOf(ChapterFixture(id = "chapter-browser", translatedLanguage = "en", number = "1")),
        )
        val server = embeddedServer(Netty, port = port) {
            routing {
                get("/manga/manga-id/feed") {
                    call.respondText(
                        """
                            <!doctype html>
                            <html><body><h1>400: Unsupported Browser</h1></body></html>
                        """.trimIndent(),
                        ContentType.Text.Html,
                        HttpStatusCode.BadRequest,
                    )
                }
            }
        }.start(wait = false)

        try {
            val source = MangaDexSource(
                client = OkHttpClient(),
                json = Json { ignoreUnknownKeys = true },
                baseUrl = "http://localhost:$port",
                browserJsonFetcher = { url ->
                    if (url.contains("/manga/manga-id/feed")) expectedFeed else null
                },
            )
            val manga = SManga.create().apply { url = "/manga/manga-id" }

            val chapters = source.getChapterList(manga)

            assertEquals(listOf("/chapter/chapter-browser"), chapters.map { it.url })
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        }
    }

    @Test
    fun `SourcePageFetcher sends MangaDex source headers when downloading page images`() = runTest {
        val port = freePort()
        val server = embeddedServer(Netty, port = port) {
            routing {
                get("/page.jpg") {
                    val referer = call.request.header("Referer")
                    if (referer == "https://mangadex.org/") {
                        call.respondBytes(jpegBytes(), ContentType.Image.JPEG, HttpStatusCode.OK)
                    } else {
                        call.respondText("missing referer", ContentType.Text.Plain, HttpStatusCode.Forbidden)
                    }
                }
            }
        }.start(wait = false)

        try {
            val fetcher = SourcePageFetcher(
                source = source("http://localhost:$port"),
                fallbackClient = NetworkHelper(OkHttpClient()).client,
            )

            val fileUri = fetcher.fetchToFile(Page(0, imageUrl = "http://localhost:$port/page.jpg"), tempDir)

            assertNotNull(fileUri)
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        }
    }

    @Tag("integration")
    @Tag("live-network")
    @Test
    fun `built in MangaDex source loads chapters and page image from live MangaDex on desktop`() = runTest {
        val source = source("https://api.mangadex.org")
        val manga = SManga.create().apply { url = "/manga/0cfcda3b-0dcb-459b-9931-aa823e7bf403" }

        val chapters = source.getChapterList(manga)
        assertTrue(chapters.isNotEmpty(), "Expected MangaDex live chapter list to be non-empty")
        val pages = source.getPageList(chapters.first())
        assertTrue(pages.isNotEmpty(), "Expected MangaDex live page list to be non-empty")
        val fetcher = SourcePageFetcher(
            source = source,
            fallbackClient = NetworkHelper(OkHttpClient()).client,
        )
        val fileUri = fetcher.fetchToFile(pages.first(), tempDir)

        assertNotNull(fileUri, "Expected first MangaDex page image to download")
    }

    @Tag("integration")
    @Tag("live-network")
    @Test
    fun `built in MangaDex source loads chapters for manga that stayed empty in desktop database`() = runTest {
        var browserBody: String? = null
        val desktopBrowserFetcher = DesktopMangaDexBrowserJsonFetcher()
        val source = MangaDexSource(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            baseUrl = "https://api.mangadex.org",
            browserJsonFetcher = { url ->
                desktopBrowserFetcher.fetchBlocking(url).also { browserBody = it }
            },
        )
        val manga = SManga.create().apply { url = "/manga/d86cf65b-5f6c-437d-a0af-19a31f94ec55" }

        val chapters = source.getChapterList(manga)

        assertTrue(browserBody?.contains("\"result\":\"ok\"") == true, "Expected browser fallback to return a valid live feed")
        assertTrue(chapters.isNotEmpty(), "Expected the live Nagatoro feed to contain chapters")
    }

    @Tag("integration")
    @Test
    fun `large MangaDex feed completes inside desktop source timeout`() = runBlocking {
        val chapters = (1..1_901).map { index ->
            ChapterFixture(id = "chapter-$index", translatedLanguage = "en", number = index.toString())
        }
        val requestedOffsets = Collections.synchronizedList(mutableListOf<Int>())
        val port = freePort()
        val server = embeddedServer(Netty, port = port) {
            routing {
                get("/manga/manga-id/feed") {
                    val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                    requestedOffsets += offset
                    call.respondText(
                        chapterFeedJson(chapters.drop(offset).take(500), offset, chapters.size),
                        ContentType.Application.Json,
                    )
                }
            }
        }.start(wait = false)
        val source = source("http://localhost:$port")
        val listedManga = SManga.create().apply {
            url = "/manga/manga-id"
            title = "Large fixture"
        }

        try {
            val result = safeSourceCall { source.getChapterList(listedManga) }

            assertTrue(result is SourceCallResult.Success, "Expected the full feed to finish before the 30 second timeout")
            assertEquals(1_901, (result as SourceCallResult.Success).value.size)
            assertEquals(listOf(0, 500, 1_000, 1_500), requestedOffsets.toList())
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        }
    }

    private fun source(baseUrl: String) = MangaDexSource(
        client = OkHttpClient(),
        json = Json { ignoreUnknownKeys = true },
        baseUrl = baseUrl,
    )

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun jpegBytes() = byteArrayOf(
        0xFF.toByte(),
        0xD8.toByte(),
        0xFF.toByte(),
        0xD9.toByte(),
    )

    private data class ChapterFixture(
        val id: String,
        val translatedLanguage: String,
        val number: String,
        val externalUrl: String? = null,
    )

    private fun chapterFeedJson(
        chapters: List<ChapterFixture>,
        offset: Int = 0,
        total: Int = chapters.size,
    ): String {
        return """
            {
              "result": "ok",
              "response": "collection",
              "data": [
                ${chapters.joinToString(",") { chapterJson(it) }}
              ],
              "limit": 500,
              "offset": $offset,
              "total": $total
            }
        """.trimIndent()
    }

    private fun chapterJson(chapter: ChapterFixture): String {
        return """
            {
              "id": "${chapter.id}",
              "type": "chapter",
              "attributes": {
                "volume": null,
                "chapter": "${chapter.number}",
                "title": "Chapter ${chapter.number}",
                "translatedLanguage": "${chapter.translatedLanguage}",
                "externalUrl": ${chapter.externalUrl?.let { "\"$it\"" } ?: "null"}
              },
              "relationships": []
            }
        """.trimIndent()
    }
}
