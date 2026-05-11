package mihon.desktop.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ParallelDownloadLimitTest {

    @Test
    fun `default parallel limit is 1`() {
        val prefs = DesktopDownloadPreferences(tachiyomi.core.common.preference.InMemoryPreferenceStore())
        assertEquals(1, prefs.parallelDownloadLimit.get())
    }

    @Test
    fun `parallel limit can be set to 3`() {
        val prefs = DesktopDownloadPreferences(tachiyomi.core.common.preference.InMemoryPreferenceStore())
        prefs.parallelDownloadLimit.set(3)
        assertEquals(3, prefs.parallelDownloadLimit.get())
    }

    @Test
    fun `parallel limit values are 1 to 5`() {
        // Valid range: 1..5
        val validRange = 1..5
        validRange.forEach { limit ->
            val prefs = DesktopDownloadPreferences(tachiyomi.core.common.preference.InMemoryPreferenceStore())
            prefs.parallelDownloadLimit.set(limit)
            assertEquals(limit, prefs.parallelDownloadLimit.get())
        }
    }
}
