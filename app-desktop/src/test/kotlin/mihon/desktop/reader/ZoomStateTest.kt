package mihon.desktop.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** RED — ZoomState does not exist yet. */
class ZoomStateTest {

    @Test
    fun `initial scale is 1f`() {
        val state = ZoomState()
        assertEquals(1f, state.scale)
        assertEquals(0f, state.offsetX)
        assertEquals(0f, state.offsetY)
    }

    @Test
    fun `zoomIn increases scale`() {
        val state = ZoomState().zoomIn()
        assertTrue(state.scale > 1f)
    }

    @Test
    fun `zoomIn is capped at maxScale`() {
        var state = ZoomState()
        repeat(50) { state = state.zoomIn() }
        assertEquals(ZoomState.MAX_SCALE, state.scale, 0.01f)
    }

    @Test
    fun `zoomOut decreases scale`() {
        val state = ZoomState(scale = 2f).zoomOut()
        assertTrue(state.scale < 2f)
    }

    @Test
    fun `zoomOut is floored at 1f and resets offsets`() {
        val state = ZoomState(scale = 1.1f, offsetX = 50f, offsetY = 30f).zoomOut()
        assertEquals(1f, state.scale, 0.01f)
        assertEquals(0f, state.offsetX, 0.01f)
        assertEquals(0f, state.offsetY, 0.01f)
    }

    @Test
    fun `reset returns to identity state`() {
        val state = ZoomState(scale = 3f, offsetX = 100f, offsetY = -50f).reset()
        assertEquals(1f, state.scale)
        assertEquals(0f, state.offsetX)
        assertEquals(0f, state.offsetY)
    }

    @Test
    fun `pan adds to offsets`() {
        val state = ZoomState(scale = 2f).pan(dx = 30f, dy = -20f)
        assertEquals(30f, state.offsetX)
        assertEquals(-20f, state.offsetY)
    }

    @Test
    fun `pan is no-op when scale is 1f`() {
        // At scale 1 there is nothing to pan; pan() should be a no-op
        val state = ZoomState().pan(dx = 100f, dy = 100f)
        assertEquals(0f, state.offsetX)
        assertEquals(0f, state.offsetY)
    }
}
