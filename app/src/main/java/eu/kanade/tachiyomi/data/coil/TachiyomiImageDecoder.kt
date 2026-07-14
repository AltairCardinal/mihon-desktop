package eu.kanade.tachiyomi.data.coil

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import mihon.domain.reader.PageDecodeRequest
import mihon.domain.reader.PageDecodeResult
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.decoder.ImageDecoder

/**
 * A [Decoder] that uses built-in [ImageDecoder] to decode images that is not supported by the system.
 */
class TachiyomiImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {

    override suspend fun decode(): DecodeResult {
        val request = PageDecodeRequest(
            pageIndex = UNKNOWN_PAGE_INDEX,
            generation = INITIAL_DECODE_GENERATION,
            maxWidth = options.size.widthPx(options.scale) { Int.MAX_VALUE },
            maxHeight = options.size.heightPx(options.scale) { Int.MAX_VALUE },
        )
        val decoder = AndroidTachiyomiPageDecoder(
            cropBorders = options.cropBorders,
            displayProfile = displayProfile,
            bitmapConfig = options.bitmapConfig,
            scale = options.scale,
        )
        val result = resources.sourceOrNull()?.use { source ->
            decodeWithSharedPageDecoder(source, request, decoder)
        } ?: error("Failed to open image source")
        val decoded = when (result) {
            is PageDecodeResult.Success -> result
            is PageDecodeResult.Failure -> throw result.error.cause ?: IllegalStateException("Failed to decode image")
        }

        return DecodeResult(
            image = decoded.value.asImage(),
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
        private const val UNKNOWN_PAGE_INDEX = -1
        private const val INITIAL_DECODE_GENERATION = 0L
        var displayProfile: ByteArray? = null
    }
}
