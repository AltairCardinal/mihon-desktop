package mihon.desktop.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.test.runTest
import mihon.domain.reader.PageDecodeRequest
import mihon.domain.reader.PageDecodeResult
import mihon.domain.reader.PixelBounds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopReaderRegionDecoderTest {

    @Test
    fun `large image policy uses a strict 16M pixel threshold with Long arithmetic`() {
        assertEquals(16_000_000L, DesktopReaderLargeImagePolicy.PIXEL_THRESHOLD)
        assertFalse(DesktopReaderLargeImagePolicy.requiresRegionTiles(width = 4_000, height = 4_000))
        assertTrue(DesktopReaderLargeImagePolicy.requiresRegionTiles(width = 4_001, height = 4_000))
        assertTrue(
            DesktopReaderLargeImagePolicy.requiresRegionTiles(
                width = Int.MAX_VALUE,
                height = Int.MAX_VALUE,
            ),
        )
    }

    @Test
    fun `ImageIO region adapter applies source region and subsampling to real pixels`() = runTest {
        val result = ImageIoRegionDecodeAdapter().decodeRegion(
            encoded = coordinatePng(width = 8, height = 6),
            request = PageDecodeRequest(
                pageIndex = 3,
                generation = 41,
                maxWidth = 2,
                maxHeight = 2,
                region = PixelBounds(x = 2, y = 1, width = 4, height = 4),
            ),
        )

        val success = assertInstanceOf(PageDecodeResult.Success::class.java, result)
        assertEquals(41, success.generation)
        assertEquals(2, success.width)
        assertEquals(2, success.height)
        assertEquals(16L, success.estimatedBytes)
        assertTrue(success.isSampled)

        val bitmap = (success.value as ImageBitmap).asSkiaBitmap()
        try {
            assertEquals(coordinateColor(x = 2, y = 1), bitmap.getColor(x = 0, y = 0))
            assertEquals(coordinateColor(x = 4, y = 1), bitmap.getColor(x = 1, y = 0))
            assertEquals(coordinateColor(x = 2, y = 3), bitmap.getColor(x = 0, y = 1))
            assertEquals(coordinateColor(x = 4, y = 3), bitmap.getColor(x = 1, y = 1))
        } finally {
            bitmap.close()
        }
    }

    @Test
    fun `unsupported ImageIO format returns typed failure so an existing preview can remain visible`() = runTest {
        val preview = SkiaImageDecoder.decode(coordinatePng(width = 2, height = 2))
        val previewBitmap = preview.asSkiaBitmap()
        val previewPixel = previewBitmap.getColor(x = 1, y = 1)

        try {
            val result = ImageIoRegionDecodeAdapter().decodeRegion(
                encoded = unsupportedWebpBytes(),
                request = PageDecodeRequest(
                    pageIndex = 5,
                    generation = 73,
                    maxWidth = 256,
                    maxHeight = 256,
                    region = PixelBounds(x = 0, y = 0, width = 1, height = 1),
                ),
            )

            val failure = assertInstanceOf(PageDecodeResult.Failure::class.java, result)
            assertEquals(73, failure.generation)
            assertEquals(previewPixel, previewBitmap.getColor(x = 1, y = 1))
        } finally {
            previewBitmap.close()
        }
    }

    private fun coordinatePng(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, coordinateColor(x, y))
            }
        }
        return ByteArrayOutputStream().also { output ->
            check(ImageIO.write(image, "png", output))
        }.toByteArray()
    }

    private fun coordinateColor(x: Int, y: Int): Int =
        Color(
            (x * 31 + y * 7) % 256,
            (x * 13 + y * 47) % 256,
            (x * 53 + y * 19) % 256,
        ).rgb

    private fun unsupportedWebpBytes(): ByteArray = ByteArray(20).apply {
        "RIFF".encodeToByteArray().copyInto(this, destinationOffset = 0)
        "WEBP".encodeToByteArray().copyInto(this, destinationOffset = 8)
        "VP8 ".encodeToByteArray().copyInto(this, destinationOffset = 12)
    }
}
