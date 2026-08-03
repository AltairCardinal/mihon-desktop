package mihon.desktop.ui.reader

import mihon.desktop.reader.desktopReaderSessionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderProgressPageTest {

    @Test
    fun `single page progress uses current page`() {
        val reader = desktopReaderSessionState(pageCount = 10)
        val state = ReaderState(
            context = reader.context,
            session = reader.snapshot,
            currentPage = 3,
            dualPageMode = false,
        )

        assertEquals(3, readerProgressPageForTracking(state))
    }

    @Test
    fun `dual page progress uses last page in visible group`() {
        val reader = desktopReaderSessionState(pageCount = 11)
        val state = ReaderState(
            context = reader.context,
            session = reader.snapshot,
            currentPage = 9,
            dualPageMode = true,
        )

        assertEquals(10, readerProgressPageForTracking(state))
    }

    @Test
    fun `dual page progress keeps single last page unchanged`() {
        val reader = desktopReaderSessionState(pageCount = 11)
        val state = ReaderState(
            context = reader.context,
            session = reader.snapshot,
            currentPage = 10,
            dualPageMode = true,
        )

        assertEquals(10, readerProgressPageForTracking(state))
    }
}
