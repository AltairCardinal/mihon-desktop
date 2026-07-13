package mihon.desktop.domain

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.domain.reader.repository.ReadingProgressRepository

class ReaderProgressTrackerTest {
    @Test
    fun `reading to last page marks shared event as read`() = runBlocking {
        val repository = RecordingRepository()
        val tracker = ReaderProgressTracker(RecordReadingProgress(repository))

        tracker.track(eventId = "exit-1", chapterId = 1, lastPageRead = 9, totalPages = 10)

        assertTrue(repository.event!!.isRead)
    }

    @Test
    fun `exiting mid chapter keeps shared event unread`() = runBlocking {
        val repository = RecordingRepository()
        val tracker = ReaderProgressTracker(RecordReadingProgress(repository))

        tracker.track(eventId = "exit-2", chapterId = 2, lastPageRead = 4, totalPages = 20)

        assertFalse(repository.event!!.isRead)
    }

    private class RecordingRepository : ReadingProgressRepository {
        var event: ReadingProgressEvent? = null
        override suspend fun record(event: ReadingProgressEvent) { this.event = event }
    }
}
