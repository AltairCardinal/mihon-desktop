package mihon.desktop.reader

import mihon.desktop.ui.reader.DesktopReaderScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Reader entry and mode smoke tests; dual pairing behavior lives with DualPagedPresentation. */
class PhaseEReaderTest {

    @Test
    fun `ReadingMode has LTR RTL WEBTOON values`() {
        assertEquals(3, ReadingMode.entries.size)
        assertNotNull(ReadingMode.LTR)
        assertNotNull(ReadingMode.RTL)
        assertNotNull(ReadingMode.WEBTOON)
    }

    @Test
    fun `ReadingMode display names are human-readable`() {
        assertTrue(ReadingMode.LTR.displayName.isNotBlank())
        assertTrue(ReadingMode.RTL.displayName.isNotBlank())
        assertTrue(ReadingMode.WEBTOON.displayName.isNotBlank())
    }

    @Test
    fun `DesktopReaderScreen with isDualPage true can be instantiated`() {
        val screen = DesktopReaderScreen(chapterTitle = "Chapter 1", isDualPage = true)

        assertNotNull(screen)
        assertEquals(true, screen.isDualPage)
    }

    @Test
    fun `DesktopReaderScreen isDualPage defaults to no explicit override`() {
        val screen = DesktopReaderScreen(chapterTitle = "Chapter 1")

        assertNull(screen.isDualPage)
    }

    @Test
    fun `initialReadingMode returns WEBTOON when isWebtoon is true`() {
        assertEquals(ReadingMode.WEBTOON, ReadingMode.from(isWebtoon = true, isRtl = false))
    }

    @Test
    fun `initialReadingMode returns RTL when isRtl true and not webtoon`() {
        assertEquals(ReadingMode.RTL, ReadingMode.from(isWebtoon = false, isRtl = true))
    }

    @Test
    fun `initialReadingMode returns LTR by default`() {
        assertEquals(ReadingMode.LTR, ReadingMode.from(isWebtoon = false, isRtl = false))
    }
}
