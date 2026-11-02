package mihon.desktop.ui.reader

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import mihon.desktop.reader.ZoomState

/** Desktop overscroll policy, in the same viewport coordinates used by the image transform. */
internal data class ReaderPanBounds(
    val viewport: IntSize,
    val content: Rect,
    private val preservedFocus: ZoomState? = null,
) {
    fun preservingFocus(focus: ZoomState?): ReaderPanBounds = copy(preservedFocus = focus)

    fun constrain(zoom: ZoomState): ZoomState {
        if (zoom.scale <= 1f) return zoom.reset()
        val focus = preservedFocus?.takeIf { it.scale == zoom.scale }
        fun constrainAxis(offset: Float, start: Float, end: Float, extent: Int, preservedOffset: Float?): Float {
            val center = extent / 2f
            val scaledStart = center + (start - center) * zoom.scale
            val scaledEnd = center + (end - center) * zoom.scale
            // Preserve unavoidable initial whitespace for a small or off-center image.
            val minimum = minOf(0f, extent * (2f / 3f) - scaledEnd)
            val maximum = maxOf(0f, extent / 3f - scaledStart)
            return offset.coerceIn(
                minOf(minimum, preservedOffset ?: minimum),
                maxOf(maximum, preservedOffset ?: maximum),
            )
        }
        return zoom.copy(
            offsetX = constrainAxis(zoom.offsetX, content.left, content.right, viewport.width, focus?.offsetX),
            offsetY = constrainAxis(zoom.offsetY, content.top, content.bottom, viewport.height, focus?.offsetY),
        )
    }
}

/** Keeps a frame's accepted camera stable while background geometry becomes more precise. */
internal class ReaderPanBoundsState {
    private var previousBounds: ReaderPanBounds? = null
    private var previousDisplay: ZoomState? = null
    private var preservedFocus: ZoomState? = null

    fun update(bounds: ReaderPanBounds, zoom: ZoomState): ReaderPanBounds {
        val previous = previousBounds
        val displayed = previousDisplay
        val sameViewport = previous?.viewport == bounds.viewport
        if (!sameViewport || displayed?.scale != zoom.scale) preservedFocus = null
        if (sameViewport && previous?.content != bounds.content && displayed != null &&
            displayed.scale == zoom.scale
        ) {
            preservedFocus = displayed.takeIf { bounds.constrain(it) != it }
        }
        val limited = bounds.preservingFocus(preservedFocus).constrain(zoom)
        // Further outward movement is blocked at the accepted focus. Returning toward the new
        // real boundary shrinks this allowance immediately, without a hidden drag displacement.
        preservedFocus = limited.takeIf { preservedFocus != null && bounds.constrain(it) != it }
        previousBounds = bounds
        previousDisplay = limited
        return bounds.preservingFocus(preservedFocus)
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
