package mihon.desktop.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mihon.desktop.domain.fakes.FakeHistoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

class DesktopHistoryPresenterTest {

    private lateinit var historyRepo: FakeHistoryRepository
    private lateinit var getHistory: GetHistory
    private lateinit var removeHistory: RemoveHistory

    @BeforeEach
    fun setUp() {
        historyRepo = FakeHistoryRepository()
        getHistory = GetHistory(historyRepo)
        removeHistory = RemoveHistory(historyRepo)
    }

    @Test
    fun `getHistory subscribe returns empty list when no history`() = runBlocking {
        val result = getHistory.subscribe("").first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getHistory subscribe returns matching items for query`() = runBlocking {
        val item = sampleHistory(id = 1, title = "One Piece")
        historyRepo.addHistory(item)

        val result = getHistory.subscribe("One").first()
        assertEquals(1, result.size)
        assertEquals("One Piece", result[0].title)
    }

    @Test
    fun `getHistory subscribe filters by query`() = runBlocking {
        historyRepo.addHistory(sampleHistory(id = 1, title = "Naruto"))
        historyRepo.addHistory(sampleHistory(id = 2, title = "One Piece"))

        val result = getHistory.subscribe("Naruto").first()
        assertEquals(1, result.size)
        assertEquals("Naruto", result[0].title)
    }

    @Test
    fun `removeHistory removes single entry`() = runBlocking {
        val item = sampleHistory(id = 1, title = "Bleach")
        historyRepo.addHistory(item)

        removeHistory.await(item)

        val remaining = getHistory.subscribe("").first()
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun `removeHistory awaitAll clears everything`() = runBlocking {
        historyRepo.addHistory(sampleHistory(id = 1, title = "A"))
        historyRepo.addHistory(sampleHistory(id = 2, title = "B"))

        val success = removeHistory.awaitAll()
        assertTrue(success)

        val remaining = getHistory.subscribe("").first()
        assertTrue(remaining.isEmpty())
    }

    private fun sampleHistory(
        id: Long = 1,
        title: String = "Test Manga",
        chapterId: Long = 100,
        mangaId: Long = 10,
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
