package mihon.desktop.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LibrarySelectionStateTest {
    @Test
    fun `library pruning keeps a deselected valid anchor for the next fixed Shift range`() {
        val state = LibrarySelectionState()
        for (id in listOf(1L, 2L, 3L)) state.toggle(id, 1L)
        state.toggle(3L, 1L)
        assertEquals(setOf(1L, 2L), state.selectedIds)
        state.retainExistingIds(setOf(2L, 3L, 4L))
        assertEquals(setOf(2L), state.selectedIds)
        state.handlePrimaryClick(listOf(2L, 3L, 4L), 4L, true, 1L) { error("Shift must not navigate") }
        assertEquals(setOf(3L, 4L), state.selectedIds)
    }

    @Test
    fun `shift range replaces visible selection and keeps its starting anchor`() {
        val state = LibrarySelectionState()
        val visible = listOf(1L, 2L, 3L, 4L, 5L)
        state.toggle(90L, 9L)
        state.toggle(2L, 1L)
        fun shift(id: Long) = state.handlePrimaryClick(visible, id, true, 1L) { error("Shift must not navigate") }

        shift(5L)
        assertEquals(setOf(90L, 2L, 3L, 4L, 5L), state.selectedIds)
        shift(3L)
        assertEquals(setOf(90L, 2L, 3L), state.selectedIds)
        shift(1L)
        assertEquals(setOf(90L, 1L, 2L), state.selectedIds)
        shift(4L)
        assertEquals(setOf(90L, 2L, 3L, 4L), state.selectedIds)
    }

    @Test
    fun `hidden or cross category anchor rebuilds target while replacing visible selection`() {
        val state = LibrarySelectionState()
        state.toggle(2L, 1L)
        state.selectRange(listOf(1L, 2L, 3L, 4L), 4L, 1L)
        state.handlePrimaryClick(listOf(3L, 4L, 5L), 4L, true, 1L) { error("Shift must not navigate") }
        assertEquals(setOf(2L, 4L), state.selectedIds)
        state.handlePrimaryClick(listOf(3L, 4L, 5L), 5L, true, 2L) { error("Shift must not navigate") }
        assertEquals(setOf(2L, 5L), state.selectedIds)
        state.handlePrimaryClick(listOf(3L, 4L, 5L), 3L, true, 2L) { error("Shift must not navigate") }
        assertEquals(setOf(2L, 3L, 4L, 5L), state.selectedIds)
    }

    @Test
    fun `stale primary event never selects or opens a deleted target`() {
        val state = LibrarySelectionState()
        val visible = listOf(1L, 2L)
        var opened: Long? = null
        state.handlePrimaryClick(visible, 3L, false, 1L) { opened = it }
        assertEquals(null, opened)
        state.toggle(1L, 1L)
        state.handlePrimaryClick(visible, 3L, true, 1L) { opened = it }
        assertEquals(setOf(1L), state.selectedIds)
        state.handlePrimaryClick(visible, 3L, false, 1L) { opened = it }
        assertEquals(setOf(1L), state.selectedIds)
        assertEquals(null, opened)
    }
}
