package mihon.domain.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderChapterBoundaryTest {
    @Test
    fun `only absent adjacent chapters produce terminal feedback`() {
        assertEquals(ReaderChapterBoundary.TERMINAL, readerChapterBoundary(false))
        assertEquals(ReaderChapterBoundary.NONE, readerChapterBoundary(true))
        assertEquals(ReaderChapterBoundary.LOADING, readerChapterBoundary(true, false))
        assertEquals(ReaderChapterBoundary.TERMINAL, readerChapterBoundary(false, false))
    }
}
