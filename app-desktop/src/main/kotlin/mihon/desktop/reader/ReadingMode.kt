package mihon.desktop.reader

import mihon.domain.reader.ReaderModeFlags

/**
 * Reading direction / layout mode for the reader.
 * DEFAULT inherits global settings; AUTO uses the shared adaptive RTL layout.
 */
enum class ReadingMode(val displayName: String) {
    DEFAULT("Default"),
    AUTO("Auto"),
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
private const val DUAL_PAGE_SET_FLAG = 1L shl 32
private const val DUAL_PAGE_VALUE_FLAG = 1L shl 33

fun readingModeFromViewerFlags(flags: Long): ReadingMode? = when (ReaderModeFlags.read(flags)) {
    0L -> null
    1L, 3L -> ReadingMode.LTR
    2L, 6L -> ReadingMode.RTL
    4L, 5L -> ReadingMode.WEBTOON
    7L -> ReadingMode.AUTO
    else -> null
}

fun dualPageFromViewerFlags(flags: Long): Boolean? {
    if (flags and DUAL_PAGE_SET_FLAG == 0L) return if (ReaderModeFlags.read(flags) == 6L) true else null
    return flags and DUAL_PAGE_VALUE_FLAG != 0L
}

fun viewerFlagsWithDualPage(flags: Long, enabled: Boolean): Long {
    val cleared = flags and DUAL_PAGE_VALUE_FLAG.inv()
    val value = if (enabled) DUAL_PAGE_VALUE_FLAG else 0L
    return cleared or DUAL_PAGE_SET_FLAG or value
}

fun viewerFlagsFollowingGlobal(flags: Long): Long =
    viewerFlagsWithReadingMode(flags, null) and (DUAL_PAGE_SET_FLAG or DUAL_PAGE_VALUE_FLAG).inv()

fun viewerFlagsWithReadingMode(flags: Long, mode: ReadingMode?): Long {
    val readingFlag = when (mode) {
        ReadingMode.AUTO -> ReaderModeFlags.AUTO
        null, ReadingMode.DEFAULT -> ReaderModeFlags.DEFAULT
        ReadingMode.LTR -> 1L
        ReadingMode.RTL -> 2L
        ReadingMode.WEBTOON -> 5L
    }
    return ReaderModeFlags.write(flags, readingFlag)
}
