package mihon.desktop.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import mihon.domain.error.AppError
import mihon.domain.reader.PageDecodeRequest
import mihon.domain.reader.PageDecodeResult
import mihon.domain.reader.PageDecoder
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.RegionDecoder
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect

class SkiaPageDecoder : PageDecoder<ByteArray, ImageBitmap> {
    override suspend fun decode(
        encoded: ByteArray,
        request: PageDecodeRequest,
    ): PageDecodeResult<ImageBitmap> = try {
        require(request.maxWidth > 0 && request.maxHeight > 0)
        val bitmap = SkiaImageDecoder.decodeDownsampled(encoded, request.maxWidth, request.maxHeight)
        PageDecodeResult.Success(
            generation = request.generation,
            value = bitmap,
            width = bitmap.width,
            height = bitmap.height,
            estimatedBytes = bitmap.width.toLong() * bitmap.height * BYTES_PER_PIXEL,
        )
    } catch (error: Exception) {
        PageDecodeResult.Failure(request.generation, AppError.MalformedData(error))
    }
}

/**
 * Skia tile adapter. Only the requested source rectangle is materialized in the returned bitmap;
 * the encoded source is not inserted into the ordinary decoded-page cache.
 */
class SkiaRegionPageDecoder : RegionDecoder<ByteArray, ImageBitmap> {
    override suspend fun decodeRegion(
        encoded: ByteArray,
        request: PageDecodeRequest,
    ): PageDecodeResult<ImageBitmap> = try {
        require(request.maxWidth > 0 && request.maxHeight > 0)
        val source = Image.makeFromEncoded(encoded)
        try {
            val region = request.region?.clampTo(source.width, source.height)
                ?: PixelBounds(0, 0, source.width, source.height)
            require(region.width > 0 && region.height > 0)
            val sampleSize = maxOf(
                ceilDiv(region.width, request.maxWidth),
                ceilDiv(region.height, request.maxHeight),
                1,
            )
            val targetWidth = ceilDiv(region.width, sampleSize)
            val targetHeight = ceilDiv(region.height, sampleSize)
            val target = Bitmap().apply { allocN32Pixels(targetWidth, targetHeight) }
            Canvas(target).drawImageRect(
                source,
                Rect.makeLTRB(
                    region.x.toFloat(),
                    region.y.toFloat(),
                    (region.x + region.width).toFloat(),
                    (region.y + region.height).toFloat(),
                ),
                Rect.makeWH(targetWidth.toFloat(), targetHeight.toFloat()),
            )
            PageDecodeResult.Success(
                generation = request.generation,
                value = target.asComposeImageBitmap(),
                width = targetWidth,
                height = targetHeight,
                estimatedBytes = targetWidth.toLong() * targetHeight * BYTES_PER_PIXEL,
                isSampled = sampleSize > 1,
            )
        } finally {
            source.close()
        }
    } catch (error: Exception) {
        PageDecodeResult.Failure(request.generation, AppError.MalformedData(error))
    }
}

private fun PixelBounds.clampTo(imageWidth: Int, imageHeight: Int): PixelBounds {
    val left = x.coerceIn(0, imageWidth)
    val top = y.coerceIn(0, imageHeight)
    val right = (x + width).coerceIn(left, imageWidth)
    val bottom = (y + height).coerceIn(top, imageHeight)
    return PixelBounds(left, top, right - left, bottom - top)
}

private fun ceilDiv(value: Int, divisor: Int): Int =
    ((value.toLong() + divisor - 1L) / divisor).toInt()

private const val BYTES_PER_PIXEL = 4L
