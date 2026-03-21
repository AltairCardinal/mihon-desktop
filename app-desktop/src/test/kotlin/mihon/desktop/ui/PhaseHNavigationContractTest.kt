package mihon.desktop.ui

import cafe.adriel.voyager.core.screen.Screen
import mihon.desktop.ui.extension.ExtensionListScreen
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class PhaseHNavigationContractTest {

    @Test
    fun `ExtensionListScreen implements Screen`() {
        assertInstanceOf(Screen::class.java, ExtensionListScreen())
    }
}
