package mihon.desktop.domain

import kotlinx.coroutines.runBlocking
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeHistoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.history.interactor.UpsertHistory

class ReaderProgressTrackerTest {

    private fun chapter(id: Long = 1L, mangaId: Long = 10L) = Chapter.create().copy(
        id = id, mangaId = mangaId, url = "/ch/$id", name = "Chapter $id",
    )

    @Test
    fun `reading to last page marks chapter as read`() = runBlocking<Unit> {
        val chapterRepo = FakeChapterRepository()
        chapterRepo.addAll(listOf(chapter(id = 1L)))
        val historyRepo = FakeHistoryRepository()
        val tracker = ReaderProgressTracker(UpdateChapter(chapterRepo), UpsertHistory(historyRepo))

        tracker.track(chapterId = 1L, lastPageRead = 9, totalPages = 10)

        val update = chapterRepo.updates.single()
        assertTrue(update.read == true, "Chapter must be marked read")
        assertEquals(9L, update.lastPageRead)
    }

    @Test
    fun `exiting mid-chapter does NOT mark chapter as read`() = runBlocking<Unit> {
        val chapterRepo = FakeChapterRepository()
        chapterRepo.addAll(listOf(chapter(id = 2L)))
        val historyRepo = FakeHistoryRepository()
        val tracker = ReaderProgressTracker(UpdateChapter(chapterRepo), UpsertHistory(historyRepo))

        tracker.track(chapterId = 2L, lastPageRead = 4, totalPages = 20)

        val update = chapterRepo.updates.single()
        assertFalse(update.read == true, "Chapter must NOT be marked read mid-way")
        assertEquals(4L, update.lastPageRead)
    }

    @Test
    fun `history is always recorded regardless of read state`() = runBlocking<Unit> {
        val chapterRepo = FakeChapterRepository()
        chapterRepo.addAll(listOf(chapter(id = 3L)))
        val historyRepo = FakeHistoryRepository()
        val tracker = ReaderProgressTracker(UpdateChapter(chapterRepo), UpsertHistory(historyRepo))

        tracker.track(chapterId = 3L, lastPageRead = 2, totalPages = 10)

        assertEquals(1, historyRepo.upserted.size, "History must be recorded")
        assertEquals(3L, historyRepo.upserted.single().chapterId)
    }

    @Test
    fun `single-page chapter is marked read when on first page`() = runBlocking<Unit> {
        val chapterRepo = FakeChapterRepository()
        chapterRepo.addAll(listOf(chapter(id = 4L)))
        val historyRepo = FakeHistoryRepository()
        val tracker = ReaderProgressTracker(UpdateChapter(chapterRepo), UpsertHistory(historyRepo))

        tracker.track(chapterId = 4L, lastPageRead = 0, totalPages = 1)

        assertTrue(chapterRepo.updates.single().read == true)
    }
}
