package mihon.desktop.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.LibraryUpdateChecker.UpdateResult
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.task.DesktopTaskScheduler
import mihon.desktop.task.FileTaskCheckpointStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import java.nio.file.Path

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryUpdateRecoveryIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `new instance resumes after cursor and never repeats successful manga`() = runTest {
        val calls = mutableListOf<Long>()
        val taskStore = directory.resolve("tasks.json")
        val first = scheduler(taskStore, calls, this) { id ->
            if (id == 2L) error("process stopped")
            UpdateResult(0)
        }
        first.runNow().join()

        val second = scheduler(taskStore, calls, this) { UpdateResult(0) }
        second.start()
        runCurrent()

        assertEquals(listOf(1L, 2L, 2L, 3L), calls)
        assertTrue(second.taskSnapshot()?.status?.isTerminal == true)
        second.stop()
    }

    @Test
    fun `pending task without checkpoint resumes immediately on startup`() = runTest {
        val calls = mutableListOf<Long>()
        val file = directory.resolve("tasks.json")
        DesktopTaskScheduler(FileTaskCheckpointStore(file)).register(LibraryUpdateScheduler.LIBRARY_UPDATE_TASK)

        val scheduler = scheduler(file, calls, this) { UpdateResult(0) }
        scheduler.start()
        runCurrent()

        assertEquals(listOf(1L, 2L, 3L), calls)
        scheduler.stop()
    }

    @Test
    fun `cancelling running update prevents following items`() = runTest {
        val calls = mutableListOf<Long>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scheduler = scheduler(directory.resolve("tasks.json"), calls, this) { id ->
            if (id == 1L) {
                entered.complete(Unit)
                release.await()
            }
            UpdateResult(0)
        }

        scheduler.runNow()
        entered.await()
        assertTrue(scheduler.cancelUpdate())
        release.complete(Unit)
        runCurrent()

        assertEquals(listOf(1L), calls)
        assertTrue(scheduler.taskSnapshot()?.status?.isTerminal == true)
    }

    @Test
    fun `real update emits progress and one terminal success event`() = runTest {
        val delivered = mutableListOf<DesktopNotification>()
        val scheduler = scheduler(directory.resolve("tasks.json"), mutableListOf(), this, delivered) { UpdateResult(0) }

        scheduler.runNow().join()

        assertTrue(delivered.any { it.title == "Updating library" })
        assertEquals(1, delivered.count { it.title == "Library updated" })
    }

    @Test
    fun `failed manga is persisted as a structured failed unit`() = runTest {
        val scheduler = scheduler(directory.resolve("tasks.json"), mutableListOf(), this) { id ->
            if (id == 2L) UpdateResult(0, error = "boom") else UpdateResult(0)
        }

        scheduler.runNow().join()

        assertEquals(listOf("manga:2"), scheduler.taskSnapshot()?.failedUnits)
        assertEquals(mihon.domain.task.TaskStatus.Failed, scheduler.taskSnapshot()?.status)
    }

    @Test
    fun `outer failure records failed state instead of being swallowed`() = runTest {
        val taskScheduler = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("tasks.json")))
        val delivered = mutableListOf<DesktopNotification>()
        val scheduler = LibraryUpdateScheduler(
            appPreferences = DesktopAppPreferences(InMemoryPreferenceStore()),
            updateChecker = null, getLibraryManga = null, sourceManager = null,
            taskScheduler = taskScheduler,
            taskNotifier = DesktopSystemNotifier(system = { delivered += it; true }, fallback = DesktopNotificationService()),
            scope = this,
            libraryProvider = { error("database unavailable") },
        )

        scheduler.runNow().join()

        assertEquals(mihon.domain.task.TaskStatus.Failed, scheduler.taskSnapshot()?.status)
        assertEquals(1, delivered.count { it.title == "Library update failed" })
    }

    private fun scheduler(
        file: Path,
        calls: MutableList<Long>,
        scope: kotlinx.coroutines.CoroutineScope,
        delivered: MutableList<DesktopNotification> = mutableListOf(),
        update: suspend (Long) -> UpdateResult,
    ): LibraryUpdateScheduler {
        val taskScheduler = DesktopTaskScheduler(FileTaskCheckpointStore(file))
        return LibraryUpdateScheduler(
            appPreferences = DesktopAppPreferences(InMemoryPreferenceStore()),
            updateChecker = null,
            getLibraryManga = null,
            sourceManager = null,
            taskScheduler = taskScheduler,
            taskNotifier = DesktopSystemNotifier(system = { delivered += it; true }, fallback = DesktopNotificationService()),
            scope = scope,
            libraryProvider = { (1L..3L).map(::libraryManga) },
            updateManga = { manga -> calls += manga.id; update(manga.id) },
        )
    }

    private fun libraryManga(id: Long) = LibraryManga(
        manga = Manga.create().copy(id = id, title = "M$id", favorite = true),
        categories = emptyList(), totalChapters = 0, readCount = 0, bookmarkCount = 0,
        latestUpload = 0, chapterFetchedAt = 0, lastRead = 0,
    )
}
