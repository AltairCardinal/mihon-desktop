package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Color
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import eu.kanade.tachiyomi.ui.reader.HostReaderArtFixture
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPageR2LPagerViewer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SharedDualPageContentLeaseNativeTest(private val replacementEvent: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "content-{0}")
        fun vectors() = listOf(arrayOf("leave"), arrayOf("header-retry"))
    }

    @Test
    fun visiblePixelsRetainTheirOriginalLeaseUntilRealOffscreenReleaseOrExplicitHeaderRetry() {
        HostReaderArtFixture("copymanga").use { fixture ->
            fixture.images.set(
                List(22) { index ->
                    HostReaderArtFixture.png(
                        400,
                        600,
                        when (index) {
                            1 -> Color.RED
                            2 -> Color.GREEN
                            else -> Color.BLUE
                        },
                    )
                },
            )
            fixture.response.set(200 to fixture.successWithImageIdentity("lease_${SystemClock.uptimeMillis()}"))
            fixture.configure(mode = 6)
            fixture.cold()
            fixture.launch(initialPage = 1)
            fixture.awaitStable("Original normal HTTP bytes must paint both slots") {
                fixture.containsColor(Color.RED) && fixture.containsColor(Color.GREEN)
            }
            fixture.await("Original actual pixels must settle SQL") { fixture.progress().first == 2L }
            val original = fixture.observeSlot(Color.RED)
            val now = SystemClock.uptimeMillis()
            val x = original.content.exactCenterX()
            val y = original.content.exactCenterY()
            fixture.touchWindow(MotionEvent.ACTION_DOWN, now, now, x, y)
            fixture.touchWindow(MotionEvent.ACTION_UP, now, now + 30, x, y)
            fixture.touchWindow(MotionEvent.ACTION_DOWN, now + 100, now + 100, x, y)
            fixture.touchWindow(MotionEvent.ACTION_UP, now + 100, now + 130, x, y)
            fixture.awaitStable("Original native container must retain a nondefault zoom") {
                fixture.observeSlot(Color.RED).scaleX > 1f
            }
            val before = fixture.observeSlot(Color.RED)
            val progress = fixture.progress()
            lateinit var page: ReaderPage
            val replacement = HostReaderArtFixture.png(900, 600, Color.MAGENTA)
            fixture.images.set(requireNotNull(fixture.images.get()).toMutableList().also { it[1] = replacement })
            fixture.onReaderMain { activity ->
                page = requireNotNull(activity.viewModel.state.value.viewerChapters?.currChapter?.pages)[1]
                // The normal content-identity input receives a new encoded snapshot, without publishing layout facts.
                page.stream = { replacement.inputStream() }
            }
            page.publishHeaderFact()
            fixture.onReaderMain { (before.mount as ReaderPageImageView).invalidateImageDraw() }
            repeat(12) {
                assertEquals(
                    "Background bytes cannot replace pixels, mount or transform",
                    before,
                    fixture.observeSlot(Color.RED),
                )
                assertFalse(fixture.containsColor(Color.MAGENTA))
                assertEquals(progress, fixture.progress())
                SystemClock.sleep(16)
            }
            if (replacementEvent == "header-retry") {
                // The existing explicit header retry sequence releases the encoded snapshot. This is
                // not a new Ready-page network reload action; Error/Retry UI has a separate native test.
                fixture.onReaderMain { page.retryHeaderFact() }
            } else {
                fixture.key(KeyEvent.KEYCODE_DPAD_LEFT)
                fixture.awaitStable("The original C must actually leave the viewport") {
                    fixture.logicalPageIndex() == 3 && fixture.containsColor(Color.BLUE) &&
                        !fixture.containsColor(Color.RED)
                }
                fixture.onReaderMain { activity ->
                    (activity.viewModel.state.value.viewer as DualPageR2LPagerViewer).moveToPage(page)
                }
            }
            fixture.awaitStable("New encoded bytes may paint only after release or explicit retry") {
                fixture.containsColor(Color.MAGENTA)
            }
            val wide = fixture.observeSlot(Color.MAGENTA)
            assertTrue(wide.content.width() > fixture.windowWidth() * 0.75f)
            assertEquals(1, fixture.logicalPageIndex())
        }
    }
}
