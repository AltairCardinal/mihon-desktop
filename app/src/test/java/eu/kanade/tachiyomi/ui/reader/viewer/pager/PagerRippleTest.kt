package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import tachiyomi.core.common.preference.AndroidPreferenceStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PagerRippleTest {

    private lateinit var context: Context
    private lateinit var preferences: ReaderPreferences
    private lateinit var pager: Pager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val preferenceStore = AndroidPreferenceStore(context)
        preferences = ReaderPreferences(preferenceStore)
        pager = Pager(context, isHorizontal = true)
    }

    @Test
    fun preferenceDefaultsToFalseAndCanBeToggled() {
        val pref = preferences.pageTurnRipple()
        assertFalse("E-Ink ripple animation should be disabled by default", pref.get())

        pref.set(true)
        assertTrue("E-Ink ripple animation should be enabled when toggled", pref.get())
    }

    @Test
    fun pagerSuppressesDraggingWhenRippleEnabled() {
        pager.pageTurnRippleEnabled = false
        // When disabled, onInterceptTouchEvent delegates to default ViewPager behaviour
        val downEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        assertFalse(pager.onInterceptTouchEvent(downEvent))

        pager.pageTurnRippleEnabled = true
        // When enabled, onInterceptTouchEvent always returns false so image does not follow finger
        val moveEvent = MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_MOVE, 300f, 100f, 0)
        assertFalse(
            "onInterceptTouchEvent must not intercept drags when ripple is enabled",
            pager.onInterceptTouchEvent(moveEvent),
        )
        assertTrue("onTouchEvent must consume events without scrolling ViewPager", pager.onTouchEvent(moveEvent))

        downEvent.recycle()
        moveEvent.recycle()
    }

    @Test
    fun horizontalSwipeGestureDetectionWhenRippleEnabled() {
        pager.pageTurnRippleEnabled = true
        var detectedSwipe: Pager.SwipeDirection? = null
        pager.swipeListener = { detectedSwipe = it }

        // Swipe Left (finger moves from x=300 to x=50, deltaX = -250)
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 300f, 200f, 0)
        val move = MotionEvent.obtain(0L, 50L, MotionEvent.ACTION_MOVE, 50f, 200f, 0)
        val up = MotionEvent.obtain(0L, 100L, MotionEvent.ACTION_UP, 50f, 200f, 0)

        pager.dispatchTouchEvent(down)
        pager.dispatchTouchEvent(move)
        pager.dispatchTouchEvent(up)

        assertEquals("Should detect LEFT swipe", Pager.SwipeDirection.LEFT, detectedSwipe)

        // Swipe Right (finger moves from x=50 to x=300, deltaX = +250)
        detectedSwipe = null
        val down2 = MotionEvent.obtain(200L, 200L, MotionEvent.ACTION_DOWN, 50f, 200f, 0)
        val move2 = MotionEvent.obtain(200L, 250L, MotionEvent.ACTION_MOVE, 300f, 200f, 0)
        val up2 = MotionEvent.obtain(200L, 300L, MotionEvent.ACTION_UP, 300f, 200f, 0)

        pager.dispatchTouchEvent(down2)
        pager.dispatchTouchEvent(move2)
        pager.dispatchTouchEvent(up2)

        assertEquals("Should detect RIGHT swipe", Pager.SwipeDirection.RIGHT, detectedSwipe)

        down.recycle()
        move.recycle()
        up.recycle()
        down2.recycle()
        move2.recycle()
        up2.recycle()
    }

    @Test
    fun verticalSwipeGestureDetectionWhenRippleEnabled() {
        val verticalPager = Pager(context, isHorizontal = false)
        verticalPager.pageTurnRippleEnabled = true
        var detectedSwipe: Pager.SwipeDirection? = null
        verticalPager.swipeListener = { detectedSwipe = it }

        // Swipe Up (finger moves from y=400 to y=100, deltaY = -300)
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 200f, 400f, 0)
        val move = MotionEvent.obtain(0L, 50L, MotionEvent.ACTION_MOVE, 200f, 100f, 0)
        val up = MotionEvent.obtain(0L, 100L, MotionEvent.ACTION_UP, 200f, 100f, 0)

        verticalPager.dispatchTouchEvent(down)
        verticalPager.dispatchTouchEvent(move)
        verticalPager.dispatchTouchEvent(up)

        assertEquals("Should detect UP swipe", Pager.SwipeDirection.UP, detectedSwipe)

        down.recycle()
        move.recycle()
        up.recycle()
    }

    @Test
    fun rippleOverlayDrawsClippingDuringAnimation() {
        pager.pageTurnRippleEnabled = true
        pager.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY),
        )
        pager.layout(0, 0, 800, 1200)

        // Mock bitmap and progress
        val testBitmap = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
        pager.currentRippleBitmap = testBitmap
        pager.rippleDirection = Pager.RippleDirection.RIGHT_TO_LEFT
        pager.rippleProgress = 0.5f

        val canvas = Canvas(Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888))
        // Should draw without exceptions
        pager.dispatchDraw(canvas)

        assertNotNull("Bitmap should still be held while animating", pager.currentRippleBitmap)

        // End animation
        pager.rippleProgress = 1.0f
        pager.cancelRipple()
        assertNull("Bitmap should be cleared when ripple finishes", pager.currentRippleBitmap)
    }

    @Test
    fun rippleCapturesCurrentPageContentAndRendersTransition() {
        val activity = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().visible().get()
        val testPager = Pager(activity, isHorizontal = true)
        activity.setContentView(testPager)
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(16))

        var v0Runs = 0
        var v1Runs = 0
        val v0 = object : android.view.View(activity) {
            init { setWillNotDraw(false) }
            override fun onDraw(canvas: Canvas) {
                v0Runs++
                val p = android.graphics.Paint().apply { color = android.graphics.Color.RED }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            }
        }
        val v1 = object : android.view.View(activity) {
            init { setWillNotDraw(false) }
            override fun onDraw(canvas: Canvas) {
                v1Runs++
                val p = android.graphics.Paint().apply { color = android.graphics.Color.BLUE }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            }
        }
        testPager.adapter = object : androidx.viewpager.widget.PagerAdapter() {
            override fun getCount(): Int = 2
            override fun isViewFromObject(v: android.view.View, o: Any): Boolean = v === o
            override fun instantiateItem(c: android.view.ViewGroup, p: Int): Any {
                val v = if (p == 0) v0 else v1
                c.addView(v)
                return v
            }
            override fun destroyItem(c: android.view.ViewGroup, p: Int, o: Any) { c.removeView(o as android.view.View) }
        }
        testPager.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY),
        )
        testPager.layout(0, 0, 800, 1200)

        // Turn from page 0 to page 1
        testPager.setCurrentItemWithRipple(1, Pager.RippleDirection.RIGHT_TO_LEFT)
        val snapshot = testPager.currentRippleBitmap
        assertNotNull("Snapshot should not be null", snapshot)
        val pixel = snapshot!!.getPixel(400, 600)
        assertEquals("Snapshot must capture page 0 (RED)", android.graphics.Color.RED, pixel)

        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(16))
        testPager.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY),
        )
        testPager.layout(0, 0, 800, 1200)

        // Verify dispatchDraw draws overlay at half progress (p = 0.5)
        testPager.rippleProgress = 0.5f
        val renderBitmap = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
        val renderCanvas = Canvas(renderBitmap)
        renderCanvas.translate(-testPager.scrollX.toFloat(), -testPager.scrollY.toFloat())
        testPager.draw(renderCanvas)

        // RIGHT_TO_LEFT: splitX = 800 * (1 - 0.5) = 400.
        // [0, 400) is old page (RED), [400, 800) is new page (BLUE)
        val leftPixel = renderBitmap.getPixel(200, 600)
        val rightPixel = renderBitmap.getPixel(600, 600)
        assertEquals("Left of wipe boundary should be old page (RED)", android.graphics.Color.RED, leftPixel)
        assertEquals("Right of wipe boundary should be new page (BLUE)", android.graphics.Color.BLUE, rightPixel)

        testPager.cancelRipple()
    }

    @Test
    fun rippleModePreferenceDefaultsToCleanAndCanBeChangedToShadowLine() {
        val modePref = preferences.pageTurnRippleMode()
        assertEquals(ReaderPreferences.PageTurnRippleMode.CLEAN, modePref.get())
        modePref.set(ReaderPreferences.PageTurnRippleMode.SHADOW_LINE)
        assertEquals(ReaderPreferences.PageTurnRippleMode.SHADOW_LINE, modePref.get())
    }

    @Test
    fun prepareRippleCapturesSnapshotBeforeNavigation() {
        pager.pageTurnRippleEnabled = true
        pager.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY),
        )
        pager.layout(0, 0, 800, 1200)

        val prepared = pager.prepareRipple(Pager.RippleDirection.RIGHT_TO_LEFT)
        assertTrue("prepareRipple should succeed when pager has dimensions", prepared)
        assertNotNull("currentRippleBitmap should be populated immediately", pager.currentRippleBitmap)
        assertEquals(0f, pager.rippleProgress, 0.001f)
    }

    @Test
    fun cleanModeDoesNotDrawLineWhereasShadowLineModeDoes() {
        val activity = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().visible().get()
        val testPager = Pager(activity, isHorizontal = true)
        activity.setContentView(testPager)
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(16))

        val v0 = android.view.View(activity).apply {
            setWillNotDraw(false)
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        val v1 = android.view.View(activity).apply {
            setWillNotDraw(false)
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        testPager.adapter = object : androidx.viewpager.widget.PagerAdapter() {
            override fun getCount(): Int = 2
            override fun isViewFromObject(v: android.view.View, o: Any): Boolean = v === o
            override fun instantiateItem(c: android.view.ViewGroup, p: Int): Any {
                val v = if (p == 0) v0 else v1
                c.addView(v)
                return v
            }
            override fun destroyItem(c: android.view.ViewGroup, p: Int, o: Any) { c.removeView(o as android.view.View) }
        }
        testPager.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY),
        )
        testPager.layout(0, 0, 800, 1200)

        // Test CLEAN mode: boundary pixel should remain WHITE (no black line)
        testPager.rippleMode = ReaderPreferences.PageTurnRippleMode.CLEAN
        testPager.setCurrentItemWithRipple(1, Pager.RippleDirection.RIGHT_TO_LEFT)
        testPager.rippleProgress = 0.5f // splitX = 400
        val cleanBitmap = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
        val cleanCanvas = Canvas(cleanBitmap)
        cleanCanvas.translate(-testPager.scrollX.toFloat(), -testPager.scrollY.toFloat())
        testPager.draw(cleanCanvas)
        assertEquals("CLEAN mode should not draw black boundary line at splitX", android.graphics.Color.WHITE, cleanBitmap.getPixel(400, 600))

        testPager.cancelRipple()

        // Test SHADOW_LINE mode: boundary line should draw line
        testPager.rippleMode = ReaderPreferences.PageTurnRippleMode.SHADOW_LINE
        testPager.setCurrentItemWithRipple(0, Pager.RippleDirection.LEFT_TO_RIGHT)
        testPager.rippleProgress = 0.5f // splitX = 400
        val shadowBitmap = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
        val shadowCanvas = Canvas(shadowBitmap)
        shadowCanvas.translate(-testPager.scrollX.toFloat(), -testPager.scrollY.toFloat())
        testPager.draw(shadowCanvas)
        val boundaryPixel = shadowBitmap.getPixel(400, 600)
        assertTrue("SHADOW_LINE mode should draw boundary line", boundaryPixel != android.graphics.Color.WHITE)
        testPager.cancelRipple()
    }
}
