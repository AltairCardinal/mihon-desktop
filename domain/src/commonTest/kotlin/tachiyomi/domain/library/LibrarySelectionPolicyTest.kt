package tachiyomi.domain.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LibrarySelectionPolicyTest {
    @Test
    fun `range selection only spans the same category anchor`() {
        val initial = LibrarySelectionResult(
            selectedIds = setOf(10L),
            anchor = LibrarySelectionAnchor(id = 10L, categoryId = 1L),
        )

        val crossCategory = selectLibraryRange(
            state = initial,
            visibleIds = listOf(10L, 20L, 30L),
            targetId = 20L,
            categoryId = 2L,
        )
        assertEquals(setOf(10L, 20L), crossCategory.selectedIds)

        val sameCategory = selectLibraryRange(
            state = crossCategory,
            visibleIds = listOf(10L, 20L, 30L),
            targetId = 30L,
            categoryId = 2L,
        )
        assertEquals(setOf(10L, 20L, 30L), sameCategory.selectedIds)
    }

    @Test
    fun `select all and invert clear the range anchor`() {
        val initial = LibrarySelectionResult(
            selectedIds = setOf(10L),
            anchor = LibrarySelectionAnchor(id = 10L, categoryId = 1L),
        )

        val all = selectAllLibraryItems(initial, listOf(1L, 2L))
        assertEquals(LibrarySelectionAnchor(), all.anchor)
        assertEquals(
            setOf(10L, 1L, 2L),
            selectLibraryRange(all, listOf(1L, 2L), 2L, 1L).selectedIds,
        )
        assertEquals(
            LibrarySelectionAnchor(),
            invertLibraryItems(initial, listOf(1L, 2L)).anchor,
        )
    }

    @Test
    fun `removing the last selected item clears the range anchor`() {
        val result = toggleLibraryItem(
            state = LibrarySelectionResult(
                selectedIds = setOf(10L),
                anchor = LibrarySelectionAnchor(id = 10L, categoryId = 1L),
            ),
            id = 10L,
            categoryId = 1L,
        )

        assertEquals(emptySet<Long>(), result.selectedIds)
        assertEquals(LibrarySelectionAnchor(), result.anchor)
    }
}
