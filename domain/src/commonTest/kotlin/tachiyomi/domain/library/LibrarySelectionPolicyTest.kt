package tachiyomi.domain.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LibrarySelectionPolicyTest {
    @Test
    fun `selected endpoints use display order and retain selections outside the visible interval`() {
        val selected = setOf(1L, 3L, 99L)
        val visible = listOf(9L, 1L, 7L, 3L, 5L)
        for (order in listOf(visible, visible.reversed())) {
            assertEquals(setOf(1L, 7L, 3L, 99L), selectBetweenSelectedLibraryItems(selected, order))
        }
    }

    @Test
    fun `selected endpoints do not add filtered chapters and are idempotent`() {
        val visible = listOf(1L, 3L, 5L)
        val expanded = selectBetweenSelectedLibraryItems(setOf(1L, 5L), visible)
        assertEquals(setOf(1L, 3L, 5L), expanded)
        assertEquals(expanded, selectBetweenSelectedLibraryItems(expanded, visible))
    }

    @Test
    fun `zero or one visible selected endpoint leaves the selection unchanged`() {
        for (selected in listOf(emptySet(), setOf(2L), setOf(2L, 99L), setOf(99L))) {
            assertEquals(selected, selectBetweenSelectedLibraryItems(selected, listOf(1L, 2L, 3L)))
            assertEquals(selected, selectBetweenSelectedLibraryItems(selected, emptyList()))
        }
    }

    @Test
    fun `default android range remains append with anchor moved to each target`() {
        val initial = LibrarySelectionResult(setOf(2L, 90L), LibrarySelectionAnchor(2L, 1L))
        val visible = listOf(1L, 2L, 3L, 4L, 5L)
        val expanded = selectLibraryRange(initial, visible, 5L, 1L)
        assertEquals(setOf(2L, 3L, 4L, 5L, 90L), expanded.selectedIds)
        assertEquals(LibrarySelectionAnchor(5L, 1L), expanded.anchor)
        val reverse = selectLibraryRange(expanded, visible, 3L, 1L)
        assertEquals(expanded.selectedIds, reverse.selectedIds)
        assertEquals(LibrarySelectionAnchor(3L, 1L), reverse.anchor)
        val other = selectLibraryRange(reverse, visible, 1L, 2L)
        assertEquals(reverse.selectedIds + 1L, other.selectedIds)
        assertEquals(LibrarySelectionAnchor(1L, 2L), other.anchor)
    }

    @Test
    fun `explicit windows policies replace visible only and preserve a valid starting anchor`() {
        val visible = listOf(1L, 2L, 3L, 4L, 5L)
        val initial = LibrarySelectionResult(setOf(2L, 5L, 90L), LibrarySelectionAnchor(2L, 1L))
        val replaced = selectLibraryRange(
            initial,
            visible,
            3L,
            1L,
            LibraryRangeSelectionMode.REPLACE_VISIBLE,
            LibraryRangeAnchorPolicy.KEEP_START,
        )
        assertEquals(setOf(2L, 3L, 90L), replaced.selectedIds)
        assertEquals(initial.anchor, replaced.anchor)
        val appended = selectLibraryRange(
            initial,
            visible,
            4L,
            1L,
            LibraryRangeSelectionMode.APPEND,
            LibraryRangeAnchorPolicy.KEEP_START,
        )
        assertEquals(setOf(2L, 3L, 4L, 5L, 90L), appended.selectedIds)
        assertEquals(initial.anchor, appended.anchor)
    }

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
