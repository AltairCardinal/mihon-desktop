package mihon.desktop.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import androidx.compose.ui.input.pointer.PointerButton

/**
 * Tests for [tapNavRegion] — 2D tap navigation for all 5 navigation modes.
 *
 * Coordinate system: (0,0) = top-left, (width,height) = bottom-right.
 * All tests use a 300×300 virtual screen for easy fraction math.
 */
class NavigationModeTest {

    private val W = 300f
    private val H = 300f

    // ── Disabled ──────────────────────────────────────────────────────────────

    @Test fun `Disabled - center tap returns MENU`() =
        assertEquals(TapNavRegion.MENU, tapNavRegion(150f, 150f, W, H, NavigationMode.Disabled))

    @Test fun `Disabled - left tap returns MENU`() =
        assertEquals(TapNavRegion.MENU, tapNavRegion(50f, 150f, W, H, NavigationMode.Disabled))

    @Test fun `Disabled - right tap returns MENU`() =
        assertEquals(TapNavRegion.MENU, tapNavRegion(250f, 150f, W, H, NavigationMode.Disabled))

    // ── RightAndLeft ──────────────────────────────────────────────────────────

    @Test fun `RightAndLeft LTR - left third returns PREV`() =
        assertEquals(TapNavRegion.PREV, tapNavRegion(50f, 150f, W, H, NavigationMode.RightAndLeft))

    @Test fun `RightAndLeft LTR - right third returns NEXT`() =
        assertEquals(TapNavRegion.NEXT, tapNavRegion(250f, 150f, W, H, NavigationMode.RightAndLeft))

    @Test fun `RightAndLeft LTR - center returns MENU`() =
        assertEquals(TapNavRegion.MENU, tapNavRegion(150f, 150f, W, H, NavigationMode.RightAndLeft))

    @Test fun `RightAndLeft RTL - left third returns NEXT`() =
        assertEquals(TapNavRegion.NEXT, tapNavRegion(50f, 150f, W, H, NavigationMode.RightAndLeft, isRtl = true))

    @Test fun `RightAndLeft RTL - right third returns PREV`() =
        assertEquals(TapNavRegion.PREV, tapNavRegion(250f, 150f, W, H, NavigationMode.RightAndLeft, isRtl = true))

    // ── L Navigation ─────────────────────────────────────────────────────────
    // Left column (nx < 0.33) = PREV regardless of row
    // Top row (ny < 0.33) = PREV regardless of column
    // Bottom row (ny > 0.66) = NEXT
    // Right column (nx > 0.66) = NEXT
    // Center = MENU

    @Test fun `L - top-left corner returns PREV`() =
        assertEquals(TapNavRegion.PREV, tapNavRegion(50f, 50f, W, H, NavigationMode.L))

    @Test fun `L - left column center-y returns PREV`() =
        assertEquals(TapNavRegion.PREV, tapNavRegion(50f, 150f, W, H, NavigationMode.L))

    @Test fun `L - top center returns PREV`() =
        assertEquals(TapNavRegion.PREV, tapNavRegion(150f, 50f, W, H, NavigationMode.L))

    @Test fun `L - right column center-y returns NEXT`() =
        assertEquals(TapNavRegion.NEXT, tapNavRegion(250f, 150f, W, H, NavigationMode.L))

    @Test fun `L - bottom center returns NEXT`() =
        assertEquals(TapNavRegion.NEXT, tapNavRegion(150f, 250f, W, H, NavigationMode.L))

    @Test fun `L - center returns MENU`() =
        assertEquals(TapNavRegion.MENU, tapNavRegion(150f, 150f, W, H, NavigationMode.L))

    @Test fun `L - top-right corner returns PREV (top row wins)`() =
        assertEquals(TapNavRegion.PREV, tapNavRegion(250f, 50f, W, H, NavigationMode.L))

    @Test fun `L - bottom-left corner returns NEXT (bottom row wins)`() =
        assertEquals(TapNavRegion.NEXT, tapNavRegion(50f, 250f, W, H, NavigationMode.L))

    // ── Kindle ────────────────────────────────────────────────────────────────
    // Top row (ny < 0.33) = MENU
    // Left half (nx < 0.5) in non-top rows = PREV
    // Right half (nx >= 0.5) in non-top rows = NEXT

