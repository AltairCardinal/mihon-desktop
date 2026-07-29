package mihon.desktop.ui.settings

import mihon.desktop.settings.DesktopAppPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.util.Locale

class LibrarySettingsDisplayItemTest {

    private fun prefs() = DesktopAppPreferences(InMemoryPreferenceStore())

    @Test
    fun `missing chapter indicator row exposes label and unchecked default`() {
        val item = missingChapterIndicatorSettingsItem(prefs(), locale = Locale.forLanguageTag("zh-CN"))

        assertEquals("隐藏缺话提示", item.title)
        assertFalse(item.checked)
    }

    @Test
    fun `missing chapter indicator row click hides missing chapter rows`() {
        val prefs = prefs()
        val item = missingChapterIndicatorSettingsItem(prefs)

        item.onClick()

        assertTrue(prefs.hideMissingChapterIndicators.get())
    }

    @Test
    fun `missing chapter indicator checkbox writes requested value`() {
        val prefs = prefs()
        val item = missingChapterIndicatorSettingsItem(prefs)

        item.onCheckedChange(true)

        assertTrue(prefs.hideMissingChapterIndicators.get())
    }
}
