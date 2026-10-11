package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import eu.kanade.tachiyomi.ui.reader.HostReaderArtFixture
import eu.kanade.tachiyomi.ui.reader.actualArtField
import eu.kanade.tachiyomi.ui.reader.actualArtReaderState
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPagerPageHolder
import mihon.domain.reader.ReaderDualPageViewportTicket
import mihon.domain.reader.SharedDualPageSession
import mihon.domain.reader.session.ReaderPageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(Parameterized::class)
class SharedDualPageSourceNavigationNativeTest(private val input: String) {
    private var lastTapUp = 0L
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "source-{0}")
        fun vectors() = listOf(arrayOf("key"), arrayOf("tap"), arrayOf("wheel"), arrayOf("drag"))
    }

    @Test
    fun realNavigationSkipsTheCurrentGroupWithoutAcknowledgingTheHeldPartner() {
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
                Log.i(
                    "NativeSourceInput",
                    "$input first source=${fixture.logicalPageIndex()} progress=${fixture.progress()}",
                )
                assertEquals(
                    "All inputs must enter the next E/F group, not stop at held D",
                    3,
                    fixture.logicalPageIndex(),
                )
                assertEquals("Held D's original response must remain unreleased", 1L, release.count)
                assertHeldPartnerHasNoDrawnAcknowledgement(fixture)
                assertTrue("Only C or the real E/F group may have reached SQL", fixture.progress().first in 1L..4L)
                next(fixture)
                fixture.await("A second request must enter the next whole group") { fixture.logicalPageIndex() != 3 }
                Log.i(
                    "NativeSourceInput",
                    "$input second source=${fixture.logicalPageIndex()} progress=${fixture.progress()}",
                )
                assertEquals(5, fixture.logicalPageIndex())
                assertEquals(1L, release.count)
                assertHeldPartnerHasNoDrawnAcknowledgement(fixture)
                assertTrue("SQL must never run ahead of actual visited G/H group", fixture.progress().first in 1L..6L)
            } finally {
                release.countDown()
            }
        }
    }

    private fun assertHeldPartnerHasNoDrawnAcknowledgement(fixture: HostReaderArtFixture) {
        fixture.onReaderMain { activity ->
            val viewer = actualArtField<Viewer?>(actualArtReaderState(activity), "viewer")
            val pager = actualArtField<ViewGroup>(requireNotNull(viewer), "pager")
            var observedScopes = 0
            for (index in 0 until pager.childCount) {
                val holder = pager.getChildAt(index) as? DualPagerPageHolder ?: continue
                val scope = actualArtField<Pair<SharedDualPageSession, ReaderDualPageViewportTicket>?>(
                    holder,
                    "presentationScope",
                ) ?: continue
                val (owner, ticket) = scope
                val frame = requireNotNull(owner.frame(ticket)) { "A live native scope requires its actual frame" }
                val drawn = actualArtField<Set<ReaderPageId>>(frame, "drawnPageIds")
                observedScopes++
                assertTrue(
                    "A held original page cannot acquire a drawn acknowledgement",
                    drawn.none { actualArtField<Int>(it, "sourcePageIndex") == 2 },
                )
            }
            assertTrue("No-D acknowledgement must observe at least one real native frame", observedScopes > 0)
        }
    }

    private fun next(fixture: HostReaderArtFixture) {
        val now = SystemClock.uptimeMillis()
        val width = fixture.windowWidth().toFloat()
        when (input) {
            "key" -> fixture.key(KeyEvent.KEYCODE_DPAD_LEFT)
            "tap" -> {
                fixture.await("Independent tap requires a settled native viewport") {
                    var idle = false
                    fixture.onReaderMain { activity ->
                        val state = actualArtReaderState(activity)
                        val viewer = actualArtField<Viewer?>(state, "viewer")
                        val pager = actualArtField<Any>(requireNotNull(viewer), "pager")
                        val field = generateSequence<Class<*>>(pager.javaClass) { it.superclass }
                            .flatMap { it.declaredFields.asSequence() }.first { it.name == "mScrollState" }
                        field.isAccessible = true
                        idle = field.getInt(pager) == 0
                    }
                    idle
                }
                val interval = ViewConfiguration.getDoubleTapTimeout().toLong() + 50
                if (lastTapUp != 0L) {
                    SystemClock.sleep((lastTapUp + interval - SystemClock.uptimeMillis()).coerceAtLeast(0))
                }
                val down = SystemClock.uptimeMillis()
                fixture.touchWindow(MotionEvent.ACTION_DOWN, down, down, width * 0.05f, 1200f)
                SystemClock.sleep(30)
                lastTapUp = SystemClock.uptimeMillis()
                fixture.touchWindow(MotionEvent.ACTION_UP, down, lastTapUp, width * 0.05f, 1200f)
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
                    SystemClock.sleep(30)
                    fixture.touchWindow(
                        MotionEvent.ACTION_MOVE,
                        now,
                        SystemClock.uptimeMillis(),
                        width * (0.15f + step * 0.11f),
                        1200f,
                    )
                }
                SystemClock.sleep(30)
                fixture.touchWindow(MotionEvent.ACTION_UP, now, SystemClock.uptimeMillis(), width * 0.85f, 1200f)
            }
        }
    }
}
