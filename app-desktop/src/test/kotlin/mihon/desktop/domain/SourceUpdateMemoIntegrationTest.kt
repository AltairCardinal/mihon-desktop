package mihon.desktop.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mihon.desktop.backup.DesktopBackupCreator
import mihon.desktop.backup.DesktopBackupRestorer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.JvmDatabaseHandler
import tachiyomi.data.Mangas
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.creator.repository.NoopCreatorLibraryIndexWriter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceMangaUpdateService
import tachiyomi.domain.source.service.toSourceManga
import java.io.File

class SourceUpdateMemoIntegrationTest {
    @TempDir lateinit var directory: File
    private val mangaMemo = Json.parseToJsonElement("""{"token":"中文","nested":[null,{"id":9223372036854775807}]}""").jsonObject
    private val chapterMemo = Json.parseToJsonElement("""{"pages":["one","two"],"empty":{}}""").jsonObject

    @Test
    fun `linked chapter fetch and library scheduler use combined Source-only update without initializing details`() = runBlocking<Unit> {
        val path = directory.resolve("linked-scheduled.db")
        var calls = 0
        val source = object : Source {
            override val id = 42L
            override val name = "Source-only"
            override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
                calls++
                assertEquals(false, fetchDetails)
                assertEquals(true, fetchChapters)
                if (calls == 2) {
                    assertEquals(mangaMemo, manga.memo)
                    assertEquals(chapterMemo, chapters.single().memo)
                }
                manga.memo = mangaMemo
                return SMangaUpdate(manga, listOf(SChapter.create().apply {
                    url = "/linked"; name = "Chapter 1"; memo = chapterMemo
                }))
            }
        }
        Storage(path, true).use { storage ->
            val manga = storage.mangas.insertNetworkManga(listOf(Manga.create().copy(
                source = 42, url = "/manga", title = "Custom", favorite = true,
            ))).single()
            val result = storage.details.awaitLinkedChapter(source, manga.toSourceManga(), SChapter.create().apply {
                url = "/linked"; name = "Chapter 1"
            })
            assertEquals(chapterMemo, result.chapter!!.memo)
            assertEquals(false, storage.mangas.getMangaById(manga.id).initialized)
        }
        Storage(path, false).use { storage ->
            val manager = object : tachiyomi.domain.source.service.SourceManager {
                override val isInitialized = kotlinx.coroutines.flow.MutableStateFlow(true)
                override val catalogueSources = kotlinx.coroutines.flow.flowOf(emptyList<eu.kanade.tachiyomi.source.CatalogueSource>())
                override fun get(sourceKey: Long): Source? = source.takeIf { sourceKey == it.id }
                override fun getOrStub(sourceKey: Long): Source = requireNotNull(get(sourceKey))
                override fun getOnlineSources() = emptyList<eu.kanade.tachiyomi.source.online.HttpSource>()
                override fun getCatalogueSources() = emptyList<eu.kanade.tachiyomi.source.CatalogueSource>()
                override fun getStubSources() = emptyList<tachiyomi.domain.source.model.StubSource>()
            }
            val scheduler = LibraryUpdateScheduler(
                mihon.desktop.settings.DesktopAppPreferences(InMemoryPreferenceStore()),
                LibraryUpdateChecker(storage.chapters, storage.mangas),
                tachiyomi.domain.manga.interactor.GetLibraryManga(storage.mangas), manager, scope = this,
            )
            scheduler.runNow().join()
            assertEquals(2, calls)
            assertEquals(false, storage.mangas.getFavorites().single().initialized)
            assertEquals("Custom", storage.mangas.getFavorites().single().title)
        }
    }

    @Test
    fun `reader page materialization passes persisted chapter memo to source after reopen`() = runBlocking<Unit> {
        val path = directory.resolve("reader.db")
        val ids = Storage(path, true).use { storage ->
            val manga = storage.mangas.insertNetworkManga(listOf(Manga.create().copy(source = 42, url = "/manga", title = "Reader"))).single()
            val chapter = storage.chapters.addAll(listOf(Chapter.create().copy(mangaId = manga.id, url = "/chapter", name = "Chapter 1", memo = chapterMemo))).single()
            manga.id to chapter.id
        }
        Storage(path, false).use { storage ->
            val source = memoPageSource("https://images.invalid/page.png")
            val port = mihon.desktop.reader.DesktopReaderChapterContentPort(
                context = mihon.desktop.reader.DesktopReaderChapterContext(ids.second, 42, "/chapter", "Reader", "Chapter 1", 1.0, 0, 0, false, mangaId = ids.first),
                downloadProvider = mihon.desktop.download.DesktopDownloadProvider(directory.resolve("reader-downloads")),
                sourceManager = mihon.desktop.source.FakeDesktopSourceManager(listOf(source)),
                chapterRepository = storage.chapters,
            )
            val pages = port.loadChapterContent(mihon.domain.reader.materialize.ReaderChapterContentRequest(
                mihon.domain.reader.session.ReaderChapterId(ids.second), 1,
            ))
            assertEquals(1, pages.size)
            assertEquals("https://images.invalid/page.png", pages.single().imageUrl)
        }
    }

    private fun memoPageSource(imageUrl: String, receive: (SChapter) -> Unit = { assertEquals(chapterMemo, it.memo) }) = object : eu.kanade.tachiyomi.source.CatalogueSource {
        override val id = 42L
        override val name = "Memo pages"
        override val lang = "en"
        override val supportsLatest = false
        override suspend fun getPopularManga(page: Int) = eu.kanade.tachiyomi.source.model.MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int) = eu.kanade.tachiyomi.source.model.MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: eu.kanade.tachiyomi.source.model.FilterList) =
            eu.kanade.tachiyomi.source.model.MangasPage(emptyList(), false)
        override fun getFilterList() = eu.kanade.tachiyomi.source.model.FilterList()
        override suspend fun getPageList(chapter: SChapter): List<eu.kanade.tachiyomi.source.model.Page> {
            receive(chapter)
            return listOf(eu.kanade.tachiyomi.source.model.Page(0, "", imageUrl))
        }
    }

    @Test
    fun `library update uses chapters only and persists returned manga memo without marking details initialized`() = runBlocking<Unit> {
        Storage(directory.resolve("library.db"), true).use { storage ->
            val manga = storage.mangas.insertNetworkManga(listOf(Manga.create().copy(source = 42, url = "/manga", title = "Custom", favorite = true))).single()
            var calls = 0
            val source = object : Source {
                override val id = 42L
                override val name = "Combined"
                override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
                    calls++
                    assertEquals(false, fetchDetails)
                    assertEquals(true, fetchChapters)
                    manga.memo = mangaMemo
                    return SMangaUpdate(manga, listOf(SChapter.create().apply { url = "/chapter"; name = "Chapter 1"; memo = chapterMemo }))
                }
            }
            val result = LibraryUpdateChecker(storage.chapters, storage.mangas).checkForUpdates(manga, source)
            assertEquals(1, calls)
            assertEquals(1, result.newChapterCount)
            assertEquals(mangaMemo, storage.mangas.getMangaById(manga.id).memo)
            assertEquals(false, storage.mangas.getMangaById(manga.id).initialized)
            assertEquals(chapterMemo, storage.chapters.getChapterByMangaId(manga.id).single().memo)
        }
    }

    @Test
    fun `detail update persists result and supplies reopened memo and ordered chapters on next refresh`() = runBlocking {
        val path = directory.resolve("updates.db")
        var expectedCalls = 0
        val source = object : Source {
            override val id = 42L
            override val name = "Combined"
            override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
                assertEquals(true, fetchDetails)
                assertEquals(true, fetchChapters)
                if (expectedCalls++ > 0) {
                    assertEquals(mangaMemo, manga.memo)
                    assertEquals(chapterMemo, chapters.single().memo)
                }
                return SMangaUpdate(
                    SManga.create().apply { url = manga.url; title = "Remote title"; memo = mangaMemo },
                    listOf(SChapter.create().apply { url = "/chapter"; name = "Chapter 1"; memo = chapterMemo }),
                )
            }
        }
        val id = Storage(path, true).use { storage ->
            storage.details.awaitFromSource(source, SManga.create().apply { url = "/manga"; title = "Listed" }).id
        }
        Storage(path, false).use { storage ->
            val manga = storage.mangas.getMangaById(id)
            assertEquals(mangaMemo, manga.memo)
            assertEquals(chapterMemo, storage.chapters.getChapterByMangaId(id).single().memo)
            storage.details.awaitFromSource(source, manga.toSourceManga())
        }
        assertEquals(2, expectedCalls)
    }

    @Test
    fun `production backup creation and restore preserve reopened memo and chapter progress for source consumption`() = runBlocking<Unit> {
        val originalPath = directory.resolve("original.db")
        var updateCalls = 0
        val updatingSource = object : Source {
            override val id = 42L
            override val name = "Update then backup"
            override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
                if (updateCalls++ > 0) {
                    assertEquals(mangaMemo, manga.memo)
                    assertEquals(chapterMemo, chapters.single().memo)
                }
                manga.memo = mangaMemo
                chapters.single().memo = chapterMemo
                return SMangaUpdate(manga, chapters)
            }
        }
        Storage(originalPath, true).use { storage ->
            val manga = storage.mangas.insertNetworkManga(listOf(Manga.create().copy(
                source = 42, url = "/manga", title = "User title", favorite = true, initialized = true,
            ))).single()
            storage.chapters.addAll(listOf(Chapter.create().copy(
                mangaId = manga.id, url = "/chapter", name = "Chapter 1", read = true, bookmark = true,
                lastPageRead = 7, sourceOrder = 4,
            )))
            storage.details.awaitFromSource(updatingSource, manga.toSourceManga())
        }
        val backup = Storage(originalPath, false).use { storage ->
            storage.details.awaitFromSource(updatingSource, storage.mangas.getFavorites().single().toSourceManga())
            assertEquals(2, updateCalls)
            DesktopBackupCreator.createFromDatabase(
                storage.mangas, storage.chapters, storage.categories, storage.history,
                trackRepository = TrackRepositoryImpl(storage.handler),
                preferenceStore = InMemoryPreferenceStore(),
                sourcePreferenceStore = { InMemoryPreferenceStore() },
                extensionRepoRepository = mihon.data.repository.ExtensionRepoRepositoryImpl(storage.handler),
            )
        }
        val encoded = DesktopBackupCreator.encodeToBytes(backup)
        val decoded = DesktopBackupCreator.decodeFromBytes(encoded)
        val restoredPath = directory.resolve("restored.db")
        Storage(restoredPath, true).use { storage ->
            val result = DesktopBackupRestorer(storage.mangas, storage.chapters, storage.categories, storage.history).restore(decoded)
            assertEquals(1, result.successCount)
        }
        Storage(restoredPath, false).use { storage ->
            val manga = storage.mangas.getFavorites().single()
            val chapter = storage.chapters.getChapterByMangaId(manga.id).single()
            assertEquals(mangaMemo, manga.memo)
            assertEquals(chapterMemo, chapter.memo)
            assertTrue(chapter.read)
            assertTrue(chapter.bookmark)
            assertEquals(7, chapter.lastPageRead)
            val source = object : Source {
                override val id = 42L
                override val name = "Restored"
                override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
                    assertEquals(mangaMemo, manga.memo)
                    assertEquals(chapterMemo, chapters.single().memo)
                    return SMangaUpdate(manga, chapters)
                }
            }
            SourceMangaUpdateService().await(source, manga, listOf(chapter), true, true)
            verifyRestoredPageConsumers(storage, manga, chapter)
        }
    }

    private suspend fun verifyRestoredPageConsumers(storage: Storage, manga: Manga, chapter: Chapter) {
        val imageUrl = "https://memo-images.invalid/page.png"
        val received = java.util.concurrent.CopyOnWriteArrayList<kotlinx.serialization.json.JsonObject>()
        val source = memoPageSource(imageUrl) { received += it.memo }
        val provider = mihon.desktop.download.DesktopDownloadProvider(directory.resolve("restored-downloads"))
        val reader = mihon.desktop.reader.DesktopReaderChapterContentPort(
            mihon.desktop.reader.DesktopReaderChapterContext(chapter.id, manga.source, chapter.url, manga.title, chapter.name,
                chapter.chapterNumber, 0, chapter.lastPageRead.toInt(), chapter.read, mangaId = manga.id),
            provider, mihon.desktop.source.FakeDesktopSourceManager(listOf(source)), chapterRepository = storage.chapters,
        )
        assertEquals(imageUrl, reader.loadChapterContent(mihon.domain.reader.materialize.ReaderChapterContentRequest(
            mihon.domain.reader.session.ReaderChapterId(chapter.id), 1,
        )).single().imageUrl)
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5GkAAAAASUVORK5CYII=")
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(png.toResponseBody("image/png".toMediaType())).build()
        }.build()
        val manager = mihon.desktop.download.DesktopDownloadManager(
            provider, networkHelper = eu.kanade.tachiyomi.network.NetworkHelper(client),
            downloadPreferences = mihon.desktop.download.DesktopDownloadPreferences(InMemoryPreferenceStore()),
            sourceResolver = { source }, chapterRepository = storage.chapters,
        )
        try {
            manager.enqueue(mihon.desktop.download.DownloadItem(manga.source, manga.title, chapter.name, chapter.id,
                mangaId = manga.id, chapterUrl = chapter.url))
            manager.start()
            kotlinx.coroutines.withTimeout(10_000) {
                while (manager.queue.value.isNotEmpty()) {
                    check(manager.queue.value.none { it.status == mihon.desktop.download.DownloadStatus.ERROR }) { "Download failed" }
                    kotlinx.coroutines.delay(20)
                }
            }
            assertEquals(listOf(chapterMemo, chapterMemo), received.toList())
            assertTrue(directory.resolve("restored-downloads").walkTopDown().any { it.isFile && it.extension == "png" })
        } finally {
            manager.stopAndJoin()
        }
    }

    private class Storage(path: File, create: Boolean) : AutoCloseable {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePath}")
        val database = run {
            if (create) Database.Schema.create(driver)
            Database(driver, historyAdapter = History.Adapter(DateColumnAdapter), mangasAdapter = Mangas.Adapter(StringListColumnAdapter, UpdateStrategyColumnAdapter))
        }
        val handler = JvmDatabaseHandler(database, driver)
        val mangas = MangaRepositoryImpl(handler, NoopCreatorLibraryIndexWriter)
        val chapters = ChapterRepositoryImpl(handler)
        val categories = CategoryRepositoryImpl(handler)
        val history = HistoryRepositoryImpl(handler)
        val details = SaveSourceMangaForDetails(NetworkToLocalManga(mangas), mangas, chapters)
        override fun close() = driver.close()
    }
}
