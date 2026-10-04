package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import eu.kanade.tachiyomi.ui.reader.HostReaderArtFixture
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(Parameterized::class)
class SharedDualPageSourceNavigationNativeTest(private val input: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "source-{0}")
        fun vectors() = listOf(arrayOf("key"), arrayOf("tap"), arrayOf("wheel"), arrayOf("drag"))
    }

    @Test
    fun realNavigationTargetsTheUnpaintedPartnerAndTheNextRequestCanContinue() {
        HostReaderArtFixture("copymanga").use { fixture ->
            val release = CountDownLatch(1)
            fixture.beforeImageRequest.set { index -> if (index == 2) check(release.await(90, TimeUnit.SECONDS)) }
            fixture.images.set(
                List(22) { index ->
                    HostReaderArtFixture.png(400, 600, if (index == 1) Color.RED else Color.BLUE)
                },
            )
            fixture.response.set(
                200 to fixture.successWithImageIdentity("source_${input}_${SystemClock.uptimeMillis()}"),
            )
            fixture.configure(mode = 6)
            fixture.cold()
            try {
                fixture.launch(initialPage = 1)
                fixture.awaitStable("Only C must actually paint while D's original request is held") {
                    fixture.containsColor(Color.RED)
                }
                fixture.await("C real pixels must reach SQL before navigation") { fixture.progress().first == 1L }
                next(fixture)
                fixture.await("The real navigation event must change its logical source target") {
                    fixture.logicalPageIndex() != 1
                }
                assertEquals("An unpainted D cannot be swallowed by its candidate group", 2, fixture.logicalPageIndex())
                assertEquals("Merely targeting D must not mark it read", 1L, fixture.progress().first)
                next(fixture)
                fixture.await("A second request must continue beyond unpainted D") { fixture.logicalPageIndex() != 2 }
                assertEquals(3, fixture.logicalPageIndex())
            } finally {
                release.countDown()
            }
        }
    }

    private fun next(fixture: HostReaderArtFixture) {
        val now = SystemClock.uptimeMillis()
        val width = fixture.windowWidth().toFloat()
        when (input) {
            "key" -> fixture.key(KeyEvent.KEYCODE_DPAD_LEFT)
            "tap" -> {
                fixture.touchWindow(MotionEvent.ACTION_DOWN, now, now, width * 0.05f, 1200f)
                fixture.touchWindow(MotionEvent.ACTION_UP, now, now + 30, width * 0.05f, 1200f)
            }
            "wheel" -> fixture.onReaderMain { activity ->
                val pointer = MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_MOUSE
                }
                val coordinates = MotionEvent.PointerCoords().apply {
                    x = width / 2
                    y = 1200f
                    setAxisValue(MotionEvent.AXIS_VSCROLL, -1f)
                }
                val event = MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(pointer), arrayOf(coordinates),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
                )
                try {
                    activity.dispatchGenericMotionEvent(event)
                } finally {
                    event.recycle()
                }
            }
            "drag" -> {
                fixture.touchWindow(MotionEvent.ACTION_DOWN, now, now, width * 0.15f, 1200f)
                for (step in 1..6) {
                    fixture.touchWindow(
                        MotionEvent.ACTION_MOVE,
                        now,
                        now + step * 30,
                        width * (0.15f + step * 0.11f),
                        1200f,
                    )
                }
                fixture.touchWindow(MotionEvent.ACTION_UP, now, now + 210, width * 0.85f, 1200f)
            }
        }
    }
}
