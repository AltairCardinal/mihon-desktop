package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnPreDraw
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Native dispatch/velocity integration; holder ownership and camera lifetime are covered on JVM. */
class PagerHandoffNativeTest {
    @Test
    fun childHandoffStartsNativePagingAtTheCurrentPointer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".dev"))
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        Injekt.get<BasePreferences>().shownOnboardingFlow().set(true)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            lateinit var pager: Pager
            val touches = mutableListOf<String>()
            val firstFrame = CountDownLatch(1)
            scenario.onActivity { activity ->
                pager = Pager(activity)
                pager.adapter = object : ViewPagerAdapter() {
                    override fun getCount() = 3
                    override fun createView(container: ViewGroup, position: Int): View = View(activity).apply {
                        setOnTouchListener { child, event ->
                            touches += "$position:${event.actionMasked}:${event.eventTime - event.downTime}"
                            // A controlled yielding child exercises the real native Pager adapter.
                            // Production holder edge/pinch decisions are deliberately not duplicated.
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> child.parent.requestDisallowInterceptTouchEvent(true)
                                MotionEvent.ACTION_MOVE -> if (event.eventTime - event.downTime == 240L) {
                                    pager.rebaseDragFromChild(child, event)
                                    child.parent.requestDisallowInterceptTouchEvent(false)
                                }
                            }
                            true
                        }
                    }
                }
                activity.setContentView(pager)
                pager.currentItem = 1
                pager.doOnPreDraw { firstFrame.countDown() }
            }
            assertTrue("Wait for the native pager's first layout/draw", firstFrame.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertTrue(pager.width > 0)
                assertTrue("The pager must remain in the real activity window", pager.isAttachedToWindow)
                assertEquals("The native fixture must start at its middle page", 1, pager.currentItem)
                // ViewPager may anchor its initially populated middle item at offset zero.
                // Displacement is relative to that real starting offset, not index * width.
                val initialScroll = pager.scrollX
                val down = SystemClock.uptimeMillis()
                val stepSize = pager.width / 16f
                val start = pager.width * 0.9f
                fun send(action: Int, step: Int) {
                    MotionEvent.obtain(down, down + step * 30L, action, start - step * stepSize, pager.height / 2f, 0)
                        .also { event ->
                            pager.dispatchTouchEvent(event)
                            event.recycle()
                        }
                }
                send(MotionEvent.ACTION_DOWN, 0)
                var previous = pager.scrollX
                for (step in 1..12) {
                    send(MotionEvent.ACTION_MOVE, step)
                    assertTrue("No native handoff jump at step $step", abs(pager.scrollX - previous) <= stepSize + 1)
                    previous = pager.scrollX
                }
                assertTrue(
                    "Native pager must move after handoff: initial=$initialScroll, scroll=${pager.scrollX}, touches=$touches",
                    pager.scrollX > initialScroll,
                )
                send(MotionEvent.ACTION_UP, 13)
                assertEquals("The native fling must still select the adjacent page", 2, pager.currentItem)
            }
        }
    }
}
