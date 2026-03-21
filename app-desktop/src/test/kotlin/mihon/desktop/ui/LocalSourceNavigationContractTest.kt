package mihon.desktop.ui

import cafe.adriel.voyager.core.screen.Screen
import mihon.desktop.ui.browse.LocalChapterScreen
import mihon.desktop.ui.browse.LocalMangaBrowseScreen
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class LocalSourceNavigationContractTest {

    @Test
    fun `LocalMangaBrowseScreen implements Screen`() {
        assertInstanceOf(Screen::class.java, LocalMangaBrowseScreen())
    }

    @Test
    fun `LocalChapterScreen implements Screen with path parameter`() {
        val screen = LocalChapterScreen(
            mangaDirPath = "/home/user/manga/One Piece",
            mangaName = "One Piece",
        )
        assertInstanceOf(Screen::class.java, screen)
        assertNotNull(screen)
    }

    @Test
    fun `LocalMangaBrowseScreen can be instantiated with no parameters`() {
        val screen = LocalMangaBrowseScreen()
        assertNotNull(screen)
    }
}