    @Test fun `Kindle - top-left corner returns MENU`() =
        assertEquals(TapNavRegion.MENU, tapNavRegion(50f, 50f, W, H, NavigationMode.Kindle))

    @Test fun `Kindle - top-right corner returns MENU`() =
        assertEquals(TapNavRegion.MENU, tapNavRegion(250f, 50f, W, H, NavigationMode.Kindle))

    @Test fun `Kindle - bottom-left returns PREV`() =
        assertEquals(TapNavRegion.PREV, tapNavRegion(50f, 250f, W, H, NavigationMode.Kindle))

    @Test fun `Kindle - bottom-right returns NEXT`() =
        assertEquals(TapNavRegion.NEXT, tapNavRegion(250f, 250f, W, H, NavigationMode.Kindle))

    @Test fun `Kindle - center-left returns PREV`() =
        assertEquals(TapNavRegion.PREV, tapNavRegion(100f, 150f, W, H, NavigationMode.Kindle))

    @Test fun `Kindle - center-right returns NEXT`() =
        assertEquals(TapNavRegion.NEXT, tapNavRegion(200f, 150f, W, H, NavigationMode.Kindle))

    // ── Edge ─────────────────────────────────────────────────────────────────
    // Left edge (nx < 0.2) = PREV
    // Right edge (nx > 0.8) = NEXT
    // Top edge (ny < 0.2) = PREV
    // Bottom edge (ny > 0.8) = NEXT
    // Center region = MENU

    @Test fun `Edge - left edge returns PREV`() =
        assertEquals(TapNavRegion.PREV, tapNavRegion(30f, 150f, W, H, NavigationMode.Edge))

    @Test fun `Edge - right edge returns NEXT`() =
        assertEquals(TapNavRegion.NEXT, tapNavRegion(270f, 150f, W, H, NavigationMode.Edge))

    @Test fun `Edge - top edge returns PREV`() =
        assertEquals(TapNavRegion.PREV, tapNavRegion(150f, 30f, W, H, NavigationMode.Edge))

    @Test fun `Edge - bottom edge returns NEXT`() =
        assertEquals(TapNavRegion.NEXT, tapNavRegion(150f, 270f, W, H, NavigationMode.Edge))

    @Test fun `Edge - center returns MENU`() =
        assertEquals(TapNavRegion.MENU, tapNavRegion(150f, 150f, W, H, NavigationMode.Edge))

    @Test fun `Edge - top-left corner returns PREV (left edge wins)`() =
        assertEquals(TapNavRegion.PREV, tapNavRegion(30f, 30f, W, H, NavigationMode.Edge))

    @Test fun `Edge - bottom-right corner returns NEXT (right edge wins)`() =
        assertEquals(TapNavRegion.NEXT, tapNavRegion(270f, 270f, W, H, NavigationMode.Edge))

    // ── Zero-size safety ──────────────────────────────────────────────────────

    @Test fun `zero width returns MENU safely`() =
        assertEquals(TapNavRegion.MENU, tapNavRegion(0f, 0f, 0f, 300f, NavigationMode.RightAndLeft))

    @Test fun `zero height returns MENU safely`() =
        assertEquals(TapNavRegion.MENU, tapNavRegion(0f, 0f, 300f, 0f, NavigationMode.RightAndLeft))

    @Test
    fun `mouse primary button can trigger reader tap navigation`() {
        assertEquals(
            TapNavRegion.PREV,
            tapNavRegionForPointerButton(
                button = PointerButton.Primary,
                x = 50f,
                y = 150f,
                width = W,
                height = H,
                mode = NavigationMode.RightAndLeft,
            ),
        )
    }

    @Test
    fun `mouse secondary and tertiary buttons do not trigger reader tap navigation`() {
        assertNull(
            tapNavRegionForPointerButton(
                button = PointerButton.Secondary,
                x = 50f,
                y = 150f,
                width = W,
                height = H,
                mode = NavigationMode.RightAndLeft,
            ),
        )
        assertNull(
            tapNavRegionForPointerButton(
                button = PointerButton.Tertiary,
                x = 250f,
                y = 150f,
                width = W,
                height = H,
                mode = NavigationMode.RightAndLeft,
            ),
        )
    }
}
