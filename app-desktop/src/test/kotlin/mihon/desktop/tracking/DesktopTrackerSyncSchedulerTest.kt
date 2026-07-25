package mihon.desktop.tracking

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import mihon.desktop.task.DesktopTaskScheduler
import mihon.desktop.task.FileTaskCheckpointStore
import mihon.domain.task.BackgroundTask
import mihon.domain.task.TaskCheckpoint
import mihon.domain.task.TaskConstraint
import mihon.domain.task.TaskStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.service.DelayedTrackerSyncItem
import tachiyomi.domain.track.service.DelayedTrackerSyncReport
import tachiyomi.domain.track.interactor.ReadingProgressTrackSync
import tachiyomi.domain.track.interactor.ReadingProgressTrackRetryConsumer
import tachiyomi.domain.track.interactor.TrackerSyncRequest
import java.nio.file.Path
import java.nio.file.Files
import java.util.UUID

class DesktopTrackerSyncSchedulerTest {
    private val directory: Path = Files.createDirectories(Path.of(".test-tmp", "tracker-${UUID.randomUUID()}"))

    @Test
    fun `failed sync request is persisted and recovered by a new scheduler`() = runTest {
        val file = directory.resolve("tasks.json")
        val firstStore = DesktopTaskScheduler(FileTaskCheckpointStore(file))
        val first = DesktopTrackerSyncScheduler(firstStore) { error("not used") }
        val request = TrackerSyncRequest("reader", mangaId = 3, chapterNumber = 4.0, trackerId = 9, attempt = 1)

        first.schedule(request)

        val recovered = mutableListOf<TrackerSyncRequest>()
        val secondStore = DesktopTaskScheduler(FileTaskCheckpointStore(file))
        val second = DesktopTrackerSyncScheduler(secondStore) { ReadingProgressTrackSync(recovered::add) }
        second.runPending()

        assertEquals(listOf(request), recovered)
        assertTrue(secondStore.pendingTasks().none { it.id.startsWith("tracker-sync-") })
    }

