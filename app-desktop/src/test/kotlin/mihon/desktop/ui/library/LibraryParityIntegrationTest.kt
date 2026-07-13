package mihon.desktop.ui.library

import kotlinx.coroutines.test.runTest
import mihon.desktop.domain.fakes.FakeMangaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.interactor.SetMangaCategories

class LibraryParityIntegrationTest {
    @Test
    fun `shift selection selects the inclusive visible range from the anchor`() {
        val selection = LibrarySelectionState()
        val visibleIds = listOf(10L, 20L, 30L, 40L, 50L)

        selection.toggle(20L)
        selection.selectRange(visibleIds, 50L)

        assertEquals(setOf(20L, 30L, 40L, 50L), selection.selectedIds)
    }

    @Test
    fun `shift mouse click selects visible range and does not open manga`() {
        val selection = LibrarySelectionState().apply { toggle(20L) }
        var openedMangaId: Long? = null

        selection.handlePrimaryClick(
            visibleIds = listOf(10L, 20L, 30L, 40L),
            targetId = 40L,
            shiftPressed = true,
            onOpen = { openedMangaId = it },
        )

        assertEquals(setOf(20L, 30L, 40L), selection.selectedIds)
        assertEquals(null, openedMangaId)
    }

    @Test
    fun `batch category assignment reports partial failure and continues`() = runTest {
        val repository = FakeMangaRepository().apply { failCategoryAssignmentFor = 2L }
        val result = SetMangaCategories(repository).awaitBatch(
            mangaIds = listOf(1L, 2L, 3L),
            categoryIds = listOf(7L),
        )

        assertEquals(setOf(1L, 3L), result.succeededIds.toSet())
        assertEquals(listOf(2L), result.failures.map { it.id })
        assertEquals(listOf(7L), repository.getMangaCategoryIds(3L))
    }


    @Test
    fun `library model exposes batch category partial failure to UI`() = runTest {
        val repository = FakeMangaRepository().apply { failCategoryAssignmentFor = 2L }
        val model = LibraryScreenModel(setMangaCategories = SetMangaCategories(repository))

        model.setCategoriesForManga(listOf(1L, 2L, 3L), listOf(7L))

        assertEquals("2 updated, 1 failed", model.state.value.batchCategoryResultMessage)
    }
}
