package mihon.desktop.ui.library

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.interactor.LibraryFilter

class LibraryFilterUiTest {
    @Test
    fun `downloaded filter is locked by global downloaded only`() {
        val filter = LibraryFilter(globalDownloadedOnly = true)

        assertFalse(isFilterFieldEnabled(filter, LibraryFilterField.DOWNLOADED))
        assertTrue(isFilterFieldEnabled(filter, LibraryFilterField.UNREAD))
    }
}
