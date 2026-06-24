package mihon.desktop.download

import eu.kanade.tachiyomi.network.NetworkHelper
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket

/** RED — DesktopDownloadManager does not exist yet. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerTest {

    @TempDir
    lateinit var tempDir: File

    private fun manager() = DesktopDownloadManager(
        provider = DesktopDownloadProvider(baseDir = tempDir),
    )

    private fun manager(provider: DesktopDownloadProvider, scope: TestScope) = DesktopDownloadManager(
        provider = provider,
        networkHelper = NetworkHelper(OkHttpClient()),
        workerScope = scope,
    )

    private fun jpegBytes() = byteArrayOf(
        0xFF.toByte(),
        0xD8.toByte(),
        0xFF.toByte(),
        0xD9.toByte(),
    )

    @Test
    fun `initial queue is empty`() = runTest {
        assertEquals(emptyList<DownloadItem>(), manager().queue.first())
    }

    @Test
    fun `enqueue adds item to queue`() = runTest {
        val mgr = manager()
        mgr.enqueue(
            DownloadItem(
                sourceId = 1L,
                mangaTitle = "Test",
                chapterName = "Ch 1",
                chapterId = 10L,
                pageUrls = listOf("https://example.com/1.jpg"),
            ),
        )
        assertEquals(1, mgr.queue.first().size)
        assertEquals("Ch 1", mgr.queue.first()[0].chapterName)
    }

    @Test
    fun `enqueue deduplicates by chapterId`() = runTest {
        val mgr = manager()
        val item = DownloadItem(
            sourceId = 1L, mangaTitle = "Test", chapterName = "Ch 1",
            chapterId = 10L, pageUrls = listOf("https://example.com/1.jpg"),
        )
        mgr.enqueue(item)
        mgr.enqueue(item)
        assertEquals(1, mgr.queue.first().size)
    }

    @Test
    fun `cancel removes item from queue`() = runTest {
        val mgr = manager()
        mgr.enqueue(
            DownloadItem(
                sourceId = 1L, mangaTitle = "Test", chapterName = "Ch 1",
                chapterId = 10L, pageUrls = listOf("https://example.com/1.jpg"),
            ),
        )
        mgr.cancel(chapterId = 10L)
        assertTrue(mgr.queue.first().isEmpty())
    }

    @Test
    fun `download enters error when server returns html instead of image`() = runTest {
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, port = port) {
            routing {
                get("/001.jpg") {
                    call.respondText(
                        text = "<html>forbidden</html>",
                        contentType = ContentType.Text.Html,
                        status = HttpStatusCode.Forbidden,
                    )
                }
            }
        }.start(wait = false)
        val provider = DesktopDownloadProvider(baseDir = tempDir)
        val mgr = manager(provider, this)
        val workerJob = mgr.start()

        try {
            mgr.enqueue(
                DownloadItem(
                    sourceId = 1L,
                    mangaTitle = "Test",
                    chapterName = "Ch 1",
                    chapterId = 10L,
                    pageUrls = listOf("http://localhost:$port/001.jpg"),
                ),
            )

            advanceUntilIdle()

            assertEquals(DownloadStatus.ERROR, mgr.queue.value.single().status)
            assertFalse(provider.isChapterDownloaded(1L, "Test", "Ch 1"))
            assertTrue(provider.getDownloadedPages(1L, "Test", "Ch 1").isEmpty())
        } finally {
            workerJob.cancel()
            server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        }
    }

    @Test
    fun `download stores readable image when server returns jpeg`() = runTest {
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, port = port) {
            routing {
                get("/001.jpg") {
                    call.respondBytes(
                        bytes = jpegBytes(),
                        contentType = ContentType.Image.JPEG,
                        status = HttpStatusCode.OK,
                    )
                }
            }
        }.start(wait = false)
        val provider = DesktopDownloadProvider(baseDir = tempDir)
        val mgr = manager(provider, this)
        val workerJob = mgr.start()

        try {
            mgr.enqueue(
                DownloadItem(
                    sourceId = 1L,
                    mangaTitle = "Test",
                    chapterName = "Ch 1",
                    chapterId = 10L,
                    pageUrls = listOf("http://localhost:$port/001.jpg"),
                ),
            )

            advanceUntilIdle()

            assertTrue(mgr.queue.value.isEmpty())
            assertTrue(provider.isChapterDownloaded(1L, "Test", "Ch 1"))
            assertEquals(1, provider.getDownloadedPages(1L, "Test", "Ch 1").size)
        } finally {
            workerJob.cancel()
            server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        }
    }

    @Test
    fun `isDownloaded reflects provider state`() {
        val mgr = manager()
        assertFalse(mgr.isDownloaded(sourceId = 1L, mangaTitle = "Test", chapterName = "Ch 1"))

        // Seed a fake download on disk
        val dir = DesktopDownloadProvider(tempDir).chapterDownloadDir(1L, "Test", "Ch 1")
        dir.mkdirs()
        File(dir, "001.jpg").writeBytes(jpegBytes())

        assertTrue(mgr.isDownloaded(sourceId = 1L, mangaTitle = "Test", chapterName = "Ch 1"))
    }
}
