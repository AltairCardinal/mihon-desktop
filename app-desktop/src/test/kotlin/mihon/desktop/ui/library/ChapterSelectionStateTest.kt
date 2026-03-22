package mihon.desktop.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterSelectionStateTest {

    @Test
    fun `initially no chapters are selected`() {
        val state = ChapterSelectionState()
        assertTrue(state.selectedIds.isEmpty())
    }

    @Test
    fun `toggle adds chapter when not selected`() {
        val state = ChapterSelectionState()
        state.toggle(1L)
        assertTrue(1L in state.selectedIds)
    }

    @Test
    fun `toggle removes chapter when already selected`() {
        val state = ChapterSelectionState()
        state.toggle(1L)
        state.toggle(1L)
        assertFalse(1L in state.selectedIds)
    }

    @Test
    fun `selectAll sets all provided ids`() {
        val state = ChapterSelectionState()
        state.selectAll(listOf(1L, 2L, 3L))
        assertEquals(setOf(1L, 2L, 3L), state.selectedIds)
    }

    @Test
    fun `clear removes all selected ids`() {
        val state = ChapterSelectionState()
        state.selectAll(listOf(1L, 2L))
        state.clear()
        assertTrue(state.selectedIds.isEmpty())
    }

    @Test
    fun `isActive is true when any chapter is selected`() {
        val state = ChapterSelectionState()
        assertFalse(state.isActive)
        state.toggle(1L)
        assertTrue(state.isActive)
    }

    @Test
    fun `isActive is false after clearing all selections`() {
        val state = ChapterSelectionState()
        state.toggle(1L)
        state.clear()
        assertFalse(state.isActive)
    }
}
