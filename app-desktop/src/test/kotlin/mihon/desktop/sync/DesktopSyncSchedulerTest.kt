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
}
