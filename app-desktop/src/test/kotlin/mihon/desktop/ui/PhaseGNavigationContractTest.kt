package mihon.desktop.ui

import cafe.adriel.voyager.core.screen.Screen
import mihon.desktop.ui.browse.GlobalSearchScreen
import mihon.desktop.ui.browse.SourceSearchResult
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/** Navigation and instantiation contracts for Phase 5/7/8 screens. */
class PhaseGNavigationContractTest {

    @Test
    fun `GlobalSearchScreen implements Screen`() {
        val screen = GlobalSearchScreen()
        assertInstanceOf(Screen::class.java, screen)
    }

    @Test
    fun `GlobalSearchScreen with initial query can be instantiated`() {
        val screen = GlobalSearchScreen(initialQuery = "one piece")
        assertNotNull(screen)
    }
}
