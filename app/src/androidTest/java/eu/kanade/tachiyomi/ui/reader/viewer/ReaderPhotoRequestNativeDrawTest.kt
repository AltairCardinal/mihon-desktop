package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Color
import android.view.ViewGroup
import android.widget.FrameLayout
import eu.kanade.tachiyomi.ui.reader.HostReaderArtFixture
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The original Coil request and PhotoView draw path; only the new encoded stream is held. */
class ReaderPhotoRequestNativeDrawTest {
    @Test
    fun aRetainedOldAnimatedDrawableCannotPrepareOrAcknowledgeTheReplacementRequest() {
        HostReaderArtFixture("copymanga").use { fixture ->
            fixture.launch()
            fixture.await("Original reader must be mounted") { fixture.currentImages().isNotEmpty() }
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val request = AtomicInteger(1)
            val replacementPreparations = AtomicInteger()
            val replacementDraws = AtomicInteger()
            lateinit var image: ReaderPageImageView
            try {
                fixture.onReaderMain { activity ->
                    image = ReaderPageImageView(activity)
                    activity.window.decorView.findViewById<ViewGroup>(android.R.id.content).addView(
                        image,
                        FrameLayout.LayoutParams(600, 900),
                    )
                    image.beforeImageDraw = { _, _ ->
                        if (request.get() == 2) replacementPreparations.incrementAndGet()
                        true
                    }
                    image.onImageDrawn = { if (request.get() == 2) replacementDraws.incrementAndGet() }
                    image.setImage(
                        Buffer().write(animatedPixel(Color.RED)),
                        true,
                        ReaderPageImageView.Config(0),
                        0,
                        callbackIsCurrent = { request.get() == 1 },
                    )
                }
                fixture.await("The real Coil animated request must paint its original red pixels") {
                    fixture.containsColor(Color.RED)
                }
                val originalChild = image.getChildAt(0)
                val pending = object : ForwardingSource(Buffer().write(animatedPixel(Color.GREEN))) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        entered.countDown()
                        check(release.await(60, TimeUnit.SECONDS)) { "Replacement encoded request gate timed out" }
                        return super.read(sink, byteCount)
                    }
                }.buffer()
                fixture.onReaderMain {
                    request.set(2)
                    image.setImage(
                        pending,
                        true,
                        ReaderPageImageView.Config(0),
                        0,
                        callbackIsCurrent = { request.get() == 2 },
                    )
                }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                fixture.onReaderMain { image.getChildAt(0).invalidate() }
                // Observe the actual retained drawable through the normal window paint path.
                fixture.awaitStable("The old drawable remains visible while replacement bytes are held") {
                    fixture.containsColor(Color.RED)
                }
                assertSame(originalChild, image.getChildAt(0))
                assertEquals("Old pixels cannot lock replacement geometry", 0, replacementPreparations.get())
                assertEquals("Old pixels cannot count as replacement content", 0, replacementDraws.get())
                release.countDown()
                fixture.await("Actual replacement decode and native paint must acknowledge the new request") {
                    fixture.containsColor(Color.GREEN) && replacementDraws.get() > 0
                }
            } finally {
                release.countDown()
                fixture.onReaderMain {
                    image.recycle()
                    (image.parent as? ViewGroup)?.removeView(image)
                }
            }
        }
    }

    private fun animatedPixel(color: Int): ByteArray =
        byteArrayOf(
            71, 73, 70, 56, 57, 97, 1, 0, 1, 0, -128, 0, 0,
            Color.red(color).toByte(), Color.green(color).toByte(), Color.blue(color).toByte(), 0, 0, 0,
            33, -1, 11, 78, 69, 84, 83, 67, 65, 80, 69, 50, 46, 48, 3, 1, 0, 0, 0,
            33, -7, 4, 0, 10, 0, 0, 0, 44, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 68, 1, 0,
            33, -7, 4, 0, 10, 0, 0, 0, 44, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 68, 1, 0, 59,
        )
}
