package eu.kanade.tachiyomi.ui.history

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate

class HistorySharedControllerWiringTest {
    @Test
    fun `Android favorite adapter commits shared membership then invokes existing enhanced binding`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = mockk<HistoryRepository>()
        every { repository.getHistory(any()) } returns MutableStateFlow(emptyList())
        val manga = tachiyomi.domain.manga.model.Manga.create().copy(
            id = 10,
            source = 42,
            url = "/favorite",
            title = "Title",
        )
        val mangas = mockk<tachiyomi.domain.manga.repository.MangaRepository>()
        coEvery { mangas.getMangaById(10) } returns manga
        coEvery { mangas.getDuplicateLibraryManga(10, any()) } returns emptyList()
        val categories = mockk<tachiyomi.domain.category.repository.CategoryRepository>()
        coEvery { categories.getAll() } returns emptyList()
        val written = CompletableDeferred<LibraryMembershipUpdate>()
        val bound = CompletableDeferred<Unit>()
        val sourceManager = mockk<tachiyomi.domain.source.service.SourceManager>()
        val source = mockk<eu.kanade.tachiyomi.source.Source>()
        every { sourceManager.getOrStub(42) } returns source
        val addTracks = mockk<eu.kanade.domain.track.interactor.AddTracks>()
        coEvery { addTracks.bindEnhancedTrackers(manga, source) } coAnswers {
            assertEquals(true, written.isCompleted)
            bound.complete(Unit)
        }
        val model = HistoryScreenModel(
            addTracks = addTracks,
            getCategories = tachiyomi.domain.category.interactor.GetCategories(categories),
            getDuplicateLibraryManga = tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga(mangas),
            getHistory = GetHistory(
                repository,
            ),
            getManga = tachiyomi.domain.manga.interactor.GetManga(mangas), getNextChapters = mockk(),
            libraryPreferences = tachiyomi.domain.library.service.LibraryPreferences(
                tachiyomi.core.common.preference.InMemoryPreferenceStore(),
            ),
            removeHistory = RemoveHistory(repository),
            updateMembership = tachiyomi.domain.manga.interactor.UpdateLibraryMembership { written.complete(it) },
            sourceManager = sourceManager,
        )
        try {
            model.addFavorite(10)
            val membership = kotlinx.coroutines.withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) { written.await().also { bound.await() } }
            }
            assertEquals(10L, membership.mangaId)
            assertEquals(true, membership.favorite)
            assertEquals(emptyList<Long>(), membership.categoryIds)
        } finally {
            model.controller.close()
        }
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Android history follows official target instead of another synced chapter`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = mockk<HistoryRepository>()
        every { repository.getHistory(any()) } returns MutableStateFlow(emptyList())
        val next = mockk<tachiyomi.domain.history.interactor.GetNextChapters>()
        val first = tachiyomi.domain.chapter.model.Chapter.create().copy(id = 1, mangaId = 10)
        val second = first.copy(id = 2)
        coEvery { next.await(10, 1, false) } returns listOf(first, second)
        coEvery { next.await(10, false) } returns listOf(first, second)
        val model = HistoryScreenModel(
            addTracks = mockk(), getCategories = mockk(), getDuplicateLibraryManga = mockk(),
            getHistory = GetHistory(repository), getManga = mockk(), getNextChapters = next,
            libraryPreferences = mockk(),
            removeHistory = RemoveHistory(
                repository,
            ),
            updateMembership = mockk(), sourceManager = mockk(),
        )
        try {
            model.getNextChapterForManga(10, 1)
            assertEquals(1L, (model.events.first() as HistoryScreenModel.Event.OpenChapter).chapter?.id)
        } finally {
            model.controller.close()
        }
    }

    @Test
    fun `Android screen model consumes shared history state and immediate query`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rows = MutableStateFlow<List<HistoryWithRelations>>(emptyList())
        val repository = mockk<HistoryRepository>()
        every { repository.getHistory(any()) } returns rows
        val model = HistoryScreenModel(
            addTracks = mockk(), getCategories = mockk(), getDuplicateLibraryManga = mockk(),
            getHistory = GetHistory(repository), getManga = mockk(), getNextChapters = mockk(),
            libraryPreferences = mockk(),
            removeHistory = RemoveHistory(
                repository,
            ),
            updateMembership = mockk(), sourceManager = mockk(),
        )
        assertNull(model.state.value.list)
        model.updateSearchQuery("abc")
        assertEquals("abc", model.state.value.searchQuery)
        assertEquals("abc", model.controller.state.value.searchQuery)
        runCurrent()
        assertEquals(emptyList<Any>(), model.state.value.list)
        model.updateSearchQuery(null)
        runCurrent()
        assertNull(model.state.value.searchQuery)
        model.controller.close()
    }
}
