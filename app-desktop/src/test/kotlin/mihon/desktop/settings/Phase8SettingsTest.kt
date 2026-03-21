package mihon.desktop.settings

import mihon.desktop.download.DesktopDownloadPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class Phase8SettingsTest {

    private val store = InMemoryPreferenceStore()
    private val prefs = DesktopDownloadPreferences(store)

    @Test
    fun `downloadAsCbz defaults to false`() {
        assertFalse(prefs.downloadAsCbz.get())
    }

    @Test
    fun `autoDownloadNewChapters defaults to false`() {
        assertFalse(prefs.autoDownloadNewChapters.get())
    }

    @Test
    fun `deleteAfterRead defaults to false`() {
        assertFalse(prefs.deleteAfterRead.get())
    }

    @Test
    fun `downloadAsCbz can be toggled`() {
        prefs.downloadAsCbz.set(true)
        assertEquals(true, prefs.downloadAsCbz.get())
    }
}
