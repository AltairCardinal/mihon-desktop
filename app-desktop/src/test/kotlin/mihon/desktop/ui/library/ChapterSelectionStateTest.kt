package mihon.desktop.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterSelectionStateTest {
    @Test
    fun `range fill undo restores selection and anchor and can fill anew`() {
        val state = ChapterSelectionState(42L)
        val visible = listOf(1L, 2L, 3L, 4L, 5L)
        state.toggle(1L)
        state.toggle(5L)
        state.toggle(3L)
        state.selectBetweenSelected(visible)
        assertEquals(visible.toSet(), state.selectedIds)
        state.selectBetweenSelected(visible)
        assertEquals(setOf(1L, 3L, 5L), state.selectedIds)
        assertEquals(3L, state.anchorId)
        state.selectBetweenSelected(visible)
        assertEquals(visible.toSet(), state.selectedIds)
    }

    @Test
    fun `other selection actions consume undo even when selected ids are unchanged`() {
        val visible = listOf(1L, 2L, 3L)
        val actions: List<(ChapterSelectionState) -> Unit> = listOf(
            { it.selectAll(visible) },
            { it.selectRange(visible, 3L) },
            { it.toggle(2L); it.toggle(2L) },
            { it.invertVisible(visible); it.invertVisible(visible) },
            { it.clear(); it.selectAll(visible) },
        )
        for (action in actions) {
            val state = ChapterSelectionState()
            state.toggle(1L)
            state.toggle(3L)
            state.selectBetweenSelected(visible)
            action(state)
            assertFalse(state.canUndoRangeSelection)
            state.selectBetweenSelected(visible)
            assertEquals(visible.toSet(), state.selectedIds)
        }
    }

    @Test
    fun `visible reorder addition and removal revoke undo while an identical projection keeps it`() {
        val visible = listOf(1L, 2L, 3L)
        for (changed in listOf(visible.reversed(), visible + 4L, listOf(1L, 3L))) {
            val state = ChapterSelectionState()
            state.toggle(1L)
            state.toggle(3L)
            state.selectBetweenSelected(visible)
            state.retainVisibleIds(changed)
            assertFalse(state.canUndoRangeSelection)
            assertEquals(changed.toSet().intersect(visible.toSet()), state.selectedIds)
            state.retainVisibleIds(visible)
            state.selectBetweenSelected(visible)
            assertEquals(visible.toSet(), state.selectedIds)
        }
        val state = ChapterSelectionState()
        state.toggle(1L)
        state.toggle(3L)
        state.selectBetweenSelected(visible)
        state.retainVisibleIds(visible.toList())
        state.selectBetweenSelected(visible)
        assertEquals(setOf(1L, 3L), state.selectedIds)
    }

    @Test
    fun `successful batch mutation consumes undo and undo protects against a late completion`() {
        val visible = listOf(1L, 2L, 3L)
        val state = ChapterSelectionState()
        state.toggle(1L)
        state.toggle(3L)
        state.selectBetweenSelected(visible)
        state.captureCompletion()(listOf(2L))
        state.selectBetweenSelected(visible)
        assertEquals(visible.toSet(), state.selectedIds)
        val lateCompletion = state.captureCompletion()
        state.selectBetweenSelected(visible)
        assertEquals(setOf(1L, 3L), state.selectedIds)
        lateCompletion(listOf(1L, 3L))
        assertEquals(setOf(1L, 3L), state.selectedIds)
    }

    @Test
    fun `fill selected endpoints follows display order rather than ids or the last toggled anchor`() {
        for (visible in listOf(listOf(90L, 10L, 70L, 20L, 50L), listOf(50L, 20L, 70L, 10L, 90L))) {
            val state = ChapterSelectionState(42L)
            state.toggle(90L)
            state.toggle(50L)
            state.toggle(70L)
            state.selectBetweenSelected(visible)
            assertEquals(visible.toSet(), state.selectedIds)
            assertEquals(70L, state.anchorId)
        }
    }

    @Test
    fun `fill selected endpoints leaves outside and filtered chapters unselected`() {
        val state = ChapterSelectionState(42L)
        state.toggle(2L)
        state.toggle(4L)
        state.selectBetweenSelected(listOf(1L, 2L, 3L, 4L, 5L))
        assertEquals(setOf(2L, 3L, 4L), state.selectedIds)
        state.clear()
        state.toggle(1L)
        state.toggle(5L)
        state.selectBetweenSelected(listOf(1L, 3L, 5L))
        assertEquals(setOf(1L, 3L, 5L), state.selectedIds)
    }

    @Test
    fun `fill selected endpoints with zero or one visible selection is a no op`() {
        val state = ChapterSelectionState(42L)
        state.selectBetweenSelected(listOf(1L, 2L, 3L))
        assertFalse(state.isActive)
        assertFalse(state.canUndoRangeSelection)
        state.toggle(2L)
        state.selectBetweenSelected(listOf(1L, 2L, 3L))
        state.selectBetweenSelected(emptyList())
        assertEquals(setOf(2L), state.selectedIds)
        assertEquals(2L, state.anchorId)
        assertFalse(state.canUndoRangeSelection)
        state.selectAll(listOf(1L, 2L, 3L))
        state.selectBetweenSelected(listOf(1L, 2L, 3L))
        assertFalse(state.canUndoRangeSelection)
    }

    @Test
    fun `filling a range invalidates an earlier batch completion`() {
        val state = ChapterSelectionState(42L)
        state.toggle(1L)
        state.toggle(3L)
        val completion = state.captureCompletion()
        state.selectBetweenSelected(listOf(1L, 2L, 3L))
        completion(listOf(1L, 3L))
        assertEquals(setOf(1L, 2L, 3L), state.selectedIds)
    }

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
