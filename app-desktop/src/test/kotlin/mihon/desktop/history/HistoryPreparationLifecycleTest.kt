package mihon.desktop.history

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
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
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.repository.MangaRepository
import java.util.Date

class HistoryPreparationLifecycleTest {
    @Test fun `completed row cannot publish twice before history returns`() = runTest {
        val fixture = fixture(backgroundScope)
        assertNotNull(fixture.model.readerRequestFor(fixture.item))
        assertNull(fixture.model.readerRequestFor(fixture.item))
        fixture.model.cancelRead()
        assertNotNull(fixture.model.readerRequestFor(fixture.item))
        fixture.model.onDispose()
    }

    @Test fun `global reselect cannot publish twice before history returns`() = runTest {
        val fixture = fixture(backgroundScope)
        assertNotNull(fixture.model.latestReaderRequest())
        assertNull(fixture.model.latestReaderRequest())
        fixture.model.onDispose()
    }

    @Test fun `global reselect cancelled or disposed during selection rejects late delivery`() = runTest {
        for (dispose in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = fixture(backgroundScope) {
                entered.complete(Unit)
                release.await()
            }
            fixture.model.loadHistory("Work")
            val request = async { fixture.model.latestReaderRequest() }
            entered.await()
            if (dispose) fixture.model.onDispose() else fixture.model.cancelRead()
            release.complete(Unit)
            assertNull(request.await())
            assertEquals("Work", fixture.model.state.value.searchQuery)
            fixture.model.onDispose()
        }
    }

    @Test fun `removed history row revokes a selected global request`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = fixture(backgroundScope) {
            entered.complete(Unit)
            release.await()
        }
        fixture.model.loadHistory("Work")
        val request = async { fixture.model.latestReaderRequest() }
        entered.await()
        fixture.model.removeHistory(fixture.item)
        release.complete(Unit)
        assertNull(request.await())
        fixture.model.controller.state.first { it.list.orEmpty().filterIsInstance<tachiyomi.domain.history.service.HistoryUiModel.Item>().isEmpty() }
        runCurrent()
        assertEquals(emptyList<HistoryWithRelations>(), fixture.model.state.value.items)
        assertEquals("Work", fixture.model.state.value.searchQuery)
        fixture.model.onDispose()
    }

    @Test fun `failed clear cannot reopen delivery while the reader is already mounted`() = runTest {
        val fixture = fixture(backgroundScope, clearSucceeds = false)
        fixture.model.loadHistory()
        assertNotNull(fixture.model.readerRequestFor(fixture.item))
        fixture.model.clearAllHistory()
        assertNull(fixture.model.readerRequestFor(fixture.item))
        assertEquals(listOf(fixture.item), fixture.model.state.value.items)
        fixture.model.onDispose()
    }

    private data class Fixture(val model: HistoryScreenModel, val item: HistoryWithRelations)
    private fun fixture(scope: kotlinx.coroutines.CoroutineScope, clearSucceeds: Boolean = true, beforeManga: suspend () -> Unit = {}): Fixture {
        val storedMangas = FakeMangaRepository().apply { seed(Manga.create().copy(id = 10, source = 42, url = "/work", title = "Work")) }
        val mangas = object : MangaRepository by storedMangas {
            override suspend fun getMangaById(id: Long): Manga {
                beforeManga()
                return storedMangas.getMangaById(id)
            }
        }
        val chapters = FakeChapterRepository().apply {
            listOf(3, 2, 1).forEachIndexed { index, n -> seed(Chapter.create().copy(id = n.toLong(), mangaId = 10, url = "/$n", name = "Chapter $n", sourceOrder = index.toLong())) }
        }
        val item = HistoryWithRelations(1, 2, 10, "Work", 2.0, Date(), 1, MangaCover(10, 42, false, null, 0))
        val storedHistory = FakeHistoryRepository().apply { addHistory(item) }
        val liveRows = kotlinx.coroutines.flow.MutableStateFlow(listOf(item))
        val history = object : tachiyomi.domain.history.repository.HistoryRepository by storedHistory {
            override suspend fun deleteAllHistory(): Boolean = if (clearSucceeds) storedHistory.deleteAllHistory().also { liveRows.value = emptyList() } else false
            override fun getHistory(query: String) = liveRows.map { rows -> rows.filter { it.title.contains(query) } }
            override suspend fun resetHistory(historyId: Long) {
                storedHistory.resetHistory(historyId)
                liveRows.value = liveRows.value.filterNot { it.id == historyId }
            }
        }
        val ownedScope = kotlinx.coroutines.CoroutineScope(scope.coroutineContext + kotlinx.coroutines.SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job]))
        val getChapters = GetChaptersByMangaId(chapters)
        val getManga = GetManga(mangas)
        return Fixture(
            HistoryScreenModel(
                GetHistory(history),
                RemoveHistory(history),
                getManga,
                getChapters = getChapters,
                getNextChapters = GetNextChapters(getChapters, getManga, history),
                observationScope = ownedScope,
            ),
            item,
        )
    }
}
