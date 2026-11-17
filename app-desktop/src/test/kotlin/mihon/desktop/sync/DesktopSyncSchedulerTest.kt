package mihon.desktop.sync

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.DesktopAppRuntime
import mihon.desktop.DesktopRuntimeService
import mihon.domain.sync.runtime.SyncCoordinator
import mihon.domain.sync.runtime.SyncPreferences
import mihon.domain.sync.runtime.SyncRunPort
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunResult
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopSyncSchedulerTest {
    @Test
    fun `temporary recovery lookup failure does not permanently stop the scheduler`() = runTest {
        val preferences = SyncPreferences(InMemoryPreferenceStore())
        preferences.startup.set(false)
        preferences.setInterval(0)
        var probes = 0
        var failures = 0
        val coordinator = SyncCoordinator(SyncRunPort { SyncRunResult(SyncRunStatus.SUCCESS) })
        val scheduler = DesktopSyncScheduler(
            coordinator,
            preferences,
            backgroundScope,
            resumeIfNeeded = {
                if (++probes == 1) throw java.sql.SQLException("database temporarily locked", null, 5)
                false
            },
            hasResumableRun = { probes < 2 },
            onRecoveryFailure = { failures++ },
        )
        scheduler.start()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, probes)
        assertEquals(1, failures)
        scheduler.stop()
        scheduler.awaitStopped()
    }

    @Test
    fun `persisted retry wakes after restart with periodic synchronization disabled`() = runTest {
        val preferences = SyncPreferences(InMemoryPreferenceStore())
        preferences.startup.set(false)
        preferences.setInterval(0)
        val triggers = mutableListOf<SyncTrigger>()
        var probes = 0
        val deadline = 5_000L
        val coordinator = SyncCoordinator(
            SyncRunPort {
                triggers += it
                SyncRunResult(SyncRunStatus.SUCCESS)
            },
        )
        val scheduler = DesktopSyncScheduler(
            coordinator,
            preferences,
            backgroundScope,
            resumeIfNeeded = {
                probes++
                if (testScheduler.currentTime >= deadline) coordinator.synchronize(SyncTrigger.RECOVERY)
                true
            },
            recoveryDelayMillis = { (deadline - testScheduler.currentTime).coerceAtLeast(0) },
            hasResumableRun = { triggers.isEmpty() },
        )
        scheduler.start()
        runCurrent()
        assertEquals(1, probes)
        advanceTimeBy(deadline)
        runCurrent()
        assertEquals(listOf(SyncTrigger.RECOVERY), triggers)
        scheduler.stop()
        scheduler.awaitStopped()
    }

    @Test
    fun `remote changed completion wakes accepted recovery without periodic synchronization`() = runTest {
        val preferences = SyncPreferences(InMemoryPreferenceStore())
        preferences.startup.set(false)
        preferences.setInterval(0)
        val triggers = mutableListOf<SyncTrigger>()
        val coordinator = SyncCoordinator(
            SyncRunPort {
                triggers += it
                if (triggers.size == 1) {
                    SyncRunResult(SyncRunStatus.FAILED, problem = SyncRunProblem.REMOTE_CHANGED)
                } else {
                    SyncRunResult(SyncRunStatus.SUCCESS)
                }
            },
        )
        val scheduler = DesktopSyncScheduler(
            coordinator,
            preferences,
            backgroundScope,
            resumeIfNeeded = {
                if (triggers.size == 1) {
                    coordinator.synchronize(SyncTrigger.RECOVERY)
                    true
                } else {
                    false
                }
            },
            recoveryDelayMillis = { (1_000L - testScheduler.currentTime).coerceAtLeast(0) },
            hasResumableRun = { triggers.size == 1 },
        )
        scheduler.start()
        runCurrent()
        coordinator.synchronize(SyncTrigger.MANUAL)
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf(SyncTrigger.MANUAL, SyncTrigger.RECOVERY), triggers)
        scheduler.stop()
        scheduler.awaitStopped()
    }

    @Test
    fun `manual success invalidates an older recovery deadline without starting another run`() = runTest {
        val preferences = SyncPreferences(InMemoryPreferenceStore())
        preferences.startup.set(false)
        preferences.setInterval(0)
        val triggers = mutableListOf<SyncTrigger>()
        val coordinator = SyncCoordinator(
            SyncRunPort {
                triggers += it
                if (triggers.size == 1) {
                    SyncRunResult(SyncRunStatus.FAILED, problem = SyncRunProblem.NETWORK)
                } else {
                    SyncRunResult(SyncRunStatus.SUCCESS)
                }
            },
        )
        val scheduler = DesktopSyncScheduler(
            coordinator,
            preferences,
            backgroundScope,
            resumeIfNeeded = {
                if (triggers.size == 1 && testScheduler.currentTime >= 1_000L) {
                    coordinator.synchronize(SyncTrigger.RECOVERY)
                    true
                } else {
                    false
                }
            },
            recoveryDelayMillis = { (1_000L - testScheduler.currentTime).coerceAtLeast(0) },
            hasResumableRun = { triggers.size == 1 },
        )
        scheduler.start()
        runCurrent()
        coordinator.synchronize(SyncTrigger.MANUAL)
        runCurrent()
        advanceTimeBy(500)
        coordinator.synchronize(SyncTrigger.MANUAL)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(SyncTrigger.MANUAL, SyncTrigger.MANUAL), triggers)
        scheduler.stop()
        scheduler.awaitStopped()
    }

    @Test
    fun `paused durable recovery and stopped scheduler cannot wake the old deadline`() = runTest {
        val preferences = SyncPreferences(InMemoryPreferenceStore())
        preferences.startup.set(false)
        preferences.setInterval(0)
        var accepted = true
        var calls = 0
        val coordinator = SyncCoordinator(SyncRunPort { SyncRunResult(SyncRunStatus.SUCCESS) })
        val scheduler = DesktopSyncScheduler(
            coordinator,
            preferences,
            backgroundScope,
            resumeIfNeeded = {
                calls++
                true
            },
            recoveryDelayMillis = { (1_000L - testScheduler.currentTime).coerceAtLeast(0) },
            hasResumableRun = { accepted },
        )
        scheduler.start()
        runCurrent()
        assertEquals(1, calls)
        accepted = false
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, calls)
        scheduler.stop()
        scheduler.awaitStopped()
        accepted = true
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun `scheduler uses the deadline already displayed by the panel`() = runTest {
        val preferences = SyncPreferences(InMemoryPreferenceStore())
        preferences.startup.set(false)
        preferences.setInterval(15)
        preferences.scheduleAnchor.set(1)
        var calls = 0
        val coordinator = SyncCoordinator(
            SyncRunPort {
                calls++
                SyncRunResult(SyncRunStatus.SUCCESS)
            },
        )
        advanceTimeBy(5 * 60_000L)
        val scheduler =
            DesktopSyncScheduler(coordinator, preferences, backgroundScope) { testScheduler.currentTime + 1 }
        scheduler.start()
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(testScheduler.currentTime + 1, preferences.scheduleAnchor.get())
        scheduler.stop()
        scheduler.awaitStopped()
    }

    @Test
    fun `canceling one periodic exchange does not stop the periodic observer`() = runTest {
        val preferences = SyncPreferences(InMemoryPreferenceStore())
        preferences.startup.set(false)
        preferences.setInterval(15)
        var calls = 0
        val coordinator = SyncCoordinator(
            SyncRunPort {
                if (++calls == 1) awaitCancellation()
                SyncRunResult(SyncRunStatus.SUCCESS)
            },
        )
        val scheduler =
            DesktopSyncScheduler(coordinator, preferences, backgroundScope) { testScheduler.currentTime + 1 }
        scheduler.start()
        runCurrent()
        advanceTimeBy(15 * 60_000L)
        runCurrent()
        assertEquals(1, calls)
        coordinator.cancelAndJoin()
        runCurrent()
        advanceTimeBy(15 * 60_000L)
        runCurrent()
        assertEquals(2, calls)
        scheduler.stop()
        scheduler.awaitStopped()
    }

    @Test
    fun `startup is asynchronous periodic options work and runtime shutdown joins actual cancellation`() = runTest {
        val preferences = SyncPreferences(InMemoryPreferenceStore())
        preferences.setInterval(15)
        val triggers = mutableListOf<SyncTrigger>()
        var hang = false
        var cleaned = false
        val coordinator = SyncCoordinator(
            SyncRunPort {
                triggers += it
                if (hang) {
                    try {
                        awaitCancellation()
                    } finally {
                        cleaned = true
                    }
                }
                SyncRunResult(SyncRunStatus.SUCCESS)
            },
        )
        val scheduler =
            DesktopSyncScheduler(coordinator, preferences, backgroundScope) { testScheduler.currentTime + 1 }
        val noOp = object : DesktopRuntimeService {
            override fun start() = Unit
            override fun stop() = Unit
        }
        val app =
            DesktopAppRuntime(noOp, noOp, noOp, startupCleanup = {}, syncService = scheduler, scope = backgroundScope)
        app.start()
        assertTrue(triggers.isEmpty())
        runCurrent()
        assertEquals(listOf(SyncTrigger.STARTUP), triggers)
        advanceTimeBy(15 * 60_000L)
        runCurrent()
        assertEquals(listOf(SyncTrigger.STARTUP, SyncTrigger.PERIODIC), triggers)
        preferences.setInterval(0)
        runCurrent()
        advanceTimeBy(60 * 60_000L)
        runCurrent()
        assertEquals(2, triggers.size)
        preferences.setInterval(60)
        runCurrent()
        hang = true
        advanceTimeBy(60 * 60_000L)
        runCurrent()
        assertTrue(coordinator.activity.value.running)
        app.closeAndJoin()
        assertTrue(cleaned)
        assertFalse(coordinator.activity.value.running)
        val count = triggers.size
        advanceTimeBy(24 * 60 * 60_000L)
        runCurrent()
        assertEquals(count, triggers.size)
    }

    @Test
    fun `manual attempt postpones next periodic run and disabled startup stays silent`() = runTest {
        val preferences = SyncPreferences(InMemoryPreferenceStore())
        preferences.startup.set(false)
        preferences.setInterval(15)
        var count = 0
        val coordinator = SyncCoordinator(
            SyncRunPort {
                count++
                SyncRunResult(SyncRunStatus.SUCCESS)
            },
        )
        val scheduler =
            DesktopSyncScheduler(coordinator, preferences, backgroundScope) { testScheduler.currentTime + 1 }
        scheduler.start()
        runCurrent()
        assertEquals(0, count)
        advanceTimeBy(10 * 60_000L)
        preferences.lastAttempt.set(testScheduler.currentTime + 1)
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertEquals(0, count)
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(1, count)
        scheduler.stop()
        scheduler.awaitStopped()
    }

    @Test
    fun `startup and periodic scheduling use the shared recovery entry`() = runTest {
        val preferences = SyncPreferences(InMemoryPreferenceStore())
        preferences.setInterval(15)
        val triggers = mutableListOf<SyncTrigger>()
        var resumes = 0
        val coordinator = SyncCoordinator(
            SyncRunPort {
                triggers += it
                SyncRunResult(SyncRunStatus.SUCCESS)
            },
        )
        val scheduler = DesktopSyncScheduler(
            coordinator,
            preferences,
            backgroundScope,
            clock = { testScheduler.currentTime + 1 },
            resumeIfNeeded = {
                resumes++
                true
            },
        )

        scheduler.start()
        runCurrent()
        assertEquals(1, resumes)
        assertTrue(triggers.isEmpty())

        advanceTimeBy(15 * 60_000L)
        runCurrent()
        assertEquals(2, resumes)
        assertTrue(triggers.isEmpty())
        scheduler.stop()
        scheduler.awaitStopped()
    }

    @Test
    fun `network failure gets a scheduled recovery attempt`() = runTest {
        val preferences = SyncPreferences(InMemoryPreferenceStore())
        preferences.startup.set(false)
        val triggers = mutableListOf<SyncTrigger>()
        var calls = 0
        val coordinator = SyncCoordinator(
            SyncRunPort {
                triggers += it
                if (++calls == 1) {
                    SyncRunResult(SyncRunStatus.FAILED, problem = SyncRunProblem.NETWORK)
                } else {
                    SyncRunResult(SyncRunStatus.SUCCESS)
                }
            },
        )
        val scheduler = DesktopSyncScheduler(
            coordinator,
            preferences,
            backgroundScope,
            recoveryDelayMillis = { (1_000L - testScheduler.currentTime).coerceAtLeast(0) },
            hasResumableRun = { calls == 1 },
            resumeIfNeeded = {
                if (calls == 1) {
                    coordinator.synchronize(SyncTrigger.RECOVERY)
                    true
                } else {
                    false
                }
            },
        )
        scheduler.start()
        runCurrent()
        coordinator.synchronize(SyncTrigger.MANUAL)
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf(SyncTrigger.MANUAL, SyncTrigger.RECOVERY), triggers)
        scheduler.stop()
        scheduler.awaitStopped()
    }
}
