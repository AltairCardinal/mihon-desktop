package tachiyomi.domain.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterRangeSelectionUndoTest {
    @Test
    fun `fill undo and refill preserve the original platform anchor and selection`() {
        for (visible in listOf(listOf(9L, 3L, 7L, 1L), listOf(1L, 7L, 3L, 9L))) {
            val selected = setOf(9L, 1L, 99L)
            val filled = toggleChapterRangeSelection(selected, 3 to 1, visible, null)
            assertEquals(visible.toSet() + 99L, filled.selectedIds)
            assertNotNull(filled.undo)
            val restored = toggleChapterRangeSelection(filled.selectedIds, 0 to 3, visible, filled.undo)
            assertEquals(selected, restored.selectedIds)
            assertEquals(3 to 1, restored.anchor)
            assertNull(restored.undo)
            val refilled = toggleChapterRangeSelection(restored.selectedIds, restored.anchor, visible, restored.undo)
            assertEquals(filled.selectedIds, refilled.selectedIds)
            assertNotNull(refilled.undo)
        }
    }

    @Test
    fun `empty single and already complete selections do not create undo`() {
        val visible = listOf(1L, 2L, 3L)
        for (ids in listOf(emptySet(), setOf(1L), visible.toSet(), setOf(99L))) {
            val result = toggleChapterRangeSelection(ids, "anchor", visible, null)
            assertEquals(ids, result.selectedIds)
            assertNull(result.undo)
        }
    }

    @Test
    fun `undo requires both the filled selection and the exact visible identity order`() {
        val visible = listOf(1L, 2L, 3L)
        val filled = toggleChapterRangeSelection(setOf(1L, 3L), "anchor", visible, null)
        val undo = filled.undo!!
        assertTrue(undo.isValidFor(filled.selectedIds.toSet(), visible.toList()))
        assertFalse(undo.isValidFor(filled.selectedIds - 2L, visible))
        for (changed in listOf(visible.reversed(), visible + 4L, listOf(1L, 3L))) {
            assertFalse(undo.isValidFor(filled.selectedIds, changed))
            val result = toggleChapterRangeSelection(filled.selectedIds, "current anchor", changed, undo)
            assertEquals(filled.selectedIds, result.selectedIds)
            assertEquals("current anchor", result.anchor)
            assertNull(result.undo)
        }
    }

    @Test
    fun `snapshot owns its identity collections instead of retaining mutable platform caches`() {
        val selected = mutableSetOf(1L, 3L)
        val visible = mutableListOf(1L, 2L, 3L)
        val filled = toggleChapterRangeSelection(selected, 0 to 2, visible, null)
        selected.clear()
        visible.reverse()
        assertEquals(setOf(1L, 3L), filled.undo!!.selectedIds)
        assertEquals(listOf(1L, 2L, 3L), filled.undo!!.visibleIds)
        assertEquals(setOf(1L, 2L, 3L), filled.undo!!.filledIds)
    }
}
