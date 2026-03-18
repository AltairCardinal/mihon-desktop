package mihon.desktop.download

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** RED — DesktopDownloadManager does not exist yet. */
class DownloadManagerTest {

    @TempDir
    lateinit var tempDir: File

    private fun manager() = DesktopDownloadManager(
        provider = DesktopDownloadProvider(baseDir = tempDir),
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
    fun `isDownloaded reflects provider state`() {
        val mgr = manager()
        assertFalse(mgr.isDownloaded(sourceId = 1L, mangaTitle = "Test", chapterName = "Ch 1"))

        // Seed a fake download on disk
        val dir = DesktopDownloadProvider(tempDir).chapterDownloadDir(1L, "Test", "Ch 1")
        dir.mkdirs()
        File(dir, "001.jpg").writeBytes(ByteArray(10))

        assertTrue(mgr.isDownloaded(sourceId = 1L, mangaTitle = "Test", chapterName = "Ch 1"))
    }
}
