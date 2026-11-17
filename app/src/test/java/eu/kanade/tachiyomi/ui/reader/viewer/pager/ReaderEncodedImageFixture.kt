package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** JVM-only PNG codec seam; SSIV still owns its tasks, Ready callbacks, tiles and draw ACKs. */
internal class ReaderPngRegionDecoderFixture : AutoCloseable {
    private val pixels = java.util.concurrent.ConcurrentHashMap<Any, Bitmap>()

    init {
        io.mockk.mockkConstructor(com.davemorrissey.labs.subscaleview.decoder.Decoder::class)
        io.mockk.every {
            anyConstructed<com.davemorrissey.labs.subscaleview.decoder.Decoder>().init(any(), any())
        } answers {
            val source = secondArg<com.davemorrissey.labs.subscaleview.provider.InputProvider>()
                .openStream()!!.use { BitmapFactory.decodeStream(it)!! }
            pixels[self] = source
            android.graphics.Point(source.width, source.height)
        }
        io.mockk.every {
            anyConstructed<com.davemorrissey.labs.subscaleview.decoder.Decoder>().decodeRegion(any(), any())
        } answers {
            val region = firstArg<Rect>()
            val sample = secondArg<Int>()
            Bitmap.createBitmap(
                (region.width() + sample - 1) / sample,
                (region.height() + sample - 1) / sample,
                Bitmap.Config.ARGB_8888,
            ).also { tile ->
                Canvas(tile).drawBitmap(
                    pixels[self]!!,
                    region,
                    Rect(0, 0, tile.width, tile.height),
                    android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
                )
            }
        }
        io.mockk.every {
            anyConstructed<com.davemorrissey.labs.subscaleview.decoder.Decoder>().isReady()
        } answers { pixels[self]?.isRecycled == false }
        io.mockk.every {
            anyConstructed<com.davemorrissey.labs.subscaleview.decoder.Decoder>().recycle()
        } answers {
            pixels.remove(self)?.recycle()
            Unit
        }
    }

    override fun close() {
        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (ReaderPageImageView.foregroundWork.value > 0 && System.nanoTime() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            check(ReaderPageImageView.foregroundWork.value == 0) { "Original codec work must finish before teardown" }
        } finally {
            io.mockk.unmockkConstructor(com.davemorrissey.labs.subscaleview.decoder.Decoder::class)
            pixels.values.forEach { if (!it.isRecycled) it.recycle() }
            pixels.clear()
        }
    }
}

/** The JVM host supplies decoded pixels; the original SSIV child still prepares, paints and acknowledges them. */
internal fun paintReaderImage(image: ReaderPageImageView, encoded: ByteArray) {
    check(image.width > 0 && image.height > 0) { "The original image slot must be laid out before drawing" }
    val native = ReflectionHelpers.getField<SubsamplingScaleImageView>(image, "pageView")
    val pixels = requireNotNull(BitmapFactory.decodeByteArray(encoded, 0, encoded.size))
    native.setImage(ImageSource.bitmap(pixels))
    val prepare = image.beforeImageDraw
    var prepared = false
    var prepareCalls = 0
    image.beforeImageDraw = { width, height ->
        prepareCalls++
        (prepare?.invoke(width, height) != false).also { prepared = it }
    }
    val output = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
    try {
        image.draw(Canvas(output))
        check(native.isReady) { "The original native child must actually draw its decoded bitmap" }
        check(prepared) {
            "The original bitmap draw must prepare in that pass: calls=$prepareCalls ready=${native.isReady}, " +
                "loaded=${native.isImageLoaded}, shown=${image.isShown}, visible=${image.getGlobalVisibleRect(
                    Rect(),
                )}, " +
                "bounds=${image.left},${image.top},${image.right},${image.bottom}, attached=${image.isAttachedToWindow}"
        }
    } finally {
        image.beforeImageDraw = prepare
        output.recycle()
    }
}

internal fun readerTestPng(w: Int, h: Int): ByteArray {
    // Encode transparent scanlines without allocating a full bitmap for every vector.
    val compressed = ByteArrayOutputStream()
    DeflaterOutputStream(compressed).use { output ->
        val row = ByteArray(1 + w * 4)
        repeat(h) { output.write(row) }
    }
    val image = ByteArrayOutputStream()
    DataOutputStream(image).use { output ->
        output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun chunk(name: String, payload: ByteArray) {
            val type = name.toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply {
                update(type)
                update(payload)
            }
            output.writeInt(payload.size)
            output.write(type)
            output.write(payload)
            output.writeInt(crc.value.toInt())
        }
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use {
            it.writeInt(w)
            it.writeInt(h)
            it.write(byteArrayOf(8, 6, 0, 0, 0))
        }
        chunk("IHDR", header.toByteArray())
        chunk("IDAT", compressed.toByteArray())
        chunk("IEND", byteArrayOf())
    }
    return image.toByteArray()
}
