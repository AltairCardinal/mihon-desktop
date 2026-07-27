package mihon.desktop.backup

import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.settings.DesktopAppPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AutoBackupSchedulerTest {

    @Test
    fun `default auto-backup interval is OFF`() {
        val prefs = TestAutoBackupPrefs()
        assertEquals(AutoBackupInterval.OFF, prefs.interval)
    }

    @Test
    fun `max backups default is 2`() {
        val prefs = TestAutoBackupPrefs()
        assertEquals(2, prefs.maxBackups)
    }

    @Test
    fun `pruneOldBackups keeps only maxBackups files`(@TempDir dir: File) {
        // Create 5 fake backup files with different timestamps
        (1..5).forEach { i ->
            val f = File(dir, "mihon_desktop_auto_$i.tachibk")
            f.writeText("backup$i")
            f.setLastModified(i * 1000L)
        }

        pruneOldBackups(dir, maxBackups = 2)

        val remaining = dir.listFiles()?.filter { it.extension == "tachibk" } ?: emptyList()
        assertEquals(2, remaining.size)
        // Most recent 2 should remain (timestamps 4000, 5000)
        assertTrue(remaining.any { it.name == "mihon_desktop_auto_5.tachibk" })
        assertTrue(remaining.any { it.name == "mihon_desktop_auto_4.tachibk" })
    }

    @Test
    fun `pruneOldBackups does nothing when fewer backups than limit`(@TempDir dir: File) {
        File(dir, "mihon_desktop_auto_1.tachibk").writeText("backup1")

        pruneOldBackups(dir, maxBackups = 3)

        val remaining = dir.listFiles()?.filter { it.extension == "tachibk" } ?: emptyList()
        assertEquals(1, remaining.size)
    }

    @Test
    fun `overdue persisted schedule runs on process restart and records success`() = runTest {
        val now = 100_000_000L
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore()).apply {
            autoBackupInterval.set(AutoBackupInterval.EVERY_6H.name)
            autoBackupLastSuccessAt.set(now - AutoBackupInterval.EVERY_6H.toMillis() - 1)
        }
        var writes = 0
        val scheduler = scheduler(preferences, now) { writes++ }

        val result = scheduler.runDueBackup()

        assertInstanceOf(AutoBackupRunResult.Success::class.java, result)
        assertEquals(1, writes)
        assertEquals(now, preferences.autoBackupLastSuccessAt.get())
        assertEquals("", preferences.autoBackupLastError.get())
    }

    @Test
    fun `restart before persisted deadline does not duplicate automatic backup`() = runTest {
        val now = 100_000_000L
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore()).apply {
            autoBackupInterval.set(AutoBackupInterval.EVERY_6H.name)
            autoBackupLastSuccessAt.set(now - 1_000)
        }
        var writes = 0
        val scheduler = scheduler(preferences, now) { writes++ }

        val result = scheduler.runDueBackup()

        assertInstanceOf(AutoBackupRunResult.NotDue::class.java, result)
        assertEquals(0, writes)
        assertEquals(now - 1_000, preferences.autoBackupLastSuccessAt.get())
    }

    @Test
    fun `automatic backup failure stays visible and remains due for retry`() = runTest {
        val now = 100_000_000L
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore()).apply {
            autoBackupInterval.set(AutoBackupInterval.EVERY_6H.name)
        }
        val scheduler = scheduler(preferences, now) { error("disk full") }

        val result = scheduler.runDueBackup()

        assertInstanceOf(AutoBackupRunResult.Failure::class.java, result)
        assertEquals(0, preferences.autoBackupLastSuccessAt.get())
        assertEquals("disk full", preferences.autoBackupLastError.get())
    }

    @Test
    fun `successful deadline uses completion time rather than start time`() = runTest {
        var now = 100_000_000L
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore()).apply {
            autoBackupInterval.set(AutoBackupInterval.EVERY_6H.name)
        }
        val scheduler = scheduler(
            preferences = preferences,
            now = { now },
            backupAction = { now += 5_000 },
        )

        scheduler.runDueBackup()

        assertEquals(now, preferences.autoBackupLastSuccessAt.get())
    }

    @Test
    fun `start catches up immediately and stop cancels the waiting lifecycle`() = runTest {
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore()).apply {
            autoBackupInterval.set(AutoBackupInterval.EVERY_6H.name)
        }
        var writes = 0
        val scheduler = scheduler(
            preferences = preferences,
            now = { 100_000_000L },
            backupAction = { writes++ },
            waitForNextCheck = { awaitCancellation() },
            scope = this,
        )

        scheduler.start()
        runCurrent()
        assertEquals(1, writes)
        assertTrue(scheduler.isRunning)

        scheduler.stop()
        runCurrent()
        assertFalse(scheduler.isRunning)
    }

    @Test
    fun `periodic lifecycle retries a failed due backup`() = runTest {
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore()).apply {
            autoBackupInterval.set(AutoBackupInterval.EVERY_6H.name)
        }
        var attempts = 0
        var waits = 0
        val scheduler = scheduler(
            preferences = preferences,
            now = { 100_000_000L },
            backupAction = {
                attempts++
                if (attempts == 1) error("first failure")
            },
            waitForNextCheck = {
                if (waits++ > 0) awaitCancellation()
            },
            scope = this,
        )

        scheduler.start()
        runCurrent()

        assertEquals(2, attempts)
        assertEquals("", preferences.autoBackupLastError.get())
        scheduler.stop()
    }

    @Test
    fun `failure without a message persists stable visible feedback`() = runTest {
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore()).apply {
            autoBackupInterval.set(AutoBackupInterval.EVERY_6H.name)
        }
        val scheduler = scheduler(
            preferences = preferences,
            now = { 100_000_000L },
            backupAction = { throw object : Exception() {} },
        )

        scheduler.runDueBackup()

        assertEquals("Unknown error", preferences.autoBackupLastError.get())
    }

    private fun scheduler(
        preferences: DesktopAppPreferences,
        now: Long,
        backupAction: suspend () -> Unit,
    ) = scheduler(preferences, { now }, backupAction)

    private fun scheduler(
        preferences: DesktopAppPreferences,
        now: () -> Long,
        backupAction: suspend () -> Unit,
        waitForNextCheck: suspend (Long) -> Unit = { awaitCancellation() },
        scope: CoroutineScope? = null,
    ) = AutoBackupScheduler(
        appPreferences = preferences,
        mangaRepository = mockk<MangaRepository>(relaxed = true),
        chapterRepository = mockk<ChapterRepository>(relaxed = true),
        categoryRepository = mockk<CategoryRepository>(relaxed = true),
        historyRepository = mockk<HistoryRepository>(relaxed = true),
        nowMillis = now,
        backupAction = backupAction,
        waitForNextCheck = waitForNextCheck,
        scope = scope,
    )
}

/** Simple test-only prefs holder. */
data class TestAutoBackupPrefs(
    val interval: AutoBackupInterval = AutoBackupInterval.OFF,
    val maxBackups: Int = 2,
)
