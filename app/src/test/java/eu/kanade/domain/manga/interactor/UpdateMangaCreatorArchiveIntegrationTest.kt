package eu.kanade.domain.manga.interactor

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.creator.model.ChapterCatalogCompleteness
import tachiyomi.domain.creator.model.SourceDateExtensionIdentity
import tachiyomi.domain.creator.model.SourceDateField
import tachiyomi.domain.creator.model.SourceDatePrecision
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], manifest = org.robolectric.annotation.Config.NONE)
class UpdateMangaCreatorArchiveIntegrationTest {
    @Test
    fun `detail updater joins catalog already fetching for reader and retains original chapter sync`() = runBlocking {
        val manga = Manga.create().copy(id = 42, source = 10, url = "/shared-detail", title = "Shared detail")
        val entered = CompletableDeferred<Unit>()
        val detailEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val remote = SManga.create().apply {
            url = manga.url
            title = manga.title
        }
        val remoteChapter = SChapter.create().apply {
            url = "/shared-detail/1"
            name = "Chapter 1"
        }
        val source = mockk<Source> {
            every { id } returns manga.source
            coEvery { getMangaUpdate(any(), any(), any(), any()) } coAnswers {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                SMangaUpdate(remote, listOf(remoteChapter))
            }
        }
        val repository = mockk<MangaRepository>(relaxed = true) {
            coEvery { getMangaById(manga.id) } returns manga
            coEvery { update(any()) } returns true
        }
        val chapters = mockk<ChapterRepository>(relaxed = true) {
            coEvery { getChapterByMangaId(manga.id) } coAnswers {
                detailEntered.complete(Unit)
                emptyList()
            }
        }
        val sync = mockk<eu.kanade.domain.chapter.interactor.SyncChaptersWithSource>(relaxed = true)
        val updater = UpdateManga(repository, FetchInterval(mockk<GetChaptersByMangaId>()))
        val reader = async(start = CoroutineStart.UNDISPATCHED) {
            tachiyomi.domain.source.service.SourceMangaUpdateService().awaitSharedCatalog(
                source,
                manga,
                emptyList(),
                false,
            )
        }
        withTimeout(5_000) { entered.await() }
        val detail = async(start = CoroutineStart.UNDISPATCHED) {
            updater.awaitFromRemote(
                manga, source, fetchDetails = false, fetchChapters = true,
                chapterRepository = chapters, syncChaptersWithSource = sync,
                coverCache = mockk(relaxed = true), libraryPreferences = mockk(relaxed = true),
                downloadManager = mockk(relaxed = true),
            )
        }
        try {
            withTimeout(5_000) { detailEntered.await() }
            assertEquals(1, calls.get())
            release.complete(Unit)
            reader.await()
            detail.await()
            coVerify(exactly = 1) { sync.await(listOf(remoteChapter), manga, source, false, 0L to 0L) }
        } finally {
            release.complete(Unit)
            reader.cancelAndJoin()
            detail.cancelAndJoin()
        }
    }

    @Test
    fun `source chapter refresh records complete archive catalogue facts`() = runBlocking {
        val path = java.nio.file.Files.createTempFile("archive-update-", ".db")
        val storage = eu.kanade.tachiyomi.data.backup.SourceUpdateMemoBackupIntegrationTest.Storage(path.toFile(), true)
        try {
            val manga = storage.mangas.insertNetworkManga(
                listOf(
                    Manga.create().copy(

                        source = 9L,
                        url = "/catalog",
                        title = "Catalog",
                        author = "Author",
                        artist = "Artist",
                    ),
                ),
            ).single()
            val chapter = SChapter.create().apply {
                url = "/catalog/chapter-1"
                name = "Chapter 1"
                date_upload = 1_790_000_000_000L
            }
            val remote = SManga.create().apply {
                url = manga.url
                title = manga.title
            }
            val source = mockk<Source> {
                every { id } returns manga.source
                coEvery { getMangaUpdate(any(), any(), false, true) } returns SMangaUpdate(remote, listOf(chapter))
            }
            val mangaRepository = storage.mangas
            val archive = mockk<CreatorArchiveRepository>(relaxed = true) {
                coEvery {
                    updateSourceWorkCatalog(any(), any(), any(), any(), any(), any())
                } just Runs
            }
            val chapterRepository = storage.chapters
            val downloads = mockk<eu.kanade.tachiyomi.data.download.DownloadManager>(relaxed = true)
            coEvery {
                downloads.withDirectoryChanges(
                    any(),
                    any<suspend () -> tachiyomi.domain.chapter.service.ChapterDirectoryResult>(),
                )
            } coAnswers { secondArg<suspend () -> tachiyomi.domain.chapter.service.ChapterDirectoryResult>()() }
            val preferences = tachiyomi.domain.library.service.LibraryPreferences(
                tachiyomi.core.common.preference.AndroidPreferenceStore(
                    org.robolectric.RuntimeEnvironment.getApplication(),
                    org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences(
                        "archive-refresh-${System.nanoTime()}",
                        android.content.Context.MODE_PRIVATE,
                    ),
                ),
            )
            val update = UpdateManga(
                mangaRepository = mangaRepository,
                fetchInterval = storage.fetchInterval,
                creatorArchiveRepository = archive,
                sourceDateExtensionIdentityProvider = {
                    SourceDateExtensionIdentity("example.extension", "1.0+1")
                },
            )

            val sync = eu.kanade.domain.chapter.interactor.SyncChaptersWithSource(
                downloads, mockk(relaxed = true), chapterRepository,
                tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter(), update,
                tachiyomi.domain.chapter.interactor.UpdateChapter(chapterRepository), storage.getChapters,
                GetExcludedScanlators(storage.handler), preferences,
            )
            update.awaitFromRemote(
                manga = manga,
                source = source,
                fetchDetails = false,
                fetchChapters = true,
                chapterRepository = chapterRepository,
                syncChaptersWithSource = sync,
                coverCache = mockk(relaxed = true),
                libraryPreferences = preferences,
                downloadManager = downloads,
            )

            coVerify(exactly = 1) {
                archive.recordSourceDateQualityObservations(
                    match { observations ->
                        observations.single().identity.field == SourceDateField.CHAPTER_UPDATED &&
                            observations.single().identity.extensionPackage == "example.extension" &&
                            observations.single().identity.extensionVersion == "1.0+1" &&
                            observations.single().workNaturalKey == manga.url &&
                            observations.single().chapterNaturalKey == chapter.url &&
                            observations.single().valueAt == chapter.date_upload &&
                            observations.single().precision == SourceDatePrecision.DAY
                    },
                    now = any(),
                )
            }
            coVerify(exactly = 1) {
                archive.updateSourceWorkCatalog(
                    sourceWork = SourceWorkNaturalKey(manga.source, manga.url),
                    chapterCount = 1L,
                    completeness = ChapterCatalogCompleteness.COMPLETE,
                    latestChapterAt = chapter.date_upload,
                    observedAt = any(),
                    mangaId = manga.id,
                )
            }
            val saved = mangaRepository.getMangaById(manga.id)
            assertEquals(manga.url, saved.url)
            assertEquals(manga.title, saved.title)
            assertEquals(listOf(chapter.url), chapterRepository.getChapterByMangaId(manga.id).map { it.url })
            assertEquals(null, chapterRepository.pendingDirectoryPhase(manga.id))
        } finally {
            storage.close()
            java.nio.file.Files.deleteIfExists(path)
        }
    }
}
