package mihon.desktop.ui.reader

import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.test.runTest
import mihon.desktop.reader.PagePreloader
import mihon.desktop.reader.SkiaImageDecoder
import mihon.domain.reader.PageRotation
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.splitPageBounds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ReaderPageCacheIntegrationTest {

    @Test
    fun `late preload revision replaces the ordinary full image request with a cache hit`() = runTest {
        val bytes = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "png", it)
        }.toByteArray()
        val preloader = PagePreloader(fetcher = { bytes }, windowSize = 0)
        val url = "https://example.invalid/page.png"
        val initialRevision = preloader.cacheRevision.value

        assertEquals(url, readerPagePainterModel(url, preloader.get(0)))

        preloader.preload(0, listOf(url))

        assertTrue(preloader.cacheRevision.value > initialRevision)
        assertNull(readerPagePainterModel(url, preloader.get(0)))
    }

    @Test
    fun `cache hit applies left right and rotated virtual page source bounds`() {
        val horizontal = decodeBitmap(
            width = 6,
            height = 2,
            colorAt = { x, _ -> if (x < 3) RED else BLUE },
        )

        val left = transformCachedPageBitmap(horizontal, splitHalf = PageSplitHalf.LEFT)
        val right = transformCachedPageBitmap(horizontal, splitHalf = PageSplitHalf.RIGHT)

        assertEquals(3, left.width)
        assertEquals(RED, left.asSkiaBitmap().getColor(0, 0))
        assertEquals(3, right.width)
        assertEquals(BLUE, right.asSkiaBitmap().getColor(0, 0))

        val vertical = decodeBitmap(
            width = 2,
            height = 6,
            colorAt = { _, y -> if (y < 3) GREEN else MAGENTA },
        )
        val rotatedLeftBounds = requireNotNull(
            splitPageBounds(2, 6, PageSplitHalf.LEFT, PageRotation.CLOCKWISE_90),
        )
        val rotatedRightBounds = requireNotNull(
            splitPageBounds(2, 6, PageSplitHalf.RIGHT, PageRotation.CLOCKWISE_90),
        )

        val rotatedLeft = transformCachedPageBitmap(
            vertical,
            splitHalf = PageSplitHalf.LEFT,
            sourceBounds = rotatedLeftBounds,
        )
        val rotatedRight = transformCachedPageBitmap(
            vertical,
            splitHalf = PageSplitHalf.RIGHT,
            sourceBounds = rotatedRightBounds,
        )

        assertEquals(3, rotatedLeft.height)
        assertEquals(MAGENTA, rotatedLeft.asSkiaBitmap().getColor(0, 0))
        assertEquals(3, rotatedRight.height)
        assertEquals(GREEN, rotatedRight.asSkiaBitmap().getColor(0, 0))
    }

    @Test
    fun `cache hit applies pager border crop before display`() {
        val bordered = decodeBitmap(
            width = 6,
            height = 6,
            colorAt = { x, y -> if (x in 2..3 && y in 1..4) BLACK else WHITE },
        )

        val cropped = transformCachedPageBitmap(bordered, cropBorders = true)

        assertEquals(2, cropped.width)
        assertEquals(4, cropped.height)
        assertEquals(BLACK, cropped.asSkiaBitmap().getColor(0, 0))
    }

    private fun decodeBitmap(
        width: Int,
        height: Int,
        colorAt: (x: Int, y: Int) -> Int,
    ) = ByteArrayOutputStream().use { output ->
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        repeat(height) { y ->
            repeat(width) { x -> image.setRGB(x, y, colorAt(x, y)) }
        }
        ImageIO.write(image, "png", output)
        SkiaImageDecoder.decode(output.toByteArray())
    }

    private companion object {
        const val RED: Int = 0xFFFF0000.toInt()
        const val BLUE: Int = 0xFF0000FF.toInt()
        const val GREEN: Int = 0xFF00FF00.toInt()
        const val MAGENTA: Int = 0xFFFF00FF.toInt()
        const val BLACK: Int = 0xFF000000.toInt()
        const val WHITE: Int = 0xFFFFFFFF.toInt()
    }
}
