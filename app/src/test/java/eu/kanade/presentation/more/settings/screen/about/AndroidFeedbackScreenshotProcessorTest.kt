package eu.kanade.presentation.more.settings.screen.about

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tachiyomi.domain.feedback.FeedbackImageStage
import tachiyomi.domain.feedback.FeedbackScreenshotInput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidFeedbackScreenshotProcessorTest {
    @Test
    fun `real PNG is reencoded to white JPEG and only completed bytes become preview`() = runBlocking {
        val original = Bitmap.createBitmap(3000, 1200, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { original.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        original.recycle()
        val stages = mutableListOf<FeedbackImageStage>()
        val image = AndroidFeedbackScreenshotProcessor().process(
            FeedbackScreenshotInput(bytes.size.toLong()) { ByteArrayInputStream(bytes) },
            stages::add,
        )
        System.getenv("FEEDBACK_V2_IMAGE_DIR")?.let { directory ->
            java.io.File(directory).mkdirs()
            java.io.File(directory, "android-final.jpg").writeBytes(image.bytes())
        }
        assertEquals(1920, image.width)
        assertEquals(768, image.height)
        assertTrue(image.byteLength <= 524288)
        assertTrue(
            "Final JPEG must not retain encoder ICC metadata",
            !image.bytes().toString(Charsets.ISO_8859_1).contains("ICC_PROFILE"),
        )
        val finalImage = BitmapFactory.decodeByteArray(image.bytes(), 0, image.byteLength)
        assertEquals(image.width, finalImage.width)
        assertEquals(0xffffff, finalImage.getPixel(0, 0) and 0xffffff)
        finalImage.recycle()
        assertEquals(FeedbackImageStage.entries, stages)
    }

    @Test
    fun `large input uses bounded sampling before full pixel decode`() {
        assertEquals(2, feedbackScreenshotSampleSize(4000, 4000))
        assertEquals(8, feedbackScreenshotSampleSize(16000, 1000))
        assertEquals(1, feedbackScreenshotSampleSize(3000, 1200))
        assertEquals(1, feedbackScreenshotSampleSize(40, 30))
    }

    @Test
    fun `real Android JPEG rotation and mirror preserve visible pixel orientation`() = runBlocking {
        for (orientation in listOf(2, 6)) {
            val width = if (orientation == 6) 4000 else 40
            val height = if (orientation == 6) 1000 else 20
            val original = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(original)
            val paint = android.graphics.Paint()
            paint.color = android.graphics.Color.RED
            canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), paint)
            paint.color = android.graphics.Color.BLUE
            canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), paint)
            val jpeg = ByteArrayOutputStream().also {
                original.compress(Bitmap.CompressFormat.JPEG, 85, it)
            }.toByteArray()
            original.recycle()
            val exif = byteArrayOf(
                -1, -31, 0, 34, 69, 120, 105, 102, 0, 0,
                77, 77, 0, 42, 0, 0, 0, 8, 0, 1, 1, 18, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, 0, 0, 0, 0,
            )
            val source = jpeg.copyOfRange(0, 2) + exif + jpeg.copyOfRange(2, jpeg.size)
            val result = AndroidFeedbackScreenshotProcessor().process(
                FeedbackScreenshotInput(source.size.toLong()) { ByteArrayInputStream(source) },
                {},
            )
            val decoded = BitmapFactory.decodeByteArray(result.bytes(), 0, result.byteLength)
            if (orientation == 6) {
                assertEquals(480, decoded.width)
                assertEquals(1920, decoded.height)
                assertTrue(android.graphics.Color.red(decoded.getPixel(240, 200)) > 200)
                assertTrue(android.graphics.Color.blue(decoded.getPixel(240, 1700)) > 200)
            } else {
                assertEquals(40, decoded.width)
                assertEquals(20, decoded.height)
                assertTrue(android.graphics.Color.blue(decoded.getPixel(5, 10)) > 200)
                assertTrue(android.graphics.Color.red(decoded.getPixel(35, 10)) > 200)
            }
            decoded.recycle()
        }
    }
}
