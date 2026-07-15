package eu.kanade.tachiyomi.data.coil

import android.graphics.Bitmap
import coil3.Image
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import kotlinx.coroutines.CancellationException
import mihon.domain.reader.PageDecodeRequest
import mihon.domain.reader.PageDecodeResult
import mihon.domain.reader.PageDecoder
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.decoder.ImageDecoder

/**
 * A [Decoder] that uses built-in [ImageDecoder] to decode images that is not supported by the system.
 */
class TachiyomiImageDecoder internal constructor(
    private val resources: ImageSource,
    private val options: Options,
    private val identity: DecodeRequestIdentity = options.readerDecodeIdentity
        ?: CoilDecodeRequestIdentitySource.next(options),
    private val pageDecoder: PageDecoder<BufferedSource, Bitmap> = AndroidTachiyomiPageDecoder(
        cropBorders = options.cropBorders,
        displayProfile = displayProfile,
        bitmapConfig = options.bitmapConfig,
    ),
    private val imageMapper: (Bitmap) -> Image = { it.asImage() },
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val request = PageDecodeRequest(
            pageIndex = identity.pageIndex,
            generation = identity.generation,
            maxWidth = options.size.widthPx(options.scale) { Int.MAX_VALUE },
            maxHeight = options.size.heightPx(options.scale) { Int.MAX_VALUE },
        )
        val result = resources.sourceOrNull()?.use { source ->
            decodeWithSharedPageDecoder(source, request, pageDecoder)
        } ?: error("Failed to open image source")
        val decoded = when (result) {
            is PageDecodeResult.Success -> result
            is PageDecodeResult.Failure -> throw result.error.cause ?: IllegalStateException("Failed to decode image")
        }
        if (decoded.generation != identity.generation || !identity.isCurrent()) {
            throw CancellationException("Ignoring stale reader decode generation ${decoded.generation}")
        }

        return DecodeResult(
            image = imageMapper(decoded.value),
            isSampled = decoded.isSampled,
        )
    }

    class Factory : Decoder.Factory {

        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            return if (options.customDecoder || isApplicable(result.source.source())) {
                TachiyomiImageDecoder(result.source, options)
            } else {
                null
            }
        }

        private fun isApplicable(source: BufferedSource): Boolean {
            val type = source.peek().inputStream().use {
                ImageUtil.findImageType(it)
            }
            return when (type) {
                ImageUtil.ImageType.AVIF, ImageUtil.ImageType.JXL, ImageUtil.ImageType.HEIF -> true
                else -> false
            }
        }

        override fun equals(other: Any?) = other is Factory

        override fun hashCode() = javaClass.hashCode()
    }

    companion object {
        var displayProfile: ByteArray? = null
    }
}
