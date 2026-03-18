package mihon.desktop.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/** RED — DesktopAppPreferences and ThemeMode do not exist yet. */
class DesktopAppPreferencesTest {

    private fun prefs() = DesktopAppPreferences(InMemoryPreferenceStore())

    @Test
    fun `default theme is SYSTEM`() {
        assertEquals(ThemeMode.SYSTEM, prefs().themeMode.get())
    }

    @Test
    fun `default reader mode is PAGER`() {
        assertEquals(ReaderDefaultMode.PAGER, prefs().defaultReaderMode.get())
    }

    @Test
    fun `default grid columns is 3`() {
        assertEquals(3, prefs().libraryGridColumns.get())
    }

    @Test
    fun `default rtl is false`() {
        assertFalse(prefs().defaultRtl.get())
    }

    @Test
    fun `theme preference round-trips DARK`() {
        val p = prefs()
        p.themeMode.set(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, p.themeMode.get())
    }

    @Test
    fun `reader mode preference round-trips WEBTOON`() {
        val p = prefs()
        p.defaultReaderMode.set(ReaderDefaultMode.WEBTOON)
        assertEquals(ReaderDefaultMode.WEBTOON, p.defaultReaderMode.get())
    }

    @Test
    fun `grid columns preference round-trips 4`() {
        val p = prefs()
        p.libraryGridColumns.set(4)
        assertEquals(4, p.libraryGridColumns.get())
    }

    @Test
    fun `rtl preference round-trips true`() {
        val p = prefs()
        p.defaultRtl.set(true)
        assertEquals(true, p.defaultRtl.get())
    }
}
