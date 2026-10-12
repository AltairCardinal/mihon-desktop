package mihon.desktop.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
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

    /** Sampling-only raster: preserve straight channels before white composition. */
    internal fun decodeStraightSrgb(bytes: ByteArray): ImageBitmap =
        SkiaData.makeFromBytes(bytes).use { data ->
            SkiaCodec.makeFromData(data).use { codec ->
                val bitmap = SkiaBitmap()
                try {
                    check(
                        bitmap.allocPixels(
                            org.jetbrains.skia.ImageInfo(
                                codec.width,
                                codec.height,
                                org.jetbrains.skia.ColorType.RGBA_8888,
                                org.jetbrains.skia.ColorAlphaType.UNPREMUL,
                                org.jetbrains.skia.ColorSpace.sRGB,
                            ),
                        ),
                    )
                    codec.readPixels(bitmap)
                    bitmap.asComposeImageBitmap()
                } catch (error: Throwable) {
                    bitmap.close()
                    throw error
                }
            }
        }

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
        return SkiaImage.makeFromEncoded(bytes).use { image -> decodeDownsampled(image, maxWidth, maxHeight) }
    }

    internal fun decodeDownsampledWithOriginal(
        bytes: ByteArray,
        maxWidth: Int,
        maxHeight: Int,
        reserveMemory: (Long) -> AutoCloseable = { AutoCloseable {} },
    ): Pair<ImageBitmap, Map<mihon.domain.reader.PageRotation, mihon.domain.reader.ReaderOriginalPageSamples>> =
        SkiaData.makeFromBytes(bytes).use { data ->
            SkiaCodec.makeFromData(data).use { codec ->
                val width = codec.width
                val height = codec.height
                val orientation = mihon.domain.reader.readImageOrientation(bytes)
                val size = orientation.orientedSize(width, height)
                val sample = calculateSampleSize(size.width, size.height, maxWidth, maxHeight)
                val targetWidth = size.width / sample
                val targetHeight = size.height / sample
                // Account for both simultaneously live rasters before allocating either of them.
                reserveMemory((width.toLong() * height + targetWidth.toLong() * targetHeight) * 4).use {
                    SkiaBitmap().use { original ->
                        check(
                            original.allocPixels(
                                org.jetbrains.skia.ImageInfo(
                                    width,
                                    height,
                                    org.jetbrains.skia.ColorType.RGBA_8888,
                                    org.jetbrains.skia.ColorAlphaType.UNPREMUL,
                                    org.jetbrains.skia.ColorSpace.sRGB,
                                ),
                            ),
                        )
                        codec.readPixels(original)
                        original.setImmutable()
                        val evidence = mihon.domain.reader.PageRotation.entries.associateWith { rotation ->
                            val plan =
                                requireNotNull(
                                    mihon.domain.reader.ReaderOriginalPageSampling.plan(
                                        width,
                                        height,
                                        orientation,
                                        rotation,
                                    ),
                                )
                            plan.samples(
                                plan.left.map { (x, y) -> original.getColor(x, y) },
                                plan.right.map { (x, y) -> original.getColor(x, y) },
                            )
                        }
                        val output = SkiaBitmap()
                        try {
                            check(output.allocN32Pixels(targetWidth, targetHeight))
                            SkiaCanvas(output).use { canvas ->
                                canvas.scale(targetWidth.toFloat() / size.width, targetHeight.toFloat() / size.height)
                                val transform = when (orientation) {
                                    mihon.domain.reader.ReaderImageOrientation.NORMAL -> floatArrayOf(
                                        1f,
                                        0f,
                                        0f,
                                        0f,
                                        1f,
                                        0f,
                                    )
                                    mihon.domain.reader.ReaderImageOrientation.MIRROR_HORIZONTAL -> floatArrayOf(
                                        -1f,
                                        0f,
                                        width.toFloat(),
                                        0f,
                                        1f,
                                        0f,
                                    )
                                    mihon.domain.reader.ReaderImageOrientation.HALF_TURN -> floatArrayOf(
                                        -1f,
                                        0f,
                                        width.toFloat(),
                                        0f,
                                        -1f,
                                        height.toFloat(),
                                    )
                                    mihon.domain.reader.ReaderImageOrientation.MIRROR_VERTICAL -> floatArrayOf(
                                        1f,
                                        0f,
                                        0f,
                                        0f,
                                        -1f,
                                        height.toFloat(),
                                    )
                                    mihon.domain.reader.ReaderImageOrientation.TRANSPOSE -> floatArrayOf(
                                        0f,
                                        1f,
                                        0f,
                                        1f,
                                        0f,
                                        0f,
                                    )
                                    mihon.domain.reader.ReaderImageOrientation.CLOCKWISE -> floatArrayOf(
                                        0f,
                                        -1f,
                                        height.toFloat(),
                                        1f,
                                        0f,
                                        0f,
                                    )
                                    mihon.domain.reader.ReaderImageOrientation.TRANSVERSE -> floatArrayOf(
                                        0f,
                                        -1f,
                                        height.toFloat(),
                                        -1f,
                                        0f,
                                        width.toFloat(),
                                    )
                                    mihon.domain.reader.ReaderImageOrientation.COUNTER_CLOCKWISE -> floatArrayOf(
                                        0f,
                                        1f,
                                        0f,
                                        -1f,
                                        0f,
                                        width.toFloat(),
                                    )
                                }
                                canvas.concat(org.jetbrains.skia.Matrix33(*(transform + floatArrayOf(0f, 0f, 1f))))
                                SkiaImage.makeFromBitmap(original).use { image -> canvas.drawImage(image, 0f, 0f) }
                            }
                            output.asComposeImageBitmap() to evidence
                        } catch (error: Throwable) {
                            output.close()
                            throw error
                        }
                    }
                }
            }
        }

    private fun decodeDownsampled(image: SkiaImage, maxWidth: Int, maxHeight: Int): ImageBitmap {
        val sampleSize = calculateSampleSize(image.width, image.height, maxWidth, maxHeight)
        if (sampleSize <= 1) return image.toComposeImageBitmap()

        val targetW = image.width / sampleSize
        val targetH = image.height / sampleSize
        val bitmap = SkiaBitmap()
        bitmap.allocN32Pixels(targetW, targetH)
        SkiaCanvas(bitmap).use { canvas ->
            canvas.drawImageRect(
                image,
                SkiaRect.makeWH(image.width.toFloat(), image.height.toFloat()),
                SkiaRect.makeWH(targetW.toFloat(), targetH.toFloat()),
            )
        }
        return bitmap.asComposeImageBitmap()
    }

    // Ceiling ratio guarantees the cached decode never exceeds either requested bound.
    private fun calculateSampleSize(imgW: Int, imgH: Int, maxW: Int, maxH: Int): Int {
        require(maxW > 0 && maxH > 0) { "decoded bounds must be positive" }
        val widthRatio = (imgW.toLong() + maxW - 1L) / maxW
        val heightRatio = (imgH.toLong() + maxH - 1L) / maxH
        return maxOf(widthRatio, heightRatio, 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    internal fun originalDecodePeakBytes(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Long {
        val sample = calculateSampleSize(width, height, maxWidth, maxHeight)
        return (width.toLong() * height + (width / sample).toLong() * (height / sample)) * 4
    }
}
