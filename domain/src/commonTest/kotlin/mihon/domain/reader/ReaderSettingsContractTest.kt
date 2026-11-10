package mihon.domain.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderSettingsContractTest {
    private val defaults = ReaderSettings()

    @Test
    fun `legacy desktop layout without a mode remains custom until explicitly following default`() {
        val old = (1L shl 32) or (1L shl 33) or 0x28L
        assertFalse(ReaderSettingsCodec.isFollowingDefault(old))
        assertEquals(ReaderSettingsLayout.DOUBLE, ReaderSettingsCodec.effective(old, defaults).layout)
        val followed = ReaderSettingsCodec.followDefault(old, defaults)
        assertTrue(ReaderSettingsCodec.isFollowingDefault(followed))
        assertEquals(ReaderSettingsLayout.DOUBLE, ReaderSettingsCodec.custom(followed, defaults).layout)
        val verticalDefault = defaults.copy(mode = ReaderSettingsMode.VERTICAL)
        val oldVertical = ReaderSettingsCodec.custom(old, verticalDefault)
        assertEquals(ReaderSettingsMode.VERTICAL, oldVertical.mode)
        assertEquals(ReaderSettingsLayout.DOUBLE, oldVertical.layout)
    }

    @Test
    fun `quick mode updates win over saved active fields and preserve hidden settings`() {
        val saved = ReaderSettingsCodec.setCustom(0L, defaults.copy(gap = true, match = false))
        val vertical = ReaderModeFlags.write(saved, 3L)
        val settings = ReaderSettingsCodec.custom(vertical, defaults)
        assertEquals(ReaderSettingsMode.VERTICAL, settings.mode)
        assertEquals(ReaderScrollMode.PAGED, settings.scroll)
        assertTrue(settings.gap)
        assertFalse(settings.match)
        val foreign = (1L shl 13) or (1L shl 28) or (1L shl 42)
        assertEquals(foreign, ReaderSettingsCodec.setCustom(foreign, defaults) and foreign)
    }

    @Test
    fun `following defaults retains every custom field through persistence and backup`() {
        val custom = ReaderSettings(
            mode = ReaderSettingsMode.VERTICAL,
            layout = ReaderSettingsLayout.DOUBLE,
            direction = ReaderDirection.LTR,
            scroll = ReaderScrollMode.PAGED,
            match = false,
            gap = true,
        )
        val orientation = 0x28L
        val saved = ReaderSettingsCodec.setCustom(orientation, custom)
        val inherited = ReaderSettingsCodec.followDefault(saved, defaults)
        assertTrue(ReaderSettingsCodec.isFollowingDefault(inherited))
        assertEquals(defaults, ReaderSettingsCodec.effective(inherited, defaults))
        val restored = ReaderSettingsCodec.restoreCustom(inherited.toInt().toLong(), defaults)
        assertFalse(ReaderSettingsCodec.isFollowingDefault(restored))
        assertEquals(custom, ReaderSettingsCodec.effective(restored, defaults))
        assertEquals(orientation, restored and 0x38L)
    }

    @Test
    fun `all visible settings map to existing viewer semantics without losing hidden choices`() {
        for (layout in ReaderSettingsLayout.entries) {
            for (direction in listOf(ReaderDirection.LTR, ReaderDirection.RTL)) {
                val setting = defaults.copy(layout = layout, direction = direction, gap = true, match = false)
                val flags = ReaderSettingsCodec.setCustom(0L, setting)
                assertEquals(setting, ReaderSettingsCodec.effective(flags, defaults))
                assertEquals(setting, ReaderSettingsCodec.fromMode(ReaderSettingsCodec.mode(setting), setting))
            }
        }
        for (gap in listOf(false, true)) {
            val continuous = defaults.copy(mode = ReaderSettingsMode.VERTICAL, gap = gap)
            assertEquals(if (gap) 5L else 4L, ReaderSettingsCodec.mode(continuous))
            val paged = continuous.copy(scroll = ReaderScrollMode.PAGED)
            assertEquals(3L, ReaderSettingsCodec.mode(paged))
            assertEquals(paged, ReaderSettingsCodec.custom(ReaderSettingsCodec.setCustom(0L, paged), defaults))
        }
    }

    @Test
    fun `legacy modes migrate lazily without mutating unrelated flags`() {
        for (mode in 1L..9L) {
            val legacy = ReaderModeFlags.write(0x18L or (1L shl 42), mode)
            val settings = ReaderSettingsCodec.custom(legacy, defaults)
            assertEquals(mode, ReaderSettingsCodec.mode(settings))
            val followed = ReaderSettingsCodec.followDefault(legacy, defaults)
            assertEquals(settings, ReaderSettingsCodec.custom(followed, defaults))
            assertEquals(0x18L or (1L shl 42), followed and (0x38L or (1L shl 42)))
        }
        val oldDesktopSingle = 2L or (1L shl 32)
        assertEquals(ReaderSettingsLayout.SINGLE, ReaderSettingsCodec.custom(oldDesktopSingle, defaults).layout)
        val oldDesktopDual = 1L or (1L shl 32) or (1L shl 33)
        assertEquals(ReaderSettingsLayout.DOUBLE, ReaderSettingsCodec.custom(oldDesktopDual, defaults).layout)
    }
}
