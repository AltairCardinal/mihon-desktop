package mihon.domain.reader

/**
 * Shared mode values. Zero inherits globally; seven is an explicit automatic RTL layout.
 * Mode eight uses bit 30 plus legacy LTR (one), preserving orientation and Int backup flags.
 * Older clients that only read the low three bits fall back to single-page LTR.
 */
object ReaderModeFlags {
    const val DEFAULT = 0L
    const val AUTO = 7L
    const val MASK = 7L
    const val DUAL_PAGE_LTR = 8L
    private const val DUAL_PAGE_LTR_MARKER = 1L shl 30
    private const val LEGACY_AUTO = 1L shl 34

    fun read(flags: Long): Long = when {
        flags and DUAL_PAGE_LTR_MARKER != 0L && flags and MASK == 1L -> DUAL_PAGE_LTR
        flags and LEGACY_AUTO != 0L && flags and MASK == 2L -> AUTO
        else -> flags and MASK
    }

    fun write(flags: Long, mode: Long): Long {
        // Inheritance must also remove the old Desktop-only explicit single/dual override.
        val layoutOverride = if (mode == DEFAULT) (1L shl 32) or (1L shl 33) else 0L
        val encoded = if (mode == DUAL_PAGE_LTR) DUAL_PAGE_LTR_MARKER or 1L else mode and MASK
        return (flags and (MASK or LEGACY_AUTO or DUAL_PAGE_LTR_MARKER or layoutOverride).inv()) or encoded
    }
}
