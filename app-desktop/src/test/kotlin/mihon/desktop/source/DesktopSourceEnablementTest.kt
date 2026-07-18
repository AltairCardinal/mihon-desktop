package mihon.desktop.source

import mihon.desktop.extension.DesktopExtensionLoader
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.LoadedExtension
import mihon.desktop.settings.DesktopAppPreferences
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.io.File
import java.util.prefs.Preferences

class DesktopSourceEnablementTest {
    private val root = Preferences.userRoot().node("/mihon/source-enablement/${System.nanoTime()}")

    @AfterEach
    fun tearDown() = root.removeNode()

    @Test
    fun `sources are enabled by default and disabled state persists`() {
        val preferences = DesktopAppPreferences(DesktopPreferenceStore(root.node("persisted")))
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

    @Test
    fun `disabled source remains resolvable and only explicit candidates exclude it`() {
        val preferences = DesktopAppPreferences(DesktopPreferenceStore(root.node("authority")))
        val loader = object : DesktopExtensionLoader(File("missing")) {
            override fun loadExtensions() = emptyList<LoadedExtension>()
        }
        val source = FakeHttpSource(42, "en", "Authority fixture")
        val manager = DesktopSourceManager(DesktopExtensionManager(loader), preferences, listOf(source))

        manager.setSourceEnabled(source.id, false)

        assertFalse(manager.isSourceEnabled(source.id))
        assertSame(source, manager.get(source.id))
        assertSame(source, manager.getOrStub(source.id))
        assertTrue(source in manager.getCatalogueSources())
        assertTrue(source in manager.getOnlineSources())
        assertFalse(source in manager.getEnabledCatalogueSources())
        assertFalse(source in manager.getEnabledOnlineSources())
        assertTrue(source.id.toString() in preferences.disabledSources.get())
    }
}
