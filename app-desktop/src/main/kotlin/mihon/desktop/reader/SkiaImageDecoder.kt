package mihon.desktop.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.Canvas as SkiaCanvas
import org.jetbrains.skia.Codec as SkiaCodec
import org.jetbrains.skia.Data as SkiaData
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect as SkiaRect

/**
 * Native Skia-based image decoder.
 *
 * Unlike [javax.imageio.ImageIO], Skia uses libjpeg-turbo (with SIMD) internally,
 * providing 3-5× faster JPEG decoding and native WebP/AVIF support.
 *
 * No additional dependencies: Skiko is already a transitive dependency of
 * `compose.desktop.currentOs`.
 */
object SkiaImageDecoder {

    /**
     * Decodes [bytes] to an [ImageBitmap] using Skia's native codec.
     * Supports JPEG, PNG, WebP, GIF, and any format supported by the platform's Skia build.
     */
    fun decode(bytes: ByteArray): ImageBitmap =
        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()

    /**
     * Returns the `(width, height)` of the image described by [bytes] without fully
     * decoding pixel data. Returns `null` if the bytes are not a recognised image format.
     */
    fun peekSize(bytes: ByteArray): Pair<Int, Int>? = try {
        val image = SkiaImage.makeFromEncoded(bytes)
        try {
            Pair(image.width, image.height)
        } finally {
            image.close()
        }
    } catch (_: Exception) {
        null
    }

    /** Returns true only after Skia has decoded the complete encoded pixel stream. */
    fun canDecodePixels(bytes: ByteArray): Boolean = try {
        val data = SkiaData.makeFromBytes(bytes)
        try {
            val codec = SkiaCodec.makeFromData(data)
            try {
                val bitmap = codec.readPixels()
                try {
                    !bitmap.isNull && bitmap.width > 0 && bitmap.height > 0
                } finally {
                    bitmap.close()
                }
            } finally {
                codec.close()
            }
        } finally {
            data.close()
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Decodes [bytes] and scales down the result so that neither dimension exceeds
     * [maxWidth] / [maxHeight].  If the image is already within bounds, returns the
     * full-resolution decode (same as [decode]).
     *
     * Memory benefit: an 8 000×6 000 scan page viewed at 1 920×1 080 needs only
     * ~12 MB instead of ~183 MB after downsampling by a factor of 4.
     */
    fun decodeDownsampled(bytes: ByteArray, maxWidth: Int, maxHeight: Int): ImageBitmap {
        val image = SkiaImage.makeFromEncoded(bytes)
        val sampleSize = calculateSampleSize(image.width, image.height, maxWidth, maxHeight)
        if (sampleSize <= 1) return image.toComposeImageBitmap()

        val targetW = image.width / sampleSize
        val targetH = image.height / sampleSize
        val bitmap = SkiaBitmap()
        bitmap.allocN32Pixels(targetW, targetH)
        val canvas = SkiaCanvas(bitmap)
        canvas.drawImageRect(
            image,
            SkiaRect.makeWH(image.width.toFloat(), image.height.toFloat()),
            SkiaRect.makeWH(targetW.toFloat(), targetH.toFloat()),
        )
        return bitmap.asComposeImageBitmap()
    }

    // Ceiling ratio guarantees the cached decode never exceeds either requested bound.
    private fun calculateSampleSize(imgW: Int, imgH: Int, maxW: Int, maxH: Int): Int {
        require(maxW > 0 && maxH > 0) { "decoded bounds must be positive" }
        val widthRatio = (imgW.toLong() + maxW - 1L) / maxW
        val heightRatio = (imgH.toLong() + maxH - 1L) / maxH
        return maxOf(widthRatio, heightRatio, 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
