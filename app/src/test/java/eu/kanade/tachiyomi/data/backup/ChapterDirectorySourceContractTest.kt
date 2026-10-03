package eu.kanade.tachiyomi.data.backup

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import java.io.File

class ChapterDirectorySourceContractTest {
    @TempDir lateinit var directory: File

    private val source = object : Source {
        override val id = 42L
        override val name = "Directory"
    }

    @Test
    fun `actual Android wrapper preserves original rename work across post commit refusal and restart`() =
        runBlocking<Unit> {
            val path = directory.resolve("rename-recovery.db")
            val manager = mockk<eu.kanade.tachiyomi.data.download.DownloadManager>(relaxed = true)
            val provider = mockk<eu.kanade.tachiyomi.data.download.DownloadProvider>(relaxed = true)
            every { provider.isChapterDirNameChanged(any(), any()) } returns true
            every { manager.isChapterDownloaded(any(), any(), any(), any(), any()) } returns true
            every { manager.queueState } returns kotlinx.coroutines.flow.MutableStateFlow(emptyList())
            coEvery { manager.withDirectoryChanges<Any?>(any(), any()) } coAnswers
                { secondArg<suspend () -> Any?>().invoke() }
            coEvery { manager.renameDirectoryChapter(any(), any(), any(), any()) } throws
                java.io.IOException("Rename denied")
            var mangaId = 0L
            fun adapter(s: SourceUpdateMemoBackupIntegrationTest.Storage): SyncChaptersWithSource {
                val prefs = mockk<LibraryPreferences>()
                val p = mockk<Preference<Set<String>>>()
                every { p.get() } returns emptySet()
                every { prefs.markDuplicateReadChapterAsRead() } returns p
                every { prefs.disallowNonAsciiFilenames().get() } returns false
                return SyncChaptersWithSource(
                    manager, provider, s.chapters, ShouldUpdateDbChapter(), s.updateManga,
                    UpdateChapter(s.chapters), s.getChapters, GetExcludedScanlators(s.handler), prefs,
                )
            }
            SourceUpdateMemoBackupIntegrationTest.Storage(path, true).use { s ->
                val manga = s.mangas.insertNetworkManga(
                    listOf(Manga.create().copy(source = 42, url = "/work", title = "Work")),
                ).single()
                mangaId = manga.id
                val original = s.chapters.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = "/old",
                            name = "Chapter 2",
                            chapterNumber = 2.0,
                        ),
                    ),
                ).single()
                assertThrows(java.io.IOException::class.java) {
                    runBlocking {
                        adapter(s).await(listOf(chapter("/new", "Chapter 2 revised")), manga, source)
                    }
                }
                assertEquals(original.id, s.chapters.getChapterByMangaId(manga.id).single().id)
                assertTrue(
                    s.chapters.pendingDirectoryPhase(manga.id) != null,
                    "A committed directory must retain the original file identity when the platform rename fails",
                )
            }
            coEvery { manager.renameDirectoryChapter(any(), any(), any(), any()) } returns Unit
            SourceUpdateMemoBackupIntegrationTest.Storage(path, false).use { s ->
                adapter(s).await(emptyList(), s.mangas.getMangaById(mangaId), source)
                assertEquals(null, s.chapters.pendingDirectoryPhase(mangaId))
                io.mockk.coVerify(exactly = 2) {
                    manager.renameDirectoryChapter(
                        source,
                        any(),
                        any(),
                        match {
                            it.before.url ==
                                "/old" &&
                                it.after.url == "/new"
                        },
                    )
                }
            }
        }

    private fun chapter(url: String, name: String, upload: Long = 0, scanlator: String? = null) =
        SChapter.create().apply {
            this.url = url
            this.name = name
            date_upload = upload
            this.scanlator = scanlator
        }

    private fun sync(
        storage: SourceUpdateMemoBackupIntegrationTest.Storage,
        duplicate: Boolean = false,
    ): SyncChaptersWithSource {
        val preferences = mockk<LibraryPreferences>()
        val duplicatePreference = mockk<Preference<Set<String>>>()
        every { duplicatePreference.get() } returns
            if (duplicate) setOf(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_NEW) else emptySet()
        every { preferences.markDuplicateReadChapterAsRead() } returns duplicatePreference
        every { preferences.disallowNonAsciiFilenames().get() } returns false
        return SyncChaptersWithSource(
            mockk<eu.kanade.tachiyomi.data.download.DownloadManager>(relaxed = true) {
                coEvery { withDirectoryChanges<Any?>(any(), any()) } coAnswers
                    { secondArg<suspend () -> Any?>().invoke() }
            },
            mockk(relaxed = true), storage.chapters,
            ShouldUpdateDbChapter(), storage.updateManga, UpdateChapter(storage.chapters), storage.getChapters,
            GetExcludedScanlators(storage.handler), preferences,
        )
    }

    @Test
    fun `actual Android wrapper deduplicates sanitizes recognizes and preserves upload and fetch order`() =
        runBlocking<Unit> {
            SourceUpdateMemoBackupIntegrationTest.Storage(directory.resolve("source.db"), true).use { storage ->
                val manga = storage.mangas.insertNetworkManga(
                    listOf(Manga.create().copy(source = 42, url = "/work", title = "Work", favorite = true)),
                ).single()
                val added = sync(storage).await(
                    listOf(
                        chapter("/3", " Work : Chapter 3 ", 500),
                        chapter("/3", "duplicate", 999),
                        chapter("/2", "Work_Chapter 2"),
                        chapter("/1", "Chapter 1", 100),
                    ),
                    manga,
                    source,
                )
                val rows = storage.chapters.getChapterByMangaId(manga.id).sortedBy { it.sourceOrder }
                assertEquals(listOf("/3", "/2", "/1"), rows.map { it.url })
                assertEquals(listOf("Chapter 3", "Chapter 2", "Chapter 1"), rows.map { it.name })
                assertEquals(listOf(3.0, 2.0, 1.0), rows.map { it.chapterNumber })
                assertEquals(listOf(500L, 500L, 100L), rows.map { it.dateUpload })
                assertTrue(rows.zipWithNext().all { (a, b) -> a.dateFetch > b.dateFetch })
                assertEquals(rows.map { it.id }.toSet(), added.map { it.id }.toSet())
            }
        }

    @Test
    fun `actual Android wrapper inherits removed number state and suppresses duplicate read additions`() =
        runBlocking<Unit> {
            SourceUpdateMemoBackupIntegrationTest.Storage(directory.resolve("inherit.db"), true).use { storage ->
                val manga = storage.mangas.insertNetworkManga(
                    listOf(Manga.create().copy(source = 42, url = "/work", title = "Work", favorite = true)),
                ).single()
                storage.chapters.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = "/old",
                            name = "Chapter 2",
                            chapterNumber = 2.0,
                            read = true,
                            bookmark = true,
                            dateFetch = 123,
                        ),
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = "/known",
                            name = "Chapter 1",
                            chapterNumber = 1.0,
                            read = true,
                        ),
                    ),
                )
                val added = sync(storage, duplicate = true).await(
                    listOf(
                        chapter("/new", "Chapter 2"),
                        chapter("/duplicate", "Chapter 1"),
                        chapter("/known", "Chapter 1"),
                    ),
                    manga,
                    source,
                )
                val rows = storage.chapters.getChapterByMangaId(manga.id).associateBy { it.url }
                assertEquals(setOf("/new", "/duplicate", "/known"), rows.keys)
                assertEquals(true, rows.getValue("/new").read)
                assertEquals(true, rows.getValue("/new").bookmark)
                assertEquals(123L, rows.getValue("/new").dateFetch)
                assertEquals(true, rows.getValue("/duplicate").read)
                assertTrue(added.isEmpty())
            }
        }

    @Test
    fun `actual Android empty remote response preserves existing directory`() = runBlocking<Unit> {
        SourceUpdateMemoBackupIntegrationTest.Storage(directory.resolve("empty.db"), true).use { storage ->
            val manga = storage.mangas.insertNetworkManga(
                listOf(Manga.create().copy(source = 42, url = "/work", title = "Work", favorite = true)),
            ).single()
            val original = storage.chapters.addAll(
                listOf(Chapter.create().copy(mangaId = manga.id, url = "/1", name = "Chapter 1", read = true)),
            ).single()
            assertThrows(NoChaptersException::class.java) {
                runBlocking { sync(storage).await(emptyList(), manga, source) }
            }
            assertEquals(listOf(original), storage.chapters.getChapterByMangaId(manga.id))
        }
    }
}
