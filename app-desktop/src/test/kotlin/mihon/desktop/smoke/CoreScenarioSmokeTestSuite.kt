package mihon.desktop.smoke

import mihon.desktop.test.state.DownloadState
import mihon.desktop.test.state.HistoryState
import mihon.desktop.test.state.UpdatesState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Smoke tests for Core Test Infrastructure
 *
 * Tests the basic state management classes used by the test API.
 */
class CoreStateSmokeTestSuite {

    // ── Download State Tests ───────────────────────────────────────────────────

    @Test
    fun `download state creates with defaults`() {
        val state = DownloadState()

        assertEquals(0, state.queueSize)
        assertFalse(state.isPaused)
    }

    @Test
    fun `download state can be paused`() {
        val state = DownloadState()

        state.isPaused = true

        assertTrue(state.isPaused)
    }

    @Test
    fun `download state can set queue size`() {
        val state = DownloadState()

        state.queueSize = 5

        assertEquals(5, state.queueSize)
    }

    @Test
    fun `download state resets correctly`() {
        val state = DownloadState()
        state.queueSize = 10
        state.isPaused = true

        state.reset()

        assertEquals(0, state.queueSize)
        assertFalse(state.isPaused)
    }

    // ── Updates State Tests ───────────────────────────────────────────────────

    @Test
    fun `updates state creates with defaults`() {
        val state = UpdatesState()

        assertEquals(0, state.count)
        assertFalse(state.hasUnread)
    }

    @Test
    fun `updates state can set count`() {
        val state = UpdatesState()

        state.count = 15

        assertEquals(15, state.count)
    }

    @Test
    fun `updates state can indicate unread updates`() {
        val state = UpdatesState()

        state.hasUnread = true

        assertTrue(state.hasUnread)
    }

    @Test
    fun `updates state resets correctly`() {
        val state = UpdatesState()
        state.count = 20
        state.hasUnread = true

        state.reset()

        assertEquals(0, state.count)
        assertFalse(state.hasUnread)
    }

    // ── History State Tests ───────────────────────────────────────────────────

    @Test
    fun `history state creates with defaults`() {
        val state = HistoryState()

        assertEquals(0, state.count)
    }

    @Test
    fun `history state can set count`() {
        val state = HistoryState()

        state.count = 50

        assertEquals(50, state.count)
    }

    @Test
    fun `history state resets correctly`() {
        val state = HistoryState()
        state.count = 100

        state.reset()

        assertEquals(0, state.count)
    }
}

/**
 * Smoke tests for Library Scenario
 *
 * Tests library-related functionality.
 */
class LibraryScenarioSmokeTestSuite {

    @Test
    fun `library screen model filter state combinations work correctly`() {
        val model = mihon.desktop.ui.library.LibraryScreenModel()

        // Test all filter combinations
        model.setFilters(unread = true, started = false, completed = true, downloaded = false)
        var state = model.state.value
        assertTrue(state.filterUnread)
        assertFalse(state.filterStarted)
        assertTrue(state.filterCompleted)
        assertFalse(state.filterDownloaded)

        // Toggle filters
        model.setFilters(unread = false, started = true, completed = false, downloaded = true)
        state = model.state.value
        assertFalse(state.filterUnread)
        assertTrue(state.filterStarted)
        assertFalse(state.filterCompleted)
        assertTrue(state.filterDownloaded)
    }

    @Test
    fun `library sort modes work correctly`() {
        val model = mihon.desktop.ui.library.LibraryScreenModel()

        val sortModes = listOf(
            mihon.desktop.domain.SortMode.TITLE,
            mihon.desktop.domain.SortMode.LAST_READ,
            mihon.desktop.domain.SortMode.DATE_ADDED,
            mihon.desktop.domain.SortMode.UNREAD_COUNT,
        )

        sortModes.forEach { mode ->
            model.setSortMode(mode)
            assertEquals(mode, model.state.value.sortMode)
        }
    }

    @Test
    fun `library category selection works`() {
        val model = mihon.desktop.ui.library.LibraryScreenModel()

        // Select different categories
        model.setSelectedCategoryIndex(0)
        assertEquals(0, model.state.value.selectedCategoryIndex)

        model.setSelectedCategoryIndex(5)
        assertEquals(5, model.state.value.selectedCategoryIndex)
    }

