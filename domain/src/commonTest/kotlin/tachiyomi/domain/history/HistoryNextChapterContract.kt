package tachiyomi.domain.history

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.history.service.HistoryController
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.repository.MangaRepository
import java.util.Date

abstract class HistoryNextChapterContract {
    @Test
    fun `selection honors every existing ordering and filtered catalog`() = runTest {
        val chapterRepository = mockk<ChapterRepository>()
        val mangaRepository = mockk<MangaRepository>()
        val historyRepository = mockk<HistoryRepository>()
        var manga = Manga.create().copy(id = 10)
        var chapters = listOf(
            Chapter.create().copy(
                id = 3,
                mangaId = 10,
                name = "C",
                chapterNumber = 3.0,
                sourceOrder = 2,
                dateUpload = 20,
            ),
            Chapter.create().copy(
                id = 1,
                mangaId = 10,
                name = "B",
                chapterNumber = 1.0,
                sourceOrder = 1,
                dateUpload = 30,
            ),
            Chapter.create().copy(
                id = 2,
                mangaId = 10,
                name = "A",
                chapterNumber = 2.0,
                sourceOrder = 0,
                dateUpload = 10,
            ),
        )
        coEvery { mangaRepository.getMangaById(10) } answers { manga }
        coEvery { chapterRepository.getChapterByMangaId(10, true) } answers { chapters }
        val next =
            GetNextChapters(GetChaptersByMangaId(chapterRepository), GetManga(mangaRepository), historyRepository)
        listOf(
            Manga.CHAPTER_SORTING_SOURCE to listOf(3L, 1L, 2L),
            Manga.CHAPTER_SORTING_NUMBER to listOf(1L, 2L, 3L),
            Manga.CHAPTER_SORTING_UPLOAD_DATE to listOf(2L, 3L, 1L),
            Manga.CHAPTER_SORTING_ALPHABET to listOf(2L, 1L, 3L),
        ).forEach { (sorting, expected) ->
            manga = manga.copy(chapterFlags = sorting or Manga.CHAPTER_SORT_DESC)
            assertEquals(expected, next.await(10, onlyUnread = false).map { it.id })
        }
        chapters = chapters.filterNot { it.id == 1L }.map { it.copy(read = it.id == 2L) }
        assertEquals(listOf(3L), next.await(10, 1, onlyUnread = false).map { it.id })
        assertEquals(listOf(3L), next.await(10, onlyUnread = true).map { it.id })
        chapters = emptyList()
        assertEquals(emptyList<Chapter>(), next.await(10, 1, onlyUnread = false))
        coVerify(exactly = 7) { chapterRepository.getChapterByMangaId(10, true) }
    }

    @Test
    fun `official selection keeps unfinished current and immediate next read chapter`() = runTest {
        val chapterRepository = mockk<ChapterRepository>()
        val mangaRepository = mockk<MangaRepository>()
        val historyRepository = mockk<HistoryRepository>()
        val manga = Manga.create().copy(id = 10, chapterFlags = Manga.CHAPTER_SORTING_NUMBER)
        var chapters = listOf(3, 1, 2).map { id ->
            Chapter.create().copy(id = id.toLong(), mangaId = 10, chapterNumber = id.toDouble())
        }
        coEvery { mangaRepository.getMangaById(10) } returns manga
        coEvery { chapterRepository.getChapterByMangaId(10, true) } answers { chapters }
        io.mockk.every { historyRepository.getHistory(any()) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        coEvery { historyRepository.getLastHistory() } returns
            HistoryWithRelations(1, 1, 10, "Latest", 1.0, Date(), 1, MangaCover(10, 42, true, null, 0))
        val controller =
            HistoryController(
                backgroundScope,
                GetHistory(historyRepository),
                RemoveHistory(historyRepository),
                GetNextChapters(GetChaptersByMangaId(chapterRepository), GetManga(mangaRepository), historyRepository),
            )
        assertEquals(1L, controller.nextChapter(10, 1)?.id)
        chapters = chapters.map { it.copy(read = it.id in setOf(1L, 2L)) }
        assertEquals(2L, controller.nextChapter(10, 1)?.id)
        assertEquals(3L, controller.nextChapter(10, 2)?.id)
        chapters = chapters.map { it.copy(read = true) }
        assertNull(controller.nextChapter(10, 3))
        // Fixed upstream behavior for a missing/filtered anchor: drop the first ordered chapter.
        assertEquals(2L, controller.nextChapter(10, 99)?.id)
        controller.updateSearchQuery("no visible results")
        assertEquals(2L, controller.latestChapter()?.id)
        coVerify { chapterRepository.getChapterByMangaId(10, true) }
        controller.close()
    }
}
