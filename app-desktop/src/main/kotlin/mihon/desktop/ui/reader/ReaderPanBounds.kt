package mihon.desktop.ui.reader

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import mihon.desktop.reader.ZoomState

/** Desktop overscroll policy, in the same viewport coordinates used by the image transform. */
internal data class ReaderPanBounds(val viewport: IntSize, val content: Rect) {
    fun constrain(zoom: ZoomState): ZoomState {
        if (zoom.scale <= 1f) return zoom.reset()
        fun constrainAxis(offset: Float, start: Float, end: Float, extent: Int): Float {
            val center = extent / 2f
            val scaledStart = center + (start - center) * zoom.scale
            val scaledEnd = center + (end - center) * zoom.scale
            // Preserve unavoidable initial whitespace for a small or off-center image.
            val minimum = minOf(0f, extent * (2f / 3f) - scaledEnd)
            val maximum = maxOf(0f, extent / 3f - scaledStart)
            return offset.coerceIn(minimum, maximum)
        }
        return zoom.copy(
            offsetX = constrainAxis(zoom.offsetX, content.left, content.right, viewport.width),
            offsetY = constrainAxis(zoom.offsetY, content.top, content.bottom, viewport.height),
        )
    }
}

internal fun ReaderRegionDrawGeometry.panBounds(): ReaderPanBounds = ReaderPanBounds(
    viewportSize,
    Rect(
        drawOffset.x.toFloat(),
        drawOffset.y.toFloat(),
        (drawOffset.x + drawSize.width).toFloat(),
        (drawOffset.y + drawSize.height).toFloat(),
    ),
)

internal fun combineReaderPanBounds(
    geometries: Collection<ReaderRegionDrawGeometry>,
    pendingSlots: List<Rect> = emptyList(),
): ReaderPanBounds? {
    val bounds = geometries.map { it.panBounds() }
    val viewport = bounds.firstOrNull()?.viewport ?: return null
    if (bounds.any { it.viewport != viewport }) return null
    val rectangles = bounds.map { it.content } + pendingSlots
    return ReaderPanBounds(
        viewport,
        Rect(
            rectangles.minOf { it.left },
            rectangles.minOf { it.top },
            rectangles.maxOf { it.right },
            rectangles.maxOf { it.bottom },
        ),
    )
}
