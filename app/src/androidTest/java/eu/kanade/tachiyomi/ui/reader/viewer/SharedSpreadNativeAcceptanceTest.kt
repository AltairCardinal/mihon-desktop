package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.os.Build
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.runBlocking
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderDualPageContentIdentity
import mihon.domain.reader.ReaderDualPageIdentity
import mihon.domain.reader.ReaderImageOrientation
import mihon.domain.reader.ReaderPageFact
import mihon.domain.reader.SharedDualPageSession
import mihon.domain.reader.SharedSpreadMatching
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** DP02 uses the signed candidate and its unmodified JNI adapter in the dedicated emulator. */
class SharedSpreadNativeAcceptanceTest {
    @Test
    fun originalJniNormalizesExifOnceAndSamplesStraightSrgbAlpha() = runBlocking {
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val red = 0xffff0000.toInt()
        val green = 0xff00ff00.toInt()
        val blue = 0xff0000ff.toInt()
        val yellow = 0xffffff00.toInt()
        val corners = listOf(
            listOf(red, blue, green, yellow),
            listOf(green, yellow, red, blue),
            listOf(yellow, green, blue, red),
            listOf(blue, red, yellow, green),
            listOf(red, green, blue, yellow),
            listOf(blue, yellow, red, green),
            listOf(yellow, blue, green, red),
            listOf(green, red, yellow, blue),
        )
        for (orientation in ReaderImageOrientation.entries) {
            val width = if (orientation.exifValue < 5) 40 else 60
            val height = if (orientation.exifValue < 5) 60 else 40
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    bitmap.setPixel(
                        x,
                        y,
                        when {
                            x < width / 2 && y < height / 2 -> red
                            x >= width / 2 && y < height / 2 -> green
                            x < width / 2 -> blue
                            else -> yellow
                        },
                    )
                }
            }
            val raw = ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
            }.toByteArray()
            bitmap.recycle()
            val exif = "45786966000049492a0008000000010012010300010000000100000000000000"
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray().also { it[24] = orientation.exifValue.toByte() }
            val encoded = raw.copyOfRange(0, 2) + byteArrayOf(-1, -31, 0, (exif.size + 2).toByte()) + exif +
                raw.copyOfRange(2, raw.size)
            val samples = sample(encoded, width, height, orientation)
            val actual = listOf(samples.left.first(), samples.left.last(), samples.right.first(), samples.right.last())
            actual.zip(corners[orientation.exifValue - 1]).forEach { (pixel, expected) ->
                for (shift in listOf(0, 8, 16)) {
                    assertTrue(
                        "JNI original region/EXIF ${orientation.exifValue} differs from common direction",
                        kotlin.math.abs((pixel ushr shift and 255) - (expected ushr shift and 255)) < 30,
                    )
                }
            }
        }
        val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
        for (y in 0 until 60) bitmap.setPixel(2, y, 0x80ff0000.toInt())
        val transparent = ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        bitmap.recycle()
        val samples = sample(transparent, 40, 60, ReaderImageOrientation.NORMAL)
        assertEquals(List(50) { 0xffff7f7f.toInt() }, samples.left.map(SharedSpreadMatching::normalizeSample))
        assertEquals(List(50) { -1 }, samples.right.map(SharedSpreadMatching::normalizeSample))
    }

    private suspend fun sample(
        bytes: ByteArray,
        width: Int,
        height: Int,
        orientation: ReaderImageOrientation,
    ): mihon.domain.reader.ReaderSpreadEdgeSamples {
        var closed = 0
        val page = ReaderPage(1, stream = {
            object : java.io.ByteArrayInputStream(bytes) {
                override fun close() {
                    closed++
                    super.close()
                }
            }
        })
        val ids = List(3) { ReaderPageId(ReaderChapterId(87), it) }
        val owner = SharedDualPageSession(
            ReaderDualPageIdentity(
                1,
                1,
                ids.map { id ->
                    ReaderDualPageContentIdentity(
                        id,
                        if (id.sourcePageIndex == 1) page.encodedContentIdentity() else "other",
                        sourceOrientation = orientation,
                    )
                },
            ),
            ReaderDirection.RTL,
        )
        try {
            ids.forEach {
                owner.acceptFact(
                    owner.requestFact(it),
                    ReaderPageFact.dimensions(
                        40,
                        60,
                        sourceOrientation = orientation,
                    ),
                )
            }
            owner.configure(ReaderDirection.RTL, automaticMatching = true)
            return requireNotNull(
                AndroidReaderSpreadSampler.sample(
                    page,
                    requireNotNull(owner.requestSpreadSample(ids[1])),
                    width,
                    height,
                ),
            ).also { assertEquals(1, closed) }
        } finally {
            owner.close()
        }
    }
}
