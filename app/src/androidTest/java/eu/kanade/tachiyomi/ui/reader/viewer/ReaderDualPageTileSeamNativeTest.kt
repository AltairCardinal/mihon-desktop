package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.HostReaderArtFixture
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DualPagerPageHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Real original decoder, SSIV tiles, production holder, and compositor pixels on the isolated AVD. */
class ReaderDualPageTileSeamNativeTest {
    @Test
    fun originalEncodedTilesCoverSharedSpineAcrossOddSizesAndResizeBack() {
        HostReaderArtFixture("copymanga").use { fixture ->
            val encoded = Bitmap.createBitmap(4501, 6401, Bitmap.Config.ARGB_8888).let { bitmap ->
                try {
                    bitmap.eraseColor(Color.RED)
                    ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                } finally {
                    bitmap.recycle()
                }
            }
            fixture.images.set(List(22) { encoded })
            fixture.cold()
            fixture.configure(mode = 6, matching = false)
            fixture.launch(initialPage = 1)
            fixture.awaitStable("Both original encoded pages must paint") {
                fixture.currentImages().size == 2 && fixture.containsColor(Color.RED)
            }
            val initialImages = fixture.currentImages()
            lateinit var holder: DualPagerPageHolder
            fixture.onReaderMain { activity ->
                assertTrue(
                    "Seam AVD needs a window at least 1461 by 1001",
                    activity.window.decorView.width >= 1461 && activity.window.decorView.height >= 1001,
                )
                var ancestor = initialImages.first().parent
                while (ancestor !is DualPagerPageHolder) ancestor = ancestor.parent
                holder = ancestor
            }
            for (height in listOf(1000, 1001)) {
                for (width in listOf(1283, 1327, 1393, 1401, 1461, 1400)) {
                    fixture.onReaderMain {
                        val pager = holder.parent as View
                        pager.layoutParams = pager.layoutParams.apply {
                            this.width = width
                            this.height = height
                        }
                        pager.requestLayout()
                    }
                    fixture.awaitStable("Actual holder must resize to ${width}x$height and retain both pages") {
                        holder.width == width && holder.height == height && fixture.currentImages().size == 2
                    }
                    val frame = requireNotNull(fixture.frame())
                    try {
                        var spine = 0
                        var top = 0
                        val mountedImages = fixture.currentImages()
                        fixture.onReaderMain {
                            val images = mountedImages.sortedBy { image ->
                                IntArray(2).also(image::getLocationInWindow)[0]
                            }
                            val leftSlot = images.first().parent as View
                            val location = IntArray(2).also(leftSlot::getLocationInWindow)
                            spine = location[0] + leftSlot.width
                            top = location[1]
                            images.forEach { image ->
                                val native = (image as ReaderPageImageView).getChildAt(0) as SubsamplingScaleImageView
                                assertEquals(4501, native.sWidth)
                                assertEquals(6401, native.sHeight)
                            }
                        }
                        val evidence = requireNotNull(fixture.context.getExternalFilesDir(null))
                            .resolve("tile-seam-${width}x$height.png")
                        evidence.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        android.util.Log.i(
                            "ReaderTileSeam",
                            "viewport=${width}x$height spine=$spine evidence=${evidence.absolutePath}",
                        )
                        for (y in top + height / 5 until top + height * 4 / 5) {
                            for (x in spine - 2..spine + 2) {
                                val pixel = frame.getPixel(x, y)
                                assertTrue(
                                    "Original tile seam ${width}x$height x=$x y=$y color=$pixel",
                                    Color.red(pixel) > 220 && Color.green(pixel) < 30 && Color.blue(pixel) < 30,
                                )
                            }
                        }
                    } finally {
                        frame.recycle()
                    }
                }
            }
        }
    }
}
