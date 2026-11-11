package mihon.domain.reader

enum class ReaderSettingsMode { HORIZONTAL, VERTICAL }

enum class ReaderSettingsLayout { AUTO, SINGLE, DOUBLE }

enum class ReaderScrollMode { CONTINUOUS, PAGED }

/** Retains hidden choices when changing mode, scroll method, or inheritance scope. */
data class ReaderSettings(
    val mode: ReaderSettingsMode = ReaderSettingsMode.HORIZONTAL,
    val layout: ReaderSettingsLayout = ReaderSettingsLayout.AUTO,
    val direction: ReaderDirection = ReaderDirection.RTL,
    val scroll: ReaderScrollMode = ReaderScrollMode.CONTINUOUS,
    val match: Boolean = true,
    val gap: Boolean = false,
) {
    val singlePageExtrasEnabled: Boolean
        get() = mode == ReaderSettingsMode.HORIZONTAL && layout == ReaderSettingsLayout.SINGLE
}

/**
 * Stores the complete custom choice in previously unused bits 6–12 and marker bit 18.
 * Active viewer modes remain in the existing wire format, including mode zero for inheritance.
 * All added bits survive the Android Int backup format; orientation and foreign flags are preserved.
 */
object ReaderSettingsCodec {
    private const val SNAPSHOT = 1L shl 18
    private const val VALUES = 0x7FL shl 6

    fun isFollowingDefault(flags: Long): Boolean =
        ReaderModeFlags.read(flags) == ReaderModeFlags.DEFAULT && flags and (1L shl 32) == 0L

    fun mode(settings: ReaderSettings): Long = when {
        settings.mode == ReaderSettingsMode.VERTICAL -> when {
            settings.scroll == ReaderScrollMode.PAGED -> 3L
            settings.gap -> 5L
            else -> 4L
        }
        settings.layout == ReaderSettingsLayout.AUTO ->
            if (settings.direction == ReaderDirection.LTR) ReaderModeFlags.AUTO_LTR else ReaderModeFlags.AUTO
        settings.layout == ReaderSettingsLayout.DOUBLE ->
            if (settings.direction == ReaderDirection.LTR) ReaderModeFlags.DUAL_PAGE_LTR else 6L
        settings.direction == ReaderDirection.LTR -> 1L
        else -> 2L
    }

    fun fromMode(mode: Long, fallback: ReaderSettings = ReaderSettings()): ReaderSettings = when (mode) {
        1L, 2L, 6L, 8L, 7L, 9L -> fallback.copy(
            mode = ReaderSettingsMode.HORIZONTAL,
            layout = when (mode) {
                7L, 9L -> ReaderSettingsLayout.AUTO
                6L, 8L -> ReaderSettingsLayout.DOUBLE
                else -> ReaderSettingsLayout.SINGLE
            },
            direction = if (mode in listOf(1L, 8L, 9L)) ReaderDirection.LTR else ReaderDirection.RTL,
        )
        3L -> fallback.copy(mode = ReaderSettingsMode.VERTICAL, scroll = ReaderScrollMode.PAGED)
        4L, 5L -> fallback.copy(
            mode = ReaderSettingsMode.VERTICAL,
            scroll = ReaderScrollMode.CONTINUOUS,
            gap =
            mode == 5L,
        )
        else -> fallback
    }

    fun custom(flags: Long, fallback: ReaderSettings = ReaderSettings()): ReaderSettings {
        if (flags and SNAPSHOT != 0L) {
            val bits = (flags shr 6).toInt()
            val saved = ReaderSettings(
                mode = if (bits and 1 == 0) ReaderSettingsMode.HORIZONTAL else ReaderSettingsMode.VERTICAL,
                layout = ReaderSettingsLayout.entries.getOrElse((bits shr 1) and 3) { fallback.layout },
                direction = if (bits and 8 == 0) ReaderDirection.RTL else ReaderDirection.LTR,
                scroll = if (bits and 16 == 0) ReaderScrollMode.CONTINUOUS else ReaderScrollMode.PAGED,
                match = bits and 32 != 0,
                gap = bits and 64 != 0,
            )
            // Quick mode selectors and older clients may update only the active wire mode.
            return fromMode(ReaderModeFlags.read(flags), saved)
        }
        val mode = ReaderModeFlags.read(flags)
        val migrated = fromMode(mode, fallback)
        // Preserve the old Desktop-only single/dual override for legacy horizontal pagers.
        return if (mode in listOf(0L, 1L, 2L) && flags and (1L shl 32) != 0L) {
            migrated.copy(
                layout = if (flags and (1L shl 33) !=
                    0L
                ) {
                    ReaderSettingsLayout.DOUBLE
                } else {
                    ReaderSettingsLayout.SINGLE
                },
            )
        } else {
            migrated
        }
    }

    fun effective(flags: Long, defaults: ReaderSettings): ReaderSettings =
        if (isFollowingDefault(flags)) defaults else custom(flags, defaults)

    fun setCustom(flags: Long, settings: ReaderSettings): Long =
        ReaderModeFlags.write(save(flags, settings), mode(settings))

    fun followDefault(flags: Long, fallback: ReaderSettings): Long =
        ReaderModeFlags.write(save(flags, custom(flags, fallback)), ReaderModeFlags.DEFAULT)

    fun restoreCustom(flags: Long, defaults: ReaderSettings): Long = setCustom(flags, custom(flags, defaults))

    private fun save(flags: Long, settings: ReaderSettings): Long {
        val bits = (if (settings.mode == ReaderSettingsMode.VERTICAL) 1L else 0L) or
            (settings.layout.ordinal.toLong() shl 1) or
            (if (settings.direction == ReaderDirection.LTR) 8L else 0L) or
            (if (settings.scroll == ReaderScrollMode.PAGED) 16L else 0L) or
            (if (settings.match) 32L else 0L) or
            (if (settings.gap) 64L else 0L)
        return (flags and VALUES.inv()) or SNAPSHOT or (bits shl 6)
    }
}
