package mihon.desktop.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
}
