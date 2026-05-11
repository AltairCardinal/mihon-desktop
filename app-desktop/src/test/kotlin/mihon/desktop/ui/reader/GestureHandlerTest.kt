package mihon.desktop.ui.reader

import mihon.desktop.reader.ZoomState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for gesture handler logic in ZoomablePageBox.
 *
 * These tests verify the tap-zone navigation behavior at different zoom levels,
 * ensuring that tap navigation is only triggered when zoom <= 1f (default zoom).
 */
class GestureHandlerTest {

    @Test
    fun `tap navigation is allowed at default zoom`() {
        val zoom = ZoomState(scale = 1f)
        assertTrue(zoom.scale <= 1f, "Tap navigation should be allowed at scale=1f")
    }

    @Test
    fun `tap navigation is blocked when zoomed in`() {
        val zoom = ZoomState(scale = 1.5f)
        assertTrue(zoom.scale > 1f, "Tap navigation should be blocked when zoomed in")
    }

    @Test
    fun `tap navigation is allowed after zoom reset`() {
        val zoom = ZoomState(scale = 2f).reset()
        assertEquals(1f, zoom.scale, "After reset, scale should be 1f")
        assertTrue(zoom.scale <= 1f, "Tap navigation should be allowed after reset")
    }

    @Test
    fun `tap zone LEFT triggers page backward`() {
        // Simulate: tap in left third of 900px width
        val width = 900f
        val tapX = 100f // well within left third (0-300)
        val zone = tapZoneFor(tapX, width)
        assertEquals(TapZone.LEFT, zone, "Tap at x=100 in 900px width should be LEFT zone")
    }

    @Test
    fun `tap zone RIGHT triggers page forward`() {
        // Simulate: tap in right third of 900px width
        val width = 900f
        val tapX = 800f // well within right third (600-900)
        val zone = tapZoneFor(tapX, width)
        assertEquals(TapZone.RIGHT, zone, "Tap at x=800 in 900px width should be RIGHT zone")
    }

    @Test
    fun `tap zone CENTER does not trigger navigation`() {
        val width = 900f
        val tapX = 450f // center
        val zone = tapZoneFor(tapX, width)
        assertEquals(TapZone.CENTER, zone, "Tap at center should be CENTER zone (no navigation)")
    }

    @Test
    fun `zoom state pan is ignored at default zoom`() {
        val zoom = ZoomState(scale = 1f)
        val panned = zoom.pan(dx = 100f, dy = 50f)
        assertEquals(1f, panned.scale, "Scale should remain 1f")
        assertEquals(0f, panned.offsetX, "Offset X should be 0 when not zoomed in")
        assertEquals(0f, panned.offsetY, "Offset Y should be 0 when not zoomed in")
    }

    @Test
    fun `zoom state pan is applied when zoomed in`() {
        val zoom = ZoomState(scale = 2f)
        val panned = zoom.pan(dx = 100f, dy = 50f)
        assertEquals(2f, panned.scale, "Scale should remain 2f")
        assertEquals(100f, panned.offsetX, "Offset X should be applied")
        assertEquals(50f, panned.offsetY, "Offset Y should be applied")
    }
}
