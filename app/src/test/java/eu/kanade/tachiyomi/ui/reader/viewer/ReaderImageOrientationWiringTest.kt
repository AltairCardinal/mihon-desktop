package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import mihon.domain.reader.ReaderImageOrientation
import mihon.domain.reader.ReaderPageFactState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderImageOrientationWiringTest {
    @Test
    fun `actual SSIV source regions keep each normalized odd half and remaining direction once`() {
        val context = RuntimeEnvironment.getApplication()
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        ReaderImageOrientation.entries.forEach { orientation ->
            val size = orientation.orientedSize(61, 41)
            mihon.domain.reader.PageSplitHalf.entries.forEach { half ->
                val bitmap = Bitmap.createBitmap(61, 41, Bitmap.Config.ARGB_8888)
                for (x in 0 until 61) {
                    for (y in 0 until 41) {
                        bitmap.setPixel(
                            x,
                            y,
                            colors[
                                (if (y < 20) 0 else 2) +
                                    (if (x < 30) 0 else 1),
                            ],
                        )
                    }
                }
                val bounds = requireNotNull(orientation.splitBounds(61, 41, half))
                val view = ReaderPageImageView(context)
                view.setImage(
                    BitmapDrawable(context.resources, bitmap),
                    ReaderPageImageView.Config(
                        0,
                        sourceOrientation = orientation,
                        sourceBounds = bounds,
                    ),
                )
                val start = if (half == mihon.domain.reader.PageSplitHalf.LEFT) 0 else size.width / 2
                val width = if (half ==
                    mihon.domain.reader.PageSplitHalf.LEFT
                ) {
                    size.width / 2
                } else {
                    size.width - size.width / 2
                }
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(width * 4, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size.height * 4, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, width * 4, size.height * 4)
                val output = Bitmap.createBitmap(width * 4, size.height * 4, Bitmap.Config.ARGB_8888)
                repeat(3) {
                    view.draw(Canvas(output))
                    shadowOf(Looper.getMainLooper()).idle()
                }
                val child = ReflectionHelpers.getField<SubsamplingScaleImageView>(view, "pageView")
                assertTrue(child.isReady)
                assertEquals("$orientation $half original source width", bounds.width, child.sWidth)
                assertEquals("$orientation $half original source height", bounds.height, child.sHeight)
                for ((x, y)in listOf(
                    width / 4 to size.height / 4,
                    width * 3 / 4 to size.height / 4,
                    width / 4 to size.height * 3 / 4,
                    width * 3 / 4 to size.height * 3 / 4,
                )) {
                    val(sx, sy) = orientation.sourcePixel(start + x, y, 61, 41)
                    assertEquals(
                        "$orientation $half normalized half pixel",
                        colors[
                            (if (sy < 20) 0 else 2) +
                                (if (sx < 30) 0 else 1),
                        ],
                        output.getPixel(x * 4, y * 4),
                    )
                }
                view.recycle()
                output.recycle()
            }
        }
    }

    @Test
    fun `failed metadata retry preserves the confirmed direction of the same encoded stream`() {
        val raw = requireNotNull(javaClass.classLoader?.getResourceAsStream("reader/exif-corners-raw.jpg")).use {
            it.readBytes()
        }
        val encoded = withExif(raw, 6)
        var fail = false
        val page =
            ReaderPage(0, stream = {
                if (fail) throw java.io.IOException("controlled metadata retry") else encoded.inputStream()
            })
        page.publishHeaderFact()
        val identity = page.contentIdentity()
        assertEquals(ReaderImageOrientation.CLOCKWISE, page.pageFact.value.sourceOrientation)
        fail = true
        page.publishHeaderFact()
        assertEquals(ReaderPageFactState.FAILED, page.pageFact.value.state)
        assertEquals(ReaderImageOrientation.CLOCKWISE, page.pageFact.value.sourceOrientation)
        assertEquals(identity, page.contentIdentity())
        page.retryHeaderFact()
        assertEquals(ReaderPageFactState.UNKNOWN, page.pageFact.value.state)
        assertEquals(ReaderImageOrientation.CLOCKWISE, page.pageFact.value.sourceOrientation)
        fail = false
        page.publishHeaderFact()
        assertEquals(ReaderPageFactState.PORTRAIT, page.pageFact.value.state)
        assertEquals(
            "Retry of the same direction must not manufacture another content epoch",
            identity,
            page.contentIdentity(),
        )
    }

    @Test
    fun `mirrored tiled child maps real touch and physical horizontal pan without rotating twice`() {
        val context = RuntimeEnvironment.getApplication()
        val bitmap = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        val view = ReaderPageImageView(context)
        view.setImage(
            BitmapDrawable(context.resources, bitmap),
            ReaderPageImageView.Config(
                0,
                sourceOrientation = ReaderImageOrientation.MIRROR_HORIZONTAL,
            ),
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 120, 80)
        val output = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        view.draw(canvas)
        val child = ReflectionHelpers.getField<SubsamplingScaleImageView>(view, "pageView")
        var touchedX = -1f
        child.setOnTouchListener { _, event ->
            touchedX = event.x
            true
        }
        val event = MotionEvent.obtain(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_DOWN,
            15f,
            30f,
            0,
        )
        assertTrue(view.dispatchTouchEvent(event))
        assertEquals(105f, touchedX, 0.01f)
        event.recycle()
        child.setOnTouchListener(null)
        child.setScaleAndCenter(4f, PointF(30f, 20f))
        view.draw(canvas)
        assertTrue(view.canPanLeft())
        view.panLeft()
        val end = System.nanoTime() + 1_000_000_000L
        while (System.nanoTime() < end && kotlin.math.abs(requireNotNull(child.center).x - 45f) > 0.01f) {
            Thread.sleep(10)
            view.draw(canvas)
        }
        assertEquals(45f, requireNotNull(child.center).x, 0.01f)
        assertTrue(
            "The actual animation must reach the mirrored physical edge, center=${child.center}",
            !view.canPanLeft(),
        )
        assertTrue(view.canPanRight())
        view.recycle()
        output.recycle()
    }

    @Test
    fun `real bounded JPEG header publishes normalized dimensions and stable`() {
        val raw = requireNotNull(javaClass.classLoader?.getResourceAsStream("reader/exif-corners-raw.jpg")).use {
            it.readBytes()
        }
        ReaderImageOrientation.entries.forEach { orientation ->
            val encoded = withExif(raw, orientation.exifValue)
            val page = ReaderPage(0, stream = { encoded.inputStream() })
            val before = page.contentIdentity()
            page.publishHeaderFact()
            val fact = page.captureContentFact().second.fact
            val size = orientation.orientedSize(60, 40)
            assertEquals(size.width, fact.width)
            assertEquals(size.height, fact.height)
            assertEquals(orientation, fact.sourceOrientation)
            assertEquals(
                if (size.width >=
                    size.height
                ) {
                    ReaderPageFactState.SPREAD
                } else {
                    ReaderPageFactState.PORTRAIT
                },
                fact.state,
            )
            if (orientation != ReaderImageOrientation.NORMAL) assertTrue(before != page.contentIdentity())
            val stable = page.contentIdentity()
            page.publishHeaderFact()
            assertEquals("The same direction fact must not repeatedly rebind or rotate", stable, page.contentIdentity())
        }
    }

    @Test
    fun `actual tiled view bitmap draw normalizes all eight EXIF four corner patterns`() {
        val context = RuntimeEnvironment.getApplication()
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        ReaderImageOrientation.entries.forEach { orientation ->
            val bitmap = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888)
            for (x in 0 until 60) {
                for (y in 0 until 40) {
                    bitmap.setPixel(
                        x,
                        y,
                        colors[
                            (if (y < 20) 0 else 2) +
                                (if (x < 30) 0 else 1),
                        ],
                    )
                }
            }
            val view = ReaderPageImageView(context)
            view.setImage(
                BitmapDrawable(context.resources, bitmap),
                ReaderPageImageView.Config(0, sourceOrientation = orientation),
            )
            val size = orientation.orientedSize(60, 40)
            val width = size.width * 2
            val height = size.height * 2
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val child = ReflectionHelpers.getField<SubsamplingScaleImageView>(view, "pageView")
            val canvas = Canvas(output)
            repeat(3) {
                view.draw(canvas)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertTrue("$orientation must have initialized the actual SSIV bitmap", child.isReady)
            assertTrue("$orientation source pixels must remain valid", !bitmap.isRecycled)
            assertTrue(
                "$orientation must draw real pixels before checking their direction, scale=${child.scale}",
                output.getPixel(width / 2, height / 2) != Color.TRANSPARENT,
            )
            for ((x, y) in listOf(
                size.width / 4 to size.height / 4,
                size.width * 3 / 4 to size.height / 4,
                size.width / 4 to size.height * 3 / 4,
                size.width * 3 / 4 to size.height * 3 / 4,
            )) {
                val (sx, sy) = orientation.sourcePixel(x, y, 60, 40)
                assertEquals(
                    "$orientation normalized($x,$y)",
                    colors[
                        (
                            if (sy <
                                20
                            ) {
                                0
                            } else {
                                2
                            }
                            ) + (if (sx < 30) 0 else 1),
                    ],
                    output.getPixel(x * 2, y * 2),
                )
            }
            assertTrue(ReflectionHelpers.getField<View>(view, "pageView") is SubsamplingScaleImageView)
            view.recycle()
            output.recycle()
        }
    }

    private fun withExif(raw: ByteArray, orientation: Int): ByteArray {
        val exif = "45786966000049492a0008000000010012010300010000000100000000000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray().also { it[24] = orientation.toByte() }
        return raw.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (exif.size + 2).toByte()) + exif +
            raw.copyOfRange(2, raw.size)
    }
}
