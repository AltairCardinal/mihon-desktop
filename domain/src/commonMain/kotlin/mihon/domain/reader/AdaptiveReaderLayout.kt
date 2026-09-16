package mihon.domain.reader

/** Shared policy; callers supply the usable content viewport, excluding fixed chrome. */
object AdaptiveReaderLayout {
    const val STABILITY_MILLIS = 150L

    /** null means the viewport is unavailable; preserve the current layout. */
    fun dualPage(width: Int, height: Int, currentDualPage: Boolean? = null): Boolean? {
        if (width <= 0 || height <= 0) return null
        val ratio = width.toDouble() / height
        return if (currentDualPage == true) ratio > 1.25 else ratio >= 1.35
    }
}
