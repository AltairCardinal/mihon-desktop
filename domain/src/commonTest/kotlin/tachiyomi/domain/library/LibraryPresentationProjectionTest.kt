package tachiyomi.domain.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryPresentationProjectionTest {
    @Test
    fun `badge preferences gate independent projected fields`() {
        val projected = projectLibraryBadges({ 7 }, { 3 }, { true }, { "fr" }, true, false, false, true)
        assertEquals(7, projected.downloadCount)
        assertEquals(0, projected.unreadCount)
        assertFalse(projected.isLocal)
        assertEquals("fr", projected.sourceLanguage)
    }

    @Test
    fun `toolbar title and count follow category tab mode`() {
        assertEquals(
            LibraryToolbarProjection("Library", 8),
            projectLibraryToolbar("Library", "Default", "A", false, true, true, 2, 8),
        )
        assertEquals(
            LibraryToolbarProjection("A", 2),
            projectLibraryToolbar("Library", "Default", "A", false, false, true, 2, 8),
        )
        assertEquals(
            LibraryToolbarProjection("Library", null),
            projectLibraryToolbar("Library", "Default", null, false, true, true, 0, 8),
        )
        assertEquals(
            LibraryToolbarProjection("Default", 2),
            projectLibraryToolbar("Library", "Default", "Ignored", true, false, true, 2, 8),
        )
        assertEquals(
            LibraryToolbarProjection("A", null),
            projectLibraryToolbar("Library", "Default", "A", false, false, false, 2, 8),
        )
    }

    @Test
    fun `interval filter requires both non release build and restriction`() {
        assertFalse(showLibraryIntervalFilter(false, true))
        assertFalse(showLibraryIntervalFilter(true, false))
        assertTrue(showLibraryIntervalFilter(true, true))
    }
}
