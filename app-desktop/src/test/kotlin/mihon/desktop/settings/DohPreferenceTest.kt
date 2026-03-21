package mihon.desktop.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class DohPreferenceTest {

    private val store = InMemoryPreferenceStore()
    private val prefs = DesktopAppPreferences(store)

    @Test
    fun `dohProvider defaults to OFF`() {
        assertEquals(DohProvider.OFF, prefs.dohProvider.get())
    }

    @Test
    fun `dohProvider can be changed to CLOUDFLARE`() {
        prefs.dohProvider.set(DohProvider.CLOUDFLARE)
        assertEquals(DohProvider.CLOUDFLARE, prefs.dohProvider.get())
    }

    @Test
    fun `dohProvider can be changed to GOOGLE`() {
        prefs.dohProvider.set(DohProvider.GOOGLE)
        assertEquals(DohProvider.GOOGLE, prefs.dohProvider.get())
    }

    @Test
    fun `dohProvider can be changed to ADGUARD`() {
        prefs.dohProvider.set(DohProvider.ADGUARD)
        assertEquals(DohProvider.ADGUARD, prefs.dohProvider.get())
    }

    @Test
    fun `dohProvider can be reset to OFF`() {
        prefs.dohProvider.set(DohProvider.GOOGLE)
        prefs.dohProvider.set(DohProvider.OFF)
        assertEquals(DohProvider.OFF, prefs.dohProvider.get())
    }
}