    @Test
    fun `library display mode works`() {
        val model = mihon.desktop.ui.library.LibraryScreenModel()

        val displayModes = listOf(
            mihon.desktop.ui.library.LibraryDisplayMode.COMPACT_GRID,
            mihon.desktop.ui.library.LibraryDisplayMode.COMFORTABLE_GRID,
            mihon.desktop.ui.library.LibraryDisplayMode.LIST,
        )

        displayModes.forEach { mode ->
            model.setDisplayMode(mode)
            assertEquals(mode, model.state.value.displayMode)
        }
    }
}

/**
 * Smoke tests for Download Scenario
 *
 * Tests download management functionality.
 */
class DownloadScenarioSmokeTestSuite {

    @Test
    fun `download item creates correctly`() {
        val item = mihon.desktop.download.DownloadItem(
            sourceId = 1L,
            mangaTitle = "Test Manga",
            chapterName = "Chapter 1",
            chapterId = 100L,
            chapterUrl = "/chapter/1",
        )

        assertEquals(1L, item.sourceId)
        assertEquals("Test Manga", item.mangaTitle)
        assertEquals("Chapter 1", item.chapterName)
        assertEquals(100L, item.chapterId)
        assertEquals("/chapter/1", item.chapterUrl)
        assertEquals(0, item.progress)
        assertEquals(mihon.desktop.download.DownloadStatus.QUEUED, item.status)
    }

    @Test
    fun `download status values are correct`() {
        val statuses = listOf(
            mihon.desktop.download.DownloadStatus.QUEUED,
            mihon.desktop.download.DownloadStatus.DOWNLOADING,
            mihon.desktop.download.DownloadStatus.DONE,
            mihon.desktop.download.DownloadStatus.ERROR,
            mihon.desktop.download.DownloadStatus.CANCELLED,
        )

        statuses.forEach { status ->
            assertNotNull(status)
        }
    }
}

/**
 * Smoke tests for Updates Scenario
 *
 * Tests updates tab functionality.
 */
class UpdatesScenarioSmokeTestSuite {

    @Test
    fun `updates time format works correctly`() {
        val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM dd", java.util.Locale.ENGLISH)
        val date = java.time.LocalDate.of(2026, 5, 20)
        val formatted = date.format(formatter)

        assertEquals("May 20", formatted)
    }
}

/**
 * Smoke tests for Reader Scenario
 *
 * Tests reader navigation functionality.
 */
class ReaderCoreSmokeTestSuite {

    @Test
    fun `reader navigator handles chapter boundary conditions`() {
        // Note: chapters are sorted descending by sourceOrder (newest first)
        // So nextToRead (index - 1) goes to the newer chapter
        // And previousRead (index + 1) goes to the older chapter
        val chapters = listOf(
            mihon.desktop.reader.ReaderChapterRef(2L, "/ch/2", "Ch 2"), // index 0: newest
            mihon.desktop.reader.ReaderChapterRef(1L, "/ch/1", "Ch 1"), // index 1: oldest
        )
        val navigator = mihon.desktop.reader.ReaderNavigator(chapters, currentIndex = 0)

        // At first chapter (newest), no nextToRead (index - 1 = null)
        assertNull(navigator.nextToRead)
        // But there is a previousRead (older chapter)
        assertEquals("Ch 1", navigator.previousRead?.name)

        // At last chapter (oldest)
        val navigatorLast = mihon.desktop.reader.ReaderNavigator(chapters, currentIndex = 1)
        // No previousRead (index + 1 = out of bounds)
        assertNull(navigatorLast.previousRead)
        // But there is nextToRead (newer chapter)
        assertEquals("Ch 2", navigatorLast.nextToRead?.name)
    }

    @Test
    fun `reader reading modes have expected display names`() {
        assertEquals("Left to Right", mihon.desktop.reader.ReadingMode.LTR.displayName)
        assertEquals("Right to Left", mihon.desktop.reader.ReadingMode.RTL.displayName)
        assertEquals("Webtoon (Scroll)", mihon.desktop.reader.ReadingMode.WEBTOON.displayName)
    }

    @Test
    fun `reader viewer flags map to reading modes correctly`() {
        // Standard flag values
        assertEquals(mihon.desktop.reader.ReadingMode.LTR, mihon.desktop.reader.readingModeFromViewerFlags(1L))
        assertEquals(mihon.desktop.reader.ReadingMode.RTL, mihon.desktop.reader.readingModeFromViewerFlags(2L))
        assertEquals(mihon.desktop.reader.ReadingMode.WEBTOON, mihon.desktop.reader.readingModeFromViewerFlags(4L))
    }
}
