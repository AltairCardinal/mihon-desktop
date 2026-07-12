package mihon.desktop.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
    fun `cancelling during non cancellable final source update emits cancelled only`() = runTest {
        val delivered = mutableListOf<DesktopNotification>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scheduler = scheduler(directory.resolve("tasks.json"), mutableListOf(), this, delivered) { id ->
            if (id == 3L) withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
            UpdateResult(1)
        }

        val job = scheduler.runNow()
        entered.await()
        assertTrue(scheduler.cancelUpdate())
        release.complete(Unit)
        job.join()

        assertEquals(mihon.domain.task.TaskStatus.Cancelled, scheduler.taskSnapshot()?.status)
        assertEquals(1, delivered.count { it.title == "Library update cancelled" })
        assertEquals(0, delivered.count { it.title == "Library updated" })
    }

    @Test
    fun `cancelling while non cancellable final source returns error never emits failure`() = runTest {
        val delivered = mutableListOf<DesktopNotification>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scheduler = scheduler(directory.resolve("tasks.json"), mutableListOf(), this, delivered) { id ->
            if (id == 3L) withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                return@withContext UpdateResult(0, error = "source failed after cancellation")
            }
            UpdateResult(0)
        }

        val job = scheduler.runNow()
        entered.await()
        assertTrue(scheduler.cancelUpdate())
        release.complete(Unit)
        job.join()

        assertEquals(mihon.domain.task.TaskStatus.Cancelled, scheduler.taskSnapshot()?.status)
        assertEquals(1, delivered.count { it.title == "Library update cancelled" })
        assertEquals(0, delivered.count { it.title.contains("failed", ignoreCase = true) })
    }

    @Test
    fun `creator discovery failure after successful update does not emit a second terminal`() = runTest {
        val delivered = mutableListOf<DesktopNotification>()
        val taskScheduler = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("tasks.json")))
        val scheduler = LibraryUpdateScheduler(
            appPreferences = DesktopAppPreferences(InMemoryPreferenceStore()),
            updateChecker = null,
            getLibraryManga = null,
            sourceManager = null,
            taskScheduler = taskScheduler,
            taskNotifier = DesktopSystemNotifier(system = { delivered += it; true }, fallback = DesktopNotificationService()),
            scope = this,
            libraryProvider = { listOf(libraryManga(1L)) },
            updateManga = { UpdateResult(0) },
            discoverCreators = { error("discovery unavailable") },
        )

        scheduler.runNow().join()

        assertEquals(mihon.domain.task.TaskStatus.Completed, scheduler.taskSnapshot()?.status)
        assertEquals(1, delivered.count { it.title == "Library updated" })
        assertEquals(0, delivered.count { it.title.contains("failed", ignoreCase = true) })
    }

    @Test
    fun `missing cursor after deletion restarts safely and skips completed ids`() = runTest {
        val file = directory.resolve("tasks.json")
        val firstCalls = mutableListOf<Long>()
        scheduler(file, firstCalls, this) { id -> if (id == 2L) error("stop") else UpdateResult(0) }.runNow().join()
        val resumedCalls = mutableListOf<Long>()
        val resumed = scheduler(file, resumedCalls, this, ids = listOf(3L, 1L)) { UpdateResult(0) }

        resumed.runNow().join()

        assertEquals(listOf(3L), resumedCalls)
        assertEquals(mihon.domain.task.TaskStatus.Completed, resumed.taskSnapshot()?.status)
    }

    @Test
    fun `resumed progress uses original workset after completed manga are filtered out`() = runTest {
        val file = directory.resolve("tasks.json")
        val first = scheduler(file, mutableListOf(), this) { id ->
            if (id == 3L) error("stop after two")
            UpdateResult(0)
        }
        first.runNow().join()
        val resumed = scheduler(file, mutableListOf(), this, ids = listOf(3L)) { UpdateResult(0) }

        resumed.runNow().join()

        assertEquals(1f, resumed.taskSnapshot()?.task?.checkpoint?.progress)
        assertEquals(mihon.domain.task.TaskStatus.Completed, resumed.taskSnapshot()?.status)
    }

    @Test
    fun `initialized empty workset does not absorb manga added during recovery`() = runTest {
        val file = directory.resolve("tasks.json")
        DesktopTaskScheduler(FileTaskCheckpointStore(file)).apply {
            register(LibraryUpdateScheduler.LIBRARY_UPDATE_TASK)
            start(LibraryUpdateScheduler.LIBRARY_UPDATE_TASK.id)
            setWorkset(LibraryUpdateScheduler.LIBRARY_UPDATE_TASK.id, emptyList())
            fail(LibraryUpdateScheduler.LIBRARY_UPDATE_TASK.id, mihon.domain.error.AppError.Unknown())
        }
        val calls = mutableListOf<Long>()
        val resumed = scheduler(file, calls, this, ids = listOf(4L)) { UpdateResult(0) }

        resumed.runNow().join()

        assertTrue(calls.isEmpty())
        assertEquals(mihon.domain.task.TaskStatus.Completed, resumed.taskSnapshot()?.status)
    }

    @Test
    fun `concurrent runNow calls share one occurrence`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val scheduler = scheduler(directory.resolve("tasks.json"), mutableListOf(), this) {
            calls++
            entered.complete(Unit)
            release.await()
            UpdateResult(0)
        }

        val jobs = (1..20).map { async { scheduler.runNow() } }.awaitAll()
        entered.await()
        assertEquals(1, jobs.distinct().size)
        release.complete(Unit)
        jobs.first().join()
        assertEquals(3, calls)
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
        ids: List<Long> = (1L..3L).toList(),
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
            libraryProvider = { ids.map(::libraryManga) },
            updateManga = { manga -> calls += manga.id; update(manga.id) },
        )
    }

    private fun libraryManga(id: Long) = LibraryManga(
        manga = Manga.create().copy(id = id, title = "M$id", favorite = true),
        categories = emptyList(), totalChapters = 0, readCount = 0, bookmarkCount = 0,
        latestUpload = 0, chapterFetchedAt = 0, lastRead = 0,
    )
}
