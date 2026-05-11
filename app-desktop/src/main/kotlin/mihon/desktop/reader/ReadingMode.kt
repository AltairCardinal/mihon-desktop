package mihon.desktop.reader

/**
 * Reading direction / layout mode for the reader.
 * Mirrors Android's ReadingMode enum.
 */
enum class ReadingMode(val displayName: String) {
    LTR("Left to Right"),
    RTL("Right to Left"),
    WEBTOON("Webtoon (Scroll)");

    companion object {
        /** Derives the initial ReadingMode from legacy boolean flags. */
        fun from(isWebtoon: Boolean, isRtl: Boolean): ReadingMode = when {
            isWebtoon -> WEBTOON
            isRtl -> RTL
            else -> LTR
        }
    }
}

/**
 * Maps Android's Manga.viewerFlags to a desktop ReadingMode.
 *
 * Android values: 0=default, 1=LTR, 2=RTL, 3=vertical pager,
 * 4=webtoon, 5=continuous vertical. Desktop has no vertical pager,
 * so 3 maps to LTR; 5 maps to WEBTOON.
 *
 * Returns null for 0 (use global default) or unknown values.
 */
fun readingModeFromViewerFlags(flags: Long): ReadingMode? = when (flags) {
    0L -> null
    1L -> ReadingMode.LTR
    2L -> ReadingMode.RTL
    3L -> ReadingMode.LTR
    4L -> ReadingMode.WEBTOON
    5L -> ReadingMode.WEBTOON
    else -> null
}
