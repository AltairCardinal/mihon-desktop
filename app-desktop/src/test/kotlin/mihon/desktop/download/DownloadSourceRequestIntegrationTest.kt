package mihon.desktop.download

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.error.AppError
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

class DownloadSourceRequestIntegrationTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `queued image URLs retain source headers and client`() = runBlocking {
        exercise(preResolved = true, customRequest = false)
    }

    @Test
    fun `source page list downloads retain source headers and client`() = runBlocking {
        exercise(preResolved = false, customRequest = false)
    }

    @Test
    fun `queued pages execute custom image request method body and page metadata`() = runBlocking {
        exercise(preResolved = false, customRequest = true)
    }

    @Test
    fun `source HTTP failures distinguish login access denial rate limit and server errors`() = runBlocking {
        for ((code, expected) in listOf(
            401 to AppError.Authentication::class.java,
            403 to AppError.Forbidden::class.java,
            429 to AppError.RateLimited::class.java,
            500 to AppError.Server::class.java,
        )) {
            exercise(preResolved = true, customRequest = false, responseCode = code, expectedFailure = expected)
        }
    }

    @Test
    fun `source network failure never becomes a disk error`() = runBlocking {
        exercise(true, false, expectedFailure = AppError.Network::class.java, networkFailure = true)
    }

    @Test
    fun `response body network interruption never becomes a disk error`() = runBlocking {
        exercise(true, false, expectedFailure = AppError.Network::class.java, bodyFailure = true)
    }

    private suspend fun exercise(
        preResolved: Boolean,
        customRequest: Boolean,
        responseCode: Int = 200,
        expectedFailure: Class<out AppError>? = null,
        networkFailure: Boolean = false,
        bodyFailure: Boolean = false,
    ) {
        MockWebServer().use { server ->
            server.start()
            val bytes = ByteArrayOutputStream().also {
                ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it)
            }.toByteArray()
            repeat(4) {
                server.enqueue(MockResponse.Builder().code(responseCode).body(Buffer().write(bytes)).build())
            }
            val sourceClient = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Source-Client", "present").build())
            }.build()
            val source = object : HttpSource() {
                override val id = 42L
                override val name = "protected"
                override val lang = "en"
                override val supportsLatest = false
                override val baseUrl = server.url("/").toString().removeSuffix("/")
                override val client = sourceClient

                override fun headersBuilder(): Headers.Builder = Headers.Builder()
                    .add("Referer", "$baseUrl/")
                    .add("User-Agent", "source-user-agent")

                override suspend fun getPageList(chapter: SChapter): List<Page> =
                    listOf(Page(17, "page-token", server.url("/page.png").toString()))

                override fun chapterPageParse(response: Response): SChapter = error("unused")

                override suspend fun getImage(page: Page): Response {
                    if (networkFailure) throw java.io.IOException("connection interrupted")
                    return super.getImage(page)
                }

                override fun imageRequest(page: Page): Request = if (customRequest) {
                    super.imageRequest(page).newBuilder()
                        .header("X-Source-Page", "${page.index}:${page.url}")
                        .post("signed-body".toRequestBody())
                        .build()
                } else {
                    super.imageRequest(page)
                }
            }
            val provider = DesktopDownloadProvider(tempDir)
            val manager = DesktopDownloadManager(
                provider = provider,
                downloadPreferences = DesktopDownloadPreferences(InMemoryPreferenceStore()),
                httpClient = OkHttpClient(),
                sourceResolver = { source },
                retryDelay = {},
                fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                    override suspend fun readBody(response: Response): ByteArray {
                        if (bodyFailure) throw java.io.IOException("response interrupted")
                        return DefaultDownloadFileOperations.readBody(response)
                    }
                },
            )
            val item = DownloadItem(
                sourceId = 42L,
                mangaTitle = "Protected",
                chapterName = "One",
                chapterId = 1L,
                chapterUrl = "/chapter/one",
                pageUrls = if (preResolved) listOf(server.url("/page.png").toString()) else emptyList(),
            )
            try {
                assertTrue(manager.enqueue(item))
                manager.start()
                withTimeout(10_000) {
                    manager.queue.first { it.isEmpty() || it.single().status == DownloadStatus.ERROR }
                }
                if (expectedFailure != null) {
                    org.junit.jupiter.api.Assertions.assertInstanceOf(
                        expectedFailure,
                        manager.queue.value.single().failure,
                    )
                    assertTrue(!provider.isChapterDownloaded(42, "Protected", "One"))
                    return
                }
                assertTrue(manager.queue.value.isEmpty(), manager.queue.value.toString())
                val request = server.takeRequest()
                assertEquals("${source.baseUrl}/", request.headers["Referer"])
                assertEquals("source-user-agent", request.headers["User-Agent"])
                assertEquals("present", request.headers["X-Source-Client"])
                if (customRequest) {
                    assertEquals("POST", request.method)
                    assertEquals("17:page-token", request.headers["X-Source-Page"])
                    assertEquals("signed-body", request.body?.utf8())
                }
                assertArrayEquals(
                    bytes,
                    File(provider.chapterDownloadDir(42, "Protected", "One"), "001.png").readBytes(),
                )
            } finally {
                manager.stopAndJoin()
            }
        }
    }
}
