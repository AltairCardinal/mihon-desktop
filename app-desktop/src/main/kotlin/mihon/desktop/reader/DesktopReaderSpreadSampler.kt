package mihon.desktop.reader

import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PageRotation
import mihon.domain.reader.ReaderImageOrientation
import mihon.domain.reader.ReaderOriginalPageSamples
import mihon.domain.reader.ReaderOriginalPageSampling
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.ReaderSpreadEdgeSamples
import mihon.domain.reader.SharedSpreadMatching
import mihon.domain.reader.boundedReaderSamplingRegions

/** Resource deferral is not failed/corrupt evidence and may resume without a user retry. */
internal class DesktopReaderOptionalWorkPaused : CancellationException("Optional reader work is paused")

/** Executes the shared original-coordinate plan through existing bounded region leases. */
internal object DesktopReaderSpreadSampler {
    suspend fun sample(
        pipeline: DesktopReaderPageImagePipeline,
        session: DesktopReaderRegionContentSession,
        rawWidth: Int,
        rawHeight: Int,
        orientation: ReaderImageOrientation,
        canContinue: () -> Boolean = { true },
    ): ReaderSpreadEdgeSamples? {
        val plan = SharedSpreadMatching.plan(rawWidth, rawHeight, orientation) ?: return null
        val (left, right) = sampleRegions(session, plan.left, plan.right, rawWidth, rawHeight, canContinue)
            ?: return null
        return ReaderSpreadEdgeSamples(left, right)
    }

    suspend fun sampleOriginal(
        pipeline: DesktopReaderPageImagePipeline,
        session: DesktopReaderRegionContentSession,
        rawWidth: Int,
        rawHeight: Int,
        orientation: ReaderImageOrientation,
        rotation: PageRotation = PageRotation.NONE,
        canContinue: () -> Boolean = { true },
    ): ReaderOriginalPageSamples? {
        currentCoroutineContext().ensureActive()
        val plan = ReaderOriginalPageSampling.plan(rawWidth, rawHeight, orientation, rotation) ?: return null
        val (left, right) = sampleRegions(session, plan.left, plan.right, rawWidth, rawHeight, canContinue)
            ?: return null
        return plan.samples(left, right)
    }

    private suspend fun sampleRegions(
        session: DesktopReaderRegionContentSession,
        leftPoints: List<Pair<Int, Int>>,
        rightPoints: List<Pair<Int, Int>>,
        rawWidth: Int,
        rawHeight: Int,
        canContinue: () -> Boolean,
    ): Pair<List<Int>, List<Int>>? {
        currentCoroutineContext().ensureActive()
        if (!canContinue()) throw DesktopReaderOptionalWorkPaused()
        if (leftPoints.isEmpty() && rightPoints.isEmpty()) return emptyList<Int>() to emptyList()
        suspend fun edge(points: List<Pair<Int, Int>>): List<Int>? {
            currentCoroutineContext().ensureActive()
            if (!canContinue()) throw DesktopReaderOptionalWorkPaused()
            val values = MutableList(points.size) { 0 }
            for (region in boundedReaderSamplingRegions(points, rawWidth, rawHeight, MAX_REGION_BYTES / 4)) {
                currentCoroutineContext().ensureActive()
                if (!canContinue()) throw DesktopReaderOptionalWorkPaused()
                val bounds = region.bounds
                val key =
                    ReaderPageDecodeKey(
                        session.contentKey,
                        PageDecodePurpose.REGION_TILE,
                        bounds.width,
                        bounds.height,
                        region = bounds,
                    )
                val lease = session.acquireTile(key, optional = true) ?: return null
                lease.use {
                    val asset = it.asset
                    if (asset.sampled || asset.bitmap.width != bounds.width ||
                        asset.bitmap.height != bounds.height
                    ) {
                        return null
                    }
                    val pixels = asset.bitmap.asSkiaBitmap()
                    for (index in region.pointIndices) {
                        currentCoroutineContext().ensureActive()
                        if (!canContinue()) throw DesktopReaderOptionalWorkPaused()
                        val (x, y) = points[index]
                        values[index] = pixels.getColor(x - bounds.x, y - bounds.y)
                    }
                }
            }
            return values
        }
        val left = edge(leftPoints) ?: return null
        val right = edge(rightPoints) ?: return null
        currentCoroutineContext().ensureActive()
        if (!canContinue()) throw DesktopReaderOptionalWorkPaused()
        return left to right
    }

    private const val MAX_REGION_BYTES = 4L * 1024 * 1024
}
