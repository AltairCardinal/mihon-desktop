package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.view.KeyEvent
import eu.kanade.tachiyomi.ui.reader.HostReaderArtFixture
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Formal candidate's original JNI sampler is proved by a nondefault visible pairing change. */
class SharedSpreadNativeAcceptanceTest {
    @Test
    fun originalJniNormalizesExifOnceAndSamplesStraightSrgbAlpha() {
        HostReaderArtFixture("copymanga").use { fixture ->
            for (exif in 1..9) {
                val alpha = exif == 9
                fixture.images.set(
                    List(22) { index ->
                        encodedPage(
                            index,
                            if (alpha ||
                                index != 2
                            ) {
                                1
                            } else {
                                exif
                            },
                            alpha,
                        )
                    },
                )
                fixture.cold()
                fixture.configure(mode = 6, matching = false)
                val reads = fixture.imageRequests.get()
                val secondReads = fixture.imagePageRequests.get(2)
                val thirdReads = fixture.imagePageRequests.get(3)
                fixture.launch()
                fixture.await("EXIF $exif cover must first render through native reader") {
                    fixture.containsColor(Color.CYAN)
                }
                fixture.key(KeyEvent.KEYCODE_DPAD_LEFT)
                fixture.awaitStable("EXIF $exif matching off must stably show default group 1/2") {
                    fixture.currentImages().size == 2 && fixture.containsColor(Color.RED) &&
                        fixture.containsColor(Color.GREEN)
                }
                fixture.matching(true)
                fixture.awaitStable("EXIF $exif matching on must stably show nondefault single page 1") {
                    fixture.currentImages().size == 1 && fixture.containsColor(Color.RED) &&
                        !fixture.containsColor(Color.GREEN) &&
                        fixture.imagePageRequests.get(2) > secondReads && fixture.imagePageRequests.get(3) > thirdReads
                }
                assertTrue(
                    "Matching vector must consume fresh original source body",
                    fixture.imageRequests.get() > reads,
                )
            }
        }
    }

    /** Fixture pixels, transformed by Android Matrix then labelled with original EXIF metadata. */
    private fun encodedPage(index: Int, exif: Int, alpha: Boolean): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(
            when (index) {
                0 -> Color.CYAN
                1 -> Color.RED
                2 -> Color.GREEN
                3 -> Color.BLUE
                else -> Color.WHITE
            },
        )
        if (index == 2 || index == 3) {
            for (y in 0 until 60) {
                for (x in 0 until 6) {
                    val channel = if (alpha) {
                        if (y < 30) 60 else 240
                    } else {
                        if (y < 30) 30 else 210
                    }
                    val color = if (alpha && index == 2) {
                        Color.argb(128, channel, 0, 0)
                    } else if (alpha) {
                        Color.rgb((channel * 128 + 255 * 127 + 127) / 255, 127, 127)
                    } else {
                        Color.rgb(channel, 30, 30)
                    }
                    bitmap.setPixel(if (index == 2) x else 39 - x, y, color)
                }
            }
        }
        val matrix = Matrix().apply {
            when (exif) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> setValues(floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
                6 -> setRotate(-90f)
                7 -> setValues(floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f))
                8 -> setRotate(90f)
            }
        }
        val rawBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
        val raw = ByteArrayOutputStream().also {
            rawBitmap.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 100, it)
        }.toByteArray()
        if (rawBitmap !== bitmap) rawBitmap.recycle()
        bitmap.recycle()
        if (alpha) return raw
        val metadata = "45786966000049492a0008000000010012010300010000000100000000000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray().also { it[24] = exif.toByte() }
        return raw.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (metadata.size + 2).toByte()) + metadata +
            raw.copyOfRange(2, raw.size)
    }
}
