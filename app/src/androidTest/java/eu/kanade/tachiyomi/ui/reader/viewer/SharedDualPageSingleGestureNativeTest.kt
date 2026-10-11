package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.os.SystemClock
import android.view.MotionEvent
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.HostReaderArtFixture
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DisplayPage
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPagerPageHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class SharedDualPageSingleGestureNativeTest {
    @Test
    fun halfSlotSingleZoomUsesTheFullNativeViewportAndKeepsItsOriginalImage() {
        HostReaderArtFixture("copymanga").use { fixture ->
            val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
            bitmap.recycle()
            fixture.images.set(List(22) { encoded })
            fixture.response.set(
                200 to fixture.successWithImageIdentity("single_gesture_${SystemClock.uptimeMillis()}"),
            )
            fixture.configure(mode = 6)
            fixture.cold()
            fixture.launch(initialPage = 0)
            fixture.awaitStable("The original cover must paint as a single page") { fixture.containsColor(Color.RED) }
            val before = fixture.observeSlot(Color.RED)
            val image = before.mount as ReaderPageImageView
            var baseScale = 0f
            fixture.onReaderMain {
                val holder = generateSequence(image as android.view.View) { it.parent as? android.view.View }
                    .filterIsInstance<DualPagerPageHolder>().first()
                assertTrue(holder.displayPage is DisplayPage.Single)
                baseScale = (image.getChildAt(0) as SubsamplingScaleImageView).scale
            }
            val now = SystemClock.uptimeMillis()
            val x = before.content.exactCenterX()
            val y = before.content.exactCenterY()
            fixture.touchWindow(MotionEvent.ACTION_DOWN, now, now, x, y)
            fixture.touchWindow(MotionEvent.ACTION_UP, now, now + 30, x, y)
            fixture.touchWindow(MotionEvent.ACTION_DOWN, now + 100, now + 100, x, y)
            fixture.touchWindow(MotionEvent.ACTION_UP, now + 100, now + 130, x, y)
            fixture.awaitStable("A single page double tap must reach the same native and viewport zoom") {
                var zoomed = false
                fixture.onReaderMain {
                    zoomed =
                        kotlin.math.abs((image.getChildAt(0) as SubsamplingScaleImageView).scale / baseScale - 2f) <
                        0.01f &&
                        image.viewportScale == 2f
                }
                zoomed
            }
            val zoomed = fixture.observeSlot(Color.RED)
            assertEquals(2f, zoomed.scaleX)
            assertTrue(zoomed.mount === before.mount)
            assertTrue(
                "The native pixels must expand beyond the initial half-slot Fit",
                zoomed.content.width() > before.content.width(),
            )
            fixture.onReaderMain { assertEquals(image.width, (image.getChildAt(0) as SubsamplingScaleImageView).width) }
        }
    }

    @Test
    fun animatedHalfSlotKeepsTheSameRunningDrawableAcrossZoomAndReset() {
        HostReaderArtFixture("copymanga").use { fixture ->
            val encoded = android.util.Base64.decode(
                "R0lGODlhEAAYAIEAAP8A/wAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAEAAY" +
                    "AAAIIgABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFixgzatxoMCAAIfkEAAoAAAAsAAAAABAAGACB/wD+" +
                    "AAAAAAAAAAAACCIAAQgcSLCgwYMIEypcyLChw4cQI0qcSLGixYsYM2rcaDAgADs=",
                android.util.Base64.NO_WRAP,
            )
            fixture.images.set(List(22) { encoded })
            fixture.response.set(200 to fixture.successWithImageIdentity("animated_zoom_${SystemClock.uptimeMillis()}"))
            fixture.configure(mode = 6)
            fixture.cold()
            fixture.launch(initialPage = 0)
            fixture.awaitStable("The original animated cover must paint") { fixture.containsColor(Color.MAGENTA) }
            val before = fixture.observeSlot(Color.MAGENTA)
            val image = before.mount as ReaderPageImageView
            lateinit var native: android.widget.ImageView
            lateinit var drawable: android.graphics.drawable.Drawable
            fixture.onReaderMain {
                native = image.getChildAt(0) as android.widget.ImageView
                drawable = native.drawable
                assertTrue((drawable as Animatable).isRunning)
            }
            for (target in listOf(2f, 1f)) {
                doubleTap(fixture, before.content.exactCenterX(), before.content.exactCenterY())
                fixture.awaitStable("The animated viewport must reach $target without rebinding") {
                    var settled = false
                    fixture.onReaderMain { settled = image.viewportScale == target }
                    settled
                }
                fixture.onReaderMain {
                    assertSame(native, image.getChildAt(0))
                    assertSame(drawable, native.drawable)
                    assertTrue((drawable as Animatable).isRunning)
                }
                val after = fixture.observeSlot(Color.MAGENTA)
                assertSame(before.mount, after.mount)
                if (target > 1f) {
                    assertTrue(
                        "Animated pixels must expand across the initial slot",
                        after.content.width() > before.content.width(),
                    )
                } else {
                    assertEquals(before.content, after.content)
                }
            }
        }
    }

    @Test
    fun shippedRegionDecoderPaintsFinerTilesFromTheSameOriginalStream() {
        HostReaderArtFixture("copymanga").use { fixture ->
            val bitmap = Bitmap.createBitmap(1600, 3200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            val encoded = ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
            bitmap.recycle()
            fixture.images.set(List(22) { encoded })
            fixture.response.set(200 to fixture.successWithImageIdentity("tile_zoom_${SystemClock.uptimeMillis()}"))
            fixture.configure(mode = 6)
            fixture.cold()
            fixture.launch(initialPage = 0)
            fixture.awaitStable("The original encoded cover must paint") { fixture.containsColor(Color.RED) }
            val image = fixture.observeSlot(Color.RED).mount as ReaderPageImageView
            lateinit var native: SubsamplingScaleImageView
            var decoder: Any? = null
            var provider: Any? = null
            fixture.onReaderMain {
                native = image.getChildAt(0) as SubsamplingScaleImageView
                decoder = requireNotNull(nativeField(native, "decoder"))
                provider = requireNotNull(nativeField(native, "provider"))
            }
            fixture.awaitStable("Actual base tiles must finish") {
                var loaded = false
                fixture.onReaderMain { loaded = native.isImageLoaded && decodedTiles(native).isNotEmpty() }
                loaded && fixture.containsColor(Color.RED)
            }
            var baseSample = 0
            var baseScale = 0f
            var baseline = ""
            fixture.onReaderMain {
                assertSame(decoder, nativeField(native, "decoder"))
                assertSame(provider, nativeField(native, "provider"))
                assertEquals(1f, image.viewportScale, 0.01f)
                baseSample = decodedTiles(native).minOf { it.first }
                baseScale = native.scale
                baseline = tileState(native)
            }
            assertTrue("This fixture must begin with a subsampled native base: $baseline", baseSample > 1)
            val before = fixture.observeSlot(Color.RED)
            // SSIV already decodes the base layer one sample level finer than initial Fit needs.
            // Span fourfold to cross the next sample threshold. Android starts scaling only after
            // its gesture slop, so the total pointer span ratio is not the final viewport scale.
            pinch(fixture, before.content.exactCenterX(), before.content.exactCenterY(), image.height / 6f, 4f)
            try {
                fixture.awaitStable("The shipped decoder must finish a finer visible tile after zoom") {
                    var detailed = false
                    fixture.onReaderMain {
                        detailed = image.viewportScale > 1f &&
                            decodedTiles(native).any { it.first < baseSample }
                    }
                    detailed
                }
            } catch (failure: AssertionError) {
                var finalState = ""
                fixture.onReaderMain { finalState = "viewport=${image.viewportScale}; ${tileState(native)}" }
                throw AssertionError("$baseline -> $finalState", failure)
            }
            fixture.onReaderMain {
                assertSame(native, image.getChildAt(0))
                assertSame(decoder, nativeField(native, "decoder"))
                assertSame(provider, nativeField(native, "provider"))
                assertEquals(
                    "Native tile scale must match the viewport scale produced by the real gesture",
                    image.viewportScale,
                    native.scale / baseScale,
                    0.01f,
                )
                val output = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                val drawn = mutableListOf<Bitmap>()
                try {
                    image.draw(object : android.graphics.Canvas(output) {
                        override fun drawBitmap(
                            bitmap: Bitmap,
                            matrix: android.graphics.Matrix,
                            paint: android.graphics.Paint?,
                        ) {
                            drawn += bitmap
                            super.drawBitmap(bitmap, matrix, paint)
                        }
                    })
                    val fine = decodedTiles(native).filter { it.first < baseSample }.map { it.second }
                    assertTrue(
                        "The original SSIV must actually paint a finer decoded tile",
                        drawn.any { bitmap ->
                            fine.any {
                                it ===
                                    bitmap
                            }
                        },
                    )
                } finally {
                    output.recycle()
                }
            }
            assertTrue(fixture.observeSlot(Color.RED).content.width() > before.content.width())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodedTiles(native: SubsamplingScaleImageView): List<Pair<Int, Bitmap>> =
        (nativeField(native, "tileMap") as? Map<Int, List<Any>>).orEmpty().flatMap { (sample, tiles) ->
            tiles.mapNotNull { tile ->
                tile.javaClass.getDeclaredField("bitmap").apply { isAccessible = true }.get(tile)?.let {
                    sample to
                        it as Bitmap
                }
            }
        }

    private fun nativeField(native: SubsamplingScaleImageView, name: String): Any? =
        SubsamplingScaleImageView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(native)

    private fun tileState(native: SubsamplingScaleImageView): String {
        val display = native.resources.displayMetrics
        return "nativeScale=${native.scale}, minScale=${native.minScale}, " +
            "baseSample=${nativeField(native, "fullImageSampleSize")}, " +
            "decoded=${decodedTiles(native).groupingBy { it.first }.eachCount()}, " +
            "tileDpi=${nativeField(native, "minimumTileDpi")}, dpi=${display.xdpi}/${display.ydpi}"
    }

    private fun pinch(fixture: HostReaderArtFixture, x: Float, y: Float, startSpan: Float, factor: Float) {
        val down = SystemClock.uptimeMillis()
        fixture.touchWindow(MotionEvent.ACTION_DOWN, down, down, x, y - startSpan / 2)
        fun dispatch(action: Int, time: Long, span: Float) {
            fixture.onReaderMain { activity ->
                val properties = Array(2) { index ->
                    MotionEvent.PointerProperties().apply {
                        id = index
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                }
                val coordinates = Array(2) { index ->
                    MotionEvent.PointerCoords().apply {
                        this.x = x
                        this.y = y + if (index == 0) -span / 2 else span / 2
                        pressure = 1f
                        size = 1f
                    }
                }
                val event = MotionEvent.obtain(
                    down, time, action, 2, properties, coordinates, 0, 0, 1f, 1f, 0, 0,
                    android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
                )
                try {
                    activity.window.decorView.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }
        dispatch(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            down + 20,
            startSpan,
        )
        for (step in 1..8) {
            dispatch(MotionEvent.ACTION_MOVE, down + 20 + step * 20L, startSpan * (1 + (factor - 1) * step / 8))
        }
        dispatch(
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            down + 200,
            startSpan * factor,
        )
        fixture.touchWindow(MotionEvent.ACTION_UP, down, down + 220, x, y - startSpan * factor / 2)
    }

    private fun doubleTap(fixture: HostReaderArtFixture, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for (down in listOf(now, now + 100)) {
            fixture.touchWindow(MotionEvent.ACTION_DOWN, down, down, x, y)
            fixture.touchWindow(MotionEvent.ACTION_UP, down, down + 30, x, y)
        }
    }
}
