package mihon.desktop.ui

import mihon.desktop.ui.reader.DesktopReaderScreen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

/**
 * RED — DesktopReaderScreen does not have a mangaTitle parameter yet.
 * Without it the local-file lookup uses a broken heuristic.
 */
class ReaderScreenMangaTitleTest {

    @Test
    fun `DesktopReaderScreen can be instantiated with mangaTitle`() {
        assertDoesNotThrow {
            DesktopReaderScreen(
                chapterTitle = "Chapter 1",
                mangaTitle = "Chainsaw Man",
                sourceId = 1L,
                chapterUrl = "/chapter/1",
                chapterId = 1L,
            )
        }
    }

    @Test
    fun `mangaTitle defaults to empty string for backward compat`() {
        val screen = DesktopReaderScreen(
            chapterTitle = "Chapter 1",
            sourceId = 1L,
            chapterUrl = "/chapter/1",
            chapterId = 1L,
        )
        assertEquals("", screen.mangaTitle)
    }

    @Test
    fun `mangaTitle is preserved when set`() {
        val screen = DesktopReaderScreen(
            chapterTitle = "Ch. 1",
            mangaTitle = "My Hero Academia",
            sourceId = 42L,
            chapterUrl = "/ch/1",
            chapterId = 100L,
        )
        assertEquals("My Hero Academia", screen.mangaTitle)
    }
}
