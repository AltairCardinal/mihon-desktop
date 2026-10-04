package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.ViewGroup
import android.widget.FrameLayout
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.HostReaderArtFixture
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Runs the shipped JNI decoder; only its ordinary executor's base decode tasks are held. */
class ReaderPageImageNativeDrawGateTest {
    @Test
    fun metadataOnlyNativeChildDoesNotPrepareAndReadyChildPaintsAndAcknowledgesInOnePass() {
        HostReaderArtFixture("copymanga").use { fixture ->
            fixture.launch()
            fixture.await("Original reader must be mounted") { fixture.currentImages().isNotEmpty() }
            val executor = Executors.newSingleThreadExecutor()
            val firstTask = AtomicBoolean(true)
            val blockedTasks = LinkedBlockingQueue<Runnable>()
            val preparations = AtomicInteger()
            val acknowledgements = AtomicInteger()
            val firstAcknowledgementPreparations = AtomicInteger(-1)
            lateinit var image: ReaderPageImageView
            lateinit var native: SubsamplingScaleImageView
            val mounted = AtomicBoolean(false)
            val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
            val encoded = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
            bitmap.recycle()
            try {
                fixture.onReaderMain { activity ->
                    image = ReaderPageImageView(activity)
                    mounted.set(true)
                    (activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)).addView(
                        image,
                        FrameLayout.LayoutParams(600, 900),
                    )
                    // Installs the normal production SSIV child, then binds the original encoded stream.
                    image.setImage(
                        BitmapDrawable(activity.resources, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)),
                        ReaderPageImageView.Config(zoomDuration = 0),
                    )
                    native = image.getChildAt(0) as SubsamplingScaleImageView
                    native.setExecutor { task ->
                        if (firstTask.getAndSet(false)) executor.execute(task) else blockedTasks.add(task)
                    }
                    image.beforeImageDraw = { _, _ ->
                        preparations.incrementAndGet()
                        true
                    }
                    image.onImageDrawn = {
                        firstAcknowledgementPreparations.compareAndSet(-1, preparations.get())
                        acknowledgements.incrementAndGet()
                    }
                    image.setImage(Buffer().write(encoded), false, ReaderPageImageView.Config(zoomDuration = 0), 0)
                }
                fixture.await("Real JNI metadata must finish while its base decode is held") {
                    var initialized = false
                    fixture.onReaderMain { initialized = native.sWidth == 400 && blockedTasks.isNotEmpty() }
                    initialized
                }
                fixture.onReaderMain {
                    assertTrue(!native.isImageLoaded)
                    // Executes the real production/native drawing pass while no actual image exists.
                    val blank = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
                    try {
                        image.draw(Canvas(blank))
                    } finally {
                        blank.recycle()
                    }
                    assertEquals("Metadata cannot acquire the physical presentation lock", 0, preparations.get())
                    assertEquals(0, acknowledgements.get())
                }
                val decoded = AtomicBoolean(false)
                fixture.onReaderMain {
                    native.setExecutor(executor)
                    while (true) executor.execute(blockedTasks.poll() ?: break)
                }
                try {
                    fixture.await("The original JNI base image must become actually drawable") {
                        fixture.onReaderMain { decoded.set(native.isImageLoaded && acknowledgements.get() > 0) }
                        decoded.get()
                    }
                } catch (failure: AssertionError) {
                    var nativeState = ""
                    fixture.onReaderMain {
                        nativeState = "${native.sWidth}x${native.sHeight} ready=${native.isReady} " +
                            "baseLoaded=${native.isImageLoaded} prepare=${preparations.get()} " +
                            "ack=${acknowledgements.get()} held=${blockedTasks.size}"
                    }
                    throw AssertionError("Original native drawing state: $nativeState", failure)
                }
                fixture.await("The actual native drawing pass must expose its encoded pixels") {
                    fixture.containsColor(Color.MAGENTA)
                }
                fixture.onReaderMain {
                    assertEquals(
                        "The first actual native drawing pass prepares and acknowledges together",
                        1,
                        firstAcknowledgementPreparations.get(),
                    )
                    assertTrue(acknowledgements.get() > 0)
                }
            } finally {
                fixture.onReaderMain {
                    if (mounted.get()) {
                        image.recycle()
                        (image.parent as? ViewGroup)?.removeView(image)
                    }
                }
                executor.shutdownNow()
            }
        }
    }
}
