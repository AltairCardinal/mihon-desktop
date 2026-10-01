package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.runBlocking
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.ReaderImageOrientation
import okio.Buffer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/** Executed only in DP02 on ART; Windows compilation does not establish pixel or touch acceptance. */
class SharedReaderViewNativeAcceptanceTest {
    @Test
    fun actualReaderViewExifSplitFitCropAndNativeTouch() = runBlocking {
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
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
        for (orientation in ReaderImageOrientation.entries) {
            val rawWidth = if (orientation.exifValue < 5) 80 else 60
            val rawHeight = if (orientation.exifValue < 5) 60 else 80
            val bytes = encodedCorners(rawWidth, rawHeight, orientation.exifValue)
            for (variant in 0..2) {
                val half = if (variant == 1) PageSplitHalf.LEFT else PageSplitHalf.RIGHT
                val bounds = if (variant == 0) null else orientation.splitBounds(rawWidth, rawHeight, half)
                val owned = bounds?.takeIf { variant == 2 }?.let {
                    encodeReaderPageRegion(
                        Buffer().write(bytes),
                        android.graphics.Rect(
                            it.x,
                            it.y,
                            it.x + it.width,
                            it.y + it.height,
                        ),
                        context.cacheDir,
                    )
                }
                val failure = AtomicReference<Throwable?>()
                lateinit var view: ReaderPageImageView
                instrumentation.runOnMainSync {
                    view = ReaderPageImageView(context).apply {
                        setBackgroundColor(Color.WHITE)
                        onImageLoadError = { failure.set(it ?: IllegalStateException("Native view decode failed")) }
                    }
                    val config = ReaderPageImageView.Config(
                        zoomDuration = 1,
                        sourceOrientation = orientation,
                        sourceBounds = bounds.takeIf { owned == null },
                        cropBorders = variant == 2,
                        minimumScaleType = if (variant ==
                            2
                        ) {
                            SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH
                        } else {
                            SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
                        },
                    )
                    if (owned == null) {
                        view.setImage(Buffer().write(bytes), false, config, 0)
                    } else {
                        view.setRegionImage(owned, config, 0) { true }
                    }
                }
                try {
                    val colors = expected[orientation.exifValue - 1]
                    val probes = when (variant) {
                        1 -> listOf(colors[0], colors[1], colors[0], colors[1])
                        2 -> listOf(colors[2], colors[3], colors[2], colors[3])
                        else -> colors
                    }
                    val deadline = SystemClock.uptimeMillis() + 10_000
                    var matched = false
                    while (!matched && SystemClock.uptimeMillis() < deadline) {
                        failure.get()?.let { throw AssertionError("Actual ReaderPageImageView failed", it) }
                        instrumentation.runOnMainSync {
                            view.measure(
                                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                            )
                            view.layout(0, 0, 300, 300)
                            val pixels = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
                            try {
                                view.draw(Canvas(pixels))
                                matched =
                                    listOf(
                                        75 to 75,
                                        75 to 225,
                                        225 to 75,
                                        225 to 225,
                                    ).zip(probes).all { (point, color) ->
                                        closeColor(pixels.getPixel(point.first, point.second), color)
                                    }
                            } finally {
                                pixels.recycle()
                            }
                        }
                        if (!matched) SystemClock.sleep(20)
                    }
                    assertTrue(
                        "EXIF ${orientation.exifValue}, variant=$variant must paint its actual normalized source region",
                        matched,
                    )
                    if (variant == 0) {
                        val child = view.getChildAt(0) as SubsamplingScaleImageView
                        val before = child.scale
                        instrumentation.runOnMainSync {
                            val now = SystemClock.uptimeMillis()
                            touch(view, now, now, MotionEvent.ACTION_DOWN, 150f, 150f)
                            touch(view, now, now + 30, MotionEvent.ACTION_UP, 150f, 150f)
                            touch(view, now + 100, now + 100, MotionEvent.ACTION_DOWN, 150f, 150f)
                            touch(view, now + 100, now + 130, MotionEvent.ACTION_UP, 150f, 150f)
                        }
                        pump(view, 500)
                        assertTrue("Native double tap must reach the real SSIV zoom", child.scale > before * 1.1f)
                        val center = requireNotNull(child.center)
                        instrumentation.runOnMainSync {
                            val now = SystemClock.uptimeMillis()
                            touch(view, now, now, MotionEvent.ACTION_DOWN, 150f, 150f)
                            touch(view, now, now + 30, MotionEvent.ACTION_MOVE, 170f, 150f)
                            touch(view, now, now + 60, MotionEvent.ACTION_MOVE, 220f, 150f)
                            touch(view, now, now + 90, MotionEvent.ACTION_UP, 220f, 150f)
                        }
                        pump(view, 100)
                        assertTrue(
                            "Native drag must pan the displayed image",
                            requireNotNull(child.center).x != center.x,
                        )
                        assertTrue(view.canPanLeft() || view.canPanRight())
                    }
                } finally {
                    instrumentation.runOnMainSync { view.recycle() }
                    owned?.let { check(it.delete()) }
                }
            }
        }
    }

    private fun pump(view: ReaderPageImageView, duration: Long) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + duration
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                val pixels = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(pixels))
                } finally {
                    pixels.recycle()
                }
            }
            SystemClock.sleep(16)
        }
    }

    private fun touch(view: View, down: Long, time: Long, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(down, time, action, x, y, 0).let { event ->
            try {
                view.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    private fun closeColor(pixel: Int, expected: Int): Boolean = listOf(0, 8, 16).all {
        kotlin.math.abs((pixel ushr it and 255) - (expected ushr it and 255)) < 35
    }

    private fun encodedCorners(width: Int, height: Int, exif: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                bitmap.setPixel(
                    x,
                    y,
                    when {
                        x < 4 || y < 4 || x >= width - 4 || y >= height - 4 -> Color.WHITE
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
