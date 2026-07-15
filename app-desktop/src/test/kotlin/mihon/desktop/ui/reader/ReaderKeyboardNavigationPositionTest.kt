package mihon.desktop.ui.reader

import mihon.desktop.reader.ReaderKeyboardAction
import mihon.desktop.reader.ReaderPageAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderKeyboardNavigationPositionTest {

    @Test
    fun `mouse pager adapter keeps physical scrolling and shared RTL chapter direction`() {
        assertEquals(
            ReaderPageAction.GoToPage(1),
            ReaderKeyboardAction.forPagerRight(isRtl = true, currentPagerIndex = 0, totalPages = 5),
        )
        assertEquals(
            ReaderPageAction.NoNextPage,
            ReaderKeyboardAction.forPagerLeft(isRtl = true, currentPagerIndex = 0, totalPages = 5),
        )
        assertEquals(
            ReaderPageAction.NoPrevPage,
            ReaderKeyboardAction.forPagerRight(isRtl = true, currentPagerIndex = 4, totalPages = 5),
        )
    }

    @Test
    fun `dual page keyboard position uses display groups at last paired spread`() {
        val state = ReaderState(
            currentPage = 41,
            resolvedUrls = List(43) { "page-$it" },
            dualPageMode = true,
        )

        val position = readerKeyboardNavigationPosition(state)

        assertEquals(21, position.current)
        assertEquals(22, position.total)
        assertEquals(
            ReaderPageAction.NoNextPage,
            ReaderKeyboardAction.forRight(isRtl = false, currentPage = position.current, totalPages = position.total),
        )
    }
}
