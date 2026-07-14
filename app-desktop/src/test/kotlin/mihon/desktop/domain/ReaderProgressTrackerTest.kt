package mihon.desktop.domain

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.domain.reader.repository.ReadingProgressRepository
import tachiyomi.domain.track.interactor.ReadingProgressTrackSync
import tachiyomi.domain.track.interactor.TrackerSyncRequest
import org.junit.jupiter.api.Assertions.assertEquals

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

    @Test
    fun `finishing chapter emits tracker sync after local progress commit`() = runBlocking {
        val repository = RecordingRepository()
        val requests = mutableListOf<TrackerSyncRequest>()
        val tracker = ReaderProgressTracker(
            RecordReadingProgress(repository),
            trackSync = ReadingProgressTrackSync(requests::add),
        )

        tracker.track("exit-3", 3, 9, 10, mangaId = 7, chapterNumber = 4.5)

        assertTrue(repository.event!!.isRead)
        assertEquals(listOf(TrackerSyncRequest("exit-3", 7, 4.5)), requests)
    }

    @Test
    fun `partial progress does not update remote tracker`() = runBlocking {
        val requests = mutableListOf<TrackerSyncRequest>()
        val tracker = ReaderProgressTracker(
            RecordReadingProgress(RecordingRepository()),
            trackSync = ReadingProgressTrackSync(requests::add),
        )

        tracker.track("exit-4", 4, 3, 10, mangaId = 7, chapterNumber = 4.5)

        assertTrue(requests.isEmpty())
    }

    private class RecordingRepository : ReadingProgressRepository {
        var event: ReadingProgressEvent? = null
        override suspend fun record(event: ReadingProgressEvent) { this.event = event }
    }
}
