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
private const val READING_MODE_MASK = 0xFFL
private const val DUAL_PAGE_SET_FLAG = 1L shl 32
private const val DUAL_PAGE_VALUE_FLAG = 1L shl 33

fun readingModeFromViewerFlags(flags: Long): ReadingMode? = when (flags and READING_MODE_MASK) {
    0L -> null
    1L -> ReadingMode.LTR
    2L -> ReadingMode.RTL
    3L -> ReadingMode.LTR
    4L -> ReadingMode.WEBTOON
    5L -> ReadingMode.WEBTOON
    else -> null
}

fun dualPageFromViewerFlags(flags: Long): Boolean? {
    if (flags and DUAL_PAGE_SET_FLAG == 0L) return null
    return flags and DUAL_PAGE_VALUE_FLAG != 0L
}

fun viewerFlagsWithDualPage(flags: Long, enabled: Boolean): Long {
    val cleared = flags and DUAL_PAGE_VALUE_FLAG.inv()
    val value = if (enabled) DUAL_PAGE_VALUE_FLAG else 0L
    return cleared or DUAL_PAGE_SET_FLAG or value
}

fun viewerFlagsWithReadingMode(flags: Long, mode: ReadingMode?): Long {
    val readingFlag = when (mode) {
        null -> 0L
        ReadingMode.LTR -> 1L
        ReadingMode.RTL -> 2L
        ReadingMode.WEBTOON -> 5L
    }
    return (flags and READING_MODE_MASK.inv()) or readingFlag
}
