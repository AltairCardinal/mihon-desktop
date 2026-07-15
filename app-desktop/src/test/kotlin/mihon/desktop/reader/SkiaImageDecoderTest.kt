package mihon.desktop.reader

import kotlinx.coroutines.test.runTest
import androidx.compose.ui.graphics.asSkiaBitmap
import mihon.domain.reader.PageDecodeRequest
import mihon.domain.reader.PageDecodeResult
import mihon.domain.reader.PageCacheCommitResult
import mihon.domain.reader.PageCacheWrite
import mihon.domain.reader.PixelBounds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class SkiaImageDecoderTest {

    private fun makeJpegBytes(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        // Fill with a non-white color so JPEG compression gives a valid output
        val g = img.createGraphics()
        g.color = java.awt.Color(100, 150, 200)
        g.fillRect(0, 0, width, height)
        g.dispose()
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "jpeg", bos)
        return bos.toByteArray()
    }

    private fun makePngBytes(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "png", bos)
        return bos.toByteArray()
    }

    private fun makeSplitColorPng(): ByteArray {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = java.awt.Color.RED
        graphics.fillRect(0, 0, 2, 4)
        graphics.color = java.awt.Color.BLUE
        graphics.fillRect(2, 0, 2, 4)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Test
    fun `decode JPEG returns correct dimensions`() {
        val bytes = makeJpegBytes(100, 80)
        val bitmap = SkiaImageDecoder.decode(bytes)
        assertEquals(100, bitmap.width)
        assertEquals(80, bitmap.height)
    }

    @Test
    fun `decode PNG returns correct dimensions`() {
        val bytes = makePngBytes(120, 60)
        val bitmap = SkiaImageDecoder.decode(bytes)
        assertEquals(120, bitmap.width)
        assertEquals(60, bitmap.height)
    }

    @Test
    fun `peekSize returns correct dimensions`() {
        val bytes = makeJpegBytes(200, 150)
        val (w, h) = SkiaImageDecoder.peekSize(bytes)!!
        assertEquals(200, w)
        assertEquals(150, h)
    }

    @Test
    fun `peekSize returns null for invalid bytes`() {
        assertNull(SkiaImageDecoder.peekSize("not an image".toByteArray()))
    }

    @Test
    fun `decodeDownsampled scales down when image exceeds max dimensions`() {
        // 400x300 → maxWidth=200 → sampleSize=max(400/200,300/200)=max(2,1)=2 → 200x150
        val bytes = makeJpegBytes(400, 300)
        val bitmap = SkiaImageDecoder.decodeDownsampled(bytes, maxWidth = 200, maxHeight = 200)
        assertEquals(200, bitmap.width)
        assertEquals(150, bitmap.height)
    }

    @Test
    fun `decodeDownsampled remains bounded when source is less than twice the requested size`() {
        val bytes = makeJpegBytes(300, 200)

        val bitmap = SkiaImageDecoder.decodeDownsampled(bytes, maxWidth = 200, maxHeight = 200)

        assertTrue(bitmap.width <= 200)
        assertTrue(bitmap.height <= 200)
        assertTrue(bitmap.width < 300)
    }

    @Test
    fun `decodeDownsampled returns full resolution when image fits`() {
        val bytes = makeJpegBytes(100, 80)
        val bitmap = SkiaImageDecoder.decodeDownsampled(bytes, maxWidth = 200, maxHeight = 200)
        assertEquals(100, bitmap.width)
        assertEquals(80, bitmap.height)
    }

    @Test
    fun `peekSize matches decode dimensions`() {
        val bytes = makePngBytes(64, 48)
        val (pw, ph) = SkiaImageDecoder.peekSize(bytes)!!
        val bitmap = SkiaImageDecoder.decode(bytes)
        assertEquals(pw, bitmap.width)
        assertEquals(ph, bitmap.height)
    }

    @Test
    fun `Skia region adapter decodes only the requested tile`() = runTest {
        val bytes = makePngBytes(300, 200)
        val result = SkiaRegionPageDecoder().decodeRegion(
            bytes,
            PageDecodeRequest(
                pageIndex = 0,
                generation = 7,
                maxWidth = 100,
                maxHeight = 100,
                region = PixelBounds(50, 20, 120, 80),
            ),
        ) as PageDecodeResult.Success<*>

        assertEquals(7, result.generation)
        assertEquals(60, result.width)
        assertEquals(40, result.height)
        assertEquals(60 * 40 * 4L, result.estimatedBytes)
    }

    @Test
    fun `Skia region adapter returns pixels from the requested PNG region`() = runTest {
        val result = SkiaRegionPageDecoder().decodeRegion(
            makeSplitColorPng(),
            PageDecodeRequest(
                pageIndex = 0,
                generation = 2,
                maxWidth = 2,
                maxHeight = 4,
                region = PixelBounds(2, 0, 2, 4),
            ),
        ) as PageDecodeResult.Success

        val color = result.value.asSkiaBitmap().getColor(0, 0)
        assertEquals(0xFF, color and 0xFF)
        assertEquals(0, (color shr 16) and 0xFF)
        assertTrue(result.width <= 2)
        assertTrue(result.height <= 4)
    }

    @Test
    fun `byte budgeted page cache evicts least recently used entries deterministically`() {
        val cache = DesktopPageCache(maxBytes = 400)
        val first = SkiaImageDecoder.decode(makePngBytes(10, 10))
        val second = SkiaImageDecoder.decode(makePngBytes(10, 10))

        assertTrue(cache.beginGeneration(1, emptySet()))
        assertEquals(PageCacheCommitResult.STORED, cache.commit(PageCacheWrite(1, 1, first, 400)))
        assertEquals(PageCacheCommitResult.STORED, cache.commit(PageCacheWrite(2, 1, second, 400)))

        assertNull(cache.get(1))
        assertNotNull(cache.get(2))
        assertEquals(setOf(2), cache.snapshot().keys)
        assertEquals(400, cache.snapshot().usedBytes)
    }

    @Test
    fun `byte budgeted page cache rejects oversized decoded values without evicting entries`() {
        val cache = DesktopPageCache(maxBytes = 400)
        val bitmap = SkiaImageDecoder.decode(makePngBytes(10, 10))
        cache.beginGeneration(1, emptySet())
        cache.commit(PageCacheWrite(1, 1, bitmap, 400))
        val revisionBeforeOversize = cache.revision.value

        val result = cache.commit(PageCacheWrite(2, 1, bitmap, 401))

        assertEquals(PageCacheCommitResult.REJECTED_OVERSIZED, result)
        assertEquals(setOf(1), cache.snapshot().keys)
        assertEquals(400, cache.snapshot().usedBytes)
        assertEquals(revisionBeforeOversize, cache.revision.value)
    }

    @Test
    fun `zero byte cache keeps shared no decoded memory semantics`() {
        val cache = DesktopPageCache(maxBytes = 0)
        val bitmap = SkiaImageDecoder.decode(makePngBytes(1, 1))
        cache.beginGeneration(1, emptySet())

        assertEquals(
            PageCacheCommitResult.REJECTED_OVERSIZED,
            cache.commit(PageCacheWrite(0, 1, bitmap, 4)),
        )
        assertTrue(cache.snapshot().keys.isEmpty())
    }
}
