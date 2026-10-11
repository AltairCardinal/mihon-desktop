package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.HostReaderArtFixture
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DisplayPage
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPagerPageHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class SharedDualPageSingleGestureNativeTest {
    @Test
    fun originalSingleKeepsNativeImageZoomInsteadOfSwitchingToThePairContainer() {
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
            fixture.await("A single page double tap must retain the original SSIV zoom") {
                var zoomed = false
                fixture.onReaderMain { zoomed = (image.getChildAt(0) as SubsamplingScaleImageView).scale > baseScale }
                zoomed
            }
            assertEquals(1f, fixture.observeSlot(Color.RED).scaleX)
        }
    }
}
