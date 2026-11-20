package mihon.desktop.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.presentation.manga.DetailActionMotionState

class DetailActionMotionStateTest {
    @Test
    fun `continuous drag scrolls through full track and reverses immediately at either edge`() {
        val state = DetailActionMotionState()
        state.configure(80f, 320f, 9)
        state.drag(-75f)
        assertEquals(75f, state.offset)
        state.drag(-365f)
        assertEquals(400f, state.offset)
        assertTrue(state.boundaryPullProgress > 0)
        state.drag(30f)
        assertEquals(370f, state.offset)
        assertEquals(0f, state.boundaryPullProgress)
        state.drag(410f)
        assertEquals(0f, state.offset)
        assertTrue(state.boundaryPullProgress < 0)
        state.endDrag()
        assertEquals(0f, state.boundaryPullProgress)
        state.drag(-15f)
        assertEquals(15f, state.offset)
    }

    @Test
    fun `wheel pages once per burst and arrows page independently in either direction`() {
        val state = DetailActionMotionState()
        state.configure(80f, 320f, 9)
        assertEquals(3, state.pageCount)
        assertFalse(state.canPageBack)
        state.wheel(1f, 1000)
        assertEquals(320f, state.offset)
        assertEquals(1, state.currentPage)
        state.wheel(100f, 1050)
        assertEquals(320f, state.offset)
        state.turnPage(1)
        assertEquals(400f, state.offset)
        assertEquals(2, state.currentPage)
        assertFalse(state.canPageForward)
        state.turnPage(1)
        assertEquals(400f, state.offset)
        state.wheel(-1f, 1400)
        assertEquals(320f, state.offset)
        state.turnPage(-1)
        assertEquals(0f, state.offset)
        assertEquals(0, state.currentPage)
        state.wheel(1f, 1800, modified = true)
        assertEquals(0f, state.offset)
    }

    @Test
    fun `slot positions scale after resize and arrow navigation settles an intermediate drag offset`() {
        val state = DetailActionMotionState()
        state.configure(80f, 320f, 9)
        state.scrollForReorder(366f)
        state.configure(40f, 160f, 9)
        assertEquals(183f, state.offset)
        state.turnPage(1)
        assertEquals(200f, state.offset)
        state.turnPage(-1)
        assertEquals(160f, state.offset)
        state.scrollForReorder(200f)
        assertFalse(state.canPageForward)
        assertEquals(1f, state.scrollFraction)
    }
}
