package mihon.desktop.reader

import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.test.runTest
import mihon.domain.reader.PageDecodeRequest
import mihon.domain.reader.PageDecodeResult
import mihon.domain.reader.PixelBounds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class SkiaImageDecoderTest {
    @Test
    fun `all eight encoded EXIF orientations normalize actual Skia pixels exactly once`() {
        val raw = requireNotNull(javaClass.classLoader?.getResourceAsStream("reader/exif-corners-raw.jpg")).use {
            it.readBytes()
        }
        val colors = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffff00.toInt())
        mihon.domain.reader.ReaderImageOrientation.entries.forEach { orientation ->
            val exif = "45786966000049492a0008000000010012010300010000000100000000000000"
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray().also { it[24] = orientation.exifValue.toByte() }
            val encoded =
                raw.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (exif.size + 2).toByte()) + exif +
                    raw.copyOfRange(2, raw.size)
            val pixels = SkiaImageDecoder.decode(encoded)
            val size = orientation.orientedSize(60, 40)
            assertEquals(size.width to size.height, pixels.width to pixels.height)
            assertEquals(orientation, mihon.domain.reader.readImageOrientation(encoded))
            val bitmap = pixels.asSkiaBitmap()
            for ((x, y) in listOf(
                size.width / 4 to size.height / 4,
                size.width * 3 / 4 to size.height / 4,
                size.width / 4 to size.height * 3 / 4,
                size.width * 3 / 4 to size.height * 3 / 4,
            )) {
                val(sx, sy) = orientation.sourcePixel(x, y, 60, 40)
                val expected = colors[(if (sy < 20) 0 else 2) + (if (sx < 30) 0 else 1)]
                val actual = bitmap.getColor(x, y)
                for (shift in listOf(0, 8, 16)) {
                    assertTrue(
                        kotlin.math.abs((actual ushr shift and 255) - (expected ushr shift and 255)) < 30,
                        "$orientation normalized pixel($x,$y) must have the expected encoded corner color",
                    )
                }
            }
        }
    }

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
    fun `encoded EXIF quarter turn bounds match the actual Skia decoded pixels`() {
        val raw = makeJpegBytes(60, 40)
        val exif = "45786966000049492a0008000000010012010300010000000600000000000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val encoded =
            raw.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (exif.size + 2).toByte()) + exif +
                raw.copyOfRange(2, raw.size)
        val pixels = SkiaImageDecoder.decode(encoded)
        assertEquals(
            40 to 60,
            pixels.width to pixels.height,
            "This fixture must prove which EXIF orientation the production decoder actually renders",
        )
        assertEquals(
            pixels.width to pixels.height,
            SkiaImageDecoder.peekSize(encoded),
            "Classification must use the same effective input orientation as rendered pixels",
        )
    }

    @Test
    fun `large legal JPEG dimensions remain available from the bounded encoded header`() {
        val image = BufferedImage(1200, 1600, BufferedImage.TYPE_INT_RGB)
        val noise = java.util.Random(19L)
        val row = IntArray(image.width)
        repeat(image.height) { y ->
            repeat(row.size) { x -> row[x] = noise.nextInt(0x1000000) }
            image.setRGB(0, y, image.width, 1, row, 0, image.width)
        }
        val encoded = ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
        val bound = mihon.domain.reader.ReaderDualPageFactLimits.MAX_HEADER_BYTES.toInt()
        assertTrue(encoded.size > bound, "This must exercise a truncated real JPEG, not a small complete image")
        assertEquals(1200 to 1600, SkiaImageDecoder.peekSize(encoded.copyOf(bound)))
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
    fun `decodeDownsampled materializes a standard large manga page within presentation bounds`() {
        val bytes = makeJpegBytes(2400, 3500)

        val bitmap = SkiaImageDecoder.decodeDownsampled(bytes, maxWidth = 2048, maxHeight = 2048)

        assertEquals(1200, bitmap.width)
        assertEquals(1750, bitmap.height)
        assertTrue(bitmap.asSkiaBitmap().readPixels() != null)
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
}
