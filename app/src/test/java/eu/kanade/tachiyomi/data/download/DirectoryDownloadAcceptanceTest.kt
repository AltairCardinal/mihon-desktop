package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.tachiyomi.data.backup.SourceUpdateMemoBackupIntegrationTest
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import nl.adaptivity.xmlutil.serialization.XML
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterDirectoryCommit
import tachiyomi.domain.chapter.service.ChapterDirectoryDownloadConflictException
import tachiyomi.domain.chapter.service.ChapterDirectoryEffects
import tachiyomi.domain.chapter.service.ChapterDirectoryPhase
import tachiyomi.domain.chapter.service.ChapterDirectoryPlan
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DirectoryDownloadAcceptanceTest {
    @Test
    fun `actual queue reorder cannot insert a reserved directory identity`() = runBlocking {
        fixture { manager, _, _, _, _, _ ->
            val source = mockk<HttpSource>(relaxed = true) { every { id } returns 42L }
            val manga = Manga.create().copy(id = 1, source = 42, title = "Work")
            val chapter = Chapter.create().copy(id = 2, mangaId = 1, url = "/chapter", name = "Chapter")
            val download = Download(source, manga, chapter)
            manager.withDirectoryChanges(setOf(2)) {
                assertThrows(IllegalStateException::class.java) { manager.reorderQueue(listOf(download)) }
                assertTrue(manager.queueState.value.isEmpty())
            }
        }
    }

    @Test
    fun `actual directory phase never acknowledges a missing HTTP source`() = runBlocking {
        fixture(missingSource = true) { manager, provider, storage, manga, phase, _ ->
            val source = object : Source {
                override val id = 42L
                override val name = "Directory"
            }
            runCatching { wrapper(manager, provider, storage).finishPhase(manga, source, phase) { } }
            assertTrue(
                "A Unit no-op is not accepted directory download work",
                storage.chapters.pendingDirectoryPhase(manga.id) != null,
            )
            assertTrue(manager.queueState.value.isEmpty())
        }
    }

    @Test
    fun `actual directory phase keeps pending work when synchronous download storage rejects it`() = runBlocking {
        fixture(rejectCommit = true) { manager, provider, storage, manga, phase, _ ->
            val source = mockk<HttpSource>(relaxed = true) { every { id } returns 42L }
            runCatching { wrapper(manager, provider, storage).finishPhase(manga, source, phase) { } }
            assertTrue(
                "The SQL stage cannot be acknowledged before durable queue acceptance",
                storage.chapters.pendingDirectoryPhase(manga.id) != null,
            )
            assertTrue("Rejected persistence must not publish a queue item", manager.queueState.value.isEmpty())
        }
    }

    @Test
    fun `confirmed directory queue survives reopening and matching retry retains state and order`() = runBlocking {
        fixture { manager, provider, storage, manga, phase, reopen ->
            val source = mockk<HttpSource>(relaxed = true) { every { id } returns 42L }
            wrapper(manager, provider, storage).finishPhase(manga, source, phase) { }
            assertEquals(null, storage.chapters.pendingDirectoryPhase(manga.id))
            val accepted = manager.queueState.value.single()
            accepted.status = Download.State.ERROR
            assertTrue(manager.downloadDirectoryChapters(manga, listOf(accepted.chapter), false))
            assertSame(accepted, manager.queueState.value.single())
            assertEquals(Download.State.ERROR, accepted.status)
            val restored = reopen()
            withTimeout(5000) {
                while (restored.queueState.value.isEmpty()) delay(10)
            }
            assertEquals(listOf(accepted.chapter.id), restored.queueState.value.map { it.chapter.id })
            assertTrue(restored.downloadDirectoryChapters(manga, listOf(accepted.chapter), false))
            assertEquals(1, restored.queueState.value.size)
            var entered = false
            try {
                restored.withDirectoryChanges(setOf(accepted.chapter.id)) { entered = true }
                fail("Restored accepted work must guard the first directory transaction")
            } catch (_: ChapterDirectoryDownloadConflictException) { }
            assertFalse(entered)
        }
    }

    @Test
    fun `failed original queue initialization cannot release a directory transaction`() = runBlocking {
        fixture(rejectRestore = true) { manager, _, _, _, _, _ ->
            var entered = false
            runCatching { manager.withDirectoryChanges(setOf(2)) { entered = true } }
            assertFalse("A failed launch joined successfully must not be treated as an empty accepted queue", entered)
        }
    }

    @Test
    fun `existing downloaded directory satisfies original work without accepting a duplicate queue item`() =
        runBlocking {
            fixture(realFiles = true) { manager, provider, storage, manga, phase, _ ->
                val source = Injekt.get<SourceManager>().get(manga.source)!!
                val chapter = storage.chapters.getChapterById(phase.downloadIds.single())!!
                val directory = provider.getMangaDir(manga.title, source).getOrThrow()
                    .createDirectory(provider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url))!!
                val page = directory.createFile("001.png")!!
                page.openOutputStream().use { it.write(byteArrayOf(1, 2, 3)) }
                wrapper(manager, provider, storage).finishPhase(manga, source, phase) { }
                assertEquals(null, storage.chapters.pendingDirectoryPhase(manga.id))
                assertTrue(manager.queueState.value.isEmpty())
                assertTrue(page.openInputStream().use { it.readBytes() }.contentEquals(byteArrayOf(1, 2, 3)))
            }
        }

    private fun wrapper(
        manager: DownloadManager,
        provider: DownloadProvider,
        storage: SourceUpdateMemoBackupIntegrationTest.Storage,
    ): SyncChaptersWithSource =
        SyncChaptersWithSource(
            manager, provider, storage.chapters, ShouldUpdateDbChapter(), storage.updateManga,
            UpdateChapter(storage.chapters), storage.getChapters, GetExcludedScanlators(storage.handler),
            LibraryPreferences(tachiyomi.core.common.preference.InMemoryPreferenceStore()),
        )

    private suspend fun fixture(
        missingSource: Boolean = false,
        rejectCommit: Boolean = false,
        rejectRestore: Boolean = false,
        realFiles: Boolean = false,
        test: suspend (
            DownloadManager,
            DownloadProvider,
            SourceUpdateMemoBackupIntegrationTest.Storage,
            Manga,
            ChapterDirectoryPhase,
            () -> DownloadManager,
        ) -> Unit,
    ) {
        val previous = Injekt
        Injekt = InjektScope(DefaultRegistrar())
        val directory = Files.createTempDirectory("android-directory-queue").toFile()
        val base = RuntimeEnvironment.getApplication()
        val realPreferences = base.getSharedPreferences("directory-" + directory.name, Context.MODE_PRIVATE)
        val preferences = object : SharedPreferences by realPreferences {
            override fun getAll(): MutableMap<String, *> {
                if (rejectRestore) throw java.io.IOException("Initial queue storage read refused")
                return realPreferences.all
            }
            override fun edit(): SharedPreferences.Editor {
                val editor = realPreferences.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun commit(): Boolean = if (rejectCommit) false else editor.commit()
                }
            }
        }
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "active_downloads") preferences else super.getSharedPreferences(name, mode)
        }
        try {
            SourceUpdateMemoBackupIntegrationTest.Storage(directory.resolve("queue.db"), true).use { storage ->
                val manga = storage.mangas.insertNetworkManga(
                    listOf(Manga.create().copy(source = 42, url = "/work", title = "Work", favorite = true)),
                ).single()
                val source = mockk<HttpSource>(relaxed = true) { every { id } returns 42L }
                val sourceManager = mockk<SourceManager>(relaxed = true) {
                    every { get(42L) } returns
                        if (missingSource) null else source
                }
                val provider = if (realFiles) {
                    val root = directory.resolve("downloads").apply { mkdirs() }
                    DownloadProvider(
                        context,
                        mockk {
                            every { getDownloadsDirectory() } returns com.hippo.unifile.UniFile.fromFile(root)
                        },
                        LibraryPreferences(
                            tachiyomi.core.common.preference.AndroidPreferenceStore(
                                context,
                                base.getSharedPreferences(directory.name + "-files", Context.MODE_PRIVATE),
                            ),
                        ),
                    )
                } else {
                    mockk<DownloadProvider>(relaxed = true) {
                        every { findChapterDir(any(), any(), any(), any(), any()) } returns null
                    }
                }
                val preferences = mockk<DownloadPreferences>(relaxed = true)
                val categories = mockk<GetCategories>(relaxed = true)
                Injekt.addSingleton<SourceManager>(sourceManager)
                Injekt.addSingleton<ChapterCache>(mockk(relaxed = true))
                Injekt.addSingleton<DownloadPreferences>(preferences)
                Injekt.addSingleton<GetCategories>(categories)
                Injekt.addSingleton<GetTracks>(mockk(relaxed = true))
                Injekt.addSingleton<GetManga>(GetManga(storage.mangas))
                Injekt.addSingleton<GetChapter>(GetChapter(storage.chapters))
                Injekt.addSingleton<Json>(Json)
                Injekt.addSingleton<XML>(XML {})
                fun manager() = DownloadManager(
                    context,
                    provider,
                    mockk(relaxed = true),
                    categories,
                    sourceManager,
                    preferences,
                )
                val manager = manager()
                val remote = eu.kanade.tachiyomi.source.model.SChapter.create().apply {
                    url = "/new"
                    name = "Chapter 1"
                    chapter_number =
                        1f
                }
                val result = storage.chapters.syncDirectory(
                    ChapterDirectoryCommit(
                        manga.id,
                        ChapterDirectoryPlan.prepare(manga, listOf(remote)),
                        1000,
                        effects = ChapterDirectoryEffects(
                            42,
                            "Directory",
                            manga.url,
                            manga.title,
                            "LIBRARY_UPDATE",
                            1000,
                            downloadEnabled = true,
                        ),
                    ),
                )
                val phase = requireNotNull(result.phase)
                assertEquals(1, phase.downloadIds.size)
                test(manager, provider, storage, manga, phase, ::manager)
            }
        } finally {
            Injekt = previous
            directory.deleteRecursively()
        }
    }
}
