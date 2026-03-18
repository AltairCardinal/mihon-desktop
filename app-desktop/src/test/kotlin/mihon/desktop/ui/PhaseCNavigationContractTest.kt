package mihon.desktop.ui

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.tab.Tab
import mihon.desktop.ui.download.DownloadQueueScreen
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** RED — DownloadQueueScreen does not exist yet. */
class PhaseCNavigationContractTest {

    @Test
    fun `DownloadQueueScreen is a Screen not a Tab`() {
        val screen = DownloadQueueScreen()
        assertTrue(screen is Screen)
        assertFalse(screen is Tab)
    }

    @Test
    fun `DownloadQueueScreen can be instantiated without arguments`() {
        assertDoesNotThrow { DownloadQueueScreen() }
    }
}
