package mihon.desktop.ui.reader

import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import mihon.desktop.reader.ZoomState
import mihon.domain.reader.PixelBounds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderRegionTileViewportTest {

    @Test
    fun `Fit center maps the viewport to the complete rendered source bounds`() {
        val visible = resolveReaderVisibleSourceBounds(
            viewportSize = IntSize(1_000, 800),
            renderedBitmapSize = IntSize(4_000, 2_000),
            renderedSourceBounds = PixelBounds(0, 0, 4_000, 2_000),
            contentScale = ContentScale.Fit,
            alignment = Alignment.Center,
            zoomState = ZoomState(),
        )

        assertEquals(PixelBounds(0, 0, 4_000, 2_000), visible)
    }

    @Test
    fun `FillWidth maps only the vertically visible source strip`() {
        val visible = resolveReaderVisibleSourceBounds(
            viewportSize = IntSize(1_000, 800),
            renderedBitmapSize = IntSize(1_000, 2_000),
            renderedSourceBounds = PixelBounds(0, 0, 1_000, 2_000),
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.Center,
            zoomState = ZoomState(),
        )

        assertEquals(PixelBounds(0, 600, 1_000, 800), visible)
    }

    @Test
    fun `zoom and pan are inverted around the local viewport center`() {
        val visible = resolveReaderVisibleSourceBounds(
            viewportSize = IntSize(1_000, 1_000),
            renderedBitmapSize = IntSize(1_000, 1_000),
            renderedSourceBounds = PixelBounds(0, 0, 1_000, 1_000),
            contentScale = ContentScale.Fit,
            alignment = Alignment.Center,
            zoomState = ZoomState(scale = 2f, offsetX = 100f, offsetY = -200f),
        )

        assertEquals(PixelBounds(200, 350, 500, 500), visible)
    }

    @Test
    fun `dual page slots resolve in their own half-width coordinate systems`() {
        val leftSlot = resolveReaderVisibleSourceBounds(
            viewportSize = IntSize(500, 800),
            renderedBitmapSize = IntSize(1_000, 2_000),
            renderedSourceBounds = PixelBounds(0, 0, 1_000, 2_000),
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.CenterEnd,
            zoomState = ZoomState(),
        )
        val rightSlot = resolveReaderVisibleSourceBounds(
            viewportSize = IntSize(500, 800),
            renderedBitmapSize = IntSize(1_000, 2_000),
            renderedSourceBounds = PixelBounds(0, 0, 1_000, 2_000),
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.CenterStart,
            zoomState = ZoomState(),
        )

        assertEquals(PixelBounds(0, 200, 1_000, 1_600), leftSlot)
        assertEquals(leftSlot, rightSlot)
    }

    @Test
    fun `cropped presentation maps back to source bounds and clamps outside viewport coverage`() {
        val visible = resolveReaderVisibleSourceBounds(
            viewportSize = IntSize(800, 600),
            renderedBitmapSize = IntSize(600, 600),
            renderedSourceBounds = PixelBounds(1_000, 500, 4_000, 3_000),
            contentScale = ContentScale.Fit,
            alignment = Alignment.Center,
            zoomState = ZoomState(scale = 2f, offsetX = 700f),
        )

        assertEquals(PixelBounds(1_000, 1_250, 1_000, 1_500), visible)
    }

    @Test
    fun `LOD chooses the floor power of two without undershooting one`() {
        assertEquals(
            ReaderRegionTileLevel(sampleSize = 1, sourceTileExtent = 2_048),
            resolveReaderRegionTileLevel(sourcePixelsPerScreenPixel = 0.75f, tileOutputExtent = 2_048),
        )
        assertEquals(
            ReaderRegionTileLevel(sampleSize = 2, sourceTileExtent = 4_096),
            resolveReaderRegionTileLevel(sourcePixelsPerScreenPixel = 3.9f, tileOutputExtent = 2_048),
        )
        assertEquals(
            ReaderRegionTileLevel(sampleSize = 4, sourceTileExtent = 8_192),
            resolveReaderRegionTileLevel(sourcePixelsPerScreenPixel = 4f, tileOutputExtent = 2_048),
        )
    }

    @Test
    fun `2048 tile grid aligns to rendered source origin and clips page edges`() {
        val tiles = buildReaderRegionTileGrid(
            visibleSourceBounds = PixelBounds(3_000, 1_600, 2_500, 2_000),
            renderedSourceBounds = PixelBounds(1_000, 500, 5_000, 3_500),
            sourceTileExtent = 2_048,
        )

        assertEquals(
            listOf(
                PixelBounds(1_000, 500, 2_048, 2_048),
                PixelBounds(3_048, 500, 2_048, 2_048),
                PixelBounds(5_096, 500, 904, 2_048),
                PixelBounds(1_000, 2_548, 2_048, 1_452),
                PixelBounds(3_048, 2_548, 2_048, 1_452),
                PixelBounds(5_096, 2_548, 904, 1_452),
            ),
            tiles,
        )
    }
}
