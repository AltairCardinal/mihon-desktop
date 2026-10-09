package tachiyomi.domain.history

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.history.service.HistoryController
import tachiyomi.domain.history.service.HistoryDialog
import tachiyomi.domain.history.service.HistoryFavoriteActions
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.UpdateLibraryMembership
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository

abstract class HistoryFavoriteContract {
    @Test
    fun `late category manager result cannot revive a dismissed chooser`() = runTest {
        val fixture = Fixture()
        val history = mockk<HistoryRepository>()
        every { history.getHistory(any()) } returns flowOf(emptyList())
        val controller =
            HistoryController(
                backgroundScope,
                GetHistory(history),
                RemoveHistory(history),
                favoriteActions = fixture.actions {
                },
            )
        controller.addFavorite(1)
        val gate = CompletableDeferred<Unit>()
        coEvery { fixture.categories.getAll() } coAnswers {
            gate.await()
            listOf(Category(7, "Selected", 0, 0))
        }
        val refresh = launch { controller.refreshCategoryChoices() }
        runCurrent()
        controller.setDialog(null)
        gate.complete(Unit)
        refresh.join()
        org.junit.jupiter.api.Assertions.assertNull(controller.state.value.dialog)
        controller.close()
    }

    @Test
    fun `category manager return keeps selection made during refresh`() = runTest {
        val fixture = Fixture()
        val history = mockk<HistoryRepository>()
        every { history.getHistory(any()) } returns flowOf(emptyList())
        val controller =
            HistoryController(
                backgroundScope,
                GetHistory(history),
                RemoveHistory(history),
                favoriteActions = fixture.actions {
                },
            )
        controller.addFavorite(1)
        val gate = CompletableDeferred<Unit>()
        coEvery { fixture.categories.getAll() } coAnswers
            {
                gate.await()
                listOf(Category(7, "Selected", 0, 0), Category(8, "New", 1, 0))
            }
        val refresh = launch { controller.refreshCategoryChoices() }
        runCurrent()
        controller.selectCategory(7, true)
        gate.complete(Unit)
        refresh.join()
        val dialog = controller.state.value.dialog as HistoryDialog.ChangeCategory
        assertEquals(listOf(7L), dialog.selectedIds)
        assertEquals(listOf(7L, 8L), dialog.categories.map { it.id })
        controller.close()
    }

    @Test
    fun `category confirmation never waits for tracker and duplicate confirmation writes once`() = runTest {
        val fixture = Fixture()
        val gate = CompletableDeferred<Unit>()
        val history = mockk<HistoryRepository>()
        every { history.getHistory(any()) } returns flowOf(emptyList())
        lateinit var controller: HistoryController
        var bindings = 0
        controller =
            HistoryController(
                backgroundScope,
                GetHistory(history),
                RemoveHistory(history),
                favoriteActions = fixture.actions {
                    assertTrue(controller.state.value.dialog is HistoryDialog.ChangeCategory)
                    bindings++
                    gate.await()
                },
            )
        val adding = launch { controller.addFavorite(1) }
        runCurrent()
        assertFalse(adding.isCompleted)
        controller.selectCategory(7, true)
        val first = launch { controller.confirmCategory(fixture.manga, listOf(7)) }
        val repeated = launch { controller.confirmCategory(fixture.manga, listOf(7)) }
        runCurrent()
        assertTrue(first.isCompleted)
        assertTrue(repeated.isCompleted)
        assertEquals(1, fixture.writes)
        assertEquals(listOf(7L), fixture.selected)
        assertTrue(fixture.manga.favorite)
        gate.complete(Unit)
        adding.join()
        assertEquals(1, bindings)
        controller.close()
    }

    @Test
    fun `duplicates defer binding and migration preserves current target identities`() = runTest {
        val fixture = Fixture()
        val existing = fixture.manga.copy(id = 2, url = "/existing", favorite = true)
        coEvery { fixture.mangas.getDuplicateLibraryManga(1, any()) } returns listOf(MangaWithChapterCount(existing, 3))
        val history = mockk<HistoryRepository>()
        every { history.getHistory(any()) } returns flowOf(emptyList())
        var bindings = 0
        val controller =
            HistoryController(
                backgroundScope,
                GetHistory(history),
                RemoveHistory(history),
                favoriteActions = fixture.actions {
                    bindings++
                },
            )
        controller.addFavorite(1)
        val duplicate = controller.state.value.dialog as HistoryDialog.Duplicate
        assertEquals(1L, duplicate.manga.id)
        assertEquals(listOf(2L), duplicate.duplicates.map { it.manga.id })
        assertEquals(0, bindings)
        controller.showMigration(existing, duplicate.manga)
        val migration = controller.state.value.dialog as HistoryDialog.Migrate
        assertEquals(2L, migration.current.id)
        assertEquals(1L, migration.target.id)
        controller.setDialog(null)
        assertEquals(0, fixture.writes)
        controller.close()
    }

    @Test
    fun `direct favorite failure skips trackers and successful default uses existing category`() = runTest {
        val fixture = Fixture(7)
        fixture.fail = true
        var bindings = 0
        val actions = fixture.actions { bindings++ }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { actions.add(1, false) {} }
        }
        assertFalse(fixture.manga.favorite)
        assertEquals(0, bindings)
        fixture.fail = false
        actions.add(1, false) {}
        assertEquals(listOf(7L), fixture.selected)
        assertEquals(1, bindings)
        actions.add(1, false) {}
        assertEquals(1, fixture.writes)
    }

    private class Fixture(defaultCategory: Int = -1) {
        var manga = Manga.create().copy(id = 1, source = 42, url = "/target", title = "Title")
        val mangas = mockk<MangaRepository>()
        val categories = mockk<CategoryRepository>()
        val preferences =
            LibraryPreferences(
                InMemoryPreferenceStore(
                    sequenceOf(InMemoryPreferenceStore.InMemoryPreference("default_category", defaultCategory, -1)),
                ),
            )
        var selected = emptyList<Long>()
        var writes = 0
        var fail = false
        init {
            coEvery { mangas.getMangaById(1) } answers { manga }
            coEvery { mangas.getDuplicateLibraryManga(1, any()) } returns emptyList()
            coEvery { categories.getAll() } returns listOf(Category(7, "Selected", 0, 0))
            coEvery { categories.getCategoriesByMangaId(1) } answers { selected.map { Category(it, "Selected", 0, 0) } }
        }
        fun actions(
            bind: suspend (Manga) -> Unit,
        ) = HistoryFavoriteActions(
            GetManga(mangas),
            GetCategories(categories),
            GetDuplicateLibraryManga(mangas),
            preferences,
            UpdateLibraryMembership { update ->
                if (fail) error("Actual write failure")
                writes++
                manga = manga.copy(favorite = update.favorite, dateAdded = update.dateAdded)
                selected = update.categoryIds
            },
            bind,
        )
    }
}
