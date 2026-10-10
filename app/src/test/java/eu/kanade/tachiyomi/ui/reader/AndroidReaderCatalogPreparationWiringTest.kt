package eu.kanade.tachiyomi.ui.reader

import eu.kanade.domain.DomainModule
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.tachiyomi.data.backup.SourceUpdateMemoBackupIntegrationTest
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.chapter.SourceChapterCatalogWriter
import tachiyomi.data.creator.CreatorRepositoryImpl
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.creator.repository.CreatorArchiveBootstrap
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.creator.repository.ReadyCreatorArchiveBootstrap
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.reader.interactor.ReaderCatalogPreparation
import tachiyomi.domain.reader.model.ReaderChapterIdentity
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidReaderCatalogPreparationWiringTest {
    @Test
    fun `reader rejects a source manager returning another source identity`() = rejectIdentity(999, "/identity")

    @Test
    fun `reader rejects another manga URL returned by selected source`() = rejectIdentity(73, "/wrong-work")

    private fun rejectIdentity(returnedSourceId: Long, returnedMangaUrl: String) = runBlocking {
        val directory = Files.createTempDirectory("android-reader-identity-").toFile()
        try {
            SourceUpdateMemoBackupIntegrationTest.Storage(directory.resolve("reader.sqlite"), true).use { storage ->
                val manga = storage.mangas.insertNetworkManga(
                    listOf(Manga.create().copy(source = 73, url = "/identity", title = "Identity")),
                ).single()
                val chapter = storage.chapters.addAll(
                    listOf(Chapter.create().copy(mangaId = manga.id, url = "/1", name = "One")),
                ).single()
                val source = mockk<Source> {
                    every { id } returns returnedSourceId
                    coEvery { getMangaUpdate(any(), any(), false, true) } coAnswers {
                        SMangaUpdate(
                            firstArg<SManga>().apply { url = returnedMangaUrl },
                            listOf(
                                SChapter.create().apply {
                                    url = "/1"
                                    name = "Changed One"
                                },
                            ),
                        )
                    }
                }
                val preparation = AndroidReaderCatalogPreparation(
                    storage.mangas,
                    storage.chapters,
                    mockk { every { get(73) } returns source },
                    SourceChapterCatalogWriter(
                        storage.chapters,
                        CreatorRepositoryImpl(storage.handler),
                        storage.handler,
                    ),
                )
                assertThrows(IllegalStateException::class.java) {
                    runBlocking {
                        preparation.prepare(ReaderChapterIdentity(manga.id, 73, "/identity", chapter.id, "/1"))
                    }
                }
                assertEquals(listOf(chapter), storage.chapters.getChapterByMangaId(manga.id))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `reader and detail updater share source request and retain original detail sync`() = runBlocking {
        val directory = Files.createTempDirectory("android-reader-detail-flight-").toFile()
        val release = CompletableDeferred<Unit>()
        try {
            SourceUpdateMemoBackupIntegrationTest.Storage(directory.resolve("reader.sqlite"), true).use { storage ->
                val manga = storage.mangas.insertNetworkManga(
                    listOf(Manga.create().copy(source = 72, url = "/flight", title = "Flight")),
                ).single()
                val chapter = storage.chapters.addAll(
                    listOf(Chapter.create().copy(mangaId = manga.id, url = "/one", name = "One")),
                ).single()
                val entered = CompletableDeferred<Unit>()
                val requests = AtomicInteger()
                val source = mockk<Source> {
                    every { id } returns 72
                    coEvery { getMangaUpdate(any(), any(), false, true) } coAnswers {
                        requests.incrementAndGet()
                        entered.complete(Unit)
                        release.await()
                        SMangaUpdate(
                            firstArg(),
                            listOf(
                                SChapter.create().apply {
                                    url = "/one"
                                    name = "One"
                                },
                            ),
                        )
                    }
                }
                val sources = mockk<SourceManager> { every { get(72) } returns source }
                val writer =
                    SourceChapterCatalogWriter(
                        storage.chapters,
                        CreatorRepositoryImpl(storage.handler),
                        storage.handler,
                    )
                val preparation = AndroidReaderCatalogPreparation(storage.mangas, storage.chapters, sources, writer)
                val reader = async(start = CoroutineStart.UNDISPATCHED) {
                    preparation.prepare(ReaderChapterIdentity(manga.id, 72, "/flight", chapter.id, "/one"))
                }
                try {
                    withTimeout(5_000) { entered.await() }
                    val app = org.robolectric.RuntimeEnvironment.getApplication()
                    val preferences = LibraryPreferences(
                        tachiyomi.core.common.preference.AndroidPreferenceStore(
                            app,
                            app.getSharedPreferences(
                                "reader-detail-flight-${System.nanoTime()}",
                                android.content.Context.MODE_PRIVATE,
                            ),
                        ),
                    )
                    val downloads = mockk<eu.kanade.tachiyomi.data.download.DownloadManager>(relaxed = true)
                    coEvery {
                        downloads.withDirectoryChanges(
                            any(),
                            any<suspend () -> tachiyomi.domain.chapter.service.ChapterDirectoryResult>(),
                        )
                    } coAnswers { secondArg<suspend () -> tachiyomi.domain.chapter.service.ChapterDirectoryResult>()() }
                    // Pre-read the actual phase and chapters so the second waiter's first suspension
                    // joins the held network request. Subsequent phase reads still execute real SQL.
                    val initialPhase = storage.chapters.pendingDirectoryPhase(manga.id)
                    assertEquals(null, initialPhase)
                    val localChapters = storage.chapters.getChapterByMangaId(manga.id)
                    var firstPhaseRead = true
                    val commits = mutableListOf<tachiyomi.domain.chapter.service.ChapterDirectoryCommit>()
                    val detailChapterReader = object : ChapterRepository by storage.chapters {
                        override suspend fun pendingDirectoryPhase(
                            mangaId: Long,
                        ): tachiyomi.domain.chapter.service.ChapterDirectoryPhase? {
                            assertEquals(manga.id, mangaId)
                            if (firstPhaseRead) {
                                firstPhaseRead = false
                                return initialPhase
                            }
                            return storage.chapters.pendingDirectoryPhase(mangaId)
                        }

                        override suspend fun getChapterByMangaId(
                            mangaId: Long,
                            applyScanlatorFilter: Boolean,
                        ): List<Chapter> {
                            assertEquals(manga.id, mangaId)
                            return localChapters
                        }

                        override suspend fun syncDirectory(
                            request: tachiyomi.domain.chapter.service.ChapterDirectoryCommit,
                        ): tachiyomi.domain.chapter.service.ChapterDirectoryResult {
                            commits += request
                            return storage.chapters.syncDirectory(request)
                        }
                    }
                    val sync = SyncChaptersWithSource(
                        downloads, mockk(relaxed = true), detailChapterReader,
                        ShouldUpdateDbChapter(), storage.updateManga,
                        UpdateChapter(storage.chapters), storage.getChapters,
                        GetExcludedScanlators(storage.handler), preferences,
                    )
                    val detail = async(start = CoroutineStart.UNDISPATCHED) {
                        storage.updateManga.awaitFromRemote(
                            manga, source, false, true,
                            chapterRepository = detailChapterReader, syncChaptersWithSource = sync,
                            coverCache = mockk(
                                relaxed = true,
                            ),
                            libraryPreferences = preferences, downloadManager = downloads,
                        )
                    }
                    release.complete(Unit)
                    detail.await()
                    assertEquals(1, reader.await()?.size)
                    val committed = commits.single()
                    assertEquals(manga.id, committed.mangaId)
                    assertEquals(manga.id, committed.mangaMetadata?.id)
                    assertTrue(committed.complete)
                    assertEquals(72L, committed.effects?.sourceId)
                    assertEquals(manga.url, committed.effects?.mangaUrl)
                    assertEquals(listOf("/one"), committed.source.map { it.chapter.url })
                    assertEquals(null, storage.chapters.pendingDirectoryPhase(manga.id))
                    assertEquals(1, requests.get())
                } finally {
                    withContext(NonCancellable) {
                        release.complete(Unit)
                        reader.cancelAndJoin()
                    }
                }
            }
        } finally {
            release.complete(Unit)
            directory.deleteRecursively()
        }
    }

    @Test
    fun `Android domain module resolves actual reader catalog port and shared file writer`() = runBlocking {
        val previous = Injekt
        val directory = Files.createTempDirectory("android-reader-catalog-di-").toFile()
        try {
            SourceUpdateMemoBackupIntegrationTest.Storage(directory.resolve("reader.sqlite"), true).use { storage ->
                Injekt = InjektScope(DefaultRegistrar())
                Injekt.importModule(DomainModule())
                Injekt.addSingleton<DatabaseHandler>(storage.handler)
                Injekt.addSingleton<MangaRepository>(storage.mangas)
                Injekt.addSingleton<ChapterRepository>(storage.chapters)
                Injekt.addSingleton<CreatorArchiveRepository>(CreatorRepositoryImpl(storage.handler))
                Injekt.addSingleton<CreatorArchiveBootstrap>(ReadyCreatorArchiveBootstrap)
                Injekt.addSingleton<SourceManager>(mockk(relaxed = true))
                Injekt.addSingleton<ExtensionManager>(mockk(relaxed = true))
                assertTrue(Injekt.get<ReaderCatalogPreparation>() is AndroidReaderCatalogPreparation)
                assertNotNull(Injekt.get<SourceChapterCatalogWriter>())
            }
        } finally {
            Injekt = previous
            directory.deleteRecursively()
        }
    }

    @Test
    fun `Android file repository preserves catalog and complete catalog stops fetching`() = runBlocking {
        val directory = Files.createTempDirectory("android-reader-catalog-").toFile()
        try {
            SourceUpdateMemoBackupIntegrationTest.Storage(directory.resolve("reader.sqlite"), true).use { storage ->
                val manga = storage.mangas.insertNetworkManga(
                    listOf(Manga.create().copy(source = 71, url = "/work", title = "Work")),
                ).single()
                val chapter = storage.chapters.addAll(
                    listOf(Chapter.create().copy(mangaId = manga.id, url = "/2", name = "Two", chapterNumber = 2.0)),
                ).single()
                storage.chapters.update(ChapterUpdate(chapter.id, bookmark = true, lastPageRead = 7, dateFetch = 9876))
                val before = storage.chapters.getChapterById(chapter.id)!!
                var requests = 0
                val source = mockk<Source> {
                    every { id } returns 71
                    coEvery { getMangaUpdate(any(), any(), false, true) } coAnswers {
                        requests++
                        SMangaUpdate(
                            firstArg(),
                            listOf(3, 2, 1).map { number ->
                                SChapter.create().apply {
                                    url = "/$number"
                                    name = "Chapter $number"
                                    chapter_number = number.toFloat()
                                }
                            },
                        )
                    }
                }
                val sources = mockk<SourceManager> { every { get(71) } returns source }
                val writer =
                    SourceChapterCatalogWriter(
                        storage.chapters,
                        CreatorRepositoryImpl(storage.handler),
                        storage.handler,
                    )
                val preparation = AndroidReaderCatalogPreparation(storage.mangas, storage.chapters, sources, writer)
                val target = ReaderChapterIdentity(manga.id, 71, "/work", chapter.id, "/2")
                assertEquals(3, preparation.prepare(target)?.size)
                val after = storage.chapters.getChapterById(chapter.id)!!
                assertEquals(before.id, after.id)
                assertEquals(before.read, after.read)
                assertEquals(before.bookmark, after.bookmark)
                assertEquals(before.lastPageRead, after.lastPageRead)
                assertEquals(before.dateFetch, after.dateFetch)
                assertFalse(writer.needsRefresh(manga))
                assertEquals(3, preparation.prepare(target)?.size)
                assertEquals(1, requests)
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
