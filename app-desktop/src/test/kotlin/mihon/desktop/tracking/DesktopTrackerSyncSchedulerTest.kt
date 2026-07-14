package mihon.desktop.tracking

import kotlinx.coroutines.test.runTest
import mihon.desktop.task.DesktopTaskScheduler
import mihon.desktop.task.FileTaskCheckpointStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.interactor.ReadingProgressTrackSync
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
}
