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
