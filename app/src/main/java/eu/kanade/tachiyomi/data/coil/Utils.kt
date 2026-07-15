package eu.kanade.tachiyomi.data.coil

import coil3.Extras
import coil3.getExtra
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Scale
import coil3.size.Size
import coil3.size.isOriginal
import coil3.size.pxOrElse
import java.util.concurrent.atomic.AtomicLong

internal inline fun Size.widthPx(scale: Scale, original: () -> Int): Int {
    return if (isOriginal) original() else width.toPx(scale)
}

internal inline fun Size.heightPx(scale: Scale, original: () -> Int): Int {
    return if (isOriginal) original() else height.toPx(scale)
}

internal fun Dimension.toPx(scale: Scale): Int = pxOrElse {
    when (scale) {
        Scale.FILL -> Int.MIN_VALUE
        Scale.FIT -> Int.MAX_VALUE
    }
}

fun ImageRequest.Builder.cropBorders(enable: Boolean) = apply {
    extras[cropBordersKey] = enable
}

val Options.cropBorders: Boolean
    get() = getExtra(cropBordersKey)

private val cropBordersKey = Extras.Key(default = false)

fun ImageRequest.Builder.customDecoder(enable: Boolean) = apply {
    extras[customDecoderKey] = enable
}

val Options.customDecoder: Boolean
    get() = getExtra(customDecoderKey)

private val customDecoderKey = Extras.Key(default = false)

internal class DecodeRequestIdentity(
    val pageIndex: Int,
    val generation: Long,
    private val current: () -> Boolean,
) {
    fun isCurrent(): Boolean = current()
}

internal class DecodeRequestIdentitySource {
    private var generation = 0L

    fun next(pageIndex: Int): DecodeRequestIdentity {
        val requestGeneration = ++generation
        return DecodeRequestIdentity(pageIndex, requestGeneration) { generation == requestGeneration }
    }

    fun invalidate() {
        generation++
    }
}

internal fun ImageRequest.Builder.readerDecodeIdentity(identity: DecodeRequestIdentity) = apply {
    extras[readerDecodeIdentityKey] = identity
}

internal val Options.readerDecodeIdentity: DecodeRequestIdentity?
    get() = getExtra(readerDecodeIdentityKey)

private val readerDecodeIdentityKey = Extras.Key<DecodeRequestIdentity?>(default = null)

internal object CoilDecodeRequestIdentitySource {
    private val generation = AtomicLong(0L)

    fun next(options: Options): DecodeRequestIdentity {
        val requestGeneration = generation.incrementAndGet()
        val requestPageIndex = options.diskCacheKey?.hashCode() ?: requestGeneration.toInt()
        return DecodeRequestIdentity(requestPageIndex, requestGeneration) { true }
    }
}
