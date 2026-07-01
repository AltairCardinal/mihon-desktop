package mihon.desktop.history

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeHistoryRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

class HistoryScreenModelTest {

    @Test
    fun `initial state has expected defaults`() {
        val model = buildModel()
        val state: StateFlow<HistoryState> = model.state

        assertNotNull(state)
        assertEquals("", state.value.searchQuery)
        assertTrue(state.value.items.isEmpty())
        assertFalse(state.value.showClearAllDialog)
    }

    @Test
    fun `loadHistory updates search query and items`() = runTest {
        val historyRepository = FakeHistoryRepository()
        historyRepository.addHistory(sampleHistory(id = 1L, title = "Naruto"))
        historyRepository.addHistory(sampleHistory(id = 2L, title = "One Piece"))
        val model = buildModel(historyRepository = historyRepository)

        model.loadHistory("One")

        assertEquals("One", model.state.value.searchQuery)
        assertEquals(listOf("One Piece"), model.state.value.items.map { it.title })
    }

    @Test
    fun `removeHistory removes one item and refreshes current query`() = runTest {
        val historyRepository = FakeHistoryRepository()
        val item = sampleHistory(id = 1L, title = "Bleach")
        historyRepository.addHistory(item)
        val model = buildModel(historyRepository = historyRepository)
        model.loadHistory("")

        model.removeHistory(item)

        assertTrue(model.state.value.items.isEmpty())
    }

    @Test
    fun `clearAllHistory removes all items and closes dialog`() = runTest {
        val historyRepository = FakeHistoryRepository()
        historyRepository.addHistory(sampleHistory(id = 1L, title = "A"))
        historyRepository.addHistory(sampleHistory(id = 2L, title = "B"))
        val model = buildModel(historyRepository = historyRepository)
        model.loadHistory("")
        model.setShowClearAllDialog(true)

        model.clearAllHistory()

        assertTrue(model.state.value.items.isEmpty())
        assertFalse(model.state.value.showClearAllDialog)
    }

    @Test
    fun `reader request uses chapter and manga data`() = runTest {
        val chapterRepository = FakeChapterRepository()
        val mangaRepository = FakeMangaRepository()
        chapterRepository.addAll(
            listOf(
                Chapter.create().copy(
                    id = 100L,
                    mangaId = 10L,
                    name = "Chapter 1",
                    url = "/ch/1",
                    lastPageRead = 3L,
                ),
            ),
        )
        mangaRepository.seed(
            Manga.create().copy(
                id = 10L,
                source = 42L,
                title = "Test Manga",
                viewerFlags = 0x22L,
            ),
        )
        val model = buildModel(
            chapterRepository = chapterRepository,
            mangaRepository = mangaRepository,
        )

        val request = model.readerRequestFor(sampleHistory(chapterId = 100L, mangaId = 10L))

        assertNotNull(request)
        assertEquals("Chapter 1", request?.chapterTitle)
        assertEquals("Test Manga", request?.mangaTitle)
        assertEquals(42L, request?.sourceId)
        assertEquals("/ch/1", request?.chapterUrl)
        assertEquals(100L, request?.chapterId)
        assertEquals(10L, request?.mangaId)
        assertEquals(0x22L, request?.mangaViewerFlags)
        assertEquals(3, request?.initialPage)
    }

    @Test
    fun `reader request returns null when chapter or manga is missing`() = runTest {
        val model = buildModel()

        assertNull(model.readerRequestFor(sampleHistory(chapterId = 100L, mangaId = 10L)))
    }

    private fun buildModel(
        historyRepository: FakeHistoryRepository = FakeHistoryRepository(),
        chapterRepository: FakeChapterRepository = FakeChapterRepository(),
        mangaRepository: FakeMangaRepository = FakeMangaRepository(),
    ): HistoryScreenModel {
        return HistoryScreenModel(
            getHistory = GetHistory(historyRepository),
            removeHistory = RemoveHistory(historyRepository),
            getChapter = GetChapter(chapterRepository),
            getManga = GetManga(mangaRepository),
        )
    }

    private fun sampleHistory(
        id: Long = 1L,
        title: String = "Test Manga",
        chapterId: Long = 100L,
        mangaId: Long = 10L,
    ) = HistoryWithRelations(
        id = id,
        chapterId = chapterId,
        mangaId = mangaId,
        title = title,
        chapterNumber = 1.0,
        readAt = Date(),
        readDuration = 300L,
        coverData = MangaCover(
            mangaId = mangaId,
            sourceId = 1L,
            isMangaFavorite = true,
            url = "https://example.com/cover.jpg",
            lastModified = 0L,
        ),
    )
}
