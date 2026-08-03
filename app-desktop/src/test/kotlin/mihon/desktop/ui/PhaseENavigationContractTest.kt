package mihon.desktop.ui

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.tab.Tab
import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.ui.reader.DesktopReaderScreen
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Navigation contract tests for Phase E additions. */
class PhaseENavigationContractTest {

    @Test
    fun `DesktopReaderScreen is a Screen not a Tab`() {
        val screen = DesktopReaderScreen(
            chapterTitle = "Chapter 1",
            isWebtoon = false,
            sourceId = 1L,
            chapterUrl = "/chapter/1",
            chapterId = 1L,
            chapters = emptyList(),
            currentChapterIndex = 0,
            initialPage = 0,
            isRtl = false,
        )
        assertTrue(screen is Screen)
        assertFalse(screen is Tab)
    }

    @Test
    fun `DesktopReaderScreen can be instantiated with all Phase E params`() {
        val chapters = listOf(
            ReaderChapterRef(id = 2L, url = "/ch/2", name = "Chapter 2"),
            ReaderChapterRef(id = 1L, url = "/ch/1", name = "Chapter 1"),
        )
        assertDoesNotThrow {
            DesktopReaderScreen(
                chapterTitle = "Chapter 1",
                isWebtoon = false,
                sourceId = 42L,
                chapterUrl = "/chapter/1",
                chapterId = 1L,
                chapters = chapters,
                currentChapterIndex = 1,
                initialPage = 3,
                isRtl = true,
            )
        }
    }

    @Test
    fun `DesktopReaderScreen with default params (backward compat) still works`() {
        // All Phase E params default to safe values so existing callers don't break
        assertDoesNotThrow {
            DesktopReaderScreen(
                chapterTitle = "Chapter 1",
                isWebtoon = false,
            )
        }
    }
}
