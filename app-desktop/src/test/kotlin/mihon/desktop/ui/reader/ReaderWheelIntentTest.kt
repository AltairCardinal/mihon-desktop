package mihon.desktop.ui.reader

import mihon.desktop.reader.ReadingMode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderWheelIntentTest {

    @Test
    fun `plain wheel turns pager pages in logical reading order`() {
        assertEquals(
            ReaderWheelIntent.NEXT_PAGE,
            readerWheelIntent(rotation = 1.0, isControlDown = false, readingMode = ReadingMode.LTR),
        )
        assertEquals(
            ReaderWheelIntent.PREVIOUS_PAGE,
            readerWheelIntent(rotation = -1.0, isControlDown = false, readingMode = ReadingMode.RTL),
        )
    }

    @Test
    fun `control wheel keeps zoom behavior`() {
        assertEquals(
            ReaderWheelIntent.ZOOM_OUT,
            readerWheelIntent(rotation = 1.0, isControlDown = true, readingMode = ReadingMode.LTR),
        )
        assertEquals(
            ReaderWheelIntent.ZOOM_IN,
            readerWheelIntent(rotation = -1.0, isControlDown = true, readingMode = ReadingMode.RTL),
        )
    }

    @Test
    fun `webtoon and stationary wheel keep native scrolling behavior`() {
        assertEquals(
            ReaderWheelIntent.NONE,
            readerWheelIntent(rotation = 1.0, isControlDown = false, readingMode = ReadingMode.WEBTOON),
        )
        assertEquals(
            ReaderWheelIntent.NONE,
            readerWheelIntent(rotation = 0.0, isControlDown = false, readingMode = ReadingMode.LTR),
        )
    }
}
