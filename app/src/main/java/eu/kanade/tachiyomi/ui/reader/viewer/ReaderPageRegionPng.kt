package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.BufferedSource
import tachiyomi.decoder.ImageDecoder
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Narrow adapter for original half-page crop with bounded raw-region decoding. */
internal interface ReaderPageRegionDecoder {
    fun decode(region: Rect): Bitmap
    fun recycle()
}

internal suspend fun encodeReaderPageRegion(
    source: BufferedSource,
    region: Rect,
    directory: File,
    decoderFactory: (BufferedSource) -> ReaderPageRegionDecoder = { encoded ->
        // The intermediate is sRGB; the existing SSIV converts it to the display profile once.
        val original = encoded.peek().inputStream().use { checkNotNull(ImageDecoder.newInstance(it, false, null)) }
        object : ReaderPageRegionDecoder {
            override fun decode(region: Rect) = checkNotNull(original.decode(region = region, sampleSize = 1))
            override fun recycle() = original.recycle()
        }
    },
): File {
    require(region.width() > 0 && region.height() > 0)
    val stripRows = (MAX_REGION_BITMAP_BYTES / 4 / region.width()).toInt()
    require(stripRows > 0) { "Source region row exceeds the four MiB decode bound" }
    currentCoroutineContext().ensureActive()
    check(directory.isDirectory || directory.mkdirs())
    val output = File.createTempFile("reader-half-", ".png", directory)
    var decoder: ReaderPageRegionDecoder? = null
    var committed = false
    var failure: Throwable? = null
    try {
        decoder = decoderFactory(source)
        DataOutputStream(output.outputStream().buffered()).use { png ->
            png.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            val header = java.nio.ByteBuffer.allocate(13).putInt(region.width()).putInt(region.height())
                .put(8).put(6).put(0).put(0).put(0).array()
            writePngChunk(png, "IHDR", header, header.size)
            writePngChunk(png, "sRGB", byteArrayOf(0), 1)
            val deflater = Deflater()
            try {
                DeflaterOutputStream(PngImageData(png), deflater, 64 * 1024).use { encoded ->
                    val pixels = IntArray(region.width())
                    val row = ByteArray(1 + region.width() * 4)
                    var top = region.top
                    while (top < region.bottom) {
                        currentCoroutineContext().ensureActive()
                        val bottom = minOf(region.bottom.toLong(), top.toLong() + stripRows).toInt()
                        val strip = checkNotNull(decoder).decode(Rect(region.left, top, region.right, bottom))
                        try {
                            check(
                                strip.width == region.width() && strip.height == bottom - top &&
                                    strip.allocationByteCount.toLong() <= MAX_REGION_BITMAP_BYTES,
                            ) {
                                "Native region exceeded its admitted decode bound"
                            }
                            for (y in 0 until strip.height) {
                                currentCoroutineContext().ensureActive()
                                strip.getPixels(pixels, 0, pixels.size, 0, y, pixels.size, 1)
                                pixels.forEachIndexed { x, color ->
                                    val at = 1 + x * 4
                                    row[at] = (color ushr 16).toByte()
                                    row[at + 1] = (color ushr 8).toByte()
                                    row[at + 2] = color.toByte()
                                    row[at + 3] = (color ushr 24).toByte()
                                }
                                encoded.write(row)
                            }
                        } finally {
                            strip.recycle()
                        }
                        top = bottom
                    }
                }
            } finally {
                deflater.end()
            }
            writePngChunk(png, "IEND", ByteArray(0), 0)
        }
        currentCoroutineContext().ensureActive()
        val completedDecoder = decoder
        decoder = null
        completedDecoder?.recycle()
        committed = true
        return output
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            decoder?.recycle()
        } catch (releaseError: Throwable) {
            failure?.addSuppressed(releaseError) ?: throw releaseError
        } finally {
            if (!committed) output.delete()
        }
    }
}

private const val MAX_REGION_BITMAP_BYTES = 4L * 1024 * 1024

private fun writePngChunk(output: DataOutputStream, name: String, bytes: ByteArray, count: Int) {
    val type = name.toByteArray(Charsets.US_ASCII)
    output.writeInt(count)
    output.write(type)
    output.write(bytes, 0, count)
    val crc = CRC32().apply {
        update(type)
        update(bytes, 0, count)
    }
    output.writeInt(crc.value.toInt())
}

/** Deflate output is emitted in small IDAT chunks rather than retained as one PNG buffer. */
private class PngImageData(private val output: DataOutputStream) : OutputStream() {
    private val bytes = ByteArray(64 * 1024)
    private var count = 0
    override fun write(value: Int) {
        if (count == bytes.size) flush()
        bytes[count++] = value.toByte()
    }
    override fun write(source: ByteArray, offset: Int, length: Int) {
        var at = offset
        var remaining = length
        while (remaining > 0) {
            if (count == bytes.size) flush()
            val copied = minOf(remaining, bytes.size - count)
            source.copyInto(bytes, count, at, at + copied)
            count += copied
            at += copied
            remaining -= copied
        }
    }
    override fun flush() {
        if (count > 0) {
            writePngChunk(output, "IDAT", bytes, count)
            count = 0
        }
    }
    override fun close() = flush()
}
