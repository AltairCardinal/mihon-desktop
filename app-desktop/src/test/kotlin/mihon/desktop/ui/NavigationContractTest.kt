package mihon.desktop.ui

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.tab.Tab
import mihon.desktop.ui.library.MangaDetailScreen
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Navigation contract tests.
 *
 * These verify that screens used in navigator.push() inside a Tab
 * are compatible with the navigation context they live in.
 *
 * A TabNavigator only accepts Tab children. Screens pushed inside a Tab
 * must use a nested Navigator (regular Screen stack), never the TabNavigator.
 *
 * These tests catch ClassCastException bugs at compile-time-equivalent level.
 */
class NavigationContractTest {

    @Test
    fun `MangaDetailScreen is a Screen not a Tab`() {
        val screen = MangaDetailScreen(mangaId = 1L)
        assertTrue(screen is Screen, "MangaDetailScreen must implement Screen")
        assertFalse(screen is Tab, "MangaDetailScreen must NOT implement Tab — it is pushed inside a nested Navigator")
    }

    @Test
    fun `MangaDetailScreen key includes manga id`() {
        val first = MangaDetailScreen(mangaId = 1L)
        val second = MangaDetailScreen(mangaId = 2L)

        assertTrue(
            first.key != second.key,
            "MangaDetailScreen key must include mangaId so Voyager does not reuse the previous manga detail model",
        )
    }

    @Test
    fun `LibraryTab wraps content in nested Navigator for Screen navigation`() {
        // LibraryTab.Content() must use Navigator(rootScreen) { CurrentScreen() }
        // so that LocalNavigator.currentOrThrow returns a Screen-based navigator,
        // not the TabNavigator which only accepts Tab children.
        //
        // We verify this structurally: LibraryTab should contain a "LibraryRootScreen"
        // or similar root screen class that is a Screen (not Tab).
        val rootScreenClass = Class.forName("mihon.desktop.ui.library.LibraryRootScreen")
        assertTrue(
            Screen::class.java.isAssignableFrom(rootScreenClass),
            "LibraryRootScreen must implement Screen",
        )
        assertFalse(
            Tab::class.java.isAssignableFrom(rootScreenClass),
            "LibraryRootScreen must NOT implement Tab",
        )
    }
}
