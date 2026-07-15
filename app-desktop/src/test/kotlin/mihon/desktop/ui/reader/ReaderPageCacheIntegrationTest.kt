package mihon.desktop.ui.reader

import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.test.runTest
import mihon.desktop.reader.PagePreloader
import mihon.desktop.reader.PreloadedPageBitmap
import mihon.desktop.reader.SkiaImageDecoder
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.PageRotation
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.splitPageBounds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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

    @Test
    fun `downsampled cache maps rotated odd virtual halves in original coordinates`() = runTest {
        val bytes = pngBytes(
            width = 8,
            height = 15,
            colorAt = { _, y -> if (y < 8) GREEN else MAGENTA },
        )
        val preloader = PagePreloader(
            fetcher = { bytes },
            windowSize = 0,
            maxDecodedWidth = 4,
            maxDecodedHeight = 8,
            largeImagePixelThreshold = Long.MAX_VALUE,
        )
        preloader.preload(0, listOf("rotated-odd"))
        val cachedPage = requireNotNull(preloader.getCachedPage(0))
        assertEquals(4, cachedPage.bitmap.width)
        assertEquals(7, cachedPage.bitmap.height)
        assertEquals(8, cachedPage.sourceWidth)
        assertEquals(15, cachedPage.sourceHeight)

        val clockwiseLeft = transformCachedPageBitmap(
            cachedPage = cachedPage,
            sourceBounds = requireNotNull(
                splitPageBounds(8, 15, PageSplitHalf.LEFT, PageRotation.CLOCKWISE_90),
            ),
        )
        val clockwiseRight = transformCachedPageBitmap(
            cachedPage = cachedPage,
            sourceBounds = requireNotNull(
                splitPageBounds(8, 15, PageSplitHalf.RIGHT, PageRotation.CLOCKWISE_90),
            ),
        )
        val counterClockwiseLeft = transformCachedPageBitmap(
            cachedPage = cachedPage,
            sourceBounds = requireNotNull(
                splitPageBounds(8, 15, PageSplitHalf.LEFT, PageRotation.COUNTER_CLOCKWISE_90),
            ),
        )
        val counterClockwiseRight = transformCachedPageBitmap(
            cachedPage = cachedPage,
            sourceBounds = requireNotNull(
                splitPageBounds(8, 15, PageSplitHalf.RIGHT, PageRotation.COUNTER_CLOCKWISE_90),
            ),
        )

        assertEquals(3, clockwiseLeft.height)
        assertEquals(MAGENTA, clockwiseLeft.asSkiaBitmap().getColor(0, clockwiseLeft.height - 1))
        assertEquals(4, clockwiseRight.height)
        assertEquals(GREEN, clockwiseRight.asSkiaBitmap().getColor(0, 0))
        assertEquals(3, counterClockwiseLeft.height)
        assertEquals(GREEN, counterClockwiseLeft.asSkiaBitmap().getColor(0, 0))
        assertEquals(4, counterClockwiseRight.height)
        assertEquals(MAGENTA, counterClockwiseRight.asSkiaBitmap().getColor(0, counterClockwiseRight.height - 1))
    }

    @Test
    fun `downsampled cache keeps ordinary split pixels and dimensions`() = runTest {
        val cachedPage = preloadDownsampled(
            width = 14,
            height = 8,
            maxWidth = 7,
            maxHeight = 4,
            colorAt = { x, _ -> if (x < 7) RED else BLUE },
        )

        val left = transformCachedPageBitmap(cachedPage = cachedPage, splitHalf = PageSplitHalf.LEFT)
        val right = transformCachedPageBitmap(cachedPage = cachedPage, splitHalf = PageSplitHalf.RIGHT)

        assertEquals(3, left.width)
        assertEquals(RED, left.asSkiaBitmap().getColor(0, 0))
        assertEquals(4, right.width)
        assertEquals(BLUE, right.asSkiaBitmap().getColor(right.width - 1, 0))
    }

    @Test
    fun `downsampled cache keeps pager border crop and null painter model`() = runTest {
        val cachedPage = preloadDownsampled(
            width = 12,
            height = 12,
            maxWidth = 6,
            maxHeight = 6,
            colorAt = { x, y -> if (x in 2..9 && y in 4..7) BLACK else WHITE },
        )

        val cropped = transformCachedPageBitmap(cachedPage = cachedPage, cropBorders = true)

        assertEquals(4, cropped.width)
        assertEquals(2, cropped.height)
        assertEquals(BLACK, cropped.asSkiaBitmap().getColor(0, 0))
        assertNull(readerPagePainterModel("cached-page", cachedPage.bitmap))
    }

    @Test
    fun `downsampled cache rejects source bounds outside original dimensions`() = runTest {
        val cachedPage = preloadDownsampled(
            width = 8,
            height = 15,
            maxWidth = 4,
            maxHeight = 8,
            colorAt = { _, _ -> RED },
        )

        assertThrows(IllegalArgumentException::class.java) {
            transformCachedPageBitmap(
                cachedPage = cachedPage,
                sourceBounds = PixelBounds(0, 0, 8, 16),
            )
        }
    }

    private suspend fun preloadDownsampled(
        width: Int,
        height: Int,
        maxWidth: Int,
        maxHeight: Int,
        colorAt: (x: Int, y: Int) -> Int,
    ): PreloadedPageBitmap {
        val bytes = pngBytes(width, height, colorAt)
        val preloader = PagePreloader(
            fetcher = { bytes },
            windowSize = 0,
            maxDecodedWidth = maxWidth,
            maxDecodedHeight = maxHeight,
            largeImagePixelThreshold = Long.MAX_VALUE,
        )
        preloader.preload(0, listOf("downsampled"))
        return requireNotNull(preloader.getCachedPage(0))
    }

    private fun decodeBitmap(
        width: Int,
        height: Int,
        colorAt: (x: Int, y: Int) -> Int,
    ) = SkiaImageDecoder.decode(pngBytes(width, height, colorAt))

    private fun pngBytes(
        width: Int,
        height: Int,
        colorAt: (x: Int, y: Int) -> Int,
    ) = ByteArrayOutputStream().use { output ->
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        repeat(height) { y ->
            repeat(width) { x -> image.setRGB(x, y, colorAt(x, y)) }
        }
        ImageIO.write(image, "png", output)
        output.toByteArray()
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
