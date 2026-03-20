package mihon.desktop.backup

import kotlinx.coroutines.test.runTest
import mihon.desktop.backup.models.Backup
import mihon.desktop.backup.models.BackupCategory
import mihon.desktop.backup.models.BackupChapter
import mihon.desktop.backup.models.BackupManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * RED tests for DesktopBackupRestorer.
 * These tests define the expected contract before implementation exists.
 */
class DesktopBackupRestorerTest {

    @TempDir
    lateinit var tempDir: File

    // ── Unit-level restore logic (no DB dependency) ────────────────────────────

    @Test
    fun `mergeCategories returns union of existing and backup categories`() {
        val existing = listOf("Action", "Comedy")
        val backupCats = listOf(
            BackupCategory(name = "Comedy", order = 0),
            BackupCategory(name = "Drama", order = 1),
        )
        val merged = DesktopBackupRestorer.mergeCategories(existing, backupCats)
        assertEquals(listOf("Action", "Comedy", "Drama"), merged.sorted())
    }

    @Test
    fun `mergeCategories with empty existing returns all backup categories`() {
        val backupCats = listOf(
            BackupCategory(name = "Sci-Fi", order = 0),
        )
        val merged = DesktopBackupRestorer.mergeCategories(emptyList(), backupCats)
        assertEquals(listOf("Sci-Fi"), merged)
    }

    @Test
    fun `mapCategoryNameToId resolves category id by name`() {
        // BackupManga stores category membership as the `order` value of each backup category.
        // Here order=0 corresponds to "Action" and order=1 corresponds to "Drama".
        val categoryMap = mapOf("Action" to 1L, "Comedy" to 2L, "Drama" to 3L)
        val backupCategoryOrder = listOf(
            BackupCategory(name = "Action", order = 0),
            BackupCategory(name = "Drama", order = 1),
        )
        // manga belongs to Action (order=0) and Drama (order=1)
        val backupCategoryIndices = listOf(0L, 1L)
        val ids = DesktopBackupRestorer.resolveBackupCategoryIds(
            backupCategoryIndices = backupCategoryIndices,
            backupCategories = backupCategoryOrder,
            categoryMap = categoryMap,
        )
        assertEquals(listOf(1L, 3L), ids)
    }

    @Test
    fun `RestoreResult accumulates errors`() {
        val result = DesktopBackupRestorer.RestoreResult()
        result.addError("manga_1", "source not found")
        result.addError("manga_2", "chapter conflict")
        assertEquals(2, result.errors.size)
        assertEquals("source not found", result.errors[0].second)
    }

    @Test
    fun `RestoreResult counts successes`() {
        val result = DesktopBackupRestorer.RestoreResult()
        result.incrementSuccess()
        result.incrementSuccess()
        assertEquals(2, result.successCount)
    }

    // ── File validation ────────────────────────────────────────────────────────

    @Test
    fun `readBackupFile returns null for a non-tachibk file`() {
        val badFile = File(tempDir, "test.zip")
        badFile.writeBytes(byteArrayOf(0x50, 0x4B)) // PK zip magic
        val result = DesktopBackupCreator.readBackupFile(badFile)
        // Should return null, not throw
        assertEquals(null, result)
    }

    @Test
    fun `readBackupFile returns null for empty file`() {
        val emptyFile = File(tempDir, "empty.tachibk")
        emptyFile.writeBytes(ByteArray(0))
        val result = DesktopBackupCreator.readBackupFile(emptyFile)
        assertEquals(null, result)
    }

    // ── Chapter merge logic ───────────────────────────────────────────────────

    @Test
    fun `mergeChapters preserves read state from backup when DB chapter exists`() {
        val backupChapters = listOf(
            BackupChapter(url = "/ch/1", name = "Ch 1", read = true, lastPageRead = 5),
            BackupChapter(url = "/ch/2", name = "Ch 2", read = false, lastPageRead = 0),
        )
        // Simulate existing DB chapters (unread)
        val existingReadStates = mapOf("/ch/1" to false, "/ch/2" to false)
        val merged = DesktopBackupRestorer.mergeChapterReadStates(backupChapters, existingReadStates)
        // Backup read=true should win (backup data is authoritative on read state)
        assertEquals(true, merged["/ch/1"])
        assertEquals(false, merged["/ch/2"])
    }
}
