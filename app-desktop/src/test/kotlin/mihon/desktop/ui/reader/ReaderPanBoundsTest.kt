package mihon.desktop.ui.reader

import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import mihon.desktop.reader.ZoomState
import mihon.domain.reader.PixelBounds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReaderPanBoundsTest {
    @Test
    fun `background geometry and pointer input in one frame preserve the accepted focus`() {
        val wide = ReaderPanBounds(IntSize(400, 400), Rect(0f, 0f, 400f, 400f))
        val narrow = ReaderPanBounds(IntSize(400, 400), Rect(180f, 0f, 300f, 400f))
        val accepted = ZoomState(2f, 100f, 0f)
        for (delta in listOf(1f, -1f)) {
            val state = ReaderPanBoundsState()
            assertEquals(accepted, state.update(wide, accepted).constrain(accepted))
            val moved = accepted.copy(offsetX = accepted.offsetX + delta)
            val expected = accepted.copy(offsetX = minOf(100f, moved.offsetX))
            assertEquals(expected, state.update(narrow, moved).constrain(moved))
            val reversed = accepted.copy(offsetX = 99f)
            assertEquals(reversed, state.update(narrow, reversed).constrain(reversed))
        }
    }

    @Test
    fun `offscreen portrait cannot report pixels when only slot whitespace intersects`() {
        val local = resolveReaderRegionDrawGeometry(
            IntSize(700, 1000),
            IntSize(1600, 3200),
            PixelBounds(0, 0, 1600, 3200),
            ContentScale.Fit,
            AbsoluteAlignment.CenterLeft,
        )
        val geometry = ReaderImageViewport(IntSize(1400, 1000), IntOffset(700, 0)).mapGeometry(local)
        assertNull(
            resolveReaderVisibleSourceBounds(
                geometry,
                ReaderRegionViewport.Full,
                ZoomState(2f, -2000f, 0f),
            ),
        )
    }

    @Test
    fun `each side permits at most one third viewport blank space`() {
        val bounds = ReaderPanBounds(IntSize(1200, 900), Rect(0f, 0f, 1200f, 900f))
        assertEquals(ZoomState(2f, 1000f, 750f), bounds.constrain(ZoomState(2f, 10_000f, 10_000f)))
        assertEquals(ZoomState(2f, -1000f, -750f), bounds.constrain(ZoomState(2f, -10_000f, -10_000f)))
        assertEquals(ZoomState(2f, 90f, -45f), bounds.constrain(ZoomState(2f, 90f, -45f)))
        assertEquals(ZoomState(), bounds.constrain(ZoomState(1f, 30f, 20f)))
    }

    @Test
    fun `small images retain unavoidable centered blank space without additional pan`() {
        val bounds = ReaderPanBounds(IntSize(1200, 900), Rect(550f, 400f, 650f, 500f))
        assertEquals(ZoomState(2f), bounds.constrain(ZoomState(2f, 10_000f, -10_000f)))
    }

    @Test
    fun `bounds follow actual fit geometry and whole spread rather than slot whitespace`() {
        val left = resolveReaderRegionDrawGeometry(
            IntSize(600, 900),
            IntSize(100, 300),
            PixelBounds(0, 0, 100, 300),
            ContentScale.Fit,
            AbsoluteAlignment.CenterRight,
        )
        val right = left.copy(drawOffset = IntOffset(0, 0))
        val bounds = combineReaderPanBounds(
            listOf(
                ReaderImageViewport(IntSize(1200, 900), IntOffset.Zero).mapGeometry(left),
                ReaderImageViewport(IntSize(1200, 900), IntOffset(600, 0)).mapGeometry(right),
            ),
        )
        assertNotNull(bounds)
        assertEquals(Rect(300f, 0f, 900f, 900f), bounds!!.content)
        assertEquals(ZoomState(2f, 400f, 750f), bounds.constrain(ZoomState(2f, 10_000f, 10_000f)))
    }

    @Test
    fun `shrinking scale or viewport corrects an old pan against new geometry`() {
        val bounds = ReaderPanBounds(IntSize(1200, 900), Rect(0f, 0f, 1200f, 900f))
        val old = bounds.constrain(ZoomState(4f, 10_000f, 10_000f))
        assertEquals(ZoomState(2f, 1000f, 750f), bounds.constrain(old.copy(scale = 2f)))
        val resized = ReaderPanBounds(IntSize(600, 450), Rect(0f, 0f, 600f, 450f))
        assertEquals(ZoomState(2f, 500f, 375f), resized.constrain(old.copy(scale = 2f)))
    }
}
