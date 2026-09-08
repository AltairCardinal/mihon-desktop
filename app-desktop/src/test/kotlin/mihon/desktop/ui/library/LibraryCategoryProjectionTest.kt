package mihon.desktop.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

class LibraryCategoryProjectionTest {

    @Test
    fun `system category is shown only when library contains uncategorized manga`() {
        val categories = listOf(
            category(0L, "Uncategorized"),
            category(1L, "Action"),
            category(2L, "Empty custom"),
        )

        assertEquals(
            listOf(1L, 2L),
            libraryCategoryTabs(categories, listOf(libraryManga(listOf(1L)))).map { it.id },
        )
        assertEquals(
            listOf(0L, 1L, 2L),
            libraryCategoryTabs(categories, listOf(libraryManga(listOf(0L)))).map { it.id },
        )
    }

    @Test
    fun `batch category delta preserves untouched categories and mixed values`() {
        val current = mapOf(
            10L to setOf(1L),
            20L to setOf(1L, 2L),
            30L to setOf(3L),
        )

        val delta = libraryCategoryDelta(
            currentCategoryIdsByManga = current,
            desiredStates = mapOf(
                1L to LibraryCategorySelection.ALL,
                2L to LibraryCategorySelection.NONE,
                3L to LibraryCategorySelection.MIXED,
            ),
        )

        assertEquals(setOf(1L), delta.addCategoryIds)
        assertEquals(setOf(2L), delta.removeCategoryIds)
    }

    @Test
    fun `category state is mixed when only some selected manga have category`() {
        val state = libraryCategorySelectionState(
            categoryId = 2L,
            currentCategoryIdsByManga = mapOf(10L to setOf(2L), 20L to setOf(1L)),
        )

        assertEquals(LibraryCategorySelection.MIXED, state)
    }

    @Test
    fun `initial batch states are calculated from the loaded category snapshot`() {
        val states = initialLibraryCategorySelections(
            categories = listOf(category(1L, "Action"), category(2L, "Romance")),
            currentCategoryIdsByManga = mapOf(
                10L to setOf(1L),
                20L to setOf(1L, 2L),
            ),
        )

        assertEquals(
            mapOf(
                1L to LibraryCategorySelection.ALL,
                2L to LibraryCategorySelection.MIXED,
            ),
            states,
        )
    }

    @Test
    fun `batch category delta never assigns system uncategorized category`() {
        val delta = libraryCategoryDelta(
            currentCategoryIdsByManga = mapOf(10L to emptySet()),
            desiredStates = mapOf(0L to LibraryCategorySelection.ALL),
        )

        assertEquals(emptySet<Long>(), delta.addCategoryIds)
        assertEquals(emptySet<Long>(), delta.removeCategoryIds)
    }

    private fun category(id: Long, name: String) = Category(id, name, id, 0L)

    private fun libraryManga(categories: List<Long>) = LibraryManga(
        manga = Manga.create().copy(id = categories.firstOrNull() ?: 1L),
        categories = categories,
        totalChapters = 0L,
        readCount = 0L,
        bookmarkCount = 0L,
        latestUpload = 0L,
        chapterFetchedAt = 0L,
        lastRead = 0L,
    )
}
