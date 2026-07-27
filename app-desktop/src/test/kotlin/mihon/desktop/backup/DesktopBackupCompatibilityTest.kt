package mihon.desktop.backup

import mihon.desktop.backup.models.Backup
import mihon.desktop.backup.models.BackupCategory
import mihon.desktop.backup.models.BackupChapter
import mihon.desktop.backup.models.BackupExtensionRepos
import mihon.desktop.backup.models.BackupHistory
import mihon.desktop.backup.models.BackupManga
import mihon.desktop.backup.models.BackupPreference
import mihon.desktop.backup.models.BackupSource
import mihon.desktop.backup.models.BackupSourcePreferences
import mihon.desktop.backup.models.BackupTracking
import mihon.desktop.backup.models.StringPreferenceValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import tachiyomi.data.backup.BackupCodec

class DesktopBackupCompatibilityTest {
    @Test
    @Suppress("DEPRECATION")
    fun `fixed-main Android fixture crosses Desktop creator boundary and reencodes equivalently`() {
        val authorityRef = Files.readString(
            repositoryRoot().resolve("data/src/commonTest/resources/backup/android-full.original-mihon-ref"),
        ).trim()
        assertEquals("6fbf6dfca203d99d6dd32137f2df97ced40c81b8", authorityRef)

        val bytes = Files.readAllBytes(
            repositoryRoot().resolve("data/src/commonTest/resources/backup/android-full.tachibk"),
        )
        assertEquals(
            "f8ddfe8bea24ff9d428ce06058beef8194144542c8774b6ab25493528acd89a8",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )

        val decoded = DesktopBackupCreator.decodeFromBytes(bytes)
        val manga = decoded.backupManga.single()
        assertEquals(101L, manga.source)
        assertEquals("/manga", manga.url)
        assertEquals("Canonical manga", manga.title)
        assertEquals(13, manga.viewer)
        assertEquals(17, manga.viewer_flags)
        assertEquals(1, manga.chapters.size)
        assertEquals(1, manga.history.size)
        assertEquals(1, manga.tracking.size)
        assertEquals(1, decoded.backupCategories.size)
        assertEquals(1, decoded.backupSources.size)
        assertEquals(1, decoded.backupPreferences.size)
        assertEquals(1, decoded.backupSourcePreferences.size)
        assertEquals(1, decoded.backupExtensionRepo.size)

        val reencoded = DesktopBackupCreator.encodeToBytes(decoded)
        assertEquals(decoded, DesktopBackupCreator.decodeFromBytes(reencoded))
    }

    @Test
    fun `canonical writer preserves every Android backup section`() {
        val original = Backup(
            backupManga = listOf(BackupManga(1, "/m", "M", chapters = listOf(BackupChapter("/c", "C")), history = listOf(BackupHistory("/c", 2)), tracking = listOf(BackupTracking(3, 4)))),
            backupCategories = listOf(BackupCategory("Cat")),
            backupSources = listOf(BackupSource("Source", 1)),
            backupPreferences = listOf(BackupPreference("theme", StringPreferenceValue("dark"))),
            backupSourcePreferences = listOf(BackupSourcePreferences("1", listOf(BackupPreference("lang", StringPreferenceValue("en"))))),
            backupExtensionRepo = listOf(BackupExtensionRepos("https://repo", "Repo", website = "https://site", signingKeyFingerprint = "abc")),
        )

        val desktopBytes = DesktopBackupCreator.encodeToBytes(original)
        assertEquals(original, BackupCodec.decode(Backup.serializer(), desktopBytes))

        val sharedBytes = BackupCodec.encode(Backup.serializer(), original)
        assertEquals(original, DesktopBackupCreator.decodeFromBytes(sharedBytes))
    }

    @Test
    @Suppress("DEPRECATION")
    fun `first Desktop protobuf writer fixture restores every historical field`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/backup/desktop-first-writer.tachibk")).readBytes()
        assertEquals(
            "45949d2fd91f443cab4bbf2bffa6fe37e039a3ce8e7eaed032f32fa935e87d2d",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )

        val decoded = DesktopBackupCreator.decodeFromBytes(bytes)
        val manga = decoded.backupManga.single()
        assertEquals(101L, manga.source)
        assertEquals("/desktop-manga", manga.url)
        assertEquals("Historical Desktop manga", manga.title)
        assertEquals("Desktop Artist", manga.artist)
        assertEquals("Desktop Author", manga.author)
        assertEquals("Desktop Description", manga.description)
        assertEquals(listOf("Action", "History"), manga.genre)
        assertEquals(2, manga.status)
        assertEquals("https://desktop/cover.jpg", manga.thumbnailUrl)
        assertEquals(11L, manga.dateAdded)
        assertEquals(13, manga.viewer)
        assertTrue(manga.favorite)
        assertEquals(21, manga.chapterFlags)
        assertEquals(listOf(7L), manga.categories)
        assertEquals(14L, manga.lastModifiedAt)
        assertEquals("Desktop notes", manga.notes)
        assertTrue(manga.initialized)

        val chapter = manga.chapters.single()
        assertEquals(BackupChapter("/desktop-chapter", "Desktop chapter", "Desktop Scanlator", true, true, 7, 12, 13, 1.5f, 2, 14, 3), chapter)
        assertEquals(BackupHistory("/desktop-chapter", 18, 19), manga.history.single())
        assertEquals(
            BackupTracking(9, 10, 0, "https://desktop/tracking", "Desktop tracked title", 2.5f, 20, 8.5f, 1, 22, 23, mediaId = 15),
            manga.tracking.single(),
        )
        assertEquals(BackupCategory("Desktop Category", 1, 7, 2), decoded.backupCategories.single())
        assertEquals(BackupSource("Desktop Source", 101), decoded.backupSources.single())
    }

    @Test
    fun `unproven Desktop JSON payload is rejected`() {
        val file = kotlin.io.path.createTempFile(suffix = ".tachibk").toFile()
        file.writeText("""{"backupManga":[{"source":1,"url":"/json"}]}""")
        assertNull(DesktopBackupCreator.readBackupFile(file))
    }

    private fun repositoryRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isDirectory(it.resolve("app-desktop")) && Files.isDirectory(it.resolve("data")) }
}
