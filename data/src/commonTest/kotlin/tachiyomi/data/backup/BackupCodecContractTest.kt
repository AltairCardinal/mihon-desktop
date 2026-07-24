package tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

class BackupCodecContractTest {
    @Test
    fun `Android fixture generator consumes fixed original Mihon ref`() {
        val authorityRef = requireNotNull(javaClass.getResourceAsStream("/backup/android-full.original-mihon-ref"))
            .bufferedReader()
            .use { it.readText() }

        assertEquals("6fbf6dfca203d99d6dd32137f2df97ced40c81b8", authorityRef.trim())

        val generator = repositoryFile("scripts/generate-android-backup-fixture.ps1").readText()
        assertTrue(
            generator.contains(
                "\$authorityRefResource = \"data/src/commonTest/resources/backup/android-full.original-mihon-ref\"",
            ),
        )
        assertTrue(generator.contains("\$commit = (Get-Content -Raw \$authorityRefPath).Trim()"))
    }

    private fun repositoryFile(relativePath: String): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?: error("Repository file not found: $relativePath")

    @Test
    @Suppress("DEPRECATION")
    fun `fixed-main Android full fixture decodes and reencodes with canonical schema`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/backup/android-full.tachibk")).readBytes()
        assertEquals(
            "43fa65a3469932f4da2794e8bdf69c7bef7d65d4e77fe894e1b1798ed1efad8d",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )
        val decoded = BackupCodec.decode(Backup.serializer(), bytes)
        val roundTrip = BackupCodec.decode(Backup.serializer(), BackupCodec.encode(Backup.serializer(), decoded))

        assertEquals(decoded, roundTrip)
        assertEquals(1, decoded.backupManga.size)
        assertEquals(1, decoded.backupManga.single().tracking.size)
        assertEquals(1, decoded.backupPreferences.size)
        assertEquals(1, decoded.backupSourcePreferences.size)
        assertEquals(1, decoded.backupExtensionRepo.size)
        val manga = decoded.backupManga.single()
        assertEquals(101, manga.source)
        assertEquals("/manga", manga.url)
        assertEquals("Canonical manga", manga.title)
        assertEquals("Artist", manga.artist)
        assertEquals("Author", manga.author)
        assertEquals("Description", manga.description)
        assertEquals(listOf("Action", "Drama"), manga.genre)
        assertEquals(1, manga.status)
        assertEquals("https://example/cover.jpg", manga.thumbnailUrl)
        assertEquals(11, manga.dateAdded)
        assertEquals(13, manga.viewer)
        assertEquals(17, manga.viewer_flags)
        assertTrue(manga.favorite)
        assertEquals(21, manga.chapterFlags)
        assertEquals(listOf(7L), manga.categories)
        assertEquals(listOf("Excluded"), manga.excludedScanlators)
        assertEquals(4, manga.version)
        assertEquals("Notes", manga.notes)
        assertTrue(manga.initialized)

        val chapter = manga.chapters.single()
        assertEquals("/chapter", chapter.url)
        assertEquals("Chapter 1", chapter.name)
        assertEquals("Scanlator", chapter.scanlator)
        assertTrue(chapter.read)
        assertTrue(chapter.bookmark)
        assertEquals(7, chapter.lastPageRead)
        assertEquals(12, chapter.dateFetch)
        assertEquals(13, chapter.dateUpload)
        assertEquals(1.5f, chapter.chapterNumber)
        assertEquals(2, chapter.sourceOrder)
        assertEquals(14, chapter.lastModifiedAt)
        assertEquals(3, chapter.version)

        val history = manga.history.single()
        assertEquals("/chapter", history.url)
        assertEquals(18, history.lastRead)
        assertEquals(19, history.readDuration)

        val tracking = manga.tracking.single()
        assertEquals(9, tracking.syncId)
        assertEquals(10, tracking.libraryId)
        assertEquals(11, tracking.mediaIdInt)
        assertEquals("https://tracking", tracking.trackingUrl)
        assertEquals("Tracked title", tracking.title)
        assertEquals(2.5f, tracking.lastChapterRead)
        assertEquals(20, tracking.totalChapters)
        assertEquals(8.5f, tracking.score)
        assertEquals(1, tracking.status)
        assertEquals(22, tracking.startedReadingDate)
        assertEquals(23, tracking.finishedReadingDate)
        assertTrue(tracking.private)
        assertEquals(15, tracking.mediaId)

        val category = decoded.backupCategories.single()
        assertEquals("Category", category.name)
        assertEquals(1, category.order)
        assertEquals(7, category.id)
        assertEquals(2, category.flags)
        val source = decoded.backupSources.single()
        assertEquals("Source", source.name)
        assertEquals(101, source.sourceId)
        val preference = decoded.backupPreferences.single()
        assertEquals("theme", preference.key)
        assertEquals(StringPreferenceValue("dark"), preference.value)
        val sourcePreferences = decoded.backupSourcePreferences.single()
        assertEquals("101", sourcePreferences.sourceKey)
        assertEquals("quality", sourcePreferences.prefs.single().key)
        assertEquals(IntPreferenceValue(3), sourcePreferences.prefs.single().value)
        val extensionRepo = decoded.backupExtensionRepo.single()
        assertEquals("https://repo", extensionRepo.baseUrl)
        assertEquals("Repo", extensionRepo.name)
        assertEquals("R", extensionRepo.shortName)
        assertEquals("https://repo/site", extensionRepo.website)
        assertEquals("fingerprint", extensionRepo.signingKeyFingerprint)
    }

    @Test
    fun `json and corrupt payloads are rejected with a stable reason`() {
        assertThrows(InvalidBackupException::class.java) {
            BackupCodec.decode(Backup.serializer(), "{\"version\":1}".encodeToByteArray())
        }
        assertThrows(InvalidBackupException::class.java) {
            BackupCodec.decode(Backup.serializer(), byteArrayOf(0x1f, 0x8b.toByte(), 1))
        }
    }

    @Test
    fun `random bare protobuf and semantically empty backup are rejected`() {
        assertThrows(InvalidBackupException::class.java) {
            BackupCodec.decode(Backup.serializer(), byteArrayOf(0x08, 0x01, 0x10, 0x02))
        }
        assertThrows(InvalidBackupException::class.java) {
            BackupCodec.decode(
                Backup.serializer(),
                BackupCodec.encodePlain(Backup.serializer(), Backup(emptyList())),
            )
        }
    }
}
