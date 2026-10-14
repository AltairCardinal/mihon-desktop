package tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorArchiveSection
import eu.kanade.tachiyomi.data.backup.models.BackupAuthorWatch
import eu.kanade.tachiyomi.data.backup.models.BackupCreatorIdentity
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
            "f8ddfe8bea24ff9d428ce06058beef8194144542c8774b6ab25493528acd89a8",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )
        val decoded = BackupCodec.decode(Backup.serializer(), bytes)
        val roundTrip = BackupCodec.decode(Backup.serializer(), BackupCodec.encode(Backup.serializer(), decoded))

        assertEquals(decoded, roundTrip)
        assertEquals(1, decoded.backupManga.size)
        assertArrayEquals(tachiyomi.data.JsonObjectEmptyBytes, decoded.backupManga.single().memo)
        decoded.backupManga.single().chapters.forEach {
            assertArrayEquals(tachiyomi.data.JsonObjectEmptyBytes, it.memo)
        }
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
        assertEquals(listOf(1L), manga.categories)
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

    @Test
    fun `author archive only backup is valid and field 107 round trips`() {
        val original = Backup(
            backupManga = emptyList(),
            backupAuthorArchive = BackupAuthorArchiveSection(
                creators = listOf(
                    BackupCreatorIdentity(
                        portableKey = "creator-one",
                        displayName = "ONE",
                        normalizedName = "one",
                    ),
                ),
                watches = listOf(
                    BackupAuthorWatch(
                        creatorPortableKey = "creator-one",
                        enabled = true,
                        periodMillis = 86_400_000L,
                        sourceIds = listOf(10L),
                        readingLanguageTags = listOf("ja"),
                        includeProbable = true,
                        notifyProbable = true,
                    ),
                ),
            ),
        )

        val decoded = BackupCodec.decode(
            Backup.serializer(),
            BackupCodec.encode(Backup.serializer(), original),
        )

        assertEquals(original, decoded)
    }

    @Test
    fun `legacy fixture decodes without author archive section`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/backup/android-full.tachibk")).readBytes()

        val decoded = BackupCodec.decode(Backup.serializer(), bytes)

        assertEquals(null, decoded.backupAuthorArchive)
    }

    @Test
    fun `Mihon v0_21_1 fixture generator consumes fixed authority ref`() {
        val authorityRef = requireNotNull(javaClass.getResourceAsStream("/backup/mihon-v0.21.1-ref"))
            .bufferedReader()
            .use { it.readText() }

        assertEquals("e9670d8a12ff4526d9ca09daa73280b8c42b9bb0", authorityRef.trim())

        val generator = repositoryFile("scripts/generate-v0.21.1-backup-fixture.ps1").readText()
        assertTrue(
            generator.contains(
                "\$authorityRefResource = \"data/src/commonTest/resources/backup/mihon-v0.21.1-ref\"",
            ),
        )
        assertTrue(generator.contains("\$commit = (Get-Content -Raw \$authorityRefPath).Trim()"))
    }

    @Test
    fun `original Mihon v0_21_1 full fixture decodes successfully and maps stores to repos`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/backup/mihon-v0.21.1-full.tachibk")).readBytes()
        assertEquals(
            "c83ab027d4b23eb1f1173e0e6ae941e141c3994d7b604f45125eb3fa296ea705",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )

        val decoded = BackupCodec.decode(Backup.serializer(), bytes)

        assertEquals(1, decoded.backupManga.size)
        val manga = decoded.backupManga.single()
        assertEquals("V0.21.1 Canonical manga", manga.title)
        assertEquals(1, decoded.backupCategories.size)
        assertEquals(1, decoded.backupSources.size)
        assertEquals(1, decoded.backupPreferences.size)
        assertEquals(1, decoded.backupSourcePreferences.size)

        assertEquals(2, decoded.backupExtensionRepo.size)
        val legacyStore = decoded.backupExtensionRepo[0]
        assertEquals("https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json", legacyStore.baseUrl)
        assertEquals("Keiyoushi", legacyStore.name)
        assertEquals("Keiyoushi", legacyStore.shortName)
        assertEquals("https://keiyoushi.github.io", legacyStore.website)
        assertEquals("12:34:56:78:9a:bc:de:f0", legacyStore.signingKeyFingerprint)
        assertEquals("https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json", legacyStore.indexUrl)
        assertEquals(null, legacyStore.extensionListUrl)
        assertEquals("https://discord.gg/keiyoushi", legacyStore.contactDiscord)

        val modernStore = decoded.backupExtensionRepo[1]
        assertEquals("https://example.com/store/index.json", modernStore.baseUrl)
        assertEquals("Modern Store", modernStore.name)
        assertEquals("Modern", modernStore.shortName)
        assertEquals("https://example.com", modernStore.website)
        assertEquals("fe:dc:ba:98:76:54:32:10", modernStore.signingKeyFingerprint)
        assertEquals("https://example.com/store/index.json", modernStore.indexUrl)
        assertEquals("https://example.com/store/extensions.json", modernStore.extensionListUrl)
        assertEquals("https://discord.gg/example", modernStore.contactDiscord)
    }

    @Test
    fun `legacy fixture with missing fields 6 7 8 decodes with nulls`() {
        val original = Backup(
            backupManga = emptyList(),
            backupExtensionRepo = listOf(
                eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos(
                    baseUrl = "https://legacy-repo.example",
                    name = "Legacy Repo",
                    shortName = "LR",
                    website = "https://legacy-repo.example/site",
                    signingKeyFingerprint = "11:22:33:44",
                    indexUrl = null,
                    extensionListUrl = null,
                    contactDiscord = null,
                ),
            ),
        )

        val encoded = BackupCodec.encode(Backup.serializer(), original)
        val decoded = BackupCodec.decode(Backup.serializer(), encoded)

        val repo = decoded.backupExtensionRepo.single()
        assertEquals("https://legacy-repo.example", repo.baseUrl)
        assertEquals(null, repo.indexUrl)
        assertEquals(null, repo.extensionListUrl)
        assertEquals(null, repo.contactDiscord)
    }

    @Test
    fun `legacy fixture with present fields 6 7 8 decodes with all fields intact`() {
        val original = Backup(
            backupManga = emptyList(),
            backupExtensionRepo = listOf(
                eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos(
                    baseUrl = "https://full-repo.example",
                    name = "Full Repo",
                    shortName = "FR",
                    website = "https://full-repo.example/site",
                    signingKeyFingerprint = "55:66:77:88",
                    indexUrl = "https://full-repo.example/index.min.json",
                    extensionListUrl = "https://full-repo.example/list.json",
                    contactDiscord = "https://discord.gg/full",
                ),
            ),
        )

        val encoded = BackupCodec.encode(Backup.serializer(), original)
        val decoded = BackupCodec.decode(Backup.serializer(), encoded)

        val repo = decoded.backupExtensionRepo.single()
        assertEquals("https://full-repo.example", repo.baseUrl)
        assertEquals("https://full-repo.example/index.min.json", repo.indexUrl)
        assertEquals("https://full-repo.example/list.json", repo.extensionListUrl)
        assertEquals("https://discord.gg/full", repo.contactDiscord)
    }

    @Test
    fun `conflicting field 7 wire tags in extension entry are rejected`() {
        // Construct a backup payload containing an extension entry with both tag 0x38 (varint) and tag 0x3A (delimited)
        val validEntry = eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos(
            baseUrl = "https://conflict.example",
            name = "Conflict",
            shortName = "C",
            website = "https://conflict.example",
            signingKeyFingerprint = "11:22",
        )
        val baseBackup = Backup(backupManga = emptyList(), backupExtensionRepo = listOf(validEntry))
        val normalEncoded = BackupCodec.encodePlain(Backup.serializer(), baseBackup)

        // Append a conflicting varint field 7 (tag 0x38, value 1) inside the entry
        // Tag 106 is 0xD2, 0x06 in varint. Let's create an invalid entry manually.
        val conflictingEntryBytes = okio.Buffer().apply {
            // Field 1: string "https://conflict.example"
            writeByte(0x0A)
            writeByte(24)
            writeUtf8("https://conflict.example")
            // Field 2: string "Conflict"
            writeByte(0x12)
            writeByte(8)
            writeUtf8("Conflict")
            // Field 4: string "https://conflict.example"
            writeByte(0x22)
            writeByte(24)
            writeUtf8("https://conflict.example")
            // Field 5: string "11:22"
            writeByte(0x2A)
            writeByte(5)
            writeUtf8("11:22")
            // Conflicting tag 0x38 (Boolean varint 1)
            writeByte(0x38)
            writeByte(1)
            // Conflicting tag 0x3A (String length 4 "list")
            writeByte(0x3A)
            writeByte(4)
            writeUtf8("list")
        }.readByteArray()

        val rootWithConflictingEntry = okio.Buffer().apply {
            writeByte(0xD2)
            writeByte(0x06) // Tag 106
            writeByte(conflictingEntryBytes.size)
            write(conflictingEntryBytes)
        }.readByteArray()

        assertThrows(InvalidBackupException::class.java) {
            BackupCodec.decode(Backup.serializer(), rootWithConflictingEntry)
        }
    }

    @Test
    fun `truncated extension entry wire length is rejected`() {
        // Tag 106 claiming length 50, but providing only 10 bytes
        val truncated = byteArrayOf(
            0xD2.toByte(),
            0x06.toByte(), // tag 106
            50.toByte(), // claimed length
            0x0A,
            0x02,
            0x61,
            0x62, // 4 bytes of partial data
        )

        assertThrows(InvalidBackupException::class.java) {
            BackupCodec.decode(Backup.serializer(), truncated)
        }
    }
}
