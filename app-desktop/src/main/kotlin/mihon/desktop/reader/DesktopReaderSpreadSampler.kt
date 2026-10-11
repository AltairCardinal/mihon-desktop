package mihon.desktop.reader

import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.CancellationException
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderImageOrientation
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.ReaderSpreadEdgeSamples
import mihon.domain.reader.SharedSpreadMatching

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
        suspend fun edge(points: List<Pair<Int, Int>>): List<Int>? {
            if (!canContinue()) throw DesktopReaderOptionalWorkPaused()
            val left = points.minOf { it.first }
            val top = points.minOf { it.second }
            val bounds = PixelBounds(
                left,
                top,
                points.maxOf { it.first } - left + 1,
                points.maxOf { it.second } - top + 1,
            )
            // Reject before allocation; an unsupported huge strip never falls back to full decode.
            if (bounds.width.toLong() * bounds.height > MAX_REGION_BYTES / 4) return null
            val key = ReaderPageDecodeKey(
                session.contentKey,
                PageDecodePurpose.REGION_TILE,
                bounds.width,
                bounds.height,
                region = bounds,
            )
            val lease = session.acquireTile(key, optional = true) ?: return null
            return lease.use {
                val asset = it.asset
                if (asset.sampled || asset.bitmap.width != bounds.width || asset.bitmap.height != bounds.height) {
                    return@use null
                }
                // ImageIO's bounded source raster becomes sRGB PNG. getColor returns straight ARGB,
                // rather than exposing Skia's premultiplied storage to the shared white composition.
                val pixels = asset.bitmap.asSkiaBitmap()
                points.map { (x, y) -> pixels.getColor(x - bounds.x, y - bounds.y) }
            }
        }
        val left = edge(plan.left) ?: return null
        val right = edge(plan.right) ?: return null
        return ReaderSpreadEdgeSamples(left, right)
    }

    private const val MAX_REGION_BYTES = 4L * 1024 * 1024
}
