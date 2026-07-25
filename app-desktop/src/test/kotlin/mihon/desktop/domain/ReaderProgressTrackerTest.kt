package mihon.desktop.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.task.DesktopTaskScheduler
import mihon.desktop.task.FileTaskCheckpointStore
import mihon.desktop.tracking.DesktopNetworkConnectivity
import mihon.desktop.tracking.DesktopTrackerSyncScheduler
import mihon.domain.task.TaskStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.domain.reader.repository.ReadingProgressRepository
import tachiyomi.domain.track.interactor.ReadingProgressTrackSync
import tachiyomi.domain.track.interactor.SyncReadingProgressWithTrack
import tachiyomi.domain.track.interactor.TrackerSyncRequest
import tachiyomi.domain.track.interactor.TrackerSyncRetryScheduler
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackEdit
import tachiyomi.domain.track.service.DelayedTrackerSyncItem
import tachiyomi.domain.track.service.DelayedTrackerSyncPersistence
import tachiyomi.domain.track.service.TrackSearchResult
import tachiyomi.domain.track.service.TrackerAuthentication
import tachiyomi.domain.track.service.TrackerProfile
import tachiyomi.domain.track.service.TrackerProviderCatalog
import tachiyomi.domain.track.service.TrackerProviderConfiguration
import tachiyomi.domain.track.service.TrackerProviderErrorKind
import tachiyomi.domain.track.service.TrackerProviderException
import tachiyomi.domain.track.service.TrackerProviderPort
import tachiyomi.domain.track.service.TrackerProviderRequest
import tachiyomi.domain.track.service.TrackerProviderResult
import tachiyomi.domain.track.service.TrackerProviderService
import tachiyomi.domain.track.service.TrackerProviderSession
import tachiyomi.domain.track.service.TrackerProviderWorkflow
import tachiyomi.domain.track.service.TrackerService
import tachiyomi.domain.track.service.TrackerServiceRegistry
import tachiyomi.domain.track.service.mergeHighest
import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.file.Files
import java.nio.file.Path
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

    @Test
    fun `production reader failure enters shared queue and restart success cleans it`() = runBlocking {
        val file = Files.createDirectories(Path.of(".test-tmp", "reader-tracker-${UUID.randomUUID()}"))
            .resolve("tasks.json")
        val tracks = FakeTrackRepository(mutableListOf(track()))
        val provider = FakeProviderService(fail = true)
        lateinit var sync: ReadingProgressTrackSync
        val first = DesktopTrackerSyncScheduler(DesktopTaskScheduler(FileTaskCheckpointStore(file))) { sync }
        sync = SyncReadingProgressWithTrack(tracks, registry(provider), first)
        val reader = ReaderProgressTracker(
            RecordReadingProgress(RecordingRepository()),
            appPreferences = DesktopAppPreferences(InMemoryPreferenceStore()),
            trackSync = sync,
        )

        reader.track("exit-production", 10, 9, 10, null, mangaId = 7, chapterNumber = 4.5)

        val queued = first.getItems().single()
        assertEquals(4L, queued.trackId)
        assertEquals(4.5, queued.lastChapterRead)
        assertEquals("SERVER", queued.failureReason)
        assertEquals(0, queued.attempt)

        provider.fail = false
        lateinit var restartedSync: ReadingProgressTrackSync
        val restarted = DesktopTrackerSyncScheduler(
            DesktopTaskScheduler(FileTaskCheckpointStore(file)),
        ) { restartedSync }
        restartedSync = SyncReadingProgressWithTrack(tracks, registry(provider), restarted)
        restarted.runPending()

        assertTrue(restarted.getItems().isEmpty())
        assertEquals(4.5, tracks.tracks.single().lastChapterRead)
        assertEquals(listOf("refresh", "update", "refresh", "update"), provider.events)
    }

    @Test
    fun `background retry removes a checkpoint whose track disappeared`() = runBlocking {
        val (scheduler, _) = retryScheduler(
            "missing-track",
            FakeTrackRepository(mutableListOf()),
            FakeProviderService(),
        )
        scheduler.upsertMax(retryItem())

        scheduler.runPending()

        assertTrue(scheduler.getItems().isEmpty())
    }

    @Test
    fun `background retry removes a checkpoint already reached by the stored track`() = runBlocking {
        val tracks = FakeTrackRepository(mutableListOf(track(lastChapterRead = 5.0)))
        val (scheduler, _) = retryScheduler("already-complete", tracks, FakeProviderService())
        scheduler.upsertMax(retryItem(chapter = 4.0))

        scheduler.runPending()

        assertTrue(scheduler.getItems().isEmpty())
        assertEquals(5.0, tracks.tracks.single().lastChapterRead)
    }

    @Test
    fun `logged out background retry consumes attempts zero through three then becomes terminal`() = runBlocking {
        val provider = FakeProviderService(loggedIn = false)
        val (scheduler, taskStore) = retryScheduler(
            "logged-out",
            FakeTrackRepository(mutableListOf(track())),
            provider,
        )
        scheduler.upsertMax(retryItem())

        repeat(5) { scheduler.runPending() }

        assertEquals(4, scheduler.getItems().single().attempt)
        assertEquals(TaskStatus.Failed, taskStore.allTasks().single().status)
        assertTrue(provider.events.isEmpty())
    }

    @Test
    fun `provider failure increments one attempt per background invocation`() = runBlocking {
        val provider = FakeProviderService(fail = true)
        val (scheduler, _) = retryScheduler(
            "single-increment",
            FakeTrackRepository(mutableListOf(track())),
            provider,
        )
        scheduler.upsertMax(retryItem())

        scheduler.runPending()

        assertEquals(1, scheduler.getItems().single().attempt)
        assertEquals(listOf("refresh", "update"), provider.events)
    }

    @Test
    fun `drain charges the new checkpoint replaced before its first storage read`() = runBlocking {
        val old = retryItem(eventId = "old", chapter = 4.0)
        val replacement = retryItem(eventId = "new", chapter = 6.0)
        val persistence = BarrierRetryPersistence(old, pauseFirstRead = true)
        val provider = FakeProviderService(fail = true)
        val sync = SyncReadingProgressWithTrack(
            FakeTrackRepository(mutableListOf(track())),
            registry(provider),
            persistence,
        )

        val draining = async { sync.drain(old) }
        persistence.firstReadEntered.await()
        persistence.replace(replacement)
        persistence.releaseFirstRead.complete(Unit)
        draining.await()

        assertEquals("new", persistence.item?.eventId)
        assertEquals(6.0, persistence.item?.lastChapterRead)
        assertEquals(1, persistence.item?.attempt)
        assertEquals(listOf("refresh", "update"), provider.events)
    }

    @Test
    fun `drain does not charge a new checkpoint replaced after the consumed storage read`() = runBlocking {
        val old = retryItem(eventId = "old", chapter = 4.0)
        val replacement = retryItem(eventId = "new", chapter = 6.0)
        val repositoryReadEntered = CompletableDeferred<Unit>()
        val releaseRepositoryRead = CompletableDeferred<Unit>()
        val persistence = BarrierRetryPersistence(old)
        val provider = FakeProviderService(fail = true)
        val sync = SyncReadingProgressWithTrack(
            FakeTrackRepository(mutableListOf(track())) {
                repositoryReadEntered.complete(Unit)
                releaseRepositoryRead.await()
            },
            registry(provider),
            persistence,
        )

        val draining = async { sync.drain(old) }
        repositoryReadEntered.await()
        persistence.replace(replacement)
        releaseRepositoryRead.complete(Unit)
        draining.await()

        assertEquals("new", persistence.item?.eventId)
        assertEquals(6.0, persistence.item?.lastChapterRead)
        assertEquals(0, persistence.item?.attempt)
        assertEquals(listOf("refresh", "update"), provider.events)
    }

    private fun retryScheduler(
        name: String,
        tracks: FakeTrackRepository,
        provider: FakeProviderService,
    ): Pair<DesktopTrackerSyncScheduler, DesktopTaskScheduler> {
        val file = Files.createDirectories(Path.of(".test-tmp", "reader-retry-${UUID.randomUUID()}"))
            .resolve("$name.json")
        val taskStore = DesktopTaskScheduler(FileTaskCheckpointStore(file))
        lateinit var sync: ReadingProgressTrackSync
        val scheduler = DesktopTrackerSyncScheduler(
            scheduler = taskStore,
            connectivity = DesktopNetworkConnectivity { true },
        ) { sync }
        sync = SyncReadingProgressWithTrack(tracks, registry(provider), scheduler)
        return scheduler to taskStore
    }

    private fun retryItem(
        chapter: Double = 4.0,
        eventId: String = "retry",
    ) = DelayedTrackerSyncItem(
        trackId = 4,
        mangaId = 7,
        trackerId = 9,
        lastChapterRead = chapter,
        failureReason = "SERVER",
        eventId = eventId,
        attempt = 0,
    )

    private fun registry(vararg services: TrackerService) = object : TrackerServiceRegistry {
        override val services = services.toList()
    }

    private fun track(lastChapterRead: Double = 0.0) = Track(
        4, 7, 9, 10, null, "Manga", lastChapterRead, 10, 1, 0.0, "", 0, 0, false,
    )

    private class FakeProviderService(
        var fail: Boolean = false,
        loggedIn: Boolean = true,
    ) : TrackerProviderService {
        override val profile = MutableStateFlow(TrackerProfile(9, "Tracker", TrackerAuthentication.OAUTH, loggedIn))
        override val statuses = emptyList<Pair<Long, String>>()
        override val scores = emptyList<Double>()
        override val configuration: TrackerProviderConfiguration = TrackerProviderCatalog.configuration(9)
        override val session get() = TrackerProviderSession(9, true)
        val events = mutableListOf<String>()
        override suspend fun search(query: String) = emptyList<TrackSearchResult>()
        override suspend fun bind(mangaId: Long, result: TrackSearchResult): Track = error("unused")
        override suspend fun update(track: Track, edit: TrackEdit): Track = error("provider workflow required")
        override suspend fun execute(request: TrackerProviderRequest): TrackerProviderResult =
            TrackerProviderWorkflow().execute(
                object : TrackerProviderPort {
                    override val configuration = this@FakeProviderService.configuration
                    override val session = this@FakeProviderService.session
                    override suspend fun refresh(track: Track) = track.also { events += "refresh" }
                    override suspend fun update(track: Track): Track {
                        events += "update"
                        if (fail) throw TrackerProviderException(TrackerProviderErrorKind.SERVER)
                        return track
                    }
                    override suspend fun delete(track: Track) = Unit
                },
                request,
            )
        override suspend fun logout() = Unit
    }

    private class FakeTrackRepository(
        val tracks: MutableList<Track>,
        private val beforeTrackLookup: suspend () -> Unit = {},
    ) : TrackRepository {
        override suspend fun getTrackById(id: Long): Track? {
            beforeTrackLookup()
            return tracks.find { it.id == id }
        }
        override suspend fun getTracksByMangaId(mangaId: Long) = tracks.filter { it.mangaId == mangaId }
        override fun getTracksAsFlow() = flowOf(tracks)
        override fun getTracksByMangaIdAsFlow(mangaId: Long) = flowOf(tracks.filter { it.mangaId == mangaId })
        override suspend fun delete(mangaId: Long, trackerId: Long) {
            tracks.removeAll { it.mangaId == mangaId && it.trackerId == trackerId }
        }
        override suspend fun insert(track: Track) {
            tracks.replaceAll { if (it.id == track.id) track else it }
        }
        override suspend fun insertAll(tracks: List<Track>) = tracks.forEach { insert(it) }
    }

    private class BarrierRetryPersistence(
        initial: DelayedTrackerSyncItem,
        private val pauseFirstRead: Boolean = false,
    ) : TrackerSyncRetryScheduler, DelayedTrackerSyncPersistence {
        var item: DelayedTrackerSyncItem? = initial
            private set
        val firstReadEntered = CompletableDeferred<Unit>()
        val releaseFirstRead = CompletableDeferred<Unit>()
        private var firstRead = true

        override suspend fun schedule(request: TrackerSyncRequest) = error("durable persistence must be used")

        override suspend fun getItems(): List<DelayedTrackerSyncItem> {
            if (firstRead) {
                firstRead = false
                firstReadEntered.complete(Unit)
                if (pauseFirstRead) releaseFirstRead.await()
            }
            return listOfNotNull(item)
        }

        override suspend fun upsertMax(item: DelayedTrackerSyncItem): DelayedTrackerSyncItem {
            val merged = this.item?.mergeHighest(item) ?: item
            this.item = merged
            return merged
        }

        override suspend fun removeUpTo(trackId: Long, lastChapterRead: Double): Boolean {
            val current = item ?: return false
            if (current.trackId != trackId || current.lastChapterRead > lastChapterRead) return false
            item = null
            return true
        }

        fun replace(replacement: DelayedTrackerSyncItem) {
            item = replacement
        }
    }

    private class RecordingRepository : ReadingProgressRepository {
        var event: ReadingProgressEvent? = null
        override suspend fun record(event: ReadingProgressEvent) { this.event = event }
    }
}
