package mihon.desktop.ui

import cafe.adriel.voyager.core.screen.Screen
import mihon.desktop.ui.extension.SourcePreferencesScreen
import mihon.desktop.ui.settings.ExtensionRepoScreen
import mihon.desktop.ui.updates.UpcomingScreen
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class PhaseINavigationContractTest {

    @Test
    fun `ExtensionRepoScreen implements Screen`() {
        assertInstanceOf(Screen::class.java, ExtensionRepoScreen())
    }

    @Test
    fun `SourcePreferencesScreen implements Screen`() {
        assertInstanceOf(Screen::class.java, SourcePreferencesScreen(sourceId = 1L, sourceName = "Test"))
    }

    @Test
    fun `UpcomingScreen implements Screen not Tab`() {
        assertInstanceOf(Screen::class.java, UpcomingScreen())
    }
}
