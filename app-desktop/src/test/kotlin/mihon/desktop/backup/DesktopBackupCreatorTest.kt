package mihon.desktop.backup

import kotlinx.coroutines.test.runTest
import mihon.desktop.backup.models.Backup
import mihon.desktop.backup.models.BackupCategory
import mihon.desktop.backup.models.BackupChapter
import mihon.desktop.backup.models.BackupManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * RED tests for DesktopBackupCreator.
 * These tests define the expected contract before implementation exists.
 */
class DesktopBackupCreatorTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `encodeToBytes encodes Backup to gzip-compressed protobuf bytes`() {
        val backup = Backup(backupManga = emptyList(), backupCategories = emptyList())
        val bytes = DesktopBackupCreator.encodeToBytes(backup)
        assertTrue(bytes.isNotEmpty(), "Encoded bytes must not be empty")
        // Gzip magic bytes: 0x1F 0x8B
        assertEquals(0x1F.toByte(), bytes[0])
        assertEquals(0x8B.toByte(), bytes[1])
    }

    @Test
    fun `decodeFromBytes round-trips a Backup object`() {
        val original = Backup(
            backupManga = listOf(
                BackupManga(
                    source = 1L,
                    url = "/manga/one-piece",
                    title = "One Piece",
                    chapters = listOf(
                        BackupChapter(url = "/chapter/1", name = "Chapter 1", read = true),
                    ),
                ),
            ),
            backupCategories = listOf(BackupCategory(name = "Favorites", order = 0)),
        )

        val bytes = DesktopBackupCreator.encodeToBytes(original)
        val decoded = DesktopBackupCreator.decodeFromBytes(bytes)

        assertEquals(1, decoded.backupManga.size)
        assertEquals("One Piece", decoded.backupManga[0].title)
        assertEquals("/manga/one-piece", decoded.backupManga[0].url)
        assertEquals(1, decoded.backupManga[0].chapters.size)
        assertTrue(decoded.backupManga[0].chapters[0].read)
        assertEquals(1, decoded.backupCategories.size)
        assertEquals("Favorites", decoded.backupCategories[0].name)
    }

    @Test
    fun `writeBackupFile creates a file with tachibk extension`() = runTest {
        val backup = Backup(backupManga = emptyList())
        val file = DesktopBackupCreator.writeBackupFile(backup, tempDir)
        assertTrue(file.exists())
        assertTrue(file.name.endsWith(".tachibk"), "Backup file must end with .tachibk, was ${file.name}")
        assertTrue(file.length() > 0)
    }

    @Test
    fun `writeBackupFile filename contains date`() = runTest {
        val backup = Backup(backupManga = emptyList())
        val file = DesktopBackupCreator.writeBackupFile(backup, tempDir)
        // Filename pattern: mihon_YYYY-MM-DD_HH-mm.tachibk
        assertTrue(
            file.name.matches(Regex("""mihon_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}\.tachibk""")),
            "Filename '${file.name}' does not match expected pattern",
        )
    }

    @Test
    fun `readBackupFile decodes a file written by writeBackupFile`() = runTest {
        val original = Backup(
            backupManga = listOf(BackupManga(source = 42L, url = "/m/test", title = "TestManga")),
        )
        val file = DesktopBackupCreator.writeBackupFile(original, tempDir)
        val decoded = DesktopBackupCreator.readBackupFile(file)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.backupManga.size)
        assertEquals("TestManga", decoded.backupManga[0].title)
        assertEquals(42L, decoded.backupManga[0].source)
    }
}
