package mihon.desktop.history

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.fakes.FakeChapterRepository
import mihon.desktop.domain.fakes.FakeHistoryRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetManga

class HistorySearchStateTest {
    @Test
    fun `explicit same query TestMode refresh waits for a new repository result`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var subscribed = 0
        val old = HistoryWithRelations(1, 2, 3, "Old", 1.0, java.util.Date(), 1, tachiyomi.domain.manga.model.MangaCover(3, 42, true, null, 0))
        val current = old.copy(id = 4, title = "Old updated")
        val repository = object : HistoryRepository by FakeHistoryRepository() {
            override fun getHistory(query: String): Flow<List<HistoryWithRelations>> = flow {
                if (query == "Old" && ++subscribed > 1) gate.await()
                emit(if (subscribed > 1) listOf(current) else listOf(old))
            }
        }
        val model = model(repository, backgroundScope)
        model.loadHistory("Old")
        val controller = mihon.desktop.test.http.HistoryTestModeController(model)
        var result: mihon.desktop.test.http.HistoryTestActionResult? = null
        val action = launch { result = controller.execute("history_search", mapOf("query" to "Old")) }
        runCurrent()
        org.junit.jupiter.api.Assertions.assertFalse(action.isCompleted, "Explicit refresh must not accept the previous loaded revision")
        gate.complete(Unit)
        action.join()
        assertEquals(2, subscribed)
        assertEquals(listOf(4L), requireNotNull(result).snapshot.rows.map { it.id })
        controller.close()
        model.onDispose()
    }

    @Test
    fun `loading a new query waits for its result even when old rows exist`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val old = HistoryWithRelations(
            1,
            2,
            3,
            "Old",
            1.0,
            java.util.Date(),
            1,
            tachiyomi.domain.manga.model.MangaCover(3, 42, true, null, 0),
        )
        val current = old.copy(id = 4, title = "Current")
        val repository = object : HistoryRepository by FakeHistoryRepository() {
            override fun getHistory(query: String): Flow<List<HistoryWithRelations>> = flow {
                if (query == "Current") gate.await()
                emit(if (query == "Current") listOf(current) else listOf(old))
            }
        }
        val model = model(repository, backgroundScope)
        runCurrent()
        model.loadHistory("")
        val controller = mihon.desktop.test.http.HistoryTestModeController(model)
        var result: mihon.desktop.test.http.HistoryTestActionResult? = null
        val action = launch { result = controller.execute("history_search", mapOf("query" to "Current")) }
        runCurrent()
        assertEquals("Current", model.controller.state.value.searchQuery)
        org.junit.jupiter.api.Assertions.assertFalse(action.isCompleted)
        gate.complete(Unit)
        action.join()
        assertEquals(listOf(current), model.state.value.items)
        assertEquals(listOf(4L), requireNotNull(result).snapshot.rows.map { it.id })
        controller.close()
        model.onDispose()
    }

    @Test
    fun `typing updates text before a database query completes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = object : HistoryRepository by FakeHistoryRepository() {
            override fun getHistory(query: String): Flow<List<HistoryWithRelations>> = flow {
                gate.await()
                emit(emptyList())
            }
        }
        val model = model(repository)
        val typing = launch { model.loadHistory("abc") }
        runCurrent()
        assertEquals("abc", model.state.value.searchQuery)
        gate.complete(Unit)
        typing.join()
        model.onDispose()
    }

    @Test
    fun `clear failure retains the database driven list`() = runTest {
        val backing = FakeHistoryRepository()
        val item = HistoryWithRelations(
            1,
            2,
            3,
            "Still present",
            1.0,
            java.util.Date(),
            1,
            tachiyomi.domain.manga.model.MangaCover(3, 42, true, null, 0),
        )
        backing.addHistory(item)
        val repository = object : HistoryRepository by backing {
            override suspend fun deleteAllHistory() = false
        }
        val model = model(repository)
        model.loadHistory()
        model.clearAllHistory()
        assertEquals(listOf(item), model.state.value.items)
        model.onDispose()
    }

    private fun model(repository: HistoryRepository, scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)) = HistoryScreenModel(
        GetHistory(repository),
        RemoveHistory(repository),
        GetManga(FakeMangaRepository()),
        observationScope = scope,
        getNextChapters = tachiyomi.domain.history.interactor.GetNextChapters(tachiyomi.domain.chapter.interactor.GetChaptersByMangaId(FakeChapterRepository()), GetManga(FakeMangaRepository()), repository),
    )
}
