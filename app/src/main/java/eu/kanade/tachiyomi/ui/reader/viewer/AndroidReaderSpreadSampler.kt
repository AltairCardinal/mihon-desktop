package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Rect
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import mihon.domain.reader.ReaderSpreadEdgeSamples
import mihon.domain.reader.ReaderSpreadSampleRequest
import mihon.domain.reader.SharedSpreadMatching
import tachiyomi.decoder.ImageDecoder
import java.io.InputStream

/** Narrow native region boundary; source ownership stays with the existing encoded page loader. */
internal object AndroidReaderSpreadSampler {
    val nativeDecoder: (InputStream) -> ReaderPageRegionDecoder = { encoded ->
        val original = checkNotNull(ImageDecoder.newInstance(encoded, false, null))
        object : ReaderPageRegionDecoder {
            override fun decode(region: Rect) = checkNotNull(original.decode(region = region, sampleSize = 1))
            override fun recycle() = original.recycle()
        }
    }

    suspend fun sample(
        page: ReaderPage,
        request: ReaderSpreadSampleRequest,
        rawWidth: Int,
        rawHeight: Int,
        decoderFactory: (InputStream) -> ReaderPageRegionDecoder = nativeDecoder,
    ): ReaderSpreadEdgeSamples? {
        val plan = SharedSpreadMatching.plan(rawWidth, rawHeight, request.content.sourceOrientation) ?: return null
        fun region(points: List<Pair<Int, Int>>): Rect {
            val left = points.minOf { it.first }
            val top = points.minOf { it.second }
            return Rect(left, top, points.maxOf { it.first } + 1, points.maxOf { it.second } + 1)
        }
        val leftRegion = region(plan.left)
        val rightRegion = region(plan.right)
        if (listOf(leftRegion, rightRegion).any {
                it.width().toLong() * it.height() > MAX_REGION_BYTES / 4
            }
        ) {
            return null
        }
        val stream = page.captureEncodedStream(request.content.contentVersion) ?: return null
        currentCoroutineContext().ensureActive()
        return stream().use { encoded ->
            val decoder = decoderFactory(encoded)
            try {
                suspend fun edge(points: List<Pair<Int, Int>>, bounds: Rect): List<Int>? {
                    currentCoroutineContext().ensureActive()
                    val bitmap = decoder.decode(bounds)
                    try {
                        if (bitmap.width != bounds.width() || bitmap.height != bounds.height() ||
                            bitmap.allocationByteCount.toLong() > MAX_REGION_BYTES
                        ) {
                            return null
                        }
                        // Native false/null decoding targets sRGB. getPixel exposes straight ARGB,
                        // not the premultiplied native storage; the common owner composites white.
                        return points.map { (x, y) ->
                            currentCoroutineContext().ensureActive()
                            bitmap.getPixel(x - bounds.left, y - bounds.top)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
                val left = edge(plan.left, leftRegion) ?: return@use null
                val right = edge(plan.right, rightRegion) ?: return@use null
                ReaderSpreadEdgeSamples(left, right)
            } finally {
                decoder.recycle()
            }
        }
    }

    private const val MAX_REGION_BYTES = 4L * 1024 * 1024
}
