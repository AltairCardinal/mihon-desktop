package eu.kanade.tachiyomi.data.backup

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadNotifier
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.publishLoadedPageListForTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import nl.adaptivity.xmlutil.serialization.XML
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.nio.file.Files
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SourceUpdateMemoPageConsumersIntegrationTest {

    @Test
    fun `Android directory rename preserves bytes and refuses collision with fixed ASCII names`() {
        val root = Files.createTempDirectory("directory-android-file").toFile()
        try {
            val source = object : eu.kanade.tachiyomi.source.Source {
                override val id = 42L
                override val name = "Directory source"
                override fun toString() = name
            }
            val context = RuntimeEnvironment.getApplication()
            val preferences =
                LibraryPreferences(
                    tachiyomi.core.common.preference.AndroidPreferenceStore(
                        context,
                        context.getSharedPreferences(root.name, android.content.Context.MODE_PRIVATE),
                    ),
                )
            preferences.disallowNonAsciiFilenames().set(true)
            assertTrue(preferences.disallowNonAsciiFilenames().get())
            val provider = DownloadProvider(
                RuntimeEnvironment.getApplication(),
                mockk { every { getDownloadsDirectory() } returns UniFile.fromFile(root) },
                preferences,
            )
            val before = tachiyomi.domain.chapter.service.DirectoryFileChapter(1, "/old", "旧章 2", "组")
            val after = before.copy(url = "/new", name = "新章 2")
            val effects = tachiyomi.domain.chapter.service.ChapterDirectoryEffects(
                42,
                source.toString(),
                "/work",
                "旧作品",
                "ANDROID_DIRECT",
                1000,
                disallowNonAsciiFilenames = true,
            )
            val phase = tachiyomi.domain.chapter.service.ChapterDirectoryPhase(
                "phase", 1, effects, "新作品",
                listOf(
                    tachiyomi.domain.chapter.service.DirectoryFileChange(before, after),
                ),
                emptyList(), emptyList(), false, false,
            )
            val oldManga = provider.getMangaDir(effects.mangaTitle, source).getOrThrow()
            val oldName = provider.getChapterDirName(before.name, before.scanlator, before.url)
            val oldChapter = oldManga.createDirectory(oldName)!!
            oldChapter.createFile("001.png")!!.openOutputStream()!!.use { it.write(byteArrayOf(1, 2, 3)) }
            // The whole manga title target belongs to another real directory.
            val occupied = provider.getMangaDir(phase.currentTitle, source).getOrThrow()
            occupied.createFile("other.png")!!.openOutputStream()!!.use { it.write(byteArrayOf(9)) }
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                provider.renameDirectoryChapter(source, phase, phase.files.single())
            }
            assertTrue(
                oldChapter.findFile("001.png")!!.openInputStream()!!.use {
                    it.readBytes()
                }.contentEquals(byteArrayOf(1, 2, 3)),
            )
            assertTrue(
                occupied.findFile("other.png")!!.openInputStream()!!.use {
                    it.readBytes()
                }.contentEquals(byteArrayOf(9)),
            )
            occupied.delete()
            provider.renameDirectoryChapter(source, phase, phase.files.single())
            // Retry after file success / acknowledgement failure must keep the same artifact.
            provider.renameDirectoryChapter(source, phase, phase.files.single())
            val resolved = provider.findChapterDir(after.name, after.scanlator, after.url, phase.currentTitle, source)
            assertTrue(resolved != null)
            assertTrue(
                resolved!!.findFile("001.png")!!.openInputStream()!!.use {
                    it.readBytes()
                }.contentEquals(byteArrayOf(1, 2, 3)),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `restored reopened memo reaches Android reader and downloader page and image production paths`() = runBlocking {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val directory = Files.createTempDirectory("android-memo-pages-").toFile()
        val context = RuntimeEnvironment.getApplication()
        val memo = Json.parseToJsonElement("""{"nested":[null,true,{"token":"中文"}]}""").jsonObject
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        mockkConstructor(DownloadNotifier::class)
        every { anyConstructed<DownloadNotifier>().onProgressChange(any()) } returns Unit
        every { anyConstructed<DownloadNotifier>().onComplete() } returns Unit
        every { anyConstructed<DownloadNotifier>().dismissProgress() } returns Unit
        every { anyConstructed<DownloadNotifier>().onError(any(), any(), any(), any()) } answers {
            errors += firstArg<String?>().orEmpty()
            Unit
        }
        every { anyConstructed<DownloadNotifier>().onError(any(), null, null, null) } answers {
            errors += firstArg<String?>().orEmpty()
            Unit
        }
        io.mockk.mockkObject(eu.kanade.tachiyomi.data.download.DownloadJob.Companion)
        every { eu.kanade.tachiyomi.data.download.DownloadJob.stop(any()) } returns Unit
        try {
            var updateCalls = 0
            val updatingSource: eu.kanade.tachiyomi.source.Source = object : eu.kanade.tachiyomi.source.Source {
                override val id = 42L
                override val name = "Update then backup"
                override suspend fun getMangaUpdate(
                    manga: eu.kanade.tachiyomi.source.model.SManga,
                    chapters: List<eu.kanade.tachiyomi.source.model.SChapter>,
                    fetchDetails: Boolean,
                    fetchChapters: Boolean,
                ): eu.kanade.tachiyomi.source.model.SMangaUpdate {
                    if (updateCalls++ > 0) {
                        assertEquals(memo, manga.memo)
                        assertEquals(memo, chapters.single().memo)
                    }
                    manga.memo = memo
                    chapters.single().memo = memo
                    return eu.kanade.tachiyomi.source.model.SMangaUpdate(manga, chapters)
                }
            }
            suspend fun update(storage: SourceUpdateMemoBackupIntegrationTest.Storage, manga: Manga) {
                val preferences = mockk<LibraryPreferences> {
                    every { markDuplicateReadChapterAsRead().get() } returns emptySet()
                    every { updateMangaTitles().get() } returns false
                    every { disallowNonAsciiFilenames().get() } returns false
                }
                val downloads = mockk<eu.kanade.tachiyomi.data.download.DownloadManager>(relaxed = true) {
                    coEvery { withDirectoryChanges<Any?>(any(), any()) } coAnswers
                        { secondArg<suspend () -> Any?>().invoke() }
                }
                val sync = eu.kanade.domain.chapter.interactor.SyncChaptersWithSource(
                    downloads, mockk(relaxed = true), storage.chapters,
                    tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter(), storage.updateManga,
                    tachiyomi.domain.chapter.interactor.UpdateChapter(storage.chapters), storage.getChapters,
                    eu.kanade.domain.manga.interactor.GetExcludedScanlators(storage.handler), preferences,
                )
                storage.updateManga.awaitFromRemote(
                    manga, updatingSource, true, true,
                    chapterRepository = storage.chapters, syncChaptersWithSource = sync,
                    coverCache = mockk(
                        relaxed = true,
                    ),
                    libraryPreferences = preferences, downloadManager = downloads,
                )
            }
            val original = directory.resolve("original.db")
            SourceUpdateMemoBackupIntegrationTest.Storage(original, true).use { storage ->
                val manga = storage.mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = 42,
                            url = "/manga",
                            title = "Memo manga",
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
                update(storage, manga)
            }
            val backup = SourceUpdateMemoBackupIntegrationTest.Storage(original, false).use { storage ->
                val manga = storage.mangas.getFavorites().single()
                update(storage, manga)
                assertEquals(2, updateCalls)
                Backup(
                    MangaBackupCreator(storage.handler, storage.getCategories, GetHistory(storage.history))(
                        listOf(manga),
                        BackupOptions(),
                    ),
                )
            }
            val restored = directory.resolve("restored.db")
            SourceUpdateMemoBackupIntegrationTest.Storage(restored, true).use { storage ->
                storage.restorer.restore(backup.backupManga.single(), emptyList())
            }
            SourceUpdateMemoBackupIntegrationTest.Storage(restored, false).use { storage ->
                val manga = storage.mangas.getFavorites().single()
                val chapter = storage.chapters.getChapterByMangaId(manga.id).single()
                val received = java.util.concurrent.CopyOnWriteArrayList<kotlinx.serialization.json.JsonObject>()
                val imageCalls = java.util.concurrent.atomic.AtomicInteger()
                val source = mockk<HttpSource> {
                    every { id } returns 42L
                    every { name } returns "Memo source"
                    every { lang } returns "en"
                    every { mangaDetailsRequest(any()) } returns
                        Request.Builder().url("https://memo.example/manga").build()
                    every { getChapterUrl(any()) } returns "https://memo.example/chapter"
                    coEvery { getPageList(any()) } answers {
                        received += firstArg<eu.kanade.tachiyomi.source.model.SChapter>().memo
                        listOf(Page(0, imageUrl = "https://memo.example/${received.size}.png"))
                    }
                    coEvery { getImage(any()) } answers {
                        imageCalls.incrementAndGet()
                        Response.Builder().request(Request.Builder().url("https://memo.example/page.png").build())
                            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body(Base64.getDecoder().decode(PNG).toResponseBody("image/png".toMediaType())).build()
                    }
                }
                val cacheContext = mockk<android.content.Context> {
                    every { cacheDir } returns directory.resolve("cache").apply { mkdirs() }
                }
                val cache = ChapterCache(cacheContext, Json)
                val readerChapter = ReaderChapter(chapter)
                assertEquals(
                    "legacy reader progress mapping retains memo",
                    memo,
                    readerChapter.chapter.toDomainChapter()!!.memo,
                )
                val loader = HttpPageLoader(readerChapter, source, cache)
                try {
                    val pages = loader.getPages()
                    pages.forEach { it.chapter = readerChapter }
                    readerChapter.publishLoadedPageListForTest(pages)
                    loader.onPageSelected(pages.single())
                    withTimeout(10_000) {
                        while (pages.single().status != Page.State.Ready) {
                            assertTrue(
                                "Reader image failed: ${pages.single().status}",
                                pages.single().status !is Page.State.Error,
                            )
                            delay(10)
                        }
                    }
                    assertEquals(Page.State.Ready, pages.single().status)
                } finally {
                    loader.recycle()
                }
                val sourceManager = mockk<SourceManager> { every { get(42) } returns source }
                Injekt.addSingleton(sourceManager)
                Injekt.addSingleton<Json>(Json)
                Injekt.addSingleton(GetManga(storage.mangas))
                Injekt.addSingleton(GetChapter(storage.chapters))
                val preferences = mockk<LibraryPreferences> {
                    every { disallowNonAsciiFilenames().get() } returns false
                }
                val downloads = directory.resolve("downloads").apply { mkdirs() }
                val provider = DownloadProvider(
                    context,
                    mockk {
                        every { getDownloadsDirectory() } returns UniFile.fromFile(downloads)
                    },
                    preferences,
                )
                val mangaDirectory = provider.getMangaDir(manga.title, source).getOrThrow()
                org.robolectric.shadows.ShadowStatFs.registerStats(
                    mangaDirectory.uri.path!!,
                    1_000_000,
                    1_000_000,
                    1_000_000,
                )
                assertTrue(
                    eu.kanade.tachiyomi.util.storage.DiskUtil.getAvailableStorageSpace(mangaDirectory) >
                        200L * 1024 * 1024,
                )
                val downloadPreferences = mockk<DownloadPreferences> {
                    every { parallelSourceLimit().changes() } returns kotlinx.coroutines.flow.flowOf(1)
                    every { parallelPageLimit().get() } returns 1
                    every { saveChaptersAsCBZ().get() } returns false
                    every { splitTallImages().get() } returns false
                }
                val downloader = Downloader(
                    context, provider, mockk<DownloadCache>(relaxed = true), sourceManager,
                    cache, downloadPreferences, XML {}, storage.getCategories, GetTracks(storage.tracks),
                )
                try {
                    downloader.queueChapters(manga, listOf(chapter), autoStart = false)
                    assertTrue(downloader.start())
                    withTimeout(15_000) {
                        while (downloader.queueState.value.isNotEmpty()) {
                            assertTrue(
                                "Downloader errors: $errors; " +
                                    "files: ${downloads.walkTopDown().map { it.name }.toList()}; " +
                                    "pages: ${downloader.queueState.value.flatMap {
                                        it.pages.orEmpty()
                                    }.map { it.status }}",
                                downloader.queueState.value.none { it.status == Download.State.ERROR },
                            )
                            delay(20)
                        }
                    }
                    assertEquals(listOf(memo, memo), received)
                    assertEquals(2, imageCalls.get())
                    assertTrue(downloads.walkTopDown().any { it.isFile && it.extension == "png" })
                    assertEquals(7L, storage.chapters.getChapterByMangaId(manga.id).single().lastPageRead)
                } finally {
                    downloader.pause()
                }
            }
        } finally {
            unmockkConstructor(DownloadNotifier::class)
            io.mockk.unmockkObject(eu.kanade.tachiyomi.data.download.DownloadJob.Companion)
            Injekt = previous
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aF9sAAAAASUVORK5CYII="
    }
}
