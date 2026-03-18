package mihon.desktop.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** RED — ReaderKeyboardAction does not exist yet. */
class ReaderKeyboardActionTest {

    @Test
    fun `left arrow advances in RTL mode`() {
        // RTL: left = go to next page (higher page number)
        val action = ReaderKeyboardAction.forLeft(isRtl = true, currentPage = 2, totalPages = 5)
        assertEquals(ReaderPageAction.GoToPage(3), action)
    }

    @Test
    fun `left arrow retreats in LTR mode`() {
        val action = ReaderKeyboardAction.forLeft(isRtl = false, currentPage = 2, totalPages = 5)
        assertEquals(ReaderPageAction.GoToPage(1), action)
    }

    @Test
    fun `right arrow advances in LTR mode`() {
        val action = ReaderKeyboardAction.forRight(isRtl = false, currentPage = 2, totalPages = 5)
        assertEquals(ReaderPageAction.GoToPage(3), action)
    }

    @Test
    fun `right arrow retreats in RTL mode`() {
        val action = ReaderKeyboardAction.forRight(isRtl = true, currentPage = 2, totalPages = 5)
        assertEquals(ReaderPageAction.GoToPage(1), action)
    }

    @Test
    fun `left arrow at first page in LTR returns NoPrevPage`() {
        val action = ReaderKeyboardAction.forLeft(isRtl = false, currentPage = 0, totalPages = 5)
        assertEquals(ReaderPageAction.NoPrevPage, action)
    }

    @Test
    fun `right arrow at last page returns NoNextPage`() {
        val action = ReaderKeyboardAction.forRight(isRtl = false, currentPage = 4, totalPages = 5)
        assertEquals(ReaderPageAction.NoNextPage, action)
    }

    @Test
    fun `Home always goes to first page`() {
        val action = ReaderKeyboardAction.forHome()
        assertEquals(ReaderPageAction.GoToPage(0), action)
    }

    @Test
    fun `End goes to last page`() {
        val action = ReaderKeyboardAction.forEnd(totalPages = 7)
        assertEquals(ReaderPageAction.GoToPage(6), action)
    }

    @Test
    fun `PgUp retreats by 5 pages clamped to 0`() {
        val action = ReaderKeyboardAction.forPageUp(currentPage = 3, totalPages = 10)
        assertEquals(ReaderPageAction.GoToPage(0), action)
    }

    @Test
    fun `PgDn advances by 5 pages clamped to last`() {
        val action = ReaderKeyboardAction.forPageDown(currentPage = 7, totalPages = 10)
        assertEquals(ReaderPageAction.GoToPage(9), action)
    }

    @Test
    fun `digit 1 goes to page 0`() {
        val action = ReaderKeyboardAction.forDigit(digit = 1, totalPages = 10)
        assertEquals(ReaderPageAction.GoToPage(0), action)
    }

    @Test
    fun `digit 5 goes to page 4`() {
        val action = ReaderKeyboardAction.forDigit(digit = 5, totalPages = 10)
        assertEquals(ReaderPageAction.GoToPage(4), action)
    }

    @Test
    fun `digit 0 goes to last page`() {
        val action = ReaderKeyboardAction.forDigit(digit = 0, totalPages = 10)
        assertEquals(ReaderPageAction.GoToPage(9), action)
    }
}
