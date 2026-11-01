package mihon.desktop.feedback

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.feedback.FeedbackImageSource
import tachiyomi.domain.feedback.FeedbackImageStage
import tachiyomi.domain.feedback.FeedbackScreenshotInput
import tachiyomi.domain.feedback.NativeFeedbackException
import tachiyomi.domain.feedback.NativeFeedbackFailure
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class DesktopFeedbackScreenshotProcessorTest {
    @Test
    fun `real transparent PNG becomes bounded white JPEG with actual stage progress`() = runBlocking {
        val original = BufferedImage(3000, 1200, BufferedImage.TYPE_INT_ARGB)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(original, "png", it) }.toByteArray()
        val stages = mutableListOf<FeedbackImageStage>()
        val image = DesktopFeedbackScreenshotProcessor().process(
            FeedbackScreenshotInput(bytes.size.toLong()) { ByteArrayInputStream(bytes) },
            stages::add,
        )
        System.getenv("FEEDBACK_V2_IMAGE_DIR")?.let { directory ->
            java.io.File(directory).mkdirs()
            java.io.File(directory, "windows-final.jpg").writeBytes(image.bytes())
        }
        assertEquals(1920, image.width)
        assertEquals(768, image.height)
        assertEquals("image/jpeg", image.mime)
        assertTrue(image.byteLength <= 524288)
        val finalImage = ImageIO.read(ByteArrayInputStream(image.bytes()))
        assertEquals(image.width, finalImage.width)
        assertEquals(0xffffff, finalImage.getRGB(0, 0) and 0xffffff)
        assertEquals(FeedbackImageStage.entries, stages)
        val copy = image.bytes()
        copy[0] = 0
        assertEquals(0xff.toByte(), image.bytes()[0])
    }

    @Test
    fun `size and binary checks are bounded and do not open known oversized inputs`() = runBlocking {
        var opened = false
        val processor = DesktopFeedbackScreenshotProcessor()
        try {
            processor.process(
                FeedbackScreenshotInput(10L * 1024 * 1024 + 1) {
                    opened = true
                    error("Must not read")
                },
                {},
            )
            error("Oversize accepted")
        } catch (error: NativeFeedbackException) {
            assertEquals(NativeFeedbackFailure.IMAGE_TOO_LARGE, error.failure)
        }
        assertTrue(!opened)
        for (bytes in listOf("GIF89a".encodeToByteArray(), byteArrayOf(0, 1, 2), "not an image".encodeToByteArray())) {
            try {
                processor.process(FeedbackScreenshotInput(null) { ByteArrayInputStream(bytes) }, {})
                error("Accepted")
            } catch (
                error: NativeFeedbackException,
            ) {
                assertEquals(NativeFeedbackFailure.IMAGE_INVALID, error.failure)
            }
        }
        assertThrows(NativeFeedbackException::class.java) { FeedbackImageSource.dimensions(16385, 1) }
        assertThrows(NativeFeedbackException::class.java) { FeedbackImageSource.dimensions(4001, 4000) }
        Unit
    }

    @Test
    fun `EXIF rotation is baked into final JPEG and source metadata is discarded`() = runBlocking {
        val original = BufferedImage(50, 20, BufferedImage.TYPE_INT_RGB)
        val graphics = original.createGraphics()
        graphics.color = java.awt.Color.RED
        graphics.fillRect(0, 0, 25, 20)
        graphics.color = java.awt.Color.BLUE
        graphics.fillRect(25, 0, 25, 20)
        graphics.dispose()
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(original, "jpeg", it) }.toByteArray()
        val exif = byteArrayOf(
            -1, -31, 0, 34, 69, 120, 105, 102, 0, 0,
            77, 77, 0, 42, 0, 0, 0, 8, 0, 1, 1, 18, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0,
        )
        val bytes = jpeg.copyOfRange(0, 2) + exif + jpeg.copyOfRange(2, jpeg.size)
        val image = DesktopFeedbackScreenshotProcessor().process(
            FeedbackScreenshotInput(bytes.size.toLong()) { ByteArrayInputStream(bytes) },
            {},
        )
        assertEquals(20, image.width)
        assertEquals(50, image.height)
        val decoded = ImageIO.read(ByteArrayInputStream(image.bytes()))
        assertTrue(java.awt.Color(decoded.getRGB(10, 10)).red > 200)
        assertTrue(java.awt.Color(decoded.getRGB(10, 40)).blue > 200)
        assertEquals(1, FeedbackImageSource.orientation(image.bytes()))
    }
}
