package mihon.desktop.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderChapterNavigationInitialPageTest {

    @Test
    fun `previous chapter navigation starts at last page`() {
        assertEquals(ReaderInitialPage.LAST, initialPageForChapterNavigation(ReaderChapterNavigationDirection.Previous))
    }

    @Test
    fun `next chapter navigation starts at first page`() {
        assertEquals(0, initialPageForChapterNavigation(ReaderChapterNavigationDirection.Next))
    }

    @Test
    fun `last page sentinel clamps to loaded page tail`() {
        val model = ReaderScreenModel()

        model.setLoadedPages(listOf("p1", "p2", "p3"), initialPage = ReaderInitialPage.LAST)

        assertEquals(2, model.state.value.currentPage)
    }
}
