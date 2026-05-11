package mihon.desktop.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ViewerFlagsTest {

    @Test
    fun `flags 0 returns null (use default)`() {
        assertNull(readingModeFromViewerFlags(0L))
    }

    @Test
    fun `flags 1 returns LTR`() {
        assertEquals(ReadingMode.LTR, readingModeFromViewerFlags(1L))
    }

    @Test
    fun `flags 2 returns RTL`() {
        assertEquals(ReadingMode.RTL, readingModeFromViewerFlags(2L))
    }

    @Test
    fun `flags 4 returns WEBTOON`() {
        assertEquals(ReadingMode.WEBTOON, readingModeFromViewerFlags(4L))
    }

    @Test
    fun `flags 5 returns WEBTOON (continuous vertical maps to webtoon)`() {
        assertEquals(ReadingMode.WEBTOON, readingModeFromViewerFlags(5L))
    }

    @Test
    fun `flags 3 returns LTR (vertical pager maps to LTR for desktop)`() {
        // Desktop doesn't have a vertical pager, fallback to LTR
        assertEquals(ReadingMode.LTR, readingModeFromViewerFlags(3L))
    }

    @Test
    fun `unknown flags return null`() {
        assertNull(readingModeFromViewerFlags(99L))
    }
}
