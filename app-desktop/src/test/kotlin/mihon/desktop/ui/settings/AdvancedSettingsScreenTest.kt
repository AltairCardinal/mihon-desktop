package mihon.desktop.ui.settings

import cafe.adriel.voyager.core.screen.Screen
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class AdvancedSettingsScreenTest {

    @Test
    fun `AdvancedSettingsScreen implements Screen`() {
        assertInstanceOf(Screen::class.java, AdvancedSettingsScreen())
    }

    @Test
    fun `AdvancedSettingsScreen can be instantiated with no arguments`() {
        // Must not throw
        AdvancedSettingsScreen()
    }
}
