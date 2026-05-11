package mihon.desktop.download

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DownloadManagerReorderTest {

    @TempDir
    lateinit var tempDir: File

    private fun manager() = DesktopDownloadManager(
        provider = DesktopDownloadProvider(baseDir = tempDir),
    )

    private fun item(id: Long, name: String) = DownloadItem(
        chapterId = id,
        chapterUrl = "/ch/$id",
        chapterName = name,
        mangaTitle = "Manga",
        sourceId = 1L,
        pageUrls = emptyList(),
    )

    @Test
    fun `reorderItem moves item from lower to higher index`() = runTest {
        val mgr = manager()
        val a = item(1L, "Ch 1")
        val b = item(2L, "Ch 2")
        val c = item(3L, "Ch 3")
        mgr.enqueue(a); mgr.enqueue(b); mgr.enqueue(c)

        mgr.reorderItem(from = 0, to = 2)

        val queue = mgr.queue.first()
        assertEquals(listOf(b.chapterId, c.chapterId, a.chapterId), queue.map { it.chapterId })
    }

    @Test
    fun `reorderItem moves item from higher to lower index`() = runTest {
        val mgr = manager()
        val a = item(1L, "Ch 1")
        val b = item(2L, "Ch 2")
        val c = item(3L, "Ch 3")
        mgr.enqueue(a); mgr.enqueue(b); mgr.enqueue(c)

        mgr.reorderItem(from = 2, to = 0)

        val queue = mgr.queue.first()
        assertEquals(listOf(c.chapterId, a.chapterId, b.chapterId), queue.map { it.chapterId })
    }

    @Test
    fun `reorderItem with same from and to is no-op`() = runTest {
        val mgr = manager()
        val a = item(1L, "Ch 1")
        val b = item(2L, "Ch 2")
        mgr.enqueue(a); mgr.enqueue(b)

        mgr.reorderItem(from = 0, to = 0)

        val queue = mgr.queue.first()
        assertEquals(listOf(a.chapterId, b.chapterId), queue.map { it.chapterId })
    }
}
