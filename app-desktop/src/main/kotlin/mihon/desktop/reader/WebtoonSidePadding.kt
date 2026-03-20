package mihon.desktop.reader

/**
 * Side padding for webtoon mode — limits page width so pages don't stretch
 * edge-to-edge on wide monitors.
 *
 * Android reference: ReaderPreferences.webtoonSidePadding()
 *   0 = None, 10 = 10%, 15 = 15%, 20 = 20%, 25 = 25%
 *
 * @param ratio Fraction of screen width to leave as padding on each side.
 *              0f = full width, 0.10f = 10% each side → 80% content width.
 */
enum class WebtoonSidePadding(val ratio: Float, val displayName: String) {
    NONE(0f, "None"),
    SMALL(0.10f, "10%"),
    MEDIUM(0.15f, "15%"),
    LARGE(0.20f, "20%"),
    EXTRA_LARGE(0.25f, "25%"),
    ;

    companion object {
        val DEFAULT = NONE
    }
}
