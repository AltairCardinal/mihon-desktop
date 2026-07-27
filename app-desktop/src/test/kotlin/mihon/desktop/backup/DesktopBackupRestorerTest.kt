package mihon.desktop.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.prefs.Preferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.model.Track
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import mihon.domain.extensionrepo.model.ExtensionRepo
import kotlinx.coroutines.flow.flowOf
import mihon.desktop.backup.models.*
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl

/**
 * RED tests for DesktopBackupRestorer.
 * These tests define the expected contract before implementation exists.
 */
class DesktopBackupRestorerTest {

    @Test
    fun `restore history uses Android non-regressing merge semantics with real SQL repository`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            Database.Schema.create(driver)
            val database = Database(
                driver = driver,
                historyAdapter = tachiyomi.data.History.Adapter(last_readAdapter = DateColumnAdapter),
                mangasAdapter = tachiyomi.data.Mangas.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                ),
            )
            val handler = JvmDatabaseHandler(database, driver)
            val mangaRepository = MangaRepositoryImpl(handler)
            val chapterRepository = ChapterRepositoryImpl(handler)
            val historyRepository = HistoryRepositoryImpl(handler)
            val restorer = DesktopBackupRestorer(
                mangaRepository = mangaRepository,
                chapterRepository = chapterRepository,
                categoryRepository = CategoryRepositoryImpl(handler),
                historyRepository = historyRepository,
            )
            suspend fun restoreHistory(lastRead: Long, readDuration: Long, chapterUrl: String = "/c") {
                restorer.restore(
                    Backup(
                        backupManga = listOf(
                            BackupManga(
                                source = 42,
                                url = "/m",
                                chapters = listOf(BackupChapter(chapterUrl, chapterUrl)),
                                history = listOf(BackupHistory(chapterUrl, lastRead, readDuration)),
                            ),
                        ),
                    ),
                )
            }

            restoreHistory(lastRead = 200, readDuration = 100)
            restoreHistory(lastRead = 100, readDuration = 120)
            restoreHistory(lastRead = 300, readDuration = 80)
            restoreHistory(lastRead = 400, readDuration = 50, chapterUrl = "/new")

            val histories = historyRepository.getHistoryByMangaId(1).associateBy { history -> history.chapterId }
            assertEquals(2, histories.size)
            assertEquals(120, histories.getValue(1).readDuration)
            assertEquals(300, histories.getValue(1).readAt?.time)
            assertEquals(50, histories.getValue(2).readDuration)
            assertEquals(400, histories.getValue(2).readAt?.time)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `existing initialized manga restores canonical state and newer bibliography`() = runTest {
        val mangas = FakeMangaRepository().apply {
            seed(Manga.create().copy(id = 10, source = 42, url = "/m", title = "Old", initialized = true, version = 1))
        }
        val restorer = restorer(mangaRepository = mangas)

        restorer.restore(Backup(backupManga = listOf(BackupManga(
            source = 42, url = "/m", title = "Backup", author = "Author", favorite = true,
            dateAdded = 123, viewer = 1, viewer_flags = 2, chapterFlags = 3,
            updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE, initialized = true, version = 7, notes = "note",
        ))))

        val restored = requireNotNull(mangas.get(10))
        assertEquals("Backup", restored.title)
        assertEquals("Author", restored.author)
        assertEquals(true, restored.favorite)
        assertEquals(123, restored.dateAdded)
        assertEquals(2, restored.viewerFlags)
        assertEquals(3, restored.chapterFlags)
        assertEquals(UpdateStrategy.ONLY_FETCH_ONCE, restored.updateStrategy)
        assertEquals(7, restored.version)
        assertEquals("note", restored.notes)
    }

    @Test
    fun `existing chapter merges every field without regressing local reading progress`() = runTest {
        val mangas = FakeMangaRepository().apply { seed(Manga.create().copy(id = 1, source = 42, url = "/m")) }
        val chapters = FakeChapterRepository().apply {
            seed(Chapter.create().copy(id = 5, mangaId = 1, url = "/c", read = true, bookmark = true, lastPageRead = 9))
        }
        val history = FakeHistoryRepository()
        val restorer = DesktopBackupRestorer(mangas, chapters, FakeCategoryRepository(), history)

        restorer.restore(Backup(backupManga = listOf(BackupManga(42, "/m", chapters = listOf(
            BackupChapter("/c", "Renamed", "Group", read = false, bookmark = false, lastPageRead = 2,
                dateFetch = 10, dateUpload = 11, chapterNumber = 12f, sourceOrder = 13, lastModifiedAt = 14, version = 15),
        ), history = listOf(BackupHistory("/c", 100, 321))))))

        val restored = requireNotNull(chapters.getChapterById(5))
        assertEquals("Renamed", restored.name)
        assertEquals("Group", restored.scanlator)
        assertEquals(true, restored.read)
        assertEquals(true, restored.bookmark)
        assertEquals(9, restored.lastPageRead)
        assertEquals(10, restored.dateFetch)
        assertEquals(11, restored.dateUpload)
        assertEquals(12.0, restored.chapterNumber)
        assertEquals(13, restored.sourceOrder)
        assertEquals(15, restored.version)
        assertEquals(321, history.upserted.single().sessionReadDuration)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `fixed-main Android artifact restores every Desktop persistence boundary with progress`() = runTest {
        val authorityRef = repositoryFile(
            "data/src/commonTest/resources/backup/android-full.original-mihon-ref",
        ).readText().trim()
        assertEquals("6fbf6dfca203d99d6dd32137f2df97ced40c81b8", authorityRef)
        val backup = DesktopBackupCreator.decodeFromBytes(
            repositoryFile("data/src/commonTest/resources/backup/android-full.tachibk").readBytes(),
        )

        val mangaRepository = FakeMangaRepository()
        val chapterRepository = FakeChapterRepository()
        val categoryRepository = FakeCategoryRepository()
        val historyRepository = FakeHistoryRepository()
        val tracks = mutableListOf<Track>()
        val extensionRepos = mutableListOf<ExtensionRepo>()
        val excludedScanlators = mutableListOf<Pair<Long, List<String>>>()
        val sourceStoreRequests = mutableListOf<Long>()
        val appNode = Preferences.userRoot().node("/mihon-test/fixed-android-app-${System.nanoTime()}")
        val sourceNode = Preferences.userRoot().node("/mihon-test/fixed-android-source-${System.nanoTime()}")
        val appStore = DesktopPreferenceStore(appNode)
        val sourceStore = DesktopPreferenceStore(sourceNode)
        val progress = mutableListOf<RestoreProgress>()
        try {
            val result = DesktopBackupRestorer(
                mangaRepository = mangaRepository,
                chapterRepository = chapterRepository,
                categoryRepository = categoryRepository,
                historyRepository = historyRepository,
                setExcludedScanlatorsForManga = { mangaId, excluded ->
                    excludedScanlators += mangaId to excluded
                },
                trackRepository = recordingTrackRepository(tracks),
                preferenceStore = appStore,
                sourcePreferenceStore = { sourceId ->
                    sourceStoreRequests += sourceId
                    sourceStore
                },
                extensionRepoRepository = recordingExtensionRepoRepository(extensionRepos),
            ).restore(backup, progress::add)

            assertEquals(1, result.successCount)
            assertEquals(false, result.hasErrors)
            assertEquals((1..6).map { RestoreProgress(it, 6) }, progress)

            val category = categoryRepository.getAll().single()
            assertEquals("Category", category.name)
            assertEquals(1L, category.order)
            assertEquals(2L, category.flags)
            val manga = requireNotNull(mangaRepository.get(1L))
            assertEquals(101L, manga.source)
            assertEquals("/manga", manga.url)
            assertEquals("Canonical manga", manga.title)
            assertEquals(17L, manga.viewerFlags)
            assertEquals(listOf(category.id), mangaRepository.getMangaCategoryIds(manga.id))
            assertEquals(listOf("Excluded"), excludedScanlators.single().second)

            val chapter = chapterRepository.getChapterByMangaId(manga.id).single()
            assertEquals("/chapter", chapter.url)
            assertEquals(true, chapter.read)
            assertEquals(true, chapter.bookmark)
            assertEquals(chapter.id, historyRepository.upserted.single().chapterId)
            assertEquals(18L, historyRepository.upserted.single().readAt.time)
            assertEquals(19L, historyRepository.upserted.single().sessionReadDuration)

            assertEquals(9L, tracks.single().trackerId)
            assertEquals(11L, tracks.single().remoteId)
            assertEquals("dark", appStore.getString("theme").get())
            assertEquals(listOf(101L), sourceStoreRequests)
            assertEquals(3, sourceStore.getInt("quality").get())
            assertEquals("https://repo", extensionRepos.single().baseUrl)
            assertEquals("fingerprint", extensionRepos.single().signingKeyFingerprint)
        } finally {
            appNode.removeNode()
            sourceNode.removeNode()
        }
    }

    @Test
    fun `first Desktop protobuf fixture follows the current restore chain`() = runTest {
        val mangaRepository = FakeMangaRepository()
        val chapterRepository = FakeChapterRepository()
        val categoryRepository = FakeCategoryRepository()
        val historyRepository = FakeHistoryRepository()
        val tracks = mutableListOf<Track>()
        val backup = DesktopBackupCreator.decodeFromBytes(
            requireNotNull(javaClass.getResourceAsStream("/backup/desktop-first-writer.tachibk")).readBytes(),
        )
        val result = DesktopBackupRestorer(
            mangaRepository,
            chapterRepository,
            categoryRepository,
            historyRepository,
            trackRepository = recordingTrackRepository(tracks),
        ).restore(backup)

        assertEquals(1, result.successCount)
        assertEquals(false, result.hasErrors)
        val manga = requireNotNull(mangaRepository.get(1L))
        assertEquals("Historical Desktop manga", manga.title)
        assertEquals(13L, manga.viewerFlags)
        assertEquals(21L, manga.chapterFlags)
        assertEquals("Desktop notes", manga.notes)
        assertEquals(9L, tracks.single().trackerId)
        assertEquals(15L, tracks.single().remoteId)
        assertEquals("Desktop tracked title", tracks.single().title)
    }

    @Test
    fun `restore reports every category and manga as processed including partial failures`() = runTest {
        val progress = mutableListOf<RestoreProgress>()
        val restorer = DesktopBackupRestorer(
            mangaRepository = FakeMangaRepository(),
            chapterRepository = FakeChapterRepository(),
            categoryRepository = FakeCategoryRepository(),
            historyRepository = FakeHistoryRepository(),
            setExcludedScanlatorsForManga = { _, _ -> error("scanlator failure") },
        )

        val result = restorer.restore(
            Backup(
                backupCategories = listOf(BackupCategory("Action", 0)),
                backupManga = listOf(
                    BackupManga(1, "/ok"),
                    BackupManga(1, "/partial", excludedScanlators = listOf("Group")),
                ),
            ),
            onProgress = { progress += it },
        )

        assertEquals(listOf(1, 2, 3), progress.map { it.completed })
        assertEquals(listOf(3, 3, 3), progress.map { it.total })
        assertEquals(3, progress.last().completed)
        assertEquals(1, result.errors.size)
    }

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
        val backupCategoryOrders = listOf(0L, 1L)
        val ids = DesktopBackupRestorer.resolveBackupCategoryIds(
            backupCategoryOrders = backupCategoryOrders,
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

    @Test
    fun `restore preserves viewer flags from backup viewer field`() = runTest {
        val mangaRepository = FakeMangaRepository()
        val restorer = DesktopBackupRestorer(
            mangaRepository = mangaRepository,
            chapterRepository = FakeChapterRepository(),
            categoryRepository = FakeCategoryRepository(),
            historyRepository = FakeHistoryRepository(),
        )

        val result = restorer.restore(
            Backup(
                backupManga = listOf(
                    BackupManga(
                        source = 42L,
                        url = "/m/test",
                        title = "Test Manga",
                        favorite = true,
                        viewer = 0x22,
                    ),
                ),
            ),
        )

        assertEquals(1, result.successCount)
        assertEquals(0x22L, mangaRepository.get(1L)?.viewerFlags)
    }

    @Test
    fun `restore prefers canonical viewer flags over legacy viewer`() = runTest {
        val mangaRepository = FakeMangaRepository()
        val restorer = restorer(mangaRepository = mangaRepository)

        restorer.restore(Backup(backupManga = listOf(BackupManga(42, "/m", viewer = 1, viewer_flags = 0x66))))

        assertEquals(0x66L, mangaRepository.get(1L)?.viewerFlags)
    }

    @Test
    fun `restore persists tracking preferences source preferences and extension repositories field by field`() = runTest {
        val appNode = Preferences.userRoot().node("/mihon-test/restore-${System.nanoTime()}")
        val sourceNode = Preferences.userRoot().node("/mihon-test/source-${System.nanoTime()}")
        val appStore = DesktopPreferenceStore(appNode)
        val sourceStore = DesktopPreferenceStore(sourceNode)
        val tracks = mutableListOf<Track>()
        val repos = mutableListOf<ExtensionRepo>()
        val restorer = restorer(
            trackRepository = recordingTrackRepository(tracks),
            preferenceStore = appStore,
            sourcePreferenceStore = { sourceStore },
            extensionRepoRepository = recordingExtensionRepoRepository(repos),
        )
        val tracking = BackupTracking(
            syncId = 7, libraryId = 8, mediaId = 9, trackingUrl = "https://track", title = "Tracked",
            lastChapterRead = 3.5f, totalChapters = 10, score = 8.5f, status = 2,
            startedReadingDate = 11, finishedReadingDate = 12, private = true,
        )
        val repo = BackupExtensionRepos("https://repo", "Repo", "R", "https://site", "fingerprint")

        val result = restorer.restore(
            Backup(
                backupManga = listOf(BackupManga(42, "/m", title = "M", tracking = listOf(tracking))),
                backupPreferences = listOf(
                    BackupPreference("int", IntPreferenceValue(1)),
                    BackupPreference("long", LongPreferenceValue(2)),
                    BackupPreference("float", FloatPreferenceValue(3.5f)),
                    BackupPreference("string", StringPreferenceValue("value")),
                    BackupPreference("boolean", BooleanPreferenceValue(true)),
                    BackupPreference("set", StringSetPreferenceValue(setOf("a", "b"))),
                ),
                backupSourcePreferences = listOf(
                    BackupSourcePreferences("42", listOf(BackupPreference("lang", StringPreferenceValue("en")))),
                ),
                backupExtensionRepo = listOf(repo),
            ),
        )

        assertEquals(false, result.hasErrors)
        assertEquals(7L, tracks.single().trackerId)
        assertEquals(1L, tracks.single().mangaId)
        assertEquals(9L, tracks.single().remoteId)
        assertEquals(8L, tracks.single().libraryId)
        assertEquals("Tracked", tracks.single().title)
        assertEquals(3.5, tracks.single().lastChapterRead)
        assertEquals(10L, tracks.single().totalChapters)
        assertEquals(8.5, tracks.single().score)
        assertEquals(2L, tracks.single().status)
        assertEquals("https://track", tracks.single().remoteUrl)
        assertEquals(11L, tracks.single().startDate)
        assertEquals(12L, tracks.single().finishDate)
        assertEquals(true, tracks.single().private)
        assertEquals(1, appStore.getInt("int").get())
        assertEquals(2L, appStore.getLong("long").get())
        assertEquals(3.5f, appStore.getFloat("float").get())
        assertEquals("value", appStore.getString("string").get())
        assertEquals(true, appStore.getBoolean("boolean").get())
        assertEquals(setOf("a", "b"), appStore.getStringSet("set").get())
        assertEquals("en", sourceStore.getString("lang").get())
        assertEquals("https://repo", repos.single().baseUrl)
        assertEquals("Repo", repos.single().name)
        assertEquals("R", repos.single().shortName)
        assertEquals("https://site", repos.single().website)
        assertEquals("fingerprint", repos.single().signingKeyFingerprint)
        appNode.removeNode()
        sourceNode.removeNode()
    }

    private fun restorer(
        mangaRepository: FakeMangaRepository = FakeMangaRepository(),
        trackRepository: TrackRepository? = null,
        preferenceStore: tachiyomi.core.common.preference.PreferenceStore? = null,
        sourcePreferenceStore: ((Long) -> tachiyomi.core.common.preference.PreferenceStore)? = null,
        extensionRepoRepository: ExtensionRepoRepository? = null,
    ) = DesktopBackupRestorer(
        mangaRepository, FakeChapterRepository(), FakeCategoryRepository(), FakeHistoryRepository(),
        trackRepository = trackRepository,
        preferenceStore = preferenceStore,
        sourcePreferenceStore = sourcePreferenceStore,
        extensionRepoRepository = extensionRepoRepository,
    )

    private fun recordingTrackRepository(target: MutableList<Track>) = object : TrackRepository {
        override suspend fun getTrackById(id: Long) = target.singleOrNull { it.id == id }
        override suspend fun getTracksByMangaId(mangaId: Long) = target.filter { it.mangaId == mangaId }
        override fun getTracksAsFlow() = flowOf(target.toList())
        override fun getTracksByMangaIdAsFlow(mangaId: Long) = flowOf(target.filter { it.mangaId == mangaId })
        override suspend fun delete(mangaId: Long, syncId: Long) = Unit
        override suspend fun insert(track: Track) { target += track }
        override suspend fun insertAll(tracks: List<Track>) { target += tracks }
    }

    private fun recordingExtensionRepoRepository(target: MutableList<ExtensionRepo>) = object : ExtensionRepoRepository {
        override fun subscribeAll() = flowOf(target.toList())
        override suspend fun getAll() = target.toList()
        override suspend fun getRepo(baseUrl: String) = target.singleOrNull { it.baseUrl == baseUrl }
        override suspend fun getRepoBySigningKeyFingerprint(fingerprint: String) = target.singleOrNull { it.signingKeyFingerprint == fingerprint }
        override fun getCount() = flowOf(target.size)
        override suspend fun insertRepo(baseUrl: String, name: String, shortName: String?, website: String, signingKeyFingerprint: String) = upsertRepo(baseUrl, name, shortName, website, signingKeyFingerprint)
        override suspend fun upsertRepo(baseUrl: String, name: String, shortName: String?, website: String, signingKeyFingerprint: String) {
            target.removeAll { it.baseUrl == baseUrl }
            target += ExtensionRepo(baseUrl, name, shortName, website, signingKeyFingerprint)
        }
        override suspend fun replaceRepo(newRepo: ExtensionRepo) { upsertRepo(newRepo) }
        override suspend fun deleteRepo(baseUrl: String) { target.removeAll { it.baseUrl == baseUrl } }
    }

    private fun repositoryFile(relativePath: String): File =
        generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile, File::getParentFile)
            .map { it.resolve(relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Repository file not found: $relativePath")

    @Test
    fun `restore preserves manga update metadata fields`() = runTest {
        val mangaRepository = FakeMangaRepository()
        val restorer = DesktopBackupRestorer(
            mangaRepository = mangaRepository,
            chapterRepository = FakeChapterRepository(),
            categoryRepository = FakeCategoryRepository(),
            historyRepository = FakeHistoryRepository(),
        )

        val result = restorer.restore(
            Backup(
                backupManga = listOf(
                    BackupManga(
                        source = 42L,
                        url = "/m/test",
                        title = "Test Manga",
                        favorite = true,
                        updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE,
                        favoriteModifiedAt = 123_456L,
                        version = 7L,
                    ),
                ),
            ),
        )

        val restored = mangaRepository.get(1L)
        assertEquals(1, result.successCount)
        assertEquals(UpdateStrategy.ONLY_FETCH_ONCE, restored?.updateStrategy)
        assertEquals(123_456L, restored?.favoriteModifiedAt)
        assertEquals(7L, restored?.version)
    }

    @Test
    fun `restore preserves excluded scanlators`() = runTest {
        val mangaRepository = FakeMangaRepository()
        val restoredExcluded = mutableListOf<Pair<Long, List<String>>>()
        val restorer = DesktopBackupRestorer(
            mangaRepository = mangaRepository,
            chapterRepository = FakeChapterRepository(),
            categoryRepository = FakeCategoryRepository(),
            historyRepository = FakeHistoryRepository(),
            setExcludedScanlatorsForManga = { mangaId, excluded ->
                restoredExcluded += mangaId to excluded
            },
        )

        val result = restorer.restore(
            Backup(
                backupManga = listOf(
                    BackupManga(
                        source = 42L,
                        url = "/m/test",
                        title = "Test Manga",
                        favorite = true,
                        excludedScanlators = listOf("Group A", "Group B"),
                    ),
                ),
            ),
        )

        assertEquals(1, result.successCount)
        assertEquals(listOf(1L to listOf("Group A", "Group B")), restoredExcluded)
    }
}
