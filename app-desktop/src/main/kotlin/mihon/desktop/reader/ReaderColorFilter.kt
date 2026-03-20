package mihon.desktop.reader

/**
 * Configuration for the reader colour filter overlay.
 *
 * Mirrors Android's colour filter settings from ColorFilterPage.kt:
 *  - [brightness]  : -0.75 (darkest) … 0 (neutral) … 1.0 (full bright overlay)
 *  - [r]/[g]/[b]   : colour channel values 0–255 for the tint overlay
 *  - [alpha]       : opacity of the tint overlay 0–255
 *  - [enabled]     : master switch; when false the filter has no visual effect
 */
data class ReaderColorFilter(
    val enabled: Boolean = false,
    val brightness: Float = 0f,
    val r: Int = 0,
    val g: Int = 0,
    val b: Int = 0,
    val alpha: Int = 128,
) {
    /**
     * True when the filter will actually change how pages look.
     * Disabled filters are never effective regardless of other values.
     */
    val isEffective: Boolean
        get() = enabled && (brightness != 0f || alpha > 0)

    companion object {
        const val BRIGHTNESS_MIN = -0.75f
        const val BRIGHTNESS_MAX = 1.0f
    }
}
