package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.HostReaderArtFixture
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayOutputStream

/** Signed candidate's Activity creates the real holder/config/SSIV; only original image bytes vary. */
@RunWith(Parameterized::class)
class SharedReaderViewNativeAcceptanceTest(private val exif: Int, private val variant: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "EXIF{0}-variant{1}")
        fun vectors(): List<Array<Any>> = (1..8).flatMap { exif ->
            (0..2).map { variant -> arrayOf<Any>(exif, variant) }
        }
    }

    @Test
    fun actualReaderViewExifSplitFitCropAndNativeTouch() {
        val expected = listOf(
            listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
            listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE),
            listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
            listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
            listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
        )
        HostReaderArtFixture("manhuagui").use { fixture ->
            fixture.image.set(encodedCorners(if (exif < 5) 800 else 600, if (exif < 5) 600 else 800, exif))
            fixture.cold()
            fixture.configure(
                split = variant != 0,
                invert = variant == 2,
                crop = variant == 2,
                scale = if (variant == 2) {
                    SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH
                } else {
                    SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
                },
            )
            val requests = fixture.imageRequests.get()
            fixture.launch()
            val colors = expected[exif - 1]
            val probes = when (variant) {
                1 -> listOf(colors[0], colors[1], colors[0], colors[1])
                2 -> listOf(colors[2], colors[3], colors[2], colors[3])
                else -> colors
            }
            fixture.awaitStable("EXIF $exif variant $variant current native region must paint golden corners") {
                fixture.quadrants(probes)
            }
            if (variant == 2) {
                val cropped = fixture.coloredBounds(probes)
                fixture.configure(
                    split = true,
                    invert = true,
                    crop = false,
                    scale = SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH,
                )
                fixture.awaitStable("EXIF $exif crop disabled must preserve golden content and restore white margin") {
                    fixture.quadrants(probes) && fixture.coloredBounds(probes).width() < cropped.width() - 8
                }
                val uncropped = fixture.coloredBounds(probes)
                assertTrue(
                    "EXIF $exif actual crop must remove white margin and enlarge preserved content",
                    cropped.width() > uncropped.width() + 8,
                )
            }
            assertTrue(
                "New EXIF fixture must actually fetch original bytes",
                fixture.imageRequests.get() > requests,
            )
            if (variant == 0) {
                val before = fixture.coloredBounds(colors)
                val x = before.exactCenterX()
                val y = before.exactCenterY()
                val now = SystemClock.uptimeMillis()
                fixture.touch(MotionEvent.ACTION_DOWN, now, now, x, y)
                fixture.touch(MotionEvent.ACTION_UP, now, now + 30, x, y)
                fixture.touch(MotionEvent.ACTION_DOWN, now + 100, now + 100, x, y)
                fixture.touch(MotionEvent.ACTION_UP, now + 100, now + 130, x, y)
                fixture.await("EXIF $exif real double tap must enlarge actual painted content") {
                    val after = fixture.coloredBounds(colors)
                    after.height() > before.height() * 1.1 || after.width() > before.width() * 1.1
                }
                val red = fixture.coloredBounds(listOf(Color.RED))
                assertTrue("Current zoomed RED content must be present", red.width() > 0 && red.height() > 0)
                val drag = SystemClock.uptimeMillis()
                fixture.touch(MotionEvent.ACTION_DOWN, drag, drag, x, y)
                fixture.touch(MotionEvent.ACTION_MOVE, drag, drag + 40, x + 40, y)
                fixture.touch(MotionEvent.ACTION_MOVE, drag, drag + 80, x + 180, y)
                fixture.touch(MotionEvent.ACTION_UP, drag, drag + 120, x + 180, y)
                fixture.await("EXIF $exif real drag must pan painted source pixels") {
                    val after = fixture.coloredBounds(listOf(Color.RED))
                    after.width() > 0 && after.height() > 0 &&
                        (
                            kotlin.math.abs(after.right - red.right) > 20 ||
                                kotlin.math.abs(after.left - red.left) > 20
                            )
                }
            }
        }
    }

    private fun encodedCorners(width: Int, height: Int, exif: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bitmap.setPixel(
                    x,
                    y,
                    when {
                        x < 40 || y < 40 || x >= width - 40 || y >= height - 40 -> Color.WHITE
                        x < 44 || y < 44 || x >= width - 44 || y >= height - 44 ||
                            kotlin.math.abs(
                                x - width / 2,
                            ) < 2 || kotlin.math.abs(y - height / 2) < 2 -> Color.rgb(16, 16, 16)
                        x < width / 2 && y < height / 2 -> Color.RED
                        x >= width / 2 && y < height / 2 -> Color.GREEN
                        x < width / 2 -> Color.BLUE
                        else -> Color.YELLOW
                    },
                )
            }
        }
        val raw = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }.toByteArray()
        bitmap.recycle()
        val metadata = "45786966000049492a0008000000010012010300010000000100000000000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray().also { it[24] = exif.toByte() }
        return raw.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (metadata.size + 2).toByte()) + metadata +
            raw.copyOfRange(2, raw.size)
    }
}
