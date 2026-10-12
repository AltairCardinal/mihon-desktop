package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Rect
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import mihon.domain.reader.PageRotation
import mihon.domain.reader.ReaderOriginalPageSamples
import mihon.domain.reader.ReaderOriginalPageSampling
import mihon.domain.reader.ReaderSpreadEdgeSamples
import mihon.domain.reader.ReaderSpreadSampleRequest
import mihon.domain.reader.SharedSpreadMatching
import mihon.domain.reader.boundedReaderSamplingRegions
import tachiyomi.decoder.ImageDecoder
import java.io.FilterInputStream
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
            override fun decode(region: Rect) = checkNotNull(original.decode(region = region, sampleSize = 1)).apply {
                // The dependency writes straight RGBA into a bitmap whose default flag is premultiplied.
                // This sampler only reads pixels; mark the storage correctly before getPixel unpremultiplies it.
                setPremultiplied(false)
            }
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
        val plan = SharedSpreadMatching.plan(rawWidth, rawHeight, request.content.sourceOrientation) ?: return null
        val (left, right) = sampleRegions(
            page,
            request,
            plan.left,
            plan.right,
            rawWidth,
            rawHeight,
            decoderFactory,
            canContinue,
        )
            ?: return null
        return ReaderSpreadEdgeSamples(left, right)
    }

    suspend fun sampleOriginal(
        page: ReaderPage,
        request: ReaderSpreadSampleRequest,
        rawWidth: Int,
        rawHeight: Int,
        decoderFactory: (InputStream) -> ReaderPageRegionDecoder = nativeDecoder,
        canContinue: () -> Boolean = { true },
        rotation: PageRotation = request.content.rotation,
    ): ReaderOriginalPageSamples? {
        currentCoroutineContext().ensureActive()
        val plan = ReaderOriginalPageSampling.plan(
            rawWidth,
            rawHeight,
            request.content.sourceOrientation,
            rotation,
        ) ?: return null
        val (left, right) = sampleRegions(
            page,
            request,
            plan.left,
            plan.right,
            rawWidth,
            rawHeight,
            decoderFactory,
            canContinue,
        )
            ?: return null
        return plan.samples(left, right)
    }

    private suspend fun sampleRegions(
        page: ReaderPage,
        request: ReaderSpreadSampleRequest,
        leftPoints: List<Pair<Int, Int>>,
        rightPoints: List<Pair<Int, Int>>,
        rawWidth: Int,
        rawHeight: Int,
        decoderFactory: (InputStream) -> ReaderPageRegionDecoder,
        canContinue: () -> Boolean,
    ): Pair<List<Int>, List<Int>>? {
        fun requireResources() {
            if (!canContinue()) throw AndroidReaderOptionalWorkPaused()
        }
        currentCoroutineContext().ensureActive()
        requireResources()
        if (page.captureEncodedStream(request.content.contentVersion) == null) return null
        if (leftPoints.isEmpty() && rightPoints.isEmpty()) return emptyList<Int>() to emptyList()
        val stream = page.captureEncodedStream(request.content.contentVersion) ?: return null
        currentCoroutineContext().ensureActive()
        requireResources()
        return stream().use { encoded ->
            requireResources()
            // ImageDecoder.newInstance closes its argument. The sampler owns the original stream;
            // a decoder factory only borrows it, including when construction throws or is cancelled.
            val decoder = decoderFactory(object : FilterInputStream(encoded) {
                override fun close() = Unit
            })
            try {
                suspend fun edge(points: List<Pair<Int, Int>>): List<Int>? {
                    val values = IntArray(points.size)
                    for (region in boundedReaderSamplingRegions(points, rawWidth, rawHeight, MAX_REGION_BYTES / 4, 2)) {
                        currentCoroutineContext().ensureActive()
                        requireResources()
                        val bounds = region.bounds.let { Rect(it.x, it.y, it.x + it.width, it.y + it.height) }
                        val bitmap = decoder.decode(bounds)
                        try {
                            if (bitmap.width != bounds.width() || bitmap.height != bounds.height() ||
                                bitmap.allocationByteCount.toLong() > MAX_REGION_BYTES
                            ) {
                                return null
                            }
                            for (index in region.pointIndices) {
                                currentCoroutineContext().ensureActive()
                                requireResources()
                                val (x, y) = points[index]
                                values[index] = bitmap.getPixel(x - bounds.left, y - bounds.top)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    return values.toList()
                }
                val left = edge(leftPoints) ?: return@use null
                val right = edge(rightPoints) ?: return@use null
                currentCoroutineContext().ensureActive()
                requireResources()
                if (page.captureEncodedStream(request.content.contentVersion) == null) return@use null
                left to right
            } finally {
                decoder.recycle()
            }
        }
    }

    private const val MAX_REGION_BYTES = 4L * 1024 * 1024
}
