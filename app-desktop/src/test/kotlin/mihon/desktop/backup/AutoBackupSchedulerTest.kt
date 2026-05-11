package mihon.desktop.backup

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

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
}

/** Simple test-only prefs holder. */
data class TestAutoBackupPrefs(
    val interval: AutoBackupInterval = AutoBackupInterval.OFF,
    val maxBackups: Int = 2,
)
