package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.HostReaderArtFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Original extension -> real HTTP/loader -> JNI/SSIV -> window pixels -> actual SQL progress. */
@RunWith(Parameterized::class)
class SharedDualPageStableNativeAcceptanceTest(private val partner: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "late-{0}")
        fun vectors() = listOf(arrayOf("portrait"), arrayOf("wide"), arrayOf("error"))
    }

    @Test
    fun actualPartialPixelsStayMountedThroughPartnerCompletionAndRetry() {
        HostReaderArtFixture("copymanga").use { fixture ->
            val release = CountDownLatch(1)
            fixture.beforeImageRequest.set { index ->
                if (index == 2) check(release.await(90, TimeUnit.SECONDS)) { "Partner gate timed out" }
            }
            fixture.images.set(
                List(22) { index ->
                    png(
                        if (index == 2 && partner == "wide") 900 else 400,
                        600,
                        when (index) {
                            1 -> Color.RED
                            2 -> Color.GREEN
                            else -> Color.BLUE
                        },
                    )
                },
            )
            fixture.response.set(
                200 to fixture.successWithImageIdentity("stable_${partner}_${SystemClock.uptimeMillis()}"),
            )
            fixture.configure(mode = 6)
            fixture.cold()
            val baseline = fixture.progress().second
            try {
                fixture.launch(initialPage = 1)
                fixture.awaitStable("C must paint without waiting for partner bytes") {
                    fixture.containsColor(Color.RED) && !fixture.containsColor(Color.GREEN)
                }
                val initial = fixture.observeSlot(Color.RED)
                assertTrue("First C pixels belong to the RIGHT half", initial.content.left >= fixture.windowWidth() / 2)
                fixture.await("Actual first-slot pixels must reach the real SQL progress path") {
                    fixture.progress().first == 1L
                }
                assertEquals(baseline + 1, fixture.progress().second)
                if (partner == "error") {
                    fixture.await("D pending loading feedback must remain inside its LEFT slot") {
                        fixture.loadingBounds()?.let { it.right <= fixture.windowWidth() / 2 } == true
                    }
                }
                run {
                    val x = initial.content.exactCenterX()
                    val y = initial.content.exactCenterY()
                    val now = SystemClock.uptimeMillis()
                    fixture.touchWindow(MotionEvent.ACTION_DOWN, now, now, x, y)
                    fixture.touchWindow(MotionEvent.ACTION_UP, now, now + 30, x, y)
                    fixture.touchWindow(MotionEvent.ACTION_DOWN, now + 100, now + 100, x, y)
                    fixture.touchWindow(MotionEvent.ACTION_UP, now + 100, now + 130, x, y)
                    fixture.awaitStable("Real dual-page double tap must zoom the retained container") {
                        fixture.observeSlot(Color.RED).scaleX > 1f
                    }
                }
                val before = fixture.observeSlot(Color.RED)
                if (partner == "error") fixture.imageStatuses.set(mapOf(2 to 503))
                release.countDown()
                if (partner == "error") {
                    fixture.await("D failure belongs to its LEFT slot") { fixture.retryBounds() != null }
                    assertTrue(requireNotNull(fixture.retryBounds()).right <= fixture.windowWidth() / 2)
                    val (retry, visibleRetry) = fixture.feedbackBounds(R.id.action_retry)
                    assertEquals("Zoom must not clip the Retry button", retry, visibleRetry)
                    assertTrue(retry.left >= 0 && retry.right <= fixture.windowWidth() / 2)
                    val (message, visibleMessage) = fixture.feedbackBounds(R.id.error_message)
                    assertEquals("Zoom must not clip the slot error text", message, visibleMessage)
                    assertTrue(message.left >= 0 && message.right <= fixture.windowWidth() / 2)
                    assertEquals(before, fixture.observeSlot(Color.RED))
                    fixture.imageStatuses.set(emptyMap())
                    val now = SystemClock.uptimeMillis()
                    fixture.touchWindow(MotionEvent.ACTION_DOWN, now, now, retry.exactCenterX(), retry.exactCenterY())
                    fixture.touchWindow(
                        MotionEvent.ACTION_UP,
                        now,
                        now + 30,
                        retry.exactCenterX(),
                        retry.exactCenterY(),
                    )
                }
                if (partner == "wide") {
                    // Actual D metadata must finish before checking that it never painted a half slot.
                    fixture.await("D original bytes must reach the actual adapter's spread classification") {
                        fixture.hasDecodedSpread(2)
                    }
                    repeat(30) {
                        assertTrue(
                            "Late wide partner must retain C's actual native mount",
                            before.mount.isAttachedToWindow,
                        )
                        val after = fixture.observeSlot(Color.RED)
                        assertEquals(before, after)
                        assertTrue(!fixture.containsColor(Color.GREEN))
                        SystemClock.sleep(20)
                    }
                    fixture.key(KeyEvent.KEYCODE_DPAD_LEFT)
                    fixture.awaitStable("A real turn must show wide D in the complete viewport") {
                        fixture.containsColor(Color.GREEN) &&
                            fixture.coloredBounds(listOf(Color.GREEN)).width() > fixture.windowWidth() * 0.75
                    }
                } else {
                    fixture.awaitStable("Late portrait partner must paint in the LEFT slot") {
                        fixture.containsColor(Color.GREEN)
                    }
                    val after = fixture.observeSlot(Color.RED)
                    assertEquals(before.content, after.content)
                    assertEquals(before.imageBounds, after.imageBounds)
                    assertSame(before.mount, after.mount)
                    assertSame(before.container, after.container)
                    assertEquals(before.scaleX, after.scaleX)
                    assertEquals(before.scaleY, after.scaleY)
                    assertEquals(before.translationX, after.translationX)
                    assertEquals(before.translationY, after.translationY)
                }
                fixture.await("Only actual partner pixels may append SQL progress") { fixture.progress().first == 2L }
                assertEquals(baseline + 2, fixture.progress().second)
            } finally {
                release.countDown()
            }
        }
    }

    private fun png(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
