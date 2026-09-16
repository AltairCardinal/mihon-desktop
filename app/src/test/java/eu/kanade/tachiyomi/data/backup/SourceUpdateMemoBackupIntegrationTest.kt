package eu.kanade.tachiyomi.data.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.backup.BackupCodec
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import java.io.File

class SourceUpdateMemoBackupIntegrationTest {
    @TempDir lateinit var directory: File

    @Test
    fun `remote memo only update preserves progress after reopen`() = runBlocking<Unit> {
        val path = directory.resolve("updates.db")
        val memo = Json.parseToJsonElement("""{"token":"updated","nested":[null,true]}""").jsonObject
        Storage(path, true).use { storage ->
            val manga = storage.mangas.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = 42,
                        url = "/manga",
                        title = "Custom title",
                        favorite = true,
                    ),
                ),
            ).single()
            val chapter = storage.chapters.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/chapter",
                        name = "Chapter 1",
                        chapterNumber = 1.0,
                        dateUpload = 123,
                        read = true,
                        bookmark = true,
                        lastPageRead = 7,
                    ),
                ),
            ).single()
            val source = object : eu.kanade.tachiyomi.source.Source {
                override val id = 42L
                override val name = "Combined"
                override suspend fun getMangaUpdate(
                    manga: eu.kanade.tachiyomi.source.model.SManga,
                    chapters: List<eu.kanade.tachiyomi.source.model.SChapter>,
                    fetchDetails: Boolean,
                    fetchChapters: Boolean,
                ): eu.kanade.tachiyomi.source.model.SMangaUpdate {
                    assertEquals(false, fetchDetails)
                    assertEquals(true, fetchChapters)
                    assertEquals("Custom title", manga.title)
                    manga.memo = memo
                    chapters.single().memo = memo
                    return eu.kanade.tachiyomi.source.model.SMangaUpdate(manga, chapters)
                }
            }
            val preferences = io.mockk.mockk<tachiyomi.domain.library.service.LibraryPreferences> {
                io.mockk.every { markDuplicateReadChapterAsRead().get() } returns emptySet()
                io.mockk.every { updateMangaTitles().get() } returns false
            }
            val downloads = io.mockk.mockk<eu.kanade.tachiyomi.data.download.DownloadManager>(relaxed = true)
            val sync = eu.kanade.domain.chapter.interactor.SyncChaptersWithSource(
                downloads, io.mockk.mockk(relaxed = true), storage.chapters,
                tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter(), storage.updateManga,
                tachiyomi.domain.chapter.interactor.UpdateChapter(storage.chapters), storage.getChapters,
                eu.kanade.domain.manga.interactor.GetExcludedScanlators(storage.handler), preferences,
            )
            storage.updateManga.awaitFromRemote(
                manga, source, false, true, chapterRepository = storage.chapters, syncChaptersWithSource = sync,
                coverCache = io.mockk.mockk(
                    relaxed = true,
                ),
                libraryPreferences = preferences, downloadManager = downloads,
            )
            assertEquals(chapter.id, storage.chapters.getChapterByMangaId(manga.id).single().id)
        }
        Storage(path, false).use { storage ->
            val manga = storage.mangas.getFavorites().single()
            val chapter = storage.chapters.getChapterByMangaId(manga.id).single()
            assertEquals(memo, manga.memo)
            assertEquals(memo, chapter.memo)
            assertEquals(false, manga.initialized)
            assertEquals("Custom title", manga.title)
            assertEquals(true, chapter.read)
            assertEquals(true, chapter.bookmark)
            assertEquals(7L, chapter.lastPageRead)
        }
    }

    @Test
    fun `production backup retains memo after closing both databases`() = runBlocking<Unit> {
        val memo = Json.parseToJsonElement(
            """{"nested":[null,true,{"token":"中文"}],"id":9223372036854775807}""",
        ).jsonObject
        val backup = Storage(directory.resolve("source.db"), true).use { storage ->
            val manga = storage.mangas.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = 42,
                        url = "/manga",
                        title = "User title",
                        favorite = true,
                        initialized = true,
                        memo = memo,
                    ),
                ),
            ).single()
            storage.chapters.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/chapter",
                        name = "Chapter 1",
                        read = true,
                        bookmark = true,
                        lastPageRead = 7,
                        memo = memo,
                    ),
                ),
            )
            Backup(
                MangaBackupCreator(storage.handler, storage.getCategories, GetHistory(storage.history))(
                    listOf(manga),
                    BackupOptions(),
                ),
            )
        }
        val bytes = BackupCodec.encode(Backup.serializer(), backup)
        val restoredPath = directory.resolve("restored.db")
        Storage(restoredPath, true).use { storage ->
            val decoded = BackupCodec.decode(Backup.serializer(), bytes)
            storage.restorer.restore(decoded.backupManga.single(), emptyList())
        }
        Storage(restoredPath, false).use { storage ->
            val manga = storage.mangas.getFavorites().single()
            val chapter = storage.chapters.getChapterByMangaId(manga.id).single()
            assertEquals(memo, manga.memo)
            assertEquals(memo, chapter.memo)
            assertEquals("User title", manga.title)
            assertEquals(42L, manga.source)
            assertEquals(true, chapter.read)
            assertEquals(true, chapter.bookmark)
            assertEquals(7L, chapter.lastPageRead)
        }
    }

    @Test
    fun `restoring existing chapter replaces memo while retaining read progress and bookmark`() = runBlocking<Unit> {
        Storage(directory.resolve("existing-restore.db"), true).use { storage ->
            val manga = storage.mangas.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = 42,
                        url = "/manga",
                        title = "User title",
                        favorite = true,
                    ),
                ),
            ).single()
            storage.chapters.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/chapter",
                        name = "Chapter 1",
                        read = true,
                        bookmark = true,
                        lastPageRead = 7,
                    ),
                ),
            )
            val memo = Json.parseToJsonElement("""{"restored":[null,"中文"]}""").jsonObject
            val backup = MangaBackupCreator(storage.handler, storage.getCategories, GetHistory(storage.history))(
                listOf(manga),
                BackupOptions(),
            ).single()
            val replacement = backup.copy(
                chapters = backup.chapters.map {
                    it.copy(
                        memo = tachiyomi.data.MemoColumnAdapter.encode(memo),
                        read = false,
                        bookmark = false,
                        lastPageRead = 0,
                    )
                },
            )
            storage.restorer.restore(replacement, emptyList())
            val chapter = storage.chapters.getChapterByMangaId(manga.id).single()
            assertEquals(memo, chapter.memo)
            assertEquals(true, chapter.read)
            assertEquals(true, chapter.bookmark)
            assertEquals(7L, chapter.lastPageRead)
        }
    }

    @Test
    fun `no operation errors and cancellation cannot persist a partial remote memo`() = runBlocking<Unit> {
        Storage(directory.resolve("failure.db"), true).use { storage ->
            val manga = storage.mangas.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = 42,
                        url = "/manga",
                        title = "User title",
                        favorite = true,
                    ),
                ),
            ).single()
            val chapter = storage.chapters.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/chapter",
                        name = "Chapter 1",
                        read = true,
                        bookmark = true,
                        lastPageRead = 7,
                    ),
                ),
            ).single()
            val originalManga = storage.mangas.getMangaById(manga.id)
            val originalChapter = storage.chapters.getChapterByMangaId(manga.id).single()
            for (failure in listOf(
                IllegalStateException("remote failure"),
                kotlinx.coroutines.CancellationException("remote cancellation"),
            )) {
                var calls = 0
                val source = object : eu.kanade.tachiyomi.source.Source {
                    override val id = 42L
                    override val name = "Failing"
                    override suspend fun getMangaUpdate(
                        manga: eu.kanade.tachiyomi.source.model.SManga,
                        chapters: List<eu.kanade.tachiyomi.source.model.SChapter>,
                        fetchDetails: Boolean,
                        fetchChapters: Boolean,
                    ): eu.kanade.tachiyomi.source.model.SMangaUpdate {
                        calls++
                        manga.memo = Json.parseToJsonElement("""{"uncommitted":true}""").jsonObject
                        chapters.single().memo = manga.memo
                        throw failure
                    }
                }
                val preferences = io.mockk.mockk<tachiyomi.domain.library.service.LibraryPreferences>()
                val downloads = io.mockk.mockk<eu.kanade.tachiyomi.data.download.DownloadManager>()
                val sync = eu.kanade.domain.chapter.interactor.SyncChaptersWithSource(
                    downloads, io.mockk.mockk(), storage.chapters,
                    tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter(), storage.updateManga,
                    tachiyomi.domain.chapter.interactor.UpdateChapter(storage.chapters), storage.getChapters,
                    eu.kanade.domain.manga.interactor.GetExcludedScanlators(storage.handler), preferences,
                )
                suspend fun update(details: Boolean, chapters: Boolean) = storage.updateManga.awaitFromRemote(
                    originalManga, source, details, chapters, chapterRepository = storage.chapters,
                    syncChaptersWithSource = sync, coverCache = io.mockk.mockk(),
                    libraryPreferences = preferences, downloadManager = downloads,
                )
                update(false, false)
                assertEquals(0, calls)
                org.junit.jupiter.api.Assertions.assertSame(
                    failure,
                    runCatching {
                        update(true, true)
                    }.exceptionOrNull(),
                )
                assertEquals(1, calls)
                assertEquals(originalManga, storage.mangas.getMangaById(manga.id))
                assertEquals(originalChapter, storage.chapters.getChapterByMangaId(manga.id).single())
                assertEquals(chapter.id, originalChapter.id)
            }
        }
    }

    internal class Storage(path: File, create: Boolean) : AutoCloseable {
        private val jdbcDriver = (
            Class.forName(
                "org.sqlite.JDBC",
            ).getDeclaredConstructor().newInstance() as java.sql.Driver
            )
            .also(java.sql.DriverManager::registerDriver)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePath}")
        val database = run {
            if (create) Database.Schema.create(driver)
            Database(
                driver,
                historyAdapter = History.Adapter(DateColumnAdapter),
                mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter),
            )
        }
        val handler = AndroidDatabaseHandler(database, driver)
        val mangas = MangaRepositoryImpl(handler, NoopCreatorLibraryIndexWriter)
        val chapters = ChapterRepositoryImpl(handler)
        val categories = CategoryRepositoryImpl(handler)
        val history = HistoryRepositoryImpl(handler)
        val tracks = TrackRepositoryImpl(handler)
        val getCategories = GetCategories(categories)
        val getChapters = GetChaptersByMangaId(chapters)
        val fetchInterval = FetchInterval(getChapters)
        val updateManga = UpdateManga(mangas, fetchInterval)
        val restorer = MangaRestorer(
            handler, getCategories, GetMangaByUrlAndSourceId(mangas), getChapters,
            UpdateManga(mangas, fetchInterval), GetTracks(tracks), InsertTrack(tracks),
            NoopCreatorLibraryIndexWriter, fetchInterval = fetchInterval,
        )
        override fun close() {
            driver.close()
            java.sql.DriverManager.deregisterDriver(jdbcDriver)
        }
    }
}
