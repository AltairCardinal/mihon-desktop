package mihon.desktop.ui.library

import mihon.desktop.domain.SortMode
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibrarySortUiTest {
    @Test
    fun `changing sort field keeps current direction while clicking same field toggles it`() {
        assertFalse(nextSortAscending(SortMode.DATE_ADDED, SortMode.TITLE, ascending = false))
        assertTrue(nextSortAscending(SortMode.TITLE, SortMode.TITLE, ascending = false))
    }
}
