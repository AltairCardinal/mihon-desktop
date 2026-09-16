package mihon.domain.reader

/** Shared wire values. Zero inherits globally; seven is an explicit automatic RTL layout. */
object ReaderModeFlags {
    const val DEFAULT = 0L
    const val AUTO = 7L
    const val MASK = 7L
    private const val LEGACY_AUTO = 1L shl 34

    fun read(flags: Long): Long = if (flags and LEGACY_AUTO != 0L && flags and MASK == 2L) {
        AUTO
    } else {
        flags and MASK
    }

    fun write(flags: Long, mode: Long): Long {
        // Inheritance must also remove the old Desktop-only explicit single/dual override.
        val layoutOverride = if (mode == DEFAULT) (1L shl 32) or (1L shl 33) else 0L
        return (flags and (MASK or LEGACY_AUTO or layoutOverride).inv()) or (mode and MASK)
    }
}
