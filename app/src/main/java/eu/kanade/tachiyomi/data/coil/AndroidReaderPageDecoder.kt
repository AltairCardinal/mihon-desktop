package eu.kanade.tachiyomi.data.coil

import android.graphics.Bitmap
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import mihon.domain.error.AppError
import mihon.domain.reader.PageDecodeCachePolicy
import mihon.domain.reader.PageDecodeRequest
import mihon.domain.reader.PageDecodeResult
import mihon.domain.reader.PageDecoder
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.decoder.ImageDecoder

internal suspend fun <S, T> decodeWithSharedPageDecoder(
    encoded: S,
    request: PageDecodeRequest,
    decoder: PageDecoder<S, T>,
): PageDecodeResult<T> = decoder.decode(encoded, request)

internal interface AndroidReaderNativeDecoder {
    val width: Int
    val height: Int

    fun decode(sampleSize: Int): Bitmap?
    fun recycle()
}

internal fun interface AndroidReaderNativeDecoderFactory {
    fun create(
        encoded: BufferedSource,
        cropBorders: Boolean,
        displayProfile: ByteArray?,
    ): AndroidReaderNativeDecoder?
}

private class TachiyomiReaderNativeDecoder(
    private val delegate: ImageDecoder,
) : AndroidReaderNativeDecoder {
    override val width: Int = delegate.width
    override val height: Int = delegate.height

    override fun decode(sampleSize: Int): Bitmap? = delegate.decode(sampleSize = sampleSize)

    override fun recycle() = delegate.recycle()
}

private val defaultAndroidReaderNativeDecoderFactory =
    AndroidReaderNativeDecoderFactory { encoded, cropBorders, displayProfile ->
        ImageDecoder.newInstance(encoded.inputStream(), cropBorders, displayProfile)
            ?.let(::TachiyomiReaderNativeDecoder)
    }

/** Android adapter around the upstream native decoder; encoded input remains streaming. */
internal class AndroidTachiyomiPageDecoder(
    private val cropBorders: Boolean,
    private val displayProfile: ByteArray?,
    private val bitmapConfig: Bitmap.Config,
    private val nativeDecoderFactory: AndroidReaderNativeDecoderFactory = defaultAndroidReaderNativeDecoderFactory,
) : PageDecoder<BufferedSource, Bitmap> {

    override suspend fun decode(
        encoded: BufferedSource,
        request: PageDecodeRequest,
    ): PageDecodeResult<Bitmap> {
        var decoder: AndroidReaderNativeDecoder? = null
        return try {
            require(request.maxWidth > 0 && request.maxHeight > 0)
            decoder = nativeDecoderFactory.create(encoded, cropBorders, displayProfile)
            check(decoder != null && decoder.width > 0 && decoder.height > 0) { "Failed to initialize decoder" }

            val sourceWidth = decoder.width
            val sourceHeight = decoder.height
            val sampleSize = calculateBoundedReaderSampleSize(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                maxWidth = request.maxWidth,
                maxHeight = request.maxHeight,
            )
            var bitmap = checkNotNull(decoder.decode(sampleSize = sampleSize)) { "Failed to decode image" }

            if (bitmapConfig == Bitmap.Config.HARDWARE && ImageUtil.canUseHardwareBitmap(bitmap)) {
                bitmap.copy(Bitmap.Config.HARDWARE, false)?.let { hardwareBitmap ->
                    bitmap.recycle()
                    bitmap = hardwareBitmap
                }
            }

            PageDecodeResult.Success(
                generation = request.generation,
                value = bitmap,
                width = bitmap.width,
                height = bitmap.height,
                estimatedBytes = bitmap.allocationByteCount.toLong(),
                isSampled = sampleSize > 1,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            PageDecodeResult.Failure(
                generation = request.generation,
                error = AppError.MalformedData(error),
            )
        } finally {
            decoder?.recycle()
        }
    }
}

/** Returns the smallest power-of-two sample whose ceiling-sized output fits both reader bounds. */
internal fun calculateBoundedReaderSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    maxWidth: Int,
    maxHeight: Int,
): Int {
    require(sourceWidth > 0 && sourceHeight > 0)
    require(maxWidth > 0 && maxHeight > 0)

    fun requiredSample(source: Int, maximum: Int): Long =
        (source.toLong() + maximum - 1L) / maximum

    val requiredSample = maxOf(
        1L,
        requiredSample(sourceWidth, maxWidth),
        requiredSample(sourceHeight, maxHeight),
    )
    var sampleSize = 1L
    while (sampleSize < requiredSample) {
        sampleSize = sampleSize shl 1
    }
    require(sampleSize <= Int.MAX_VALUE) { "Required sample size cannot be represented as a positive Int" }
    return sampleSize.toInt()
}

internal data class AndroidReaderCachePolicy(
    val memory: CachePolicy,
    val disk: CachePolicy,
)

internal fun mapAndroidReaderCachePolicy(policy: PageDecodeCachePolicy): AndroidReaderCachePolicy =
    AndroidReaderCachePolicy(
        memory = if (policy.decodedMemoryCache) CachePolicy.ENABLED else CachePolicy.DISABLED,
        disk = if (policy.decodedDiskCache) CachePolicy.ENABLED else CachePolicy.DISABLED,
    )

internal fun ImageRequest.Builder.applySharedReaderCachePolicy(policy: PageDecodeCachePolicy): ImageRequest.Builder =
    apply {
        val mapped = mapAndroidReaderCachePolicy(policy)
        memoryCachePolicy(mapped.memory)
        diskCachePolicy(mapped.disk)
    }
