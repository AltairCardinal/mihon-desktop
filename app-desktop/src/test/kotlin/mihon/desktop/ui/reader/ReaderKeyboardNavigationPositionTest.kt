package mihon.desktop.ui.reader

import mihon.desktop.reader.ReaderKeyboardAction
import mihon.desktop.reader.ReaderPageAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderKeyboardNavigationPositionTest {

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
