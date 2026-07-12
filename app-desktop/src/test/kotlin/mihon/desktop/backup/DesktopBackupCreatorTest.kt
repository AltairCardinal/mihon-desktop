package mihon.desktop.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import mihon.desktop.domain.fakes.FakeCategoryRepository
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeHistoryRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import mihon.desktop.backup.models.Backup
import mihon.desktop.backup.models.BackupCategory
import mihon.desktop.backup.models.BackupChapter
import mihon.desktop.backup.models.BackupManga
import mihon.desktop.backup.models.BooleanPreferenceValue
import mihon.desktop.backup.models.StringPreferenceValue
import mihon.desktop.domain.fakes.FakeExtensionRepoRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.core.common.preference.InMemoryPreferenceStore
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
        // Filename pattern: mihon_YYYY-MM-DD_HH-mm-ss-SSS.tachibk
        assertTrue(
            file.name.matches(Regex("""mihon_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}-\d{3}(_\d+)?\.tachibk""")),
            "Filename '${file.name}' does not match expected pattern",
        )
    }

    @Test
    fun `writeBackupFile creates unique files for rapid consecutive exports`() = runTest {
        val backup = Backup(backupManga = emptyList())

        val first = DesktopBackupCreator.writeBackupFile(backup, tempDir)
        val second = DesktopBackupCreator.writeBackupFile(backup, tempDir)

        assertTrue(first.exists())
        assertTrue(second.exists())
        assertTrue(first.name != second.name, "Rapid backup exports must not overwrite the previous file")
        assertEquals(2, tempDir.listFiles { file -> file.extension == "tachibk" }?.size)
    }

    @Test
    fun `writeBackupFile creates unique files for concurrent exports`() = runTest {
        val backup = Backup(backupManga = emptyList())

        val files = (1..10)
            .map {
                async(Dispatchers.Default) {
                    DesktopBackupCreator.writeBackupFile(backup, tempDir)
                }
            }
            .awaitAll()

        assertEquals(10, files.map { it.name }.toSet().size)
        assertTrue(files.all { it.exists() })
        assertEquals(10, tempDir.listFiles { file -> file.extension == "tachibk" }?.size)
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

    @Test
    fun `createFromDatabase preserves manga viewer flags in backup viewer field`() = runTest {
        val mangaRepository = FakeMangaRepository()
        val viewerFlags = 0x22L
        mangaRepository.seed(
            Manga.create().copy(
                id = 1L,
                source = 42L,
                url = "/m/test",
                title = "Test Manga",
                favorite = true,
                viewerFlags = viewerFlags,
            ),
        )

        val backup = DesktopBackupCreator.createFromDatabase(
            mangaRepository = mangaRepository,
            chapterRepository = FakeChapterRepository(),
            categoryRepository = FakeCategoryRepository(),
            historyRepository = FakeHistoryRepository(),
            trackRepository = trackRepositoryOf(),
            preferenceStore = preferenceStoreOf(),
            sourcePreferenceStore = { preferenceStoreOf() },
            extensionRepoRepository = FakeExtensionRepoRepository(),
        )

        assertEquals(viewerFlags.toInt(), backup.backupManga.single().viewer)
    }

    @Test
    fun `createFromDatabase preserves manga update metadata fields`() = runTest {
        val mangaRepository = FakeMangaRepository()
        mangaRepository.seed(
            Manga.create().copy(
                id = 1L,
                source = 42L,
                url = "/m/test",
                title = "Test Manga",
                favorite = true,
                updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE,
                favoriteModifiedAt = 123_456L,
                version = 7L,
            ),
        )

        val backup = DesktopBackupCreator.createFromDatabase(
            mangaRepository = mangaRepository,
            chapterRepository = FakeChapterRepository(),
            categoryRepository = FakeCategoryRepository(),
            historyRepository = FakeHistoryRepository(),
            trackRepository = trackRepositoryOf(),
            preferenceStore = preferenceStoreOf(),
            sourcePreferenceStore = { preferenceStoreOf() },
            extensionRepoRepository = FakeExtensionRepoRepository(),
        )

        val backupManga = backup.backupManga.single()
        assertEquals(UpdateStrategy.ONLY_FETCH_ONCE, backupManga.updateStrategy)
        assertEquals(123_456L, backupManga.favoriteModifiedAt)
        assertEquals(7L, backupManga.version)
    }

    @Test
    fun `createFromDatabase preserves excluded scanlators`() = runTest {
        val mangaRepository = FakeMangaRepository()
        mangaRepository.seed(
            Manga.create().copy(
                id = 1L,
                source = 42L,
                url = "/m/test",
                title = "Test Manga",
                favorite = true,
            ),
        )

        val backup = DesktopBackupCreator.createFromDatabase(
            mangaRepository = mangaRepository,
            chapterRepository = FakeChapterRepository(),
            categoryRepository = FakeCategoryRepository(),
            historyRepository = FakeHistoryRepository(),
            excludedScanlatorsForManga = { mangaId ->
                if (mangaId == 1L) listOf("Group A", "Group B") else emptyList()
            },
            trackRepository = trackRepositoryOf(),
            preferenceStore = preferenceStoreOf(),
            sourcePreferenceStore = { preferenceStoreOf() },
            extensionRepoRepository = FakeExtensionRepoRepository(),
        )

        assertEquals(listOf("Group A", "Group B"), backup.backupManga.single().excludedScanlators)
    }

    @Test
    fun `createFromDatabase collects tracking app preferences source preferences and extension repositories`() = runTest {
        val mangaRepository = FakeMangaRepository().apply {
            seed(
                Manga.create().copy(
                    id = 1L,
                    source = 42L,
                    url = "/m/test",
                    title = "Test Manga",
                    favorite = true,
                ),
            )
        }
        val track = Track(
            id = 7L,
            mangaId = 1L,
            trackerId = 3L,
            remoteId = 99L,
            libraryId = 12L,
            title = "Tracked Manga",
            lastChapterRead = 4.5,
            totalChapters = 20L,
            status = 2L,
            score = 8.5,
            remoteUrl = "https://tracker/manga/99",
            startDate = 100L,
            finishDate = 200L,
            private = true,
        )
        val appPreferences = preferenceStoreOf("theme" to "dark", "show_nsfw" to true)
        val sourcePreferences = preferenceStoreOf("language" to "en")
        val extensionRepositories = FakeExtensionRepoRepository().apply {
            insertRepo("https://repo.example/index.min.json", "Example", "Ex", "https://repo.example", "abc123")
        }

        val backup = DesktopBackupCreator.createFromDatabase(
            mangaRepository = mangaRepository,
            chapterRepository = FakeChapterRepository(),
            categoryRepository = FakeCategoryRepository(),
            historyRepository = FakeHistoryRepository(),
            trackRepository = trackRepositoryOf(track),
            preferenceStore = appPreferences,
            sourcePreferenceStore = { sourceId ->
                assertEquals(42L, sourceId)
                sourcePreferences
            },
            extensionRepoRepository = extensionRepositories,
        )

        val tracking = backup.backupManga.single().tracking.single()
        assertEquals(3, tracking.syncId)
        assertEquals(99L, tracking.mediaId)
        assertEquals(12L, tracking.libraryId)
        assertEquals("Tracked Manga", tracking.title)
        assertEquals(4.5f, tracking.lastChapterRead)
        assertEquals(20, tracking.totalChapters)
        assertEquals(2, tracking.status)
        assertEquals(8.5f, tracking.score)
        assertEquals("https://tracker/manga/99", tracking.trackingUrl)
        assertEquals(100L, tracking.startedReadingDate)
        assertEquals(200L, tracking.finishedReadingDate)
        assertTrue(tracking.private)
        assertEquals(StringPreferenceValue("dark"), backup.backupPreferences.single { it.key == "theme" }.value)
        assertEquals(BooleanPreferenceValue(true), backup.backupPreferences.single { it.key == "show_nsfw" }.value)
        assertEquals("42", backup.backupSourcePreferences.single().sourceKey)
        assertEquals(
            StringPreferenceValue("en"),
            backup.backupSourcePreferences.single().prefs.single { it.key == "language" }.value,
        )
        val extensionRepo = backup.backupExtensionRepo.single()
        assertEquals("https://repo.example/index.min.json", extensionRepo.baseUrl)
        assertEquals("Example", extensionRepo.name)
        assertEquals("Ex", extensionRepo.shortName)
        assertEquals("https://repo.example", extensionRepo.website)
        assertEquals("abc123", extensionRepo.signingKeyFingerprint)
    }

    private fun preferenceStoreOf(vararg entries: Pair<String, Any>): tachiyomi.core.common.preference.PreferenceStore {
        val delegate = InMemoryPreferenceStore()
        return object : tachiyomi.core.common.preference.PreferenceStore by delegate {
            override fun getAll(): Map<String, *> = entries.toMap()
        }
    }

    private fun trackRepositoryOf(vararg tracks: Track) = object : TrackRepository {
        override suspend fun getTrackById(id: Long) = tracks.singleOrNull { it.id == id }
        override suspend fun getTracksByMangaId(mangaId: Long) = tracks.filter { it.mangaId == mangaId }
        override fun getTracksAsFlow() = kotlinx.coroutines.flow.flowOf(tracks.toList())
        override fun getTracksByMangaIdAsFlow(mangaId: Long) =
            kotlinx.coroutines.flow.flowOf(tracks.filter { it.mangaId == mangaId })
        override suspend fun delete(mangaId: Long, trackerId: Long) = Unit
        override suspend fun insert(track: Track) = Unit
        override suspend fun insertAll(tracks: List<Track>) = Unit
    }
}
