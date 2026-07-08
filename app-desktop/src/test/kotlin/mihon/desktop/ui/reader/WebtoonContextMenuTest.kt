package mihon.desktop.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebtoonContextMenuTest {

    @Test
    fun `webtoon pages expose the same image context menu actions as paged reader`() {
        assertEquals(
            pageContextMenuLabels(includeSetAsCover = false),
            webtoonPageContextMenuLabels(),
        )
    }

    @Test
    fun `webtoon context menu is enabled only when a scope and page url are available`() {
        assertTrue(
            shouldShowWebtoonPageContextMenu(
                hasContextMenuScope = true,
                pageUrl = "file:///tmp/page.webp",
            ),
        )
        assertFalse(
            shouldShowWebtoonPageContextMenu(
                hasContextMenuScope = false,
                pageUrl = "file:///tmp/page.webp",
            ),
        )
        assertFalse(
            shouldShowWebtoonPageContextMenu(
                hasContextMenuScope = true,
                pageUrl = "",
            ),
        )
    }
}
