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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.creator.model.ChapterCatalogCompleteness
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

class UpdateMangaCreatorArchiveIntegrationTest {
    @Test
    fun `source chapter refresh records complete archive catalogue facts`() = runBlocking {
        val manga = Manga.create().copy(
            id = 41L,
            source = 9L,
            url = "/catalog",
            title = "Catalog",
            author = "Author",
            artist = "Artist",
        )
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
        val mangaRepository = mockk<MangaRepository>(relaxed = true) {
            coEvery { getMangaById(manga.id) } returns manga
            coEvery { update(any()) } returns true
        }
        val archive = mockk<CreatorArchiveRepository>(relaxed = true) {
            coEvery {
                updateSourceWorkCatalog(any(), any(), any(), any(), any(), any())
            } just Runs
        }
        val chapterRepository = mockk<ChapterRepository>(relaxed = true) {
            coEvery { getChapterByMangaId(manga.id) } returns emptyList()
        }
        val sync = mockk<eu.kanade.domain.chapter.interactor.SyncChaptersWithSource>(relaxed = true)
        val update = UpdateManga(
            mangaRepository = mangaRepository,
            fetchInterval = FetchInterval(mockk<GetChaptersByMangaId>()),
            creatorArchiveRepository = archive,
        )

        update.awaitFromRemote(
            manga = manga,
            source = source,
            fetchDetails = false,
            fetchChapters = true,
            chapterRepository = chapterRepository,
            syncChaptersWithSource = sync,
            coverCache = mockk(relaxed = true),
            libraryPreferences = mockk(relaxed = true),
            downloadManager = mockk(relaxed = true),
        )

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
        assertEquals(manga, mangaRepository.getMangaById(manga.id))
    }
}