    @Test
    fun `failed recovered sync preserves the next retry attempt`() = runTest {
        val store = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("retry-tasks.json")))
        lateinit var worker: DesktopTrackerSyncScheduler
        worker = DesktopTrackerSyncScheduler(store, backgroundScope) {
            ReadingProgressTrackSync { request ->
                worker.schedule(request.copy(attempt = request.attempt + 1))
            }
        }
        worker.schedule(TrackerSyncRequest("reader", 3, 4.0, trackerId = 9, attempt = 1))

        worker.runPending()

        assertEquals(2, store.pendingTasks().single().checkpoint?.cursor?.substringAfterLast('|')?.toInt())
    }

    @Test
    fun `shared persistence merges highest checkpoint and conditionally cleans after restart`() = runTest {
        val file = directory.resolve("shared-items.json")
        val firstStore = DesktopTaskScheduler(FileTaskCheckpointStore(file))
        val first = DesktopTrackerSyncScheduler(firstStore) { error("not used") }

        first.upsertMax(item(chapter = 5.0))
        first.upsertMax(item(chapter = 10.0))
        first.upsertMax(item(chapter = 7.0))

        assertFalse(first.removeUpTo(4, 8.0))
        val task = firstStore.pendingTasks().single()
        assertEquals(setOf(TaskConstraint.NetworkConnected), task.constraints)

        val second = DesktopTrackerSyncScheduler(
            DesktopTaskScheduler(FileTaskCheckpointStore(file)),
        ) { error("not used") }
        assertEquals(10.0, second.getItems().single().lastChapterRead)
        assertTrue(second.removeUpTo(4, 10.0))
        assertTrue(second.getItems().isEmpty())
    }

    @Test
    fun `exhausted shared item keeps stable terminal failure`() = runTest {
        val store = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("terminal-items.json")))
        val scheduler = DesktopTrackerSyncScheduler(store) { error("must not execute") }
        scheduler.upsertMax(item(chapter = 10.0, attempt = 4, reason = "SERVER:503"))

        scheduler.runPending()

        val terminal = store.allTasks().single()
        assertEquals(TaskStatus.Failed, terminal.status)
        assertEquals("Server", terminal.failure?.type)
        assertEquals("SERVER:503", scheduler.getItems().single().failureReason)
    }

    @Test
    fun `shared item wakes an already started scheduler`() = runTest {
        val store = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("started-items.json")))
        var processed = false
        val scheduler = DesktopTrackerSyncScheduler(store, backgroundScope) {
            retrySync { processed = true }
        }
        scheduler.start()
        runCurrent()

        scheduler.upsertMax(item(chapter = 5.0))
        runCurrent()

        assertTrue(processed)
        scheduler.stop()
    }

    @Test
    fun `offline runtime keeps pending attempt untouched and automatically resumes after reconnect`() = runTest {
        val store = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("offline-items.json")))
        var connected = false
        val requests = mutableListOf<TrackerSyncRequest>()
        val scheduler = DesktopTrackerSyncScheduler(
            scheduler = store,
            scope = backgroundScope,
            connectivity = DesktopNetworkConnectivity { connected },
            retryDelayMillis = 1_000,
        ) { retrySync(requests::add) }
        scheduler.start()

        scheduler.upsertMax(item(chapter = 5.0, attempt = 0))
        runCurrent()

        assertTrue(requests.isEmpty())
        assertEquals(0, scheduler.getItems().single().attempt)
        assertEquals(TaskStatus.Pending, store.allTasks().single().status)

        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(requests.isEmpty())
        assertEquals(0, scheduler.getItems().single().attempt)

        connected = true
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf(0), requests.map { it.attempt })
        scheduler.stop()
    }

    @Test
    fun `background retry executes attempts zero through three then becomes terminal`() = runTest {
        val store = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("bounded-retries.json")))
        val attempts = mutableListOf<Int>()
        val scheduler = DesktopTrackerSyncScheduler(
            scheduler = store,
            connectivity = DesktopNetworkConnectivity { true },
        ) {
            retrySync { request ->
                attempts += request.attempt
                error("provider unavailable")
            }
        }
        scheduler.upsertMax(item(chapter = 5.0, attempt = 0))

        repeat(5) { scheduler.runPending() }

        assertEquals(listOf(0, 1, 2, 3), attempts)
        assertEquals(4, scheduler.getItems().single().attempt)
        assertEquals(TaskStatus.Failed, store.allTasks().single().status)
    }

    @Test
    fun `two scheduler instances atomically preserve the highest concurrent checkpoint`() = runTest {
        val file = directory.resolve("atomic-upsert.json")
        val first = DesktopTrackerSyncScheduler(DesktopTaskScheduler(FileTaskCheckpointStore(file))) {
            error("not used")
        }
        val second = DesktopTrackerSyncScheduler(DesktopTaskScheduler(FileTaskCheckpointStore(file))) {
            error("not used")
        }

        repeat(64) { index ->
            val start = CompletableDeferred<Unit>()
            withContext(Dispatchers.Default) {
                val writes = listOf(
                    async {
                        start.await()
                        first.upsertMax(item(trackId = index.toLong() + 1, chapter = 10.0))
                    },
                    async {
                        start.await()
                        second.upsertMax(item(trackId = index.toLong() + 1, chapter = 7.0))
                    },
                )
                start.complete(Unit)
                writes.awaitAll()
            }
        }

        assertTrue(first.getItems().all { it.lastChapterRead == 10.0 })
        assertEquals(64, first.getItems().size)
    }

    @Test
    fun `concurrent lower cleanup cannot delete a newly raised checkpoint`() = runTest {
        val file = directory.resolve("atomic-cleanup.json")
        val first = DesktopTrackerSyncScheduler(DesktopTaskScheduler(FileTaskCheckpointStore(file))) {
            error("not used")
        }
        val second = DesktopTrackerSyncScheduler(DesktopTaskScheduler(FileTaskCheckpointStore(file))) {
            error("not used")
        }

        repeat(64) { index ->
            val trackId = index.toLong() + 1
            first.upsertMax(item(trackId = trackId, chapter = 10.0))
            val start = CompletableDeferred<Unit>()
            withContext(Dispatchers.Default) {
                val mutations = listOf(
                    async {
                        start.await()
                        first.upsertMax(item(trackId = trackId, chapter = 12.0))
                    },
                    async {
                        start.await()
                        second.removeUpTo(trackId, 10.0)
                    },
                )
                start.complete(Unit)
                mutations.awaitAll()
            }
        }

        assertEquals(64, first.getItems().size)
        assertTrue(first.getItems().all { it.lastChapterRead == 12.0 })
    }

    @Test
    fun `malformed new and legacy checkpoints fail independently while healthy tasks continue`() = runTest {
        val store = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("malformed-items.json")))
        val requests = mutableListOf<TrackerSyncRequest>()
        listOf(
            "tracker-sync-malformed-new" to "item-v1|not-a-track|3|9|5.0|reader|0|NETWORK",
            "tracker-sync-malformed-legacy" to "reader|not-a-manga|5.0|9|0",
            "tracker-sync-healthy" to "healthy|3|5.0|9|0",
        ).forEach { (id, cursor) ->
            store.register(
                BackgroundTask(
                    id = id,
                    idempotencyKey = id,
                    constraints = setOf(TaskConstraint.NetworkConnected),
                    checkpoint = TaskCheckpoint(cursor = cursor),
                ),
            )
        }
        val scheduler = DesktopTrackerSyncScheduler(
            scheduler = store,
            scope = backgroundScope,
            connectivity = DesktopNetworkConnectivity { true },
            retryDelayMillis = 1_000,
        ) { ReadingProgressTrackSync(requests::add) }

        assertTrue(scheduler.getItems().isEmpty())
        scheduler.start()
        runCurrent()

        assertEquals(listOf("healthy"), requests.map { it.eventId })
        assertEquals("MalformedData", store.snapshot("tracker-sync-malformed-new")?.failure?.type)
        assertEquals("MalformedData", store.snapshot("tracker-sync-malformed-legacy")?.failure?.type)
        assertEquals(TaskStatus.Completed, store.snapshot("tracker-sync-healthy")?.status)

        scheduler.schedule(TrackerSyncRequest("after-malformed", 3, 6.0, 9))
        runCurrent()
        assertEquals(listOf("healthy", "after-malformed"), requests.map { it.eventId })
        scheduler.stop()
    }

    private fun item(
        chapter: Double,
        trackId: Long = 4,
        attempt: Int = 1,
        reason: String = "NETWORK",
    ) = DelayedTrackerSyncItem(
        trackId = trackId,
        mangaId = 3,
        trackerId = 9,
        lastChapterRead = chapter,
        failureReason = reason,
        eventId = "reader",
        attempt = attempt,
    )

    private fun retrySync(block: suspend (TrackerSyncRequest) -> Unit): ReadingProgressTrackSync =
        object : ReadingProgressTrackSync, ReadingProgressTrackRetryConsumer {
            override suspend fun sync(request: TrackerSyncRequest) = block(request)

            override suspend fun drain(item: DelayedTrackerSyncItem): DelayedTrackerSyncReport {
                block(
                    TrackerSyncRequest(
                        eventId = requireNotNull(item.eventId),
                        mangaId = item.mangaId,
                        chapterNumber = item.lastChapterRead,
                        trackerId = item.trackerId,
                        attempt = item.attempt,
                    ),
                )
                return DelayedTrackerSyncReport(attempted = 1, succeeded = 1, queued = 0, remaining = 0)
            }
        }
}
