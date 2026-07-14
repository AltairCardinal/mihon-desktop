package eu.kanade.tachiyomi.data.coil

import android.graphics.Bitmap
import coil3.decode.DecodeUtils
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Scale
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

/** Android adapter around the upstream native decoder; encoded input remains streaming. */
internal class AndroidTachiyomiPageDecoder(
    private val cropBorders: Boolean,
    private val displayProfile: ByteArray?,
    private val bitmapConfig: Bitmap.Config,
    private val scale: Scale,
) : PageDecoder<BufferedSource, Bitmap> {

    override suspend fun decode(
        encoded: BufferedSource,
        request: PageDecodeRequest,
    ): PageDecodeResult<Bitmap> {
        var decoder: ImageDecoder? = null
        return try {
            require(request.maxWidth > 0 && request.maxHeight > 0)
            decoder = ImageDecoder.newInstance(encoded.inputStream(), cropBorders, displayProfile)
            check(decoder != null && decoder.width > 0 && decoder.height > 0) { "Failed to initialize decoder" }

            val sourceWidth = decoder.width
            val sourceHeight = decoder.height
            val destinationWidth = request.maxWidth.coerceAtMost(sourceWidth)
            val destinationHeight = request.maxHeight.coerceAtMost(sourceHeight)
            val sampleSize = DecodeUtils.calculateInSampleSize(
                srcWidth = sourceWidth,
                srcHeight = sourceHeight,
                dstWidth = destinationWidth,
                dstHeight = destinationHeight,
                scale = scale,
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
