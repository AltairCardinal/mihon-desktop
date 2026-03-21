package mihon.desktop.settings

import mihon.desktop.domain.SortMode
import mihon.desktop.ui.library.LibraryDisplayMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class LibraryCategoryPrefsTest {

    private val store = InMemoryPreferenceStore()
    private val prefs = LibraryCategoryPrefs(store)

    @Test
    fun `defaults are applied for new category`() {
        assertEquals(LibraryDisplayMode.DEFAULT, prefs.getDisplayMode(null))
        assertEquals(SortMode.TITLE, prefs.getSortMode(null))
        assertEquals(true, prefs.getSortAscending(null))
    }

    @Test
    fun `display mode persists per category`() {
        prefs.setDisplayMode(1L, LibraryDisplayMode.LIST)
        prefs.setDisplayMode(2L, LibraryDisplayMode.COMFORTABLE_GRID)

        assertEquals(LibraryDisplayMode.LIST, prefs.getDisplayMode(1L))
        assertEquals(LibraryDisplayMode.COMFORTABLE_GRID, prefs.getDisplayMode(2L))
        // "All" tab (null) still has its default
        assertEquals(LibraryDisplayMode.DEFAULT, prefs.getDisplayMode(null))
    }

    @Test
    fun `sort mode and direction persist per category`() {
        prefs.setSortMode(1L, SortMode.LAST_READ)
        prefs.setSortAscending(1L, false)

        assertEquals(SortMode.LAST_READ, prefs.getSortMode(1L))
        assertEquals(false, prefs.getSortAscending(1L))
        // Category 2 still uses defaults
        assertEquals(SortMode.TITLE, prefs.getSortMode(2L))
        assertEquals(true, prefs.getSortAscending(2L))
    }

    @Test
    fun `null category (All tab) settings are independent from real categories`() {
        prefs.setDisplayMode(null, LibraryDisplayMode.LIST)
        prefs.setSortMode(null, SortMode.UNREAD_COUNT)
        prefs.setSortAscending(null, false)

        assertEquals(LibraryDisplayMode.LIST, prefs.getDisplayMode(null))
        assertEquals(SortMode.UNREAD_COUNT, prefs.getSortMode(null))
        assertEquals(false, prefs.getSortAscending(null))

        // Category 99 should still have defaults
        assertEquals(LibraryDisplayMode.DEFAULT, prefs.getDisplayMode(99L))
        assertEquals(SortMode.TITLE, prefs.getSortMode(99L))
        assertEquals(true, prefs.getSortAscending(99L))
    }
}
