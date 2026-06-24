package mihon.desktop.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderProgressPageTest {

    @Test
    fun `single page progress uses current page`() {
        val state = ReaderState(
            currentPage = 3,
            resolvedUrls = List(10) { "page-$it" },
            dualPageMode = false,
        )

        assertEquals(3, readerProgressPageForTracking(state))
    }

    @Test
    fun `dual page progress uses last page in visible group`() {
        val state = ReaderState(
            currentPage = 9,
            resolvedUrls = List(11) { "page-$it" },
            dualPageMode = true,
        )

        assertEquals(10, readerProgressPageForTracking(state))
    }

    @Test
    fun `dual page progress keeps single last page unchanged`() {
        val state = ReaderState(
            currentPage = 10,
            resolvedUrls = List(11) { "page-$it" },
            dualPageMode = true,
        )

        assertEquals(10, readerProgressPageForTracking(state))
    }
}
