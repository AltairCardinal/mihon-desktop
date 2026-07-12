package mihon.desktop.source

import mihon.desktop.extension.DesktopExtensionLoader
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.LoadedExtension
import mihon.desktop.settings.DesktopAppPreferences
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File

class DesktopSourceEnablementTest {
    @Test
    fun `sources are enabled by default and disabled state persists`() {
        val preferences = DesktopAppPreferences(InMemoryPreferenceStore())
        val loader = object : DesktopExtensionLoader(File("missing")) {
            override fun loadExtensions() = emptyList<LoadedExtension>()
        }
        val first = DesktopSourceManager(DesktopExtensionManager(loader), preferences, emptyList())

        assertTrue(first.isSourceEnabled(42))
        first.setSourceEnabled(42, false)
        assertFalse(DesktopSourceManager(DesktopExtensionManager(loader), preferences, emptyList()).isSourceEnabled(42))
        first.setSourceEnabled(42, true)
        assertTrue(first.isSourceEnabled(42))
    }
}
