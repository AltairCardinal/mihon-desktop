package mihon.desktop.reader

/**
 * Immutable zoom + pan state for the reader.
 * All mutations return a new instance; no side effects.
 */
data class ZoomState(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    companion object {
        const val MAX_SCALE = 5f
        private const val ZOOM_FACTOR = 1.25f
    }

    fun zoomIn(factor: Float = ZOOM_FACTOR): ZoomState =
        copy(scale = (scale * factor).coerceAtMost(MAX_SCALE))

    fun zoomOut(factor: Float = ZOOM_FACTOR): ZoomState {
        val newScale = (scale / factor).coerceAtLeast(1f)
        return if (newScale <= 1f) {
            ZoomState() // reset offsets when fully zoomed out
        } else {
            copy(scale = newScale)
        }
    }

    fun reset(): ZoomState = ZoomState()

    /** Pan is ignored when the image fits the screen (scale == 1f). */
    fun pan(dx: Float, dy: Float): ZoomState {
        if (scale <= 1f) return this
        return copy(offsetX = offsetX + dx, offsetY = offsetY + dy)
    }
}
