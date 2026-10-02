package mihon.desktop.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceTimeBy
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
    fun `cancelled initialization cannot advance the durable check time`() = runTest {
        val node = java.util.prefs.Preferences.userRoot().node("mihon-ri14-init-${java.util.UUID.randomUUID()}")
        val store = tachiyomi.core.common.preference.DesktopPreferenceStore(node)
        val preferences = tachiyomi.domain.library.service.LibraryPreferences(store)
        preferences.lastUpdatedTimestamp().set(123)
        val tasks = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("initialization.json")))
        val now = java.time.Instant.parse("2026-10-02T00:00:00Z")
        val clock = object : java.time.Clock() {
            override fun getZone() = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
            override fun instant() = now
            override fun millis(): Long {
                assertTrue(tasks.cancelRunning(LibraryUpdateScheduler.LIBRARY_UPDATE_TASK.id))
                return now.toEpochMilli()
            }
        }
        val calls = mutableListOf<Long>()
        val scheduler = LibraryUpdateScheduler(
            DesktopAppPreferences(store), null, null, null,
            taskScheduler = tasks, scope = this, libraryPreferences = preferences, clock = clock,
            libraryProvider = { listOf(libraryManga(1)) },
            updateManga = {
                calls += it.id
                UpdateResult(0)
            },
        )
        try {
            scheduler.runNow().join()
            assertEquals(mihon.domain.task.TaskStatus.Cancelled, scheduler.taskSnapshot()?.status)
            assertEquals(123L, preferences.lastUpdatedTimestamp().get())
            assertTrue(calls.isEmpty())
        } finally {
            scheduler.stopAndJoin()
            node.removeNode()
        }
    }

    @Test
    fun `library occurrence continues after a failed middle unit and retains later success`() = runTest {
        val calls = mutableListOf<Long>()
        val scheduler = scheduler(directory.resolve("continue.json"), calls, this) { id ->
            if (id == 2L) UpdateResult(0, error = "failed B") else UpdateResult(0)
        }

        scheduler.runNow().join()

        assertEquals(listOf(1L, 2L, 3L), calls)
        assertEquals(setOf(1L, 3L), scheduler.taskSnapshot()?.completedUnitIds)
        assertEquals(listOf("manga:2"), scheduler.taskSnapshot()?.failedUnits)
    }

    @Test
    fun `legacy occurrence without a fixed workset cannot expand into the current library`() = runTest {
        val file = directory.resolve("legacy.json")
        DesktopTaskScheduler(FileTaskCheckpointStore(file)).register(LibraryUpdateScheduler.LIBRARY_UPDATE_TASK)
        val calls = mutableListOf<Long>()
        val scheduler = scheduler(file, calls, this) { UpdateResult(0) }
        try {
            scheduler.start().join()
            assertEquals(emptyList<Long>(), calls)
            assertEquals(mihon.domain.task.TaskStatus.Failed, scheduler.taskSnapshot()?.status)
        } finally {
            scheduler.stopAndJoin()
        }
    }

    @Test
    fun `fresh category occurrence replaces a previous failed workset instead of inheriting it`() = runTest {
        val file = directory.resolve("fresh-category.json")
        val calls = mutableListOf<Long>()
        val scheduler = LibraryUpdateScheduler(
            appPreferences = DesktopAppPreferences(InMemoryPreferenceStore()),
            updateChecker = null,
            getLibraryManga = null,
            sourceManager = null,
            taskScheduler = DesktopTaskScheduler(FileTaskCheckpointStore(file)),
            scope = this,
            libraryProvider = {
                (1L..3L).map { id -> libraryManga(id).copy(categories = listOf(if (id == 3L) 20L else 10L)) }
            },
            updateManga = { manga ->
                calls += manga.id
                if (manga.id == 2L) UpdateResult(0, error = "failed B") else UpdateResult(0)
            },
        )

        scheduler.runNow(10L).join()
        scheduler.runNow(20L).join()

        assertEquals(listOf(1L, 2L, 3L), calls)
        assertEquals(listOf(3L), scheduler.taskSnapshot()?.workset)
        assertEquals(setOf(3L), scheduler.taskSnapshot()?.completedUnitIds)
    }

    @Test
    fun `accepted library query can be cancelled before its fixed workset has loaded`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<Long>()
        val scheduler = LibraryUpdateScheduler(
            DesktopAppPreferences(InMemoryPreferenceStore()),
            null,
            null,
            null,
            taskScheduler = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("loading.json"))),
            scope = this,
            libraryProvider = {
                entered.complete(Unit)
                release.await()
                listOf(libraryManga(1))
            },
            updateManga = {
                calls += it.id
                UpdateResult(0)
            },
        )
        val job = scheduler.runNow()
        try {
            kotlinx.coroutines.withTimeout(5_000) { kotlinx.coroutines.withTimeout(5_000) { entered.await() } }
            assertTrue(scheduler.cancelUpdate(), "The accepted job is cancellable while its repository read waits")
        } finally {
            release.complete(Unit)
            job.join()
        }
        assertEquals(emptyList<Long>(), calls)
        assertEquals(mihon.domain.task.TaskStatus.Cancelled, scheduler.taskSnapshot()?.status)
    }

    @Test
    fun `library unit completion cannot replace the originally accepted source and URL identity`() {
        val tasks = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("identity.json")))
        val original = mihon.desktop.task.LibraryUpdateUnit(1, 42, "/original")
        val task = LibraryUpdateScheduler.LIBRARY_UPDATE_TASK.copy(idempotencyKey = "identity-occurrence")
        tasks.beginLibraryUpdate(
            task,
            mihon.desktop.task.LibraryUpdateContext(
                mihon.desktop.task.LibraryUpdateTrigger.MANUAL,
                mihon.desktop.task.LibraryUpdateScope.ALL,
                units = listOf(original),
            ),
        )
        tasks.start(task.id)
        val accepted = tasks.recordLibraryUnit(
            task.id,
            task.idempotencyKey,
            original.copy(
                sourceId = 43,
                mangaUrl = "/replacement",
                status = mihon.desktop.task.LibraryUnitStatus.SUCCESS,
            ),
        )
        assertEquals(false, accepted)
        assertEquals(original, tasks.snapshot(task.id)!!.libraryUpdate!!.units.single())
    }

    @Test
    fun `failed only retry cannot resume cancelled unprocessed units`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<Long>()
        val scheduler = scheduler(directory.resolve("cancelled-retry.json"), calls, this) { id ->
            if (id == 2L) {
                entered.complete(Unit)
                release.await()
            }
            UpdateResult(1)
        }
        val accepted = scheduler.runNow()
        try {
            kotlinx.coroutines.withTimeout(5_000) { entered.await() }
            assertTrue(scheduler.cancelUpdate())
        } finally {
            release.complete(Unit)
            accepted.join()
        }
        val cancelled = scheduler.taskSnapshot()
        scheduler.retryFailed().join()
        assertEquals(listOf(1L, 2L), calls, "Only resume can execute the cancelled original remainder")
        assertEquals(cancelled, scheduler.taskSnapshot())
        scheduler.resumeUpdate().join()
        assertEquals(listOf(1L, 2L, 2L, 3L), calls)
        assertEquals(mihon.domain.task.TaskStatus.Completed, scheduler.taskSnapshot()?.status)
    }

    @Test
    fun `initial durable acceptance failure preserves old completion and reports this launch failure`() = runTest {
        var reject = false
        val file = directory.resolve("acceptance.json")
        val tasks = DesktopTaskScheduler(
            FileTaskCheckpointStore(file) { source, target ->
                if (reject) throw java.io.IOException("checkpoint unavailable")
                java.nio.file.Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                true
            },
        )
        val calls = mutableListOf<Long>()
        val delivered = mutableListOf<DesktopNotification>()
        val scheduler = LibraryUpdateScheduler(
            DesktopAppPreferences(InMemoryPreferenceStore()), null, null, null,
            taskScheduler = tasks,
            taskNotifier = DesktopSystemNotifier(system = {
                delivered += it
                true
            }, fallback = DesktopNotificationService()),
            scope = this,
            libraryProvider = { listOf(libraryManga(1)) },
            updateManga = {
                calls += it.id
                UpdateResult(1)
            },
        )
        scheduler.runNow().join()
        val completed = scheduler.taskSnapshot()
        delivered.clear()
        reject = true
        scheduler.runNow().join()
        assertEquals(listOf(1L), calls, "Refused acceptance performs no source or directory operation")
        assertEquals(completed, scheduler.taskSnapshot())
        assertEquals(
            1,
            delivered.count {
                it.title == "Library update failed"
            },
            "This launch must not report the old completion",
        )
    }

    @Test
    fun `periodic restart uses durable actual check time and controlled backwards and sleep clocks`() = runTest {
        val node = java.util.prefs.Preferences.userRoot().node("mihon-ri14-clock-${java.util.UUID.randomUUID()}")
        val store = tachiyomi.core.common.preference.DesktopPreferenceStore(node)
        val preferences = tachiyomi.domain.library.service.LibraryPreferences(store)
        preferences.autoUpdateInterval().set(48)
        var now = java.time.Instant.parse("2026-10-02T00:00:00Z").toEpochMilli()
        val originalTime = now
        preferences.lastUpdatedTimestamp().set(now)
        val clock = object : java.time.Clock() {
            override fun getZone() = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = java.time.Instant.ofEpochMilli(now)
        }
        val calls = mutableListOf<Long>()
        fun newScheduler() = LibraryUpdateScheduler(
            DesktopAppPreferences(store), null, null, null,
            taskScheduler = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("periodic.json"))),
            scope = this,
            libraryProvider = { listOf(libraryManga(1)) },
            updateManga = {
                calls += it.id
                UpdateResult(0)
            },
            libraryPreferences = preferences,
            clock = clock,
        )
        var scheduler = newScheduler()
        try {
            scheduler.start().join()
            advanceTimeBy(61_000)
            runCurrent()
            assertEquals(emptyList<Long>(), calls, "Restart cannot treat an ordinary poll as a due check")
            now -= 86_400_000
            advanceTimeBy(61_000)
            runCurrent()
            assertEquals(emptyList<Long>(), calls, "Backwards clocks preserve the persisted due boundary")
            now = originalTime + 48 * 3_600_000L
            advanceTimeBy(61_000)
            runCurrent()
            assertEquals(listOf(1L), calls)
            assertEquals(now, preferences.lastUpdatedTimestamp().get())
            assertEquals(
                mihon.desktop.task.LibraryUpdateTrigger.SCHEDULED,
                scheduler.taskSnapshot()!!.libraryUpdate!!.trigger,
            )
            scheduler.stopAndJoin()
            scheduler = newScheduler()
            scheduler.start().join()
            advanceTimeBy(61_000)
            runCurrent()
            assertEquals(listOf(1L), calls, "A completed check remains not due after reopening")
            now += 7 * 86_400_000L
            advanceTimeBy(61_000)
            runCurrent()
            assertEquals(listOf(1L, 1L), calls, "A wake after several missed intervals runs one occurrence")
            preferences.autoUpdateInterval().set(0)
            now += 7 * 86_400_000L
            advanceTimeBy(61_000)
            runCurrent()
            assertEquals(listOf(1L, 1L), calls)
        } finally {
            scheduler.stopAndJoin()
            node.removeNode()
        }
    }

    @Test
    fun `original timer consumes shared extended interval and explicit off without legacy dual writes`() = runTest {
        for (hours in listOf(48, 72)) {
            val node = java.util.prefs.Preferences.userRoot().node(
                "/mihon-test/ri12-timer-${java.util.UUID.randomUUID()}",
            )
            val store = tachiyomi.core.common.preference.DesktopPreferenceStore(node)
            val preferences = tachiyomi.domain.library.service.LibraryPreferences(store)
            val app = DesktopAppPreferences(store, node.node("desktop/app"))
            app.libraryUpdateInterval.set(mihon.desktop.settings.LibraryUpdateInterval.OFF)
            preferences.autoUpdateInterval().set(hours)
            val calls = mutableListOf<Long>()
            val scheduler = LibraryUpdateScheduler(
                appPreferences = app,
                updateChecker = null,
                getLibraryManga = null,
                sourceManager = null,
                scope = this,
                libraryProvider = { listOf(libraryManga(1)) },
                updateManga = { manga ->
                    calls += manga.id
                    UpdateResult(0)
                },
                libraryPreferences = preferences,
            )
            try {
                scheduler.start().join()
                advanceTimeBy(61_000)
                runCurrent()
                assertEquals(listOf(1L), calls)
                assertEquals(hours, preferences.autoUpdateInterval().get())
                assertEquals(mihon.desktop.settings.LibraryUpdateInterval.OFF, app.libraryUpdateInterval.get())
                preferences.autoUpdateInterval().set(0)
                advanceTimeBy(61_000)
                runCurrent()
                assertEquals(listOf(1L), calls)
            } finally {
                scheduler.stopAndJoin()
                node.removeNode()
            }
        }
    }

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
        second.start().join()

        assertEquals(listOf(1L, 2L, 3L, 2L), calls)
        assertTrue(second.taskSnapshot()?.status?.isTerminal == true)
        second.stop()
    }

    @Test
    fun `pending task without checkpoint resumes immediately on startup`() = runTest {
        val calls = mutableListOf<Long>()
        val file = directory.resolve("tasks.json")
        seedPendingWorkset(file)

        val scheduler = scheduler(file, calls, this) { UpdateResult(0) }
        scheduler.start().join()

        assertEquals(listOf(1L, 2L, 3L), calls)
        scheduler.stop()
    }

    @Test
    fun `startup without pending work completes initial recovery immediately`() = runTest {
        val file = directory.resolve("tasks.json")
        val calls = mutableListOf<Long>()
        val emptyProfile = scheduler(file, calls, this) { UpdateResult(0) }
        emptyProfile.start().join()
        assertTrue(calls.isEmpty())
        assertEquals(null, emptyProfile.taskSnapshot())
        emptyProfile.stop()
        val scheduler = scheduler(file, calls, this) { UpdateResult(0) }
        scheduler.runNow().join()
        val completed = scheduler.taskSnapshot()
        val reopened = scheduler(file, calls, this) { UpdateResult(0) }
        reopened.start().join()
        assertEquals(completed, reopened.taskSnapshot())
        assertEquals(listOf(1L, 2L, 3L), calls)
        reopened.stop()

        val initialRecovery = scheduler.start()

        assertTrue(initialRecovery.isCompleted)
        assertTrue(scheduler.isRunning)
        scheduler.stop()
    }

    @Test
    fun `stop cancels initial recovery and its update`() = runTest {
        val file = directory.resolve("tasks.json")
        seedPendingWorkset(file)
        val entered = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        val scheduler = scheduler(file, mutableListOf(), this) {
            entered.complete(Unit)
            neverRelease.await()
            UpdateResult(0)
        }
        val initialRecovery = scheduler.start()
        kotlinx.coroutines.withTimeout(5_000) { entered.await() }

        scheduler.stop()
        initialRecovery.join()

        assertTrue(initialRecovery.isCancelled)
        assertTrue(!scheduler.isRunning)
    }

    @Test
    fun `stopAndJoin cancels and joins initial recovery`() = runTest {
        val file = directory.resolve("tasks.json")
        seedPendingWorkset(file)
        val entered = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        val schedulerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val scheduler = scheduler(file, mutableListOf(), schedulerScope) {
            entered.complete(Unit)
            neverRelease.await()
            UpdateResult(0)
        }
        val initialRecovery = scheduler.start()
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { entered.await() }
        }

        scheduler.stopAndJoin()

        assertTrue(initialRecovery.isCancelled)
        assertTrue(!scheduler.isRunning)
    }

    @Test
    fun `runNow during initial recovery shares the recovery occurrence`() = runTest {
        val file = directory.resolve("tasks.json")
        seedPendingWorkset(file)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scheduler = scheduler(file, mutableListOf(), this) {
            entered.complete(Unit)
            release.await()
            UpdateResult(0)
        }
        val initialRecovery = scheduler.start()
        kotlinx.coroutines.withTimeout(5_000) { entered.await() }

        val concurrent = scheduler.runNow()
        release.complete(Unit)
        initialRecovery.join()

        assertTrue(concurrent.isCompleted)
        assertEquals(mihon.domain.task.TaskStatus.Completed, scheduler.taskSnapshot()?.status)
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
        kotlinx.coroutines.withTimeout(5_000) { entered.await() }
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
            if (id == 3L) {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            UpdateResult(1)
        }

        val job = scheduler.runNow()
        kotlinx.coroutines.withTimeout(5_000) { entered.await() }
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
            if (id == 3L) {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    return@withContext UpdateResult(0, error = "source failed after cancellation")
                }
            }
            UpdateResult(0)
        }

        val job = scheduler.runNow()
        kotlinx.coroutines.withTimeout(5_000) { entered.await() }
        assertTrue(scheduler.cancelUpdate())
        release.complete(Unit)
        job.join()

        assertEquals(mihon.domain.task.TaskStatus.Cancelled, scheduler.taskSnapshot()?.status)
        assertEquals(1, delivered.count { it.title == "Library update cancelled" })
        assertEquals(0, delivered.count { it.title.contains("failed", ignoreCase = true) })
    }

    @Test
    fun `library update success triggers discovery due reevaluation without changing library terminal`() = runTest {
        val delivered = mutableListOf<DesktopNotification>()
        val taskScheduler = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("tasks.json")))
        val discovery = CreatorDiscoveryScheduler(
            taskScheduler = taskScheduler,
            discoverDue = { error("discovery unavailable") },
            discoverCreator = { error("unused") },
            scope = this,
        )
        val scheduler = LibraryUpdateScheduler(
            appPreferences = DesktopAppPreferences(InMemoryPreferenceStore()),
            updateChecker = null,
            getLibraryManga = null,
            sourceManager = null,
            taskScheduler = taskScheduler,
            taskNotifier = DesktopSystemNotifier(system = {
                delivered += it
                true
            }, fallback = DesktopNotificationService()),
            scope = this,
            libraryProvider = { listOf(libraryManga(1L)) },
            updateManga = { UpdateResult(0) },
            creatorDiscoveryScheduler = discovery,
        )

        scheduler.runNow().join()

        // Library update stays Completed; discovery is driven as an independent task
        // and its failure never leaks into the library terminal state.
        assertEquals(mihon.domain.task.TaskStatus.Completed, scheduler.taskSnapshot()?.status)
        assertEquals(mihon.domain.task.TaskStatus.Failed, discovery.taskSnapshot()?.status)
        assertEquals(1, delivered.count { it.title == "Library updated" })
        assertEquals(0, delivered.count { it.title.contains("failed", ignoreCase = true) })
        discovery.stop()
    }

    @Test
    fun `library update failure does not block independent discovery due run`() = runTest {
        val taskScheduler = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("tasks.json")))
        var dueRuns = 0
        val discovery = CreatorDiscoveryScheduler(
            taskScheduler = taskScheduler,
            discoverDue = {
                dueRuns += 1
                tachiyomi.domain.creator.service.CreatorDiscoveryResult(0, 0, emptyList())
            },
            discoverCreator = { tachiyomi.domain.creator.service.CreatorDiscoveryResult(0, 0, emptyList()) },
            hasDueWork = { true },
            scope = this,
        )
        discovery.start().join()
        advanceTimeBy(CreatorDiscoveryScheduler.CHECK_INTERVAL_MS + 1_000)
        runCurrent()
        assertTrue(dueRuns >= 1)

        val scheduler = LibraryUpdateScheduler(
            appPreferences = DesktopAppPreferences(InMemoryPreferenceStore()),
            updateChecker = null,
            getLibraryManga = null,
            sourceManager = null,
            taskScheduler = taskScheduler,
            taskNotifier = DesktopSystemNotifier(system = { true }, fallback = DesktopNotificationService()),
            scope = this,
            libraryProvider = { error("library database unavailable") },
            creatorDiscoveryScheduler = discovery,
        )
        scheduler.runNow().join()
        assertEquals(mihon.domain.task.TaskStatus.Failed, scheduler.taskSnapshot()?.status)

        // The discovery scheduler remains independently drivable after a failed library update.
        val before = dueRuns
        discovery.runNow().join()
        assertEquals(before + 1, dueRuns)
        discovery.stop()
    }

    @Test
    fun `missing cursor after deletion restarts safely and skips completed ids`() = runTest {
        val file = directory.resolve("tasks.json")
        val firstCalls = mutableListOf<Long>()
        val thirdEntered = CompletableDeferred<Unit>()
        val first = scheduler(file, firstCalls, this) { id ->
            if (id == 2L) error("stop")
            if (id == 3L) {
                thirdEntered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
            UpdateResult(0)
        }
        val original = first.runNow()
        kotlinx.coroutines.withTimeout(5_000) { thirdEntered.await() }
        assertTrue(first.cancelUpdate())
        original.join()
        val resumedCalls = mutableListOf<Long>()
        val resumed = scheduler(file, resumedCalls, this, ids = listOf(3L, 1L)) { UpdateResult(0) }

        resumed.resumeUpdate().join()

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

        resumed.resumeUpdate().join()

        assertEquals(1f, resumed.taskSnapshot()?.task?.checkpoint?.progress)
        assertEquals(mihon.domain.task.TaskStatus.Completed, resumed.taskSnapshot()?.status)
    }

    @Test
    fun `initialized empty workset does not absorb manga added during recovery`() = runTest {
        val file = directory.resolve("tasks.json")
        DesktopTaskScheduler(FileTaskCheckpointStore(file)).apply {
            beginLibraryUpdate(
                LibraryUpdateScheduler.LIBRARY_UPDATE_TASK,
                mihon.desktop.task.LibraryUpdateContext(
                    mihon.desktop.task.LibraryUpdateTrigger.MANUAL,
                    mihon.desktop.task.LibraryUpdateScope.ALL,
                    units = emptyList(),
                ),
            )
            start(LibraryUpdateScheduler.LIBRARY_UPDATE_TASK.id)
            fail(LibraryUpdateScheduler.LIBRARY_UPDATE_TASK.id, mihon.domain.error.AppError.Unknown())
        }
        val calls = mutableListOf<Long>()
        val resumed = scheduler(file, calls, this, ids = listOf(4L)) { UpdateResult(0) }

        resumed.resumeUpdate().join()

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
        kotlinx.coroutines.withTimeout(5_000) { entered.await() }
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
            updateChecker = null,
            getLibraryManga = null,
            sourceManager = null,
            taskScheduler = taskScheduler,
            taskNotifier = DesktopSystemNotifier(system = {
                delivered += it
                true
            }, fallback = DesktopNotificationService()),
            scope = this,
            libraryProvider = { error("database unavailable") },
        )

        scheduler.runNow().join()

        assertEquals(mihon.domain.task.TaskStatus.Failed, scheduler.taskSnapshot()?.status)
        assertEquals(1, delivered.count { it.title == "Library update failed" })
    }

    private fun seedPendingWorkset(file: Path) {
        DesktopTaskScheduler(FileTaskCheckpointStore(file)).beginLibraryUpdate(
            LibraryUpdateScheduler.LIBRARY_UPDATE_TASK,
            mihon.desktop.task.LibraryUpdateContext(
                mihon.desktop.task.LibraryUpdateTrigger.MANUAL,
                mihon.desktop.task.LibraryUpdateScope.ALL,
                units = (1L..3L).map { id -> mihon.desktop.task.LibraryUpdateUnit(id, 42, "/manga/$id") },
            ),
        )
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
            taskNotifier = DesktopSystemNotifier(system = {
                delivered += it
                true
            }, fallback = DesktopNotificationService()),
            scope = scope,
            libraryProvider = { ids.map(::libraryManga) },
            updateManga = { manga ->
                calls += manga.id
                update(manga.id)
            },
        )
    }

    private fun libraryManga(id: Long) = LibraryManga(
        manga = Manga.create().copy(id = id, source = 42, url = "/manga/$id", title = "M$id", favorite = true),
        categories = emptyList(),
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )
}
