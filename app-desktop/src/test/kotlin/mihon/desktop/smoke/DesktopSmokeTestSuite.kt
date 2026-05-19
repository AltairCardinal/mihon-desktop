package mihon.desktop.smoke

import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.ReaderNavigator
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.readingModeFromViewerFlags
import mihon.desktop.ui.library.LibraryScreenModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Desktop Smoke Test Suite
 *
 * Tests core desktop functionality without requiring full UI or DI initialization.
 * These tests can run in headless environments without X11/display.
 */
class DesktopSmokeTestSuite {

    // ── Reader Navigation Tests ───────────────────────────────────────────────

    @Test
    fun `reader chapter ref creates correctly`() {
        val ref = ReaderChapterRef(
            id = 1L,
            url = "/chapter/1",
            name = "Chapter 1",
        )

        assertEquals(1L, ref.id)
        assertEquals("/chapter/1", ref.url)
        assertEquals("Chapter 1", ref.name)
        assertFalse(ref.isRead)
    }

    @Test
    fun `reader chapter ref with read status`() {
        val ref = ReaderChapterRef(
            id = 2L,
            url = "/chapter/2",
            name = "Chapter 2",
            isRead = true,
        )

        assertTrue(ref.isRead)
    }

    @Test
    fun `reader navigator starts at correct chapter`() {
        val chapters = listOf(
            ReaderChapterRef(1L, "/ch/1", "Ch 1"),
            ReaderChapterRef(2L, "/ch/2", "Ch 2"),
            ReaderChapterRef(3L, "/ch/3", "Ch 3"),
        )
        val navigator = ReaderNavigator(chapters, currentIndex = 1)

        assertSame(chapters[1], navigator.current)
        assertEquals("Ch 2", navigator.current.name)
    }

    @Test
    fun `reader navigator next to read moves forward`() {
        // Note: chapters are sorted descending by sourceOrder (newest first)
        // So nextToRead (index - 1) goes to the newer chapter
        val chapters = listOf(
            ReaderChapterRef(3L, "/ch/3", "Ch 3"), // index 0: newest
            ReaderChapterRef(2L, "/ch/2", "Ch 2"), // index 1
            ReaderChapterRef(1L, "/ch/1", "Ch 1"), // index 2: oldest
        )
        val navigator = ReaderNavigator(chapters, currentIndex = 1)

        assertEquals("Ch 2", navigator.current.name)
        // nextToRead = index - 1 = newer chapter
        assertEquals("Ch 3", navigator.nextToRead?.name)
    }

    @Test
    fun `reader navigator previous read moves backward`() {
        // Note: chapters are sorted descending by sourceOrder (newest first)
        // So previousRead (index + 1) goes to the older chapter
        val chapters = listOf(
            ReaderChapterRef(3L, "/ch/3", "Ch 3"), // index 0: newest
            ReaderChapterRef(2L, "/ch/2", "Ch 2"), // index 1
            ReaderChapterRef(1L, "/ch/1", "Ch 1"), // index 2: oldest
        )
        val navigator = ReaderNavigator(chapters, currentIndex = 1)

        assertEquals("Ch 2", navigator.current.name)
        // previousRead = index + 1 = older chapter
        assertEquals("Ch 1", navigator.previousRead?.name)
    }

    @Test
    fun `reader navigator returns null at boundaries`() {
        val chapters = listOf(
            ReaderChapterRef(2L, "/ch/2", "Ch 2"), // newest at index 0
            ReaderChapterRef(1L, "/ch/1", "Ch 1"), // oldest at index 1
        )
        // At index 0 (newest), nextToRead tries index -1 = null
        val navigatorAtNewest = ReaderNavigator(chapters, currentIndex = 0)
        assertNull(navigatorAtNewest.nextToRead)

        // At index 1 (oldest), previousRead tries index + 1 = null
        val navigatorAtOldest = ReaderNavigator(chapters, currentIndex = 1)
        assertNull(navigatorAtOldest.previousRead)
    }

    @Test
    fun `reader navigator skips read chapters when enabled`() {
        // chapters sorted descending, newest first
        val chapters = listOf(
            ReaderChapterRef(4L, "/ch/4", "Ch 4"), // newest, index 0
            ReaderChapterRef(3L, "/ch/3", "Ch 3", isRead = true),
            ReaderChapterRef(2L, "/ch/2", "Ch 2", isRead = true),
            ReaderChapterRef(1L, "/ch/1", "Ch 1"), // oldest, index 3
        )
        // Start at index 1 (Ch 3, which is read)
        val navigator = ReaderNavigator(chapters, currentIndex = 1, skipReadChapters = true)

        // nextToRead searches index - 1, - 2, ... for unread
        // Skips Ch 3 (read), finds Ch 4 (unread)
        assertEquals("Ch 4", navigator.nextToRead?.name)
    }

