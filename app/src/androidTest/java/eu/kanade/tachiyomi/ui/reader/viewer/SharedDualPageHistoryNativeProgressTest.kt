package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Color
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewTreeObserver
import eu.kanade.tachiyomi.ui.reader.HostReaderArtFixture
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageR2LPagerViewer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class SharedDualPageHistoryNativeProgressTest {
    @Test
    fun aNewHolderReturningToHistoryCannotReadItsOldDrawSetBeforeActualPaint() {
        HostReaderArtFixture("copymanga").use { fixture ->
            fixture.images.set(
                List(22) { index ->
                    HostReaderArtFixture.png(
                        400,
                        600,
                        when (index) {
                            1 -> Color.RED
                            2 -> Color.GREEN
                            5, 6 -> Color.MAGENTA
                            else -> Color.BLUE
                        },
                    )
                },
            )
            fixture.response.set(200 to fixture.successWithImageIdentity("history_${SystemClock.uptimeMillis()}"))
            fixture.configure(mode = 6)
            fixture.cold()
            fixture.launch(initialPage = 1)
            fixture.awaitStable("Original C and D must actually paint") {
                fixture.containsColor(Color.RED) && fixture.containsColor(Color.GREEN)
            }
            fixture.await("Original actual pixels must settle SQL") { fixture.progress().first == 2L }
            val firstMount = fixture.observeSlot(Color.RED).mount
            fixture.onReaderMain {
                (it.viewModel.state.value.viewer as DualPageR2LPagerViewer).config.usePageTransitions = false
            }
            fixture.key(KeyEvent.KEYCODE_DPAD_LEFT)
            fixture.awaitStable("The next real source group must paint") {
                fixture.logicalPageIndex() == 3 && fixture.containsColor(Color.BLUE)
            }
            fixture.await("Both E and F must actually settle before the second turn") { fixture.progress().first == 4L }
            fixture.key(KeyEvent.KEYCODE_DPAD_LEFT)
            fixture.awaitStable("A second real turn must remove the original C holder") {
                fixture.containsColor(Color.MAGENTA)
            }
            fixture.await("G actual pixels must settle before the return") { fixture.progress().first == 6L }
            assertFalse(firstMount.isAttachedToWindow)
            val baseline = fixture.progress().second
            val allowPaint = AtomicBoolean(false)
            val listener = ViewTreeObserver.OnPreDrawListener { allowPaint.get() }
            fixture.onReaderMain { it.window.decorView.viewTreeObserver.addOnPreDrawListener(listener) }
            try {
                fixture.key(KeyEvent.KEYCODE_DPAD_RIGHT)
                fixture.key(KeyEvent.KEYCODE_DPAD_RIGHT)
                fixture.await("The normal viewer must logically return to the original C D history frame") {
                    fixture.logicalPageIndex() in setOf(1, 2)
                }
                // Only test observation is held: decoding, activation, and normal SQL jobs run freely.
                SystemClock.sleep(300)
                assertFalse(fixture.containsColor(Color.RED))
                assertEquals(
                    "A past frame draw set cannot read a newly mounted, unpainted holder",
                    baseline,
                    fixture.progress().second,
                )
                allowPaint.set(true)
                fixture.onReaderMain { it.window.decorView.invalidate() }
                fixture.awaitStable("The newly mounted holder must paint the original C and D bytes") {
                    fixture.containsColor(Color.RED) && fixture.containsColor(Color.GREEN)
                }
                fixture.await("Only the new actual paint may create the return's reading event") {
                    fixture.progress().second > baseline
                }
            } finally {
                allowPaint.set(true)
                fixture.onReaderMain { it.window.decorView.viewTreeObserver.removeOnPreDrawListener(listener) }
            }
        }
    }
}
