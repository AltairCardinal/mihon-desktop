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

    private fun item(
        id: Long,
        name: String,
        sourceId: Long = 1L,
        status: DownloadStatus = DownloadStatus.QUEUED,
    ) = DownloadItem(
        chapterId = id,
        chapterUrl = "/ch/$id",
        chapterName = name,
        mangaTitle = "Manga",
        sourceId = sourceId,
        pageUrls = emptyList(),
        status = status,
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

    @Test
    fun `reorderItem rejects moves across source groups`() = runTest {
        val mgr = manager()
        val sourceOne = item(1L, "Source one", sourceId = 1L)
        val sourceTwo = item(2L, "Source two", sourceId = 2L)
        mgr.enqueue(sourceOne)
        mgr.enqueue(sourceTwo)

        mgr.reorderItem(from = 0, to = 1)

        assertEquals(listOf(sourceOne, sourceTwo), mgr.queue.first())
    }

    @Test
    fun `reorderItem moves only the selected source items when sources are interleaved`() = runTest {
        val mgr = manager()
        val sourceOneFirst = item(1L, "Source one first", sourceId = 1L)
        val sourceTwo = item(2L, "Source two", sourceId = 2L)
        val sourceOneSecond = item(3L, "Source one second", sourceId = 1L)
        listOf(sourceOneFirst, sourceTwo, sourceOneSecond).forEach(mgr::enqueue)

        mgr.reorderItem(from = 0, to = 2)

        assertEquals(listOf(sourceOneSecond, sourceTwo, sourceOneFirst), mgr.queue.first())
    }

    @Test
    fun `sortQueue sorts inside each source without changing source group order`() = runTest {
        val mgr = manager()
        val sourceOneLater = item(30L, "Source one later", sourceId = 1L)
        val sourceTwoEarlier = item(10L, "Source two earlier", sourceId = 2L)
        val sourceOneEarlier = item(20L, "Source one earlier", sourceId = 1L)
        val sourceTwoLater = item(40L, "Source two later", sourceId = 2L)
        listOf(sourceOneLater, sourceTwoEarlier, sourceOneEarlier, sourceTwoLater).forEach(mgr::enqueue)

        mgr.sortQueue { it.chapterId }

        assertEquals(
            listOf(sourceOneEarlier, sourceOneLater, sourceTwoEarlier, sourceTwoLater),
            mgr.queue.first(),
        )
    }

    @Test
    fun `reverseQueue reverses items inside each source without reversing source groups`() = runTest {
        val mgr = manager()
        val sourceOneEarlier = item(20L, "Source one earlier", sourceId = 1L)
        val sourceTwoEarlier = item(10L, "Source two earlier", sourceId = 2L)
        val sourceOneLater = item(30L, "Source one later", sourceId = 1L)
        val sourceTwoLater = item(40L, "Source two later", sourceId = 2L)
        listOf(sourceOneEarlier, sourceTwoEarlier, sourceOneLater, sourceTwoLater).forEach(mgr::enqueue)

        mgr.reverseQueue()

        assertEquals(
            listOf(sourceOneLater, sourceOneEarlier, sourceTwoLater, sourceTwoEarlier),
            mgr.queue.first(),
        )
    }

    @Test
    fun `sortQueue keeps all downloading items first and stable inside every source`() = runTest {
        val mgr = manager()
        val sourceOneQueuedLow = item(10L, "S1 queued low", sourceId = 1L)
        val sourceTwoQueuedLow = item(50L, "S2 queued low", sourceId = 2L)
        val sourceOneDownloadingFirst = item(40L, "S1 active first", sourceId = 1L, status = DownloadStatus.DOWNLOADING)
        val sourceTwoDownloadingFirst = item(70L, "S2 active first", sourceId = 2L, status = DownloadStatus.DOWNLOADING)
        val sourceOneDownloadingSecond = item(30L, "S1 active second", sourceId = 1L, status = DownloadStatus.DOWNLOADING)
        val sourceTwoDownloadingSecond = item(60L, "S2 active second", sourceId = 2L, status = DownloadStatus.DOWNLOADING)
        val sourceOneQueuedHigh = item(20L, "S1 queued high", sourceId = 1L)
        val sourceTwoQueuedHigh = item(80L, "S2 queued high", sourceId = 2L)
        listOf(
            sourceOneQueuedLow,
            sourceTwoQueuedLow,
            sourceOneDownloadingFirst,
            sourceTwoDownloadingFirst,
            sourceOneDownloadingSecond,
            sourceTwoDownloadingSecond,
            sourceOneQueuedHigh,
            sourceTwoQueuedHigh,
        ).forEach(mgr::enqueue)

        mgr.sortQueue { it.chapterId }

        assertEquals(
            listOf(40L, 30L, 10L, 20L, 70L, 60L, 50L, 80L),
            mgr.queue.first().map { it.chapterId },
        )
    }

    @Test
    fun `reverseQueue keeps all downloading items first and stable inside every source`() = runTest {
        val mgr = manager()
        listOf(
            item(10L, "S1 queued low", sourceId = 1L),
            item(50L, "S2 queued low", sourceId = 2L),
            item(40L, "S1 active first", sourceId = 1L, status = DownloadStatus.DOWNLOADING),
            item(70L, "S2 active first", sourceId = 2L, status = DownloadStatus.DOWNLOADING),
            item(30L, "S1 active second", sourceId = 1L, status = DownloadStatus.DOWNLOADING),
            item(60L, "S2 active second", sourceId = 2L, status = DownloadStatus.DOWNLOADING),
            item(20L, "S1 queued high", sourceId = 1L),
            item(80L, "S2 queued high", sourceId = 2L),
        ).forEach(mgr::enqueue)

        mgr.reverseQueue()

        assertEquals(
            listOf(40L, 30L, 20L, 10L, 70L, 60L, 80L, 50L),
            mgr.queue.first().map { it.chapterId },
        )
    }
}
