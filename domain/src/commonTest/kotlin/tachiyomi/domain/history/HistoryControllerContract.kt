package tachiyomi.domain.history

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.history.service.HistoryController
import tachiyomi.domain.history.service.HistoryUiModel
import tachiyomi.domain.manga.model.MangaCover
import java.time.ZoneId
import java.util.Date

abstract class HistoryControllerContract {
    @Test
    fun `nullable query loading and live calendar groups are shared`() = runTest {
        val repository = Repository()
        val controller =
            HistoryController(
                backgroundScope,
                GetHistory(repository),
                RemoveHistory(repository),
                zone = ZoneId.of("UTC"),
            )
        assertNull(controller.state.value.searchQuery)
        assertNull(controller.state.value.list)
        runCurrent()
        assertEquals(emptyList<HistoryUiModel>(), controller.state.value.list)
        repository.rows.value = listOf(item(1, "One", 86_400_000), item(2, "Two", 1))
        runCurrent()
        assertEquals(2, controller.state.value.list.orEmpty().filterIsInstance<HistoryUiModel.Header>().size)
        controller.updateSearchQuery("")
        assertEquals("", controller.state.value.searchQuery)
        controller.updateSearchQuery("One")
        assertEquals("One", controller.state.value.searchQuery)
        runCurrent()
        assertEquals(
            listOf("One"),
            controller.state.value.list.orEmpty().filterIsInstance<HistoryUiModel.Item>().map {
                it.item.title
            },
        )
        controller.updateSearchQuery(null)
        runCurrent()
        assertEquals(2, controller.state.value.list.orEmpty().filterIsInstance<HistoryUiModel.Item>().size)
        controller.close()
    }

    @Test
    fun `old delayed query cannot replace current input or results`() = runTest {
        val repository = Repository()
        val gate = CompletableDeferred<Unit>()
        val delayed = object : HistoryRepository by repository {
            override fun getHistory(query: String): Flow<List<HistoryWithRelations>> =
                if (query ==
                    "old"
                ) {
                    flow {
                        gate.await()
                        emit(listOf(item(1, "old", 1)))
                    }
                } else {
                    repository.getHistory(query)
                }
        }
        repository.rows.value = listOf(item(2, "new", 1))
        val controller = HistoryController(backgroundScope, GetHistory(delayed), RemoveHistory(delayed))
        controller.updateSearchQuery("old")
        runCurrent()
        controller.updateSearchQuery("new")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals("new", controller.state.value.searchQuery)
        assertEquals(
            listOf(2L),
            controller.state.value.list.orEmpty().filterIsInstance<HistoryUiModel.Item>().map {
                it.item.id
            },
        )
        assertEquals(1, repository.activeQueries)
        controller.close()
        runCurrent()
        assertEquals(0, repository.activeQueries)
    }

    @Test
    fun `shared resume delivery rejects repeated selected chapter`() = runTest {
        val repository = Repository()
        val next = mockk<GetNextChapters> {
            coEvery { await(1, 2, false) } returns
                listOf(Chapter.create().copy(id = 2, mangaId = 1))
        }
        val controller = HistoryController(backgroundScope, GetHistory(repository), RemoveHistory(repository), next)
        val events = mutableListOf<tachiyomi.domain.history.service.HistoryEvent>()
        backgroundScope.launch { controller.events.collect { events += it } }
        controller.resume(1, 2)
        controller.resume(1, 2)
        runCurrent()
        assertEquals(1, events.size, "Repeated action must emit only one Reader delivery")
        controller.close()
    }

    @Test
    fun `shared resume closed during selection cannot publish a late event`() = runTest {
        val repository = Repository()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val next = mockk<GetNextChapters> {
            coEvery { await(1, 2, false) } coAnswers {
                entered.complete(Unit)
                release.await()
                listOf(Chapter.create().copy(id = 2, mangaId = 1))
            }
        }
        val controller = HistoryController(backgroundScope, GetHistory(repository), RemoveHistory(repository), next)
        val request = async { controller.resume(1, 2) }
        entered.await()
        controller.close()
        release.complete(Unit)
        request.await()
    }

    private fun item(id: Long, title: String, time: Long) = HistoryWithRelations(
        id,
        id,
        id,
        title,
        1.0,
        Date(time),
        1,
        MangaCover(id, 42, false, null, 0),
    )

    private class Repository : HistoryRepository {
        val rows = MutableStateFlow<List<HistoryWithRelations>>(emptyList())
        var activeQueries = 0
        override fun getHistory(query: String): Flow<List<HistoryWithRelations>> = flow {
            activeQueries++
            try {
                rows.collect { emit(it.filter { item -> item.title.contains(query) }) }
            } finally {
                activeQueries--
            }
        }
        override suspend fun getLastHistory() = rows.value.firstOrNull()
        override suspend fun getTotalReadDuration() = 0L
        override suspend fun getHistoryByMangaId(mangaId: Long) = emptyList<History>()
        override suspend fun resetHistory(historyId: Long) {
            rows.value = rows.value.filterNot { it.id == historyId }
        }
        override suspend fun resetHistoryByMangaId(mangaId: Long) {
            rows.value =
                rows.value.filterNot { it.mangaId == mangaId }
        }
        override suspend fun deleteAllHistory(): Boolean {
            rows.value = emptyList()
            return true
        }
        override suspend fun upsertHistory(historyUpdate: HistoryUpdate) = Unit
    }
}
