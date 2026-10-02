package mihon.desktop.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.platform.DesktopDeviceConditions
import mihon.desktop.platform.DeviceCondition
import mihon.desktop.platform.DeviceConditionsSnapshot
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.task.DesktopTaskScheduler
import mihon.desktop.task.FileTaskCheckpointStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryDeviceWaitingIntegrationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `automatic unknown wifi holds the original workset without consuming the periodic check`() = runTest {
        val node = java.util.prefs.Preferences.userRoot().node("mihon-ri16-${java.util.UUID.randomUUID()}")
        val store = tachiyomi.core.common.preference.DesktopPreferenceStore(node)
        val preferences = LibraryPreferences(store)
        preferences.autoUpdateInterval().set(6)
        preferences.autoUpdateMangaRestrictions().set(emptySet())
        preferences.autoUpdateDeviceRestrictions().set(setOf(LibraryPreferences.DEVICE_ONLY_ON_WIFI))
        preferences.lastUpdatedTimestamp().set(123)
        val calls = mutableListOf<Long>()
        val conditions = object : DesktopDeviceConditions {
            override val supported = DeviceCondition.entries.toSet()
            override fun query() = DeviceConditionsSnapshot()
        }
        val scheduler = LibraryUpdateScheduler(
            DesktopAppPreferences(store), null, null, null,
            taskScheduler = DesktopTaskScheduler(FileTaskCheckpointStore(directory.resolve("task.json"))),
            scope = this, libraryPreferences = preferences,
            clock = Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC),
            libraryProvider = { listOf(entry(1), entry(2)) },
            updateManga = {
                calls += it.id
                LibraryUpdateChecker.UpdateResult(0)
            },
            deviceConditions = conditions,
        )
        try {
            scheduler.start().join()
            advanceTimeBy(LibraryUpdateScheduler.CHECK_INTERVAL_MS)
            runCurrent()
            assertTrue(calls.isEmpty(), "Selected unknown Wi-Fi must wait before actual source checks")
            assertEquals(123L, preferences.lastUpdatedTimestamp().get())
            assertEquals(listOf(1L, 2L), scheduler.taskSnapshot()?.workset)
        } finally {
            scheduler.stopAndJoin()
            node.removeNode()
        }
    }

    @Test
    fun `manual refresh supersedes an automatic wait without running concurrent work`() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.scheduler.start().join()
            advanceTimeBy(LibraryUpdateScheduler.CHECK_INTERVAL_MS)
            runCurrent()
            val old = requireNotNull(fixture.scheduler.taskSnapshot())
            assertTrue(old.libraryUpdate!!.waitingForDevice.isNotEmpty())
            val manual = fixture.scheduler.runNow()
            runCurrent()
            assertTrue(manual.isCompleted, "Manual refresh must not return the suspended automatic worker")
            assertEquals(listOf(1L, 2L), fixture.calls)
            assertTrue(fixture.scheduler.taskSnapshot()!!.task.idempotencyKey != old.task.idempotencyKey)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `conditions recheck only between units and restart resumes the same fixed workset once`() = runTest {
        val fixture = Fixture(this)
        fixture.status = mihon.desktop.platform.DeviceConditionState.SATISFIED
        fixture.afterUpdate = { fixture.status = mihon.desktop.platform.DeviceConditionState.UNKNOWN }
        try {
            fixture.scheduler.start().join()
            advanceTimeBy(LibraryUpdateScheduler.CHECK_INTERVAL_MS)
            runCurrent()
            assertEquals(listOf(1L), fixture.calls)
            val original = requireNotNull(fixture.scheduler.taskSnapshot())
            assertEquals(setOf(1L), original.completedUnitIds)
            val predictionTime = original.libraryUpdate!!.checkStartedAt
            val checkedAt = fixture.preferences.lastUpdatedTimestamp().get()
            assertEquals(fixture.clock.millis(), checkedAt)
            fixture.scheduler.stopAndJoin()
            fixture.ids = listOf(1L, 2L, 3L)
            fixture.clock = Clock.fixed(Instant.parse("2026-10-02T00:10:00Z"), ZoneOffset.UTC)
            fixture.tasks = DesktopTaskScheduler(FileTaskCheckpointStore(fixture.taskPath))
            fixture.scheduler = fixture.createScheduler()
            fixture.scheduler.start()
            runCurrent()
            assertEquals(listOf(1L), fixture.calls)
            fixture.status = mihon.desktop.platform.DeviceConditionState.SATISFIED
            fixture.afterUpdate = {}
            advanceTimeBy(LibraryUpdateScheduler.CHECK_INTERVAL_MS)
            runCurrent()
            assertEquals(listOf(1L, 2L), fixture.calls)
            assertEquals(original.task.idempotencyKey, fixture.scheduler.taskSnapshot()!!.task.idempotencyKey)
            assertEquals(predictionTime, fixture.scheduler.taskSnapshot()!!.libraryUpdate!!.checkStartedAt)
            assertEquals(checkedAt, fixture.preferences.lastUpdatedTimestamp().get())
            assertEquals(listOf(1L, 2L), fixture.scheduler.taskSnapshot()!!.workset)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `device state refusal cannot run a source under a replaced occurrence`() = runTest {
        val fixture = Fixture(this)
        fixture.status = mihon.desktop.platform.DeviceConditionState.SATISFIED
        fixture.queryAction = {
            fixture.tasks.beginLibraryUpdate(
                LibraryUpdateScheduler.LIBRARY_UPDATE_TASK.copy(idempotencyKey = "replacement"),
                mihon.desktop.task.LibraryUpdateContext(
                    mihon.desktop.task.LibraryUpdateTrigger.MANUAL,
                    mihon.desktop.task.LibraryUpdateScope.ALL,
                    units = emptyList(),
                ),
            )
        }
        try {
            fixture.scheduler.start().join()
            advanceTimeBy(LibraryUpdateScheduler.CHECK_INTERVAL_MS)
            runCurrent()
            assertTrue(fixture.calls.isEmpty(), "A refused device checkpoint cannot authorize an older source call")
            assertEquals("replacement", fixture.scheduler.taskSnapshot()!!.task.idempotencyKey)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `shutdown waits for a superseded automatic owner that is still inside native query`() =
        kotlinx.coroutines.runBlocking {
            val fixture =
                Fixture(
                    kotlinx.coroutines.CoroutineScope(
                        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
                    ),
                )
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            fixture.tasks.beginLibraryUpdate(
                LibraryUpdateScheduler.LIBRARY_UPDATE_TASK,
                mihon.desktop.task.LibraryUpdateContext(
                    mihon.desktop.task.LibraryUpdateTrigger.SCHEDULED,
                    mihon.desktop.task.LibraryUpdateScope.ALL,
                    units = listOf(mihon.desktop.task.LibraryUpdateUnit(1, 42, "/manga/1")),
                    deviceRestrictions = setOf(LibraryPreferences.DEVICE_ONLY_ON_WIFI),
                    waitingForDevice = mapOf("wifi" to "UNKNOWN"),
                ),
            )
            fixture.queryAction = {
                entered.complete(Unit)
                kotlinx.coroutines.runBlocking(kotlinx.coroutines.NonCancellable) { release.await() }
            }
            try {
                fixture.scheduler.resumeUpdate()
                kotlinx.coroutines.withTimeout(5000) { entered.await() }
                fixture.scheduler.runNow()
                val stopping = async { fixture.scheduler.stopAndJoin() }
                kotlinx.coroutines.delay(50)
                assertTrue(
                    !stopping.isCompleted,
                    "Shutdown must also join the old query owner after manual replacement",
                )
                release.complete(Unit)
                kotlinx.coroutines.withTimeout(5000) { stopping.await() }
                assertTrue(fixture.calls.isEmpty())
            } finally {
                release.complete(Unit)
                fixture.close()
            }
        }

    @Test
    fun `retained unsupported restrictions and unselected unknown states never block automatic work`() = runTest {
        for (unsupported in listOf(false, true)) {
            val fixture = Fixture(this)
            if (!unsupported) fixture.preferences.autoUpdateDeviceRestrictions().set(emptySet())
            val retained = fixture.preferences.autoUpdateDeviceRestrictions().get()
            fixture.supported = if (unsupported) emptySet() else DeviceCondition.entries.toSet()
            fixture.queryAction = { error("No selected supported condition should query the native port") }
            try {
                fixture.scheduler.start().join()
                advanceTimeBy(LibraryUpdateScheduler.CHECK_INTERVAL_MS)
                runCurrent()
                assertEquals(listOf(1L, 2L), fixture.calls)
                assertEquals(retained, fixture.preferences.autoUpdateDeviceRestrictions().get())
                assertEquals(mihon.domain.task.TaskStatus.Completed, fixture.scheduler.taskSnapshot()!!.status)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `automatic condition snapshot is fixed while explicit failed retry bypasses only that wait`() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.tasks.beginLibraryUpdate(
                LibraryUpdateScheduler.LIBRARY_UPDATE_TASK,
                mihon.desktop.task.LibraryUpdateContext(
                    mihon.desktop.task.LibraryUpdateTrigger.SCHEDULED,
                    mihon.desktop.task.LibraryUpdateScope.ALL,
                    units = listOf(
                        mihon.desktop.task.LibraryUpdateUnit(
                            1,
                            42,
                            "/manga/1",
                            status = mihon.desktop.task.LibraryUnitStatus.FAILED,
                        ),
                        mihon.desktop.task.LibraryUpdateUnit(2, 42, "/manga/2"),
                    ),
                    deviceRestrictions = setOf("wifi"),
                    waitingForDevice = mapOf("wifi" to "UNKNOWN"),
                ),
            )
            fixture.scheduler.resumeUpdate()
            runCurrent()
            val original = fixture.scheduler.taskSnapshot()!!.task.idempotencyKey
            fixture.preferences.autoUpdateDeviceRestrictions().set(emptySet())
            advanceTimeBy(LibraryUpdateScheduler.CHECK_INTERVAL_MS)
            runCurrent()
            assertTrue(fixture.calls.isEmpty(), "New preferences cannot rewrite the accepted automatic workset")
            assertEquals(original, fixture.scheduler.taskSnapshot()!!.task.idempotencyKey)
            fixture.scheduler.retryFailed().join()
            assertEquals(listOf(1L), fixture.calls, "Explicit retry bypasses devices but never adds unprocessed units")
            assertEquals(listOf(1L), fixture.scheduler.taskSnapshot()!!.workset)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `actual task checkpoint refusal before a device boundary cannot start the source`() = runTest {
        val fixture = Fixture(this)
        fixture.tasks = DesktopTaskScheduler(
            FileTaskCheckpointStore(fixture.taskPath) { _, _ ->
                if (fixture.rejectWrites) throw java.io.IOException("Device checkpoint storage rejected")
                false
            },
        )
        fixture.scheduler = fixture.createScheduler()
        fixture.queryAction = { fixture.rejectWrites = true }
        try {
            fixture.scheduler.start().join()
            advanceTimeBy(LibraryUpdateScheduler.CHECK_INTERVAL_MS)
            runCurrent()
            assertTrue(fixture.calls.isEmpty())
            assertEquals(123L, fixture.preferences.lastUpdatedTimestamp().get())
            assertTrue(fixture.scheduler.lastLaunchFailure() != null)
            assertEquals(listOf(1L, 2L), fixture.scheduler.taskSnapshot()!!.workset)
        } finally {
            fixture.rejectWrites = false
            fixture.close()
        }
    }

    private inner class Fixture(val scope: kotlinx.coroutines.CoroutineScope) {
        val node = java.util.prefs.Preferences.userRoot().node("mihon-ri16-${java.util.UUID.randomUUID()}")
        val store = tachiyomi.core.common.preference.DesktopPreferenceStore(node)
        val preferences = LibraryPreferences(store).apply {
            autoUpdateInterval().set(6)
            autoUpdateMangaRestrictions().set(emptySet())
            autoUpdateDeviceRestrictions().set(setOf(LibraryPreferences.DEVICE_ONLY_ON_WIFI))
            lastUpdatedTimestamp().set(123)
        }
        val calls = mutableListOf<Long>()
        var supported = DeviceCondition.entries.toSet()
        var rejectWrites = false
        var status = mihon.desktop.platform.DeviceConditionState.UNKNOWN
        var afterUpdate: () -> Unit = {}
        var queryAction: () -> Unit = {}
        var ids = listOf(1L, 2L)
        var clock: Clock = Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC)
        val taskPath = directory.resolve("${java.util.UUID.randomUUID()}.json")
        var tasks = DesktopTaskScheduler(FileTaskCheckpointStore(taskPath))
        var scheduler = createScheduler()
        fun createScheduler() = LibraryUpdateScheduler(
            DesktopAppPreferences(store), null, null, null,
            taskScheduler = tasks, scope = scope, libraryPreferences = preferences, clock = clock,
            libraryProvider = { ids.map(::entry) },
            updateManga = {
                calls += it.id
                afterUpdate()
                LibraryUpdateChecker.UpdateResult(0)
            },
            deviceConditions = object : DesktopDeviceConditions {
                override val supported get() = this@Fixture.supported
                override fun query(): DeviceConditionsSnapshot {
                    queryAction()
                    return DeviceConditionsSnapshot(wifi = status)
                }
            },
        )
        suspend fun close() {
            scheduler.stopAndJoin()
            node.removeNode()
        }
    }

    private fun entry(id: Long) = LibraryManga(
        Manga.create().copy(id = id, source = 42, url = "/manga/$id", title = "Work $id", favorite = true),
        emptyList(),
        0,
        0,
        0,
        0,
        0,
        0,
    )
}
