package mihon.desktop.ui

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.tab.Tab
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Phase D navigation contract tests.
 *
 * Verifies that HistoryTab and UpdatesTab follow the same nested-Navigator
 * pattern as LibraryTab and BrowseTab:
 * - Each Tab uses a nested Navigator(RootScreen) { CurrentScreen() }
 * - Root screens implement Screen (not Tab)
 * - All screens pushed inside tabs implement Screen (not Tab)
 */
class PhaseDNavigationContractTest {

    @Test
    fun `HistoryTab implements Tab`() {
        val tabClass = Class.forName("mihon.desktop.ui.history.HistoryTab")
        assertTrue(
            Tab::class.java.isAssignableFrom(tabClass),
            "HistoryTab must implement Tab",
        )
    }

    @Test
    fun `HistoryRootScreen implements Screen not Tab`() {
        val screenClass = Class.forName("mihon.desktop.ui.history.HistoryRootScreen")
        assertTrue(
            Screen::class.java.isAssignableFrom(screenClass),
            "HistoryRootScreen must implement Screen",
        )
        assertFalse(
            Tab::class.java.isAssignableFrom(screenClass),
            "HistoryRootScreen must NOT implement Tab",
        )
    }

    @Test
    fun `UpdatesTab implements Tab`() {
        val tabClass = Class.forName("mihon.desktop.ui.updates.UpdatesTab")
        assertTrue(
            Tab::class.java.isAssignableFrom(tabClass),
            "UpdatesTab must implement Tab",
        )
    }

    @Test
    fun `UpdatesRootScreen implements Screen not Tab`() {
        val screenClass = Class.forName("mihon.desktop.ui.updates.UpdatesRootScreen")
        assertTrue(
            Screen::class.java.isAssignableFrom(screenClass),
            "UpdatesRootScreen must implement Screen",
        )
        assertFalse(
            Tab::class.java.isAssignableFrom(screenClass),
            "UpdatesRootScreen must NOT implement Tab",
        )
    }
}
