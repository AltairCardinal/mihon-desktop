package mihon.desktop.ui.download

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mihon.desktop.download.DesktopDownloadManager
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.download.DownloadItem
import mihon.desktop.download.DownloadQueueOrder
import mihon.desktop.download.DownloadQueueScreenModel
import mihon.desktop.source.FakeDesktopSourceManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import java.io.File

class DownloadQueueOrderPolicyTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `orders use canonical chapter metadata inside each source`() = runTest {
        val queue = listOf(
            item(1L, sourceId = 1L, mangaTitle = "Z"),
            item(2L, sourceId = 2L, mangaTitle = "A"),
            item(3L, sourceId = 1L, mangaTitle = "A"),
            item(4L, sourceId = 2L, mangaTitle = "Z"),
        )
        val repository = repository(
            chapter(1L, dateUpload = 100, chapterNumber = 1.0),
            chapter(2L, dateUpload = 400, chapterNumber = 3.0),
            chapter(3L, dateUpload = 200, chapterNumber = 2.0),
            chapter(4L, dateUpload = 300, chapterNumber = 4.0),
        )
        val expectations = mapOf(
            DownloadQueueOrder.UPLOAD_DATE_NEWEST to listOf(3L, 1L, 2L, 4L),
            DownloadQueueOrder.UPLOAD_DATE_OLDEST to listOf(1L, 3L, 4L, 2L),
            DownloadQueueOrder.CHAPTER_NUMBER_ASCENDING to listOf(1L, 3L, 2L, 4L),
            DownloadQueueOrder.CHAPTER_NUMBER_DESCENDING to listOf(3L, 1L, 4L, 2L),
        )

        expectations.forEach { (order, expected) ->
            val manager = manager(*queue.toTypedArray())
            val model = DownloadQueueScreenModel(manager, repository, FakeDesktopSourceManager(emptyList()), this)
            model.sort(order).join()
            assertEquals(expected, manager.queue.first().map { it.chapterId }, order.name)
            model.onDispose()
        }
    }

    @Test
    fun `missing metadata sorts last while ties and missing items remain stable`() = runTest {
        val queue = listOf(
            item(1L, sourceId = 1L),
            item(2L, sourceId = 1L),
            item(3L, sourceId = 1L),
            item(4L, sourceId = 1L),
        )
        val manager = manager(*queue.toTypedArray())
        val repository = repository(
            chapter(1L, dateUpload = 100),
            chapter(3L, dateUpload = 100),
        )

        val model = DownloadQueueScreenModel(manager, repository, FakeDesktopSourceManager(emptyList()), this)
        model.sort(DownloadQueueOrder.UPLOAD_DATE_NEWEST).join()

        assertEquals(listOf(1L, 3L, 2L, 4L), manager.queue.first().map { it.chapterId })
        model.onDispose()
    }

    private fun manager(vararg items: DownloadItem): DesktopDownloadManager {
        val manager = DesktopDownloadManager(DesktopDownloadProvider(tempDir))
        items.forEach(manager::enqueue)
        return manager
    }

    private fun repository(vararg chapters: Chapter): ChapterRepository = mockk {
        coEvery { getChapterById(any()) } returns null
        chapters.forEach { chapter -> coEvery { getChapterById(chapter.id) } returns chapter }
    }

    private fun item(id: Long, sourceId: Long, mangaTitle: String = "Manga") = DownloadItem(
        sourceId = sourceId,
        mangaTitle = mangaTitle,
        chapterName = "Chapter $id",
        chapterId = id,
    )

    private fun chapter(
        id: Long,
        dateUpload: Long = 0,
        chapterNumber: Double = 0.0,
    ) = Chapter.create().copy(id = id, dateUpload = dateUpload, chapterNumber = chapterNumber)
}
