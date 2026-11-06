package mihon.desktop.download

import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.inspectSourceFailure
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import mihon.domain.error.AppError
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

class DownloadExtensionCompatibilityIntegrationTest {
    @TempDir lateinit var directory: File

    @Test
    fun `wrapped host write fatal reaches the owned scope instead of becoming a normal disk failure`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val png = ByteArrayOutputStream().also {
                ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it)
            }.toByteArray()
            repeat(4) { server.enqueue(MockResponse.Builder().body(Buffer().write(png)).build()) }
            val fatal = object : VirtualMachineError("controlled host write fatal") {}
            val escaped = CompletableDeferred<Throwable>()
            val scope =
                CoroutineScope(
                    SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
                        escaped.complete(error)
                    },
                )
            val client = OkHttpClient()
            val writes = AtomicInteger()
            val provider = DesktopDownloadProvider(directory.resolve("fatal"))
            val manager = DesktopDownloadManager(
                provider,
                workerScope = scope,
                httpClient = client,
                downloadPreferences = DesktopDownloadPreferences(InMemoryPreferenceStore()),
                retryDelay = {},
                sourceResolver = { null },
                fileOperations = object : DownloadFileOperations by DefaultDownloadFileOperations {
                    override fun writePage(tmp: File, bytes: ByteArray) {
                        writes.incrementAndGet()
                        throw java.io.IOException("wrapped host storage", fatal)
                    }
                },
            )
            try {
                assertTrue(
                    manager.enqueue(
                        DownloadItem(
                            42L,
                            "Fatal",
                            "One",
                            1L,
                            pageUrls = listOf(server.url("/image.png").toString()),
                        ),
                    ),
                )
                manager.start()
                val ordinaryFailure =
                    async { manager.queue.first { it.singleOrNull()?.status == DownloadStatus.ERROR } }
                try {
                    val actual = withTimeout(5_000) {
                        select<Throwable> {
                            escaped.onAwait { it }
                            ordinaryFailure.onAwait {
                                throw AssertionError(
                                    "Host fatal became an ordinary queue failure: ${it.single().failure}",
                                )
                            }
                        }
                    }
                    org.junit.jupiter.api.Assertions.assertSame(fatal, actual)
                    assertEquals(1, writes.get())
                    assertFalse(provider.isChapterDownloaded(42L, "Fatal", "One"))
                } finally {
                    ordinaryFailure.cancel()
                }
            } finally {
                manager.stopAndJoin()
                scope.cancel()
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["pages", "url", "image", "client", "headers"])
    fun `bad source operations stop one queued chapter without retries while a healthy chapter publishes`(
        site: String,
    ) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val png = ByteArrayOutputStream().also {
                ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it)
            }.toByteArray()
            server.enqueue(MockResponse.Builder().body(Buffer().write(png)).build())
            val client = OkHttpClient()
            val failure = NoSuchMethodError("future $site ABI")
            val calls = AtomicInteger()
            val serverBase = server.url("/").toString().removeSuffix("/")
            val bad = brokenSource(site, serverBase, client, failure, calls)
            val healthyClient = client
            val healthy = object : HttpSource() {
                override val id = 43L
                override val name = "Healthy"
                override val lang = "en"
                override val supportsLatest = false
                override val baseUrl = serverBase
                override val client = healthyClient
                override fun headersBuilder() = Headers.Builder()
                override fun chapterPageParse(response: Response) = SChapter.create()
                override suspend fun getPageList(
                    chapter: SChapter,
                ) = listOf(Page(0, imageUrl = "$baseUrl/healthy.png"))
            }
            val escaped = CompletableDeferred<Throwable>()
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
                    escaped.complete(error)
                },
            )
            val provider = DesktopDownloadProvider(directory.resolve(site))
            val retries = AtomicInteger()
            val preferences = InMemoryPreferenceStore(
                sequenceOf(
                    InMemoryPreferenceStore.InMemoryPreference("parallel_download_limit", 2, 1),
                ),
            )
            val manager = DesktopDownloadManager(
                provider,
                workerScope = scope,
                downloadPreferences = DesktopDownloadPreferences(preferences),
                httpClient = client,
                sourceResolver = { if (it == 42L) bad else healthy },
                retryDelay = { retries.incrementAndGet() },
            )
            try {
                assertTrue(
                    manager.enqueue(
                        DownloadItem(
                            sourceId = 42L,
                            mangaTitle = "Broken",
                            chapterName = "One",
                            chapterId = 1L,
                            chapterUrl = "/bad",
                        ),
                    ),
                )
                assertTrue(
                    manager.enqueue(
                        DownloadItem(
                            sourceId = 43L,
                            mangaTitle = "Healthy",
                            chapterName = "Two",
                            chapterId = 2L,
                            chapterUrl = "/healthy",
                        ),
                    ),
                )
                manager.start()
                val terminal = async {
                    manager.queue.first { queue ->
                        queue.any { it.chapterId == 1L && it.status == DownloadStatus.ERROR } &&
                            queue.none { it.chapterId == 2L }
                    }
                }
                try {
                    withTimeout(5_000) {
                        select<Unit> {
                            escaped.onAwait { throw AssertionError("Uncaught $site source failure", it) }
                            terminal.onAwait { }
                        }
                    }
                } finally {
                    terminal.cancel()
                }
                val item = manager.queue.value.single()
                val error = assertInstanceOf(AppError.ExtensionIncompatible::class.java, item.failure, site)
                val typed = assertInstanceOf(SourceCompatibilityException::class.java, error.cause, site)
                assertTrue(typed.inspectSourceFailure().causes.any { it === failure })
                assertEquals(
                    when (site) {
                        "pages" -> SourceOperationPhase.PAGE_LIST
                        "url" -> SourceOperationPhase.IMAGE_URL
                        else -> SourceOperationPhase.IMAGE_REQUEST
                    },
                    typed.phase,
                )
                assertEquals(1, calls.get(), site)
                assertEquals(0, retries.get(), site)
                assertEquals(0, item.progress)
                assertEquals(0, item.retryCount)
                assertFalse(provider.isChapterDownloaded(42L, "Broken", "One"))
                assertTrue(provider.isChapterDownloaded(43L, "Healthy", "Two"))
                assertEquals(1, server.requestCount)
                assertFalse(escaped.isCompleted)
            } finally {
                manager.stopAndJoin()
                scope.cancel()
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    private fun brokenSource(
        site: String,
        url: String,
        sourceClient: OkHttpClient,
        failure: Throwable,
        calls: AtomicInteger,
    ): CatalogueSource {
        fun fail(): Nothing {
            calls.incrementAndGet()
            throw failure
        }
        if (site == "client" ||
            site == "headers"
        ) {
            return object : CatalogueSource by mockk<CatalogueSource>(relaxed = true) {
                override val id = 42L
                override val name = "Broken"
                override suspend fun getPageList(chapter: SChapter) = listOf(Page(0, imageUrl = "$url/bad.png"))

                @Suppress("unused")
                fun getClient(): OkHttpClient = if (site == "client") fail() else sourceClient

                @Suppress("unused")
                fun getHeaders(): Headers = if (site == "headers") fail() else Headers.EMPTY
            }
        }
        return object : HttpSource() {
            override val id = 42L
            override val name = "Broken"
            override val lang = "en"
            override val supportsLatest = false
            override val baseUrl = url
            override val client = sourceClient
            override fun headersBuilder() = Headers.Builder()
            override fun chapterPageParse(response: Response) = SChapter.create()
            override suspend fun getPageList(chapter: SChapter): List<Page> = if (site == "pages") {
                fail()
            } else {
                listOf(Page(0, "/resolve", "$url/bad.png".takeUnless { site == "url" }))
            }
            override suspend fun getImageUrl(page: Page): String = if (site == "url") fail() else "$url/bad.png"
            override suspend fun getImage(page: Page): Response = fail()
        }
    }
}
