package mihon.desktop.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterSelectionStateTest {
    @Test
    fun `chapter ranges append closed interval keep start and isolate manga owners`() {
        val state = ChapterSelectionState(42L)
        val visible = listOf(1L, 2L, 3L, 4L, 5L)
        state.toggle(3L)
        state.selectRange(visible, 1L)
        assertEquals(setOf(1L, 2L, 3L), state.selectedIds)
        assertEquals(3L, state.anchorId)
        state.selectRange(visible, 5L)
        assertEquals(visible.toSet(), state.selectedIds)
        assertEquals(3L, state.anchorId)
        state.selectRange(visible, 4L, ownerId = 99L)
        assertEquals(visible.toSet(), state.selectedIds)
        assertEquals(3L, state.anchorId)
    }

    @Test
    fun `guarded chapter click preserves unselected valid anchor rejects stale target and appends ctrl shift`() {
        val state = ChapterSelectionState(42L)
        val visible = listOf(1L, 2L, 3L, 4L)
        var opens = 0
        fun click(id: Long, modifiers: LibraryClickModifiers = LibraryClickModifiers()) =
            state.handlePrimaryClick(visible, id, modifiers) { opens++ }
        click(1L, LibraryClickModifiers(ctrlPressed = true))
        assertEquals(0, opens)
        click(3L)
        click(3L)
        assertEquals(setOf(1L), state.selectedIds)
        assertEquals(3L, state.anchorId)
        click(4L, LibraryClickModifiers(shiftPressed = true, ctrlPressed = true))
        assertEquals(setOf(1L, 3L, 4L), state.selectedIds)
        click(99L)
        assertEquals(setOf(1L, 3L, 4L), state.selectedIds)
        assertEquals(3L, state.anchorId)
        state.retainVisibleIds(listOf(1L, 2L, 4L))
        state.selectRange(listOf(1L, 2L, 4L), 2L)
        assertEquals(setOf(1L, 2L, 4L), state.selectedIds)
        assertEquals(2L, state.anchorId)
    }

    @Test
    fun `visible inverse clears anchor and zero selection exits`() {
        val state = ChapterSelectionState()
        state.toggle(1L)
        state.invertVisible(listOf(1L, 2L, 3L))
        assertEquals(setOf(2L, 3L), state.selectedIds)
        assertEquals(null, state.anchorId)
        state.selectAll(listOf(2L, 3L))
        state.invertVisible(listOf(2L, 3L))
        assertFalse(state.isActive)
    }

    @Test
    fun `batch completion removes only successes and cannot remove later selection`() {
        val state = ChapterSelectionState()
        state.selectAll(listOf(1L, 2L))
        val first = state.captureCompletion()
        first(listOf(1L))
        assertEquals(setOf(2L), state.selectedIds)
        val old = state.captureCompletion()
        state.clear()
        state.toggle(3L)
        old(listOf(2L))
        assertEquals(setOf(3L), state.selectedIds)
    }

    @Test
    fun `visible projection removes hidden selected chapters and invalid range anchor`() {
        val state = ChapterSelectionState()
        state.toggle(1L)
        state.toggle(2L)
        assertEquals(2L, state.anchorId)
        state.retainVisibleIds(listOf(1L, 3L))
        assertEquals(setOf(1L), state.selectedIds)
        assertEquals(null, state.anchorId)
        state.retainVisibleIds(listOf(3L))
        assertTrue(state.selectedIds.isEmpty())
        assertEquals(null, state.anchorId)
    }

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
