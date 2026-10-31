package mihon.desktop.reader

import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
    // One pairing region sampling at a time, in every runtime: the regions of one
    // sample are decoded one after another, and another sampling must not interleave
    // with them.
    private val activeSampling = java.util.concurrent.atomic.AtomicReference<Any?>(null)

    suspend fun sample(
        pipeline: DesktopReaderPageImagePipeline,
        session: DesktopReaderRegionContentSession,
        rawWidth: Int,
        rawHeight: Int,
        orientation: ReaderImageOrientation,
        canContinue: () -> Boolean = { true },
        pairingDemand: DesktopReaderPairingDecodeDemand? = null,
    ): ReaderSpreadEdgeSamples? {
        val plan = SharedSpreadMatching.plan(rawWidth, rawHeight, orientation) ?: return null
        return withSamplingSlot(canContinue) {
            val (left, right) = sampleRegions(
                session,
                plan.left,
                plan.right,
                rawWidth,
                rawHeight,
                canContinue,
                pairingDemand,
            ) ?: return@withSamplingSlot null
            ReaderSpreadEdgeSamples(left, right)
        }
    }

    suspend fun sampleOriginal(
        pipeline: DesktopReaderPageImagePipeline,
        session: DesktopReaderRegionContentSession,
        rawWidth: Int,
        rawHeight: Int,
        orientation: ReaderImageOrientation,
        rotation: PageRotation = PageRotation.NONE,
        canContinue: () -> Boolean = { true },
        pairingDemand: DesktopReaderPairingDecodeDemand? = null,
    ): ReaderOriginalPageSamples? {
        currentCoroutineContext().ensureActive()
        val plan = ReaderOriginalPageSampling.plan(rawWidth, rawHeight, orientation, rotation) ?: return null
        return withSamplingSlot(canContinue) {
            val (left, right) = sampleRegions(
                session,
                plan.left,
                plan.right,
                rawWidth,
                rawHeight,
                canContinue,
                pairingDemand,
            ) ?: return@withSamplingSlot null
            plan.samples(left, right)
        }
    }

    /**
     * Holds the global pairing sampling slot for the whole operation.
     *
     * A sampling that cannot take the slot waits for it and is bounded by the caller's
     * own budget: when the budget is spent the operation is paused, not failed, so the
     * publisher can admit it again later.
     */
    private suspend fun <T> withSamplingSlot(canContinue: () -> Boolean, block: suspend () -> T): T {
        val token = Any()
        while (!activeSampling.compareAndSet(null, token)) {
            currentCoroutineContext().ensureActive()
            if (!canContinue()) throw DesktopReaderOptionalWorkPaused()
            delay(SAMPLING_SLOT_POLL_MILLIS)
        }
        return try {
            block()
        } finally {
            activeSampling.compareAndSet(token, null)
        }
    }

    private const val SAMPLING_SLOT_POLL_MILLIS = 16L

    private suspend fun sampleRegions(
        session: DesktopReaderRegionContentSession,
        leftPoints: List<Pair<Int, Int>>,
        rightPoints: List<Pair<Int, Int>>,
        rawWidth: Int,
        rawHeight: Int,
        canContinue: () -> Boolean,
        pairingDemand: DesktopReaderPairingDecodeDemand?,
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
                // Optionality follows the pairing demand: speculation stays pausable optional work, while a
                // real certification need must not be refused because some other optional region lease is
                // busy, which used to surface as a blocking RESOURCE on the target page.
                val optional = pairingDemand?.speculative ?: true
                val lease = session.acquireTile(key, optional = optional, pairingDemand = pairingDemand)
                    ?: return null
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
