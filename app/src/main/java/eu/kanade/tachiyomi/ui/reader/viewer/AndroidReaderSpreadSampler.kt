package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Rect
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import mihon.domain.reader.ReaderSpreadEdgeSamples
import mihon.domain.reader.ReaderSpreadSampleRequest
import mihon.domain.reader.SharedSpreadMatching
import tachiyomi.decoder.ImageDecoder
import java.io.InputStream

/** Optional resource pause retains unknown samples and never marks original content malformed. */
internal class AndroidReaderOptionalWorkPaused : kotlinx.coroutines.CancellationException()

/** Narrow native region boundary; source ownership stays with the existing encoded page loader. */
internal object AndroidReaderSpreadSampler {
    // Original jobs may outlive their viewer; cancellation never clears executing region work.
    private val optionalJobs = kotlinx.coroutines.flow.MutableStateFlow(0)
    val optionalWork: kotlinx.coroutines.flow.StateFlow<Int> = optionalJobs

    fun observe(job: kotlinx.coroutines.Job) {
        optionalJobs.update { it + 1 }
        job.invokeOnCompletion { optionalJobs.update { it - 1 } }
    }

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
        canContinue: () -> Boolean = { true },
    ): ReaderSpreadEdgeSamples? {
        fun requireResources() {
            if (!canContinue()) throw AndroidReaderOptionalWorkPaused()
        }
        requireResources()
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
        requireResources()
        return stream().use { encoded ->
            requireResources()
            val decoder = decoderFactory(encoded)
            try {
                suspend fun edge(points: List<Pair<Int, Int>>, bounds: Rect): List<Int>? {
                    currentCoroutineContext().ensureActive()
                    requireResources()
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