    @Test
    fun `reader navigator index for id finds correct chapter`() {
        val chapters = listOf(
            ReaderChapterRef(10L, "/ch/10", "Ch 10"),
            ReaderChapterRef(20L, "/ch/20", "Ch 20"),
            ReaderChapterRef(30L, "/ch/30", "Ch 30"),
        )

        assertEquals(0, ReaderNavigator.indexForId(chapters, 10L))
        assertEquals(1, ReaderNavigator.indexForId(chapters, 20L))
        assertEquals(2, ReaderNavigator.indexForId(chapters, 30L))
    }

    @Test
    fun `reader navigator index for id defaults to zero for unknown id`() {
        val chapters = listOf(
            ReaderChapterRef(1L, "/ch/1", "Ch 1"),
            ReaderChapterRef(2L, "/ch/2", "Ch 2"),
        )

        assertEquals(0, ReaderNavigator.indexForId(chapters, 999L))
    }

    @Test
    fun `reading modes can be instantiated`() {
        assertNotNull(ReadingMode.LTR)
        assertNotNull(ReadingMode.RTL)
        assertNotNull(ReadingMode.WEBTOON)
    }

    @Test
    fun `reading modes have correct names`() {
        assertEquals("Left to Right", ReadingMode.LTR.displayName)
        assertEquals("Right to Left", ReadingMode.RTL.displayName)
        assertEquals("Webtoon (Scroll)", ReadingMode.WEBTOON.displayName)
    }

    @Test
    fun `readingModeFromViewerFlags maps correctly`() {
        assertEquals(ReadingMode.LTR, readingModeFromViewerFlags(1L))
        assertEquals(ReadingMode.RTL, readingModeFromViewerFlags(2L))
        assertEquals(ReadingMode.LTR, readingModeFromViewerFlags(3L))
        assertEquals(ReadingMode.WEBTOON, readingModeFromViewerFlags(4L))
        assertEquals(ReadingMode.WEBTOON, readingModeFromViewerFlags(5L))
        assertNull(readingModeFromViewerFlags(0L))
        assertNull(readingModeFromViewerFlags(99L))
    }

    @Test
    fun `empty chapter list throws on current access`() {
        val navigator = ReaderNavigator(emptyList(), currentIndex = 0)

        // Accessing current on empty list throws IndexOutOfBoundsException
        // This is expected behavior - the caller must ensure valid index
        var threw = false
        try {
            navigator.current
        } catch (e: IndexOutOfBoundsException) {
            threw = true
        }
        assertTrue(threw, "Should throw when accessing current on empty list")
    }

    @Test
    fun `single chapter has no navigation options`() {
        val chapters = listOf(
            ReaderChapterRef(1L, "/ch/1", "Ch 1"),
        )
        val navigator = ReaderNavigator(chapters, currentIndex = 0)

        assertNull(navigator.nextToRead)
        assertNull(navigator.previousRead)
    }

    // ── Library Screen Model Tests ───────────────────────────────────────────

    @Test
    fun `library screen model creates with default state`() {
        val model = LibraryScreenModel()

        assertNotNull(model.state.value)
        assertEquals(0, model.state.value.selectedCategoryIndex)
    }

    @Test
    fun `library screen model updates search query`() {
        val model = LibraryScreenModel()

        model.setSearchQuery("test manga")

        assertEquals("test manga", model.state.value.searchQuery)
    }

    @Test
    fun `library screen model updates sort mode`() {
        val model = LibraryScreenModel()
        val sortMode = mihon.desktop.domain.SortMode.TITLE

        model.setSortMode(sortMode)

        assertEquals(sortMode, model.state.value.sortMode)
    }

    @Test
    fun `library screen model updates category selection`() {
        val model = LibraryScreenModel()

        model.setSelectedCategoryIndex(2)

        assertEquals(2, model.state.value.selectedCategoryIndex)
    }

    @Test
    fun `library screen model updates display mode`() {
        val model = LibraryScreenModel()
        val displayMode = mihon.desktop.ui.library.LibraryDisplayMode.COMPACT_GRID

        model.setDisplayMode(displayMode)

        assertEquals(displayMode, model.state.value.displayMode)
    }

    @Test
    fun `library screen model toggles filters`() {
        val model = LibraryScreenModel()

        model.setFilters(
            unread = true,
            started = false,
            completed = true,
            downloaded = false,
        )

        val state = model.state.value
        assertTrue(state.filterUnread)
        assertFalse(state.filterStarted)
        assertTrue(state.filterCompleted)
        assertFalse(state.filterDownloaded)
    }

    @Test
    fun `library screen model shows category dialog`() {
        val model = LibraryScreenModel()
        assertFalse(model.state.value.showCategoryDialog)

        model.setShowCategoryDialog(true)
        assertTrue(model.state.value.showCategoryDialog)

        model.setShowCategoryDialog(false)
        assertFalse(model.state.value.showCategoryDialog)
    }
}
