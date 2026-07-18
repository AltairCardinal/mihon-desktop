package mihon.desktop.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mihon.desktop.settings.DesktopAppPreferences
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.domain.reader.repository.ReadingProgressRepository
import tachiyomi.domain.track.interactor.ReadingProgressTrackSync
import tachiyomi.domain.track.interactor.TrackerSyncRequest
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.UUID
import java.util.prefs.Preferences

class ReaderProgressTrackerTest {
    @Test
    fun `reading to last page marks shared event as read`() = runBlocking {
        val repository = RecordingRepository()
        val tracker = ReaderProgressTracker(RecordReadingProgress(repository))

        tracker.track(eventId = "exit-1", chapterId = 1, lastPageRead = 9, totalPages = 10, sourceId = null)

        assertTrue(repository.event!!.isRead)
    }

    @Test
    fun `exiting mid chapter keeps shared event unread`() = runBlocking {
        val repository = RecordingRepository()
        val tracker = ReaderProgressTracker(RecordReadingProgress(repository))

        tracker.track(eventId = "exit-2", chapterId = 2, lastPageRead = 4, totalPages = 20, sourceId = null)

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

        tracker.track("exit-3", 3, 9, 10, sourceId = null, mangaId = 7, chapterNumber = 4.5)

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

        tracker.track("exit-4", 4, 3, 10, sourceId = null, mangaId = 7, chapterNumber = 4.5)

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `incognito completion does not update remote tracker`() = runBlocking {
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore()).apply {
            incognitoMode.set(true)
        }
        val requests = mutableListOf<TrackerSyncRequest>()
        val tracker = ReaderProgressTracker(
            RecordReadingProgress(RecordingRepository()),
            appPreferences = preferences,
            trackSync = ReadingProgressTrackSync(requests::add),
        )

        tracker.track("exit-incognito", 5, 9, 10, sourceId = null, mangaId = 7, chapterNumber = 4.5)

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `disabled automatic tracking does not update remote tracker`() = runBlocking {
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore()).apply {
            autoUpdateTrack.set(false)
        }
        val requests = mutableListOf<TrackerSyncRequest>()
        val tracker = ReaderProgressTracker(
            RecordReadingProgress(RecordingRepository()),
            appPreferences = preferences,
            trackSync = ReadingProgressTrackSync(requests::add),
        )

        tracker.track("exit-auto-disabled", 6, 9, 10, sourceId = null, mangaId = 7, chapterNumber = 4.5)

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `eligible tracker sync completes after caller cancellation`() = runBlocking {
        val syncStarted = CompletableDeferred<Unit>()
        val allowSyncCompletion = CompletableDeferred<Unit>()
        val syncCompleted = CompletableDeferred<Unit>()
        val tracker = ReaderProgressTracker(
            RecordReadingProgress(RecordingRepository()),
            appPreferences = DesktopAppPreferences(InMemoryPreferenceStore()),
            trackSync = ReadingProgressTrackSync {
                syncStarted.complete(Unit)
                allowSyncCompletion.await()
                syncCompleted.complete(Unit)
            },
        )
        val caller = launch {
            tracker.track("exit-cancelled", 7, 9, 10, sourceId = null, mangaId = 7, chapterNumber = 4.5)
        }

        syncStarted.await()
        caller.cancel()
        allowSyncCompletion.complete(Unit)
        caller.join()

        assertTrue(syncCompleted.isCompleted)
    }

    @Test
    fun `extension incognito completion suppresses history and remote tracker sync`() = runBlocking {
        val preferences = DesktopAppPreferences(
            DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")),
        ).apply {
            incognitoExtensions.set(setOf("extension.hidden"))
        }
        val repository = RecordingRepository()
        val requests = mutableListOf<TrackerSyncRequest>()
        val tracker = ReaderProgressTracker(
            RecordReadingProgress(repository),
            appPreferences = preferences,
            trackSync = ReadingProgressTrackSync(requests::add),
            extensionPackageForSource = { sourceId -> if (sourceId == 10L) "extension.hidden" else "extension.visible" },
        )

        tracker.track("exit-extension-incognito", 8, 9, 10, sourceId = 10L, mangaId = 7, chapterNumber = 4.5)

        assertFalse(repository.event!!.recordHistory)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `extension incognito does not affect another extension`() = runBlocking {
        val preferences = DesktopAppPreferences(
            DesktopPreferenceStore(Preferences.userRoot().node("/mihon-test/${UUID.randomUUID()}")),
        ).apply {
            incognitoExtensions.set(setOf("extension.hidden"))
        }
        val repository = RecordingRepository()
        val requests = mutableListOf<TrackerSyncRequest>()
        val tracker = ReaderProgressTracker(
            RecordReadingProgress(repository),
            appPreferences = preferences,
            trackSync = ReadingProgressTrackSync(requests::add),
            extensionPackageForSource = { sourceId -> if (sourceId == 10L) "extension.hidden" else "extension.visible" },
        )

        tracker.track("exit-visible-extension", 9, 9, 10, sourceId = 11L, mangaId = 7, chapterNumber = 4.5)

        assertTrue(repository.event!!.recordHistory)
        assertEquals(listOf(TrackerSyncRequest("exit-visible-extension", 7, 4.5)), requests)
    }

    private class RecordingRepository : ReadingProgressRepository {
        var event: ReadingProgressEvent? = null
        override suspend fun record(event: ReadingProgressEvent) { this.event = event }
    }
}
