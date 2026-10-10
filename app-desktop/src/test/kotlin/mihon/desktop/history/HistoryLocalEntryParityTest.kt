package mihon.desktop.history

import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeHistoryRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

class HistoryLocalEntryParityTest {
    @Test
    fun `known local history opens directly from the official selected chapter`() = runTest {
        val chapters = chapters()
        val mangas = FakeMangaRepository().apply { seed(manga()) }
        val repository = FakeHistoryRepository().apply { addHistory(item(2)) }
        val model = HistoryScreenModel(
            GetHistory(repository),
            RemoveHistory(repository),
            GetManga(mangas),
            getChapters = GetChaptersByMangaId(chapters),
            getNextChapters = tachiyomi.domain.history.interactor.GetNextChapters(GetChaptersByMangaId(chapters), GetManga(FakeMangaRepository().apply { seed(manga()) }), repository),
        )
        try {
            val request = model.readerRequestFor(item(2))
            assertNotNull(request)
            assertEquals(listOf(3L, 2L, 1L), request?.chapters?.map { it.id })
            assertEquals(1, request?.currentChapterIndex)
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `finished history chapter resumes its immediate next chapter even when read`() = runTest {
        val chapters = chapters(read = setOf(1, 2))
        val repository = FakeHistoryRepository().apply { addHistory(item(1)) }
        val model = HistoryScreenModel(
            GetHistory(repository),
            RemoveHistory(repository),
            GetManga(FakeMangaRepository().apply { seed(manga()) }),
            getChapters = GetChaptersByMangaId(chapters),
            getNextChapters = tachiyomi.domain.history.interactor.GetNextChapters(GetChaptersByMangaId(chapters), GetManga(FakeMangaRepository().apply { seed(manga()) }), repository),
        )
        try {
            assertEquals(2L, model.readerRequestFor(item(1))?.chapterId)
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `finished final chapter has no target and does not reread for directory completion`() = runTest {
        val chapters = chapters(read = setOf(3))
        val repository = FakeHistoryRepository().apply { addHistory(item(3)) }
        val model = HistoryScreenModel(
            GetHistory(repository),
            RemoveHistory(repository),
            GetManga(FakeMangaRepository().apply { seed(manga()) }),
            getChapters = GetChaptersByMangaId(chapters),
            getNextChapters = tachiyomi.domain.history.interactor.GetNextChapters(GetChaptersByMangaId(chapters), GetManga(FakeMangaRepository().apply { seed(manga()) }), repository),
        )
        try {
            assertNull(model.readerRequestFor(item(3)))
        } finally {
            model.onDispose()
        }
    }

    private fun chapters(read: Set<Int> = emptySet()) = FakeChapterRepository().apply {
        (3 downTo 1).forEach { id -> seed(Chapter.create().copy(id = id.toLong(), mangaId = 10, url = "/$id", name = "Chapter $id", chapterNumber = id.toDouble(), sourceOrder = (3 - id).toLong(), read = id in read)) }
    }
    private fun manga() = Manga.create().copy(id = 10, source = 42, url = "/manga", title = "Local history")
    private fun item(chapter: Long) = HistoryWithRelations(1, chapter, 10, "Local history", chapter.toDouble(), Date(), 1, MangaCover(10, 42, true, null, 0))
}
