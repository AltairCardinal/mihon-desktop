package mihon.desktop.reader

import androidx.compose.ui.graphics.ImageBitmap
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import mihon.domain.error.AppError
import mihon.domain.reader.PageDecodeRequest
import mihon.domain.reader.PageDecodeResult
import mihon.domain.reader.PageDecoder
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.RegionDecoder

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

internal object DesktopReaderLargeImagePolicy {
    const val PIXEL_THRESHOLD = 16_000_000L

    fun requiresRegionTiles(width: Int, height: Int): Boolean {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        return width.toLong() * height.toLong() > PIXEL_THRESHOLD
    }
}

/** Native bounded raster decode adapter; unsupported formats fail without a second full-page decode. */
internal class ImageIoRegionDecodeAdapter : RegionDecoder<ByteArray, ImageBitmap> {
    override suspend fun decodeRegion(
        encoded: ByteArray,
        request: PageDecodeRequest,
    ): PageDecodeResult<ImageBitmap> = try {
        require(request.maxWidth > 0 && request.maxHeight > 0)
        val input = checkNotNull(ImageIO.createImageInputStream(ByteArrayInputStream(encoded))) {
            "Unable to open encoded image input"
        }
        input.use {
            val readers = ImageIO.getImageReaders(input)
            check(readers.hasNext()) { "No bounded ImageIO reader supports this image format" }
            val reader = readers.next()
            try {
                reader.input = input
                val sourceWidth = reader.getWidth(0)
                val sourceHeight = reader.getHeight(0)
                val region = request.region?.clampTo(sourceWidth, sourceHeight)
                    ?: PixelBounds(0, 0, sourceWidth, sourceHeight)
                require(region.width > 0 && region.height > 0)
                val sampleSize = maxOf(
                    ceilDiv(region.width, request.maxWidth),
                    ceilDiv(region.height, request.maxHeight),
                    1,
                )
                val parameters = reader.defaultReadParam.apply {
                    sourceRegion = Rectangle(region.x, region.y, region.width, region.height)
                    setSourceSubsampling(sampleSize, sampleSize, 0, 0)
                }
                val boundedRaster = reader.read(0, parameters)
                val bitmap = boundedRaster.toImageBitmap()
                PageDecodeResult.Success(
                    generation = request.generation,
                    value = bitmap,
                    width = bitmap.width,
                    height = bitmap.height,
                    estimatedBytes = bitmap.width.toLong() * bitmap.height * BYTES_PER_PIXEL,
                    isSampled = sampleSize > 1,
                )
            } finally {
                reader.dispose()
            }
        }
    } catch (error: Exception) {
        PageDecodeResult.Failure(request.generation, AppError.MalformedData(error))
    }
}

/** Compatibility name retained for existing DI and shared [RegionDecoder] consumers. */
class SkiaRegionPageDecoder(
    private val delegate: RegionDecoder<ByteArray, ImageBitmap> = ImageIoRegionDecodeAdapter(),
) : RegionDecoder<ByteArray, ImageBitmap> by delegate

private fun BufferedImage.toImageBitmap(): ImageBitmap {
    val encoded = ByteArrayOutputStream().use { output ->
        check(ImageIO.write(this, "png", output)) { "Unable to encode bounded region" }
        output.toByteArray()
    }
    return SkiaImageDecoder.decode(encoded)
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
