package tachiyomi.domain.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import java.util.UUID
import java.util.prefs.Preferences

class LibraryPreferencesTest {
    @Test
    fun `author display override follows shelf until explicit selection and ignores invalid values`() {
        val node = Preferences.userRoot().node("mihon/ax02/${UUID.randomUUID()}")
        try {
            val preferences = LibraryPreferences(DesktopPreferenceStore(node))

            assertFalse(preferences.creatorWorkDisplayModeOverride().isSet())
            assertNull(preferences.creatorWorkDisplayModeOverride().get())
            preferences.displayMode().set(LibraryDisplayMode.ComfortableGrid)
            assertEquals(LibraryDisplayMode.ComfortableGrid, preferences.displayMode().get())

            preferences.creatorWorkDisplayModeOverride().set(LibraryDisplayMode.List)
            assertTrue(preferences.creatorWorkDisplayModeOverride().isSet())
            assertEquals(LibraryDisplayMode.List, preferences.creatorWorkDisplayModeOverride().get())
            assertEquals(
                LibraryDisplayMode.ComfortableGrid,
                preferences.displayMode().get(),
                "The author override must not write back to the shelf mode",
            )

            node.put("pref_creator_work_display_mode", "not-a-display-mode")
            assertNull(preferences.creatorWorkDisplayModeOverride().get())
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun `creator presentation exclusions are app state and survive preference store recreation`() {
        val node = Preferences.userRoot().node("mihon/ax08/${UUID.randomUUID()}")
        try {
            val first = LibraryPreferences(DesktopPreferenceStore(node))
            assertFalse(first.creatorWorkPresentationExclusions().isSet())
            assertEquals(emptySet<String>(), first.creatorWorkPresentationExclusions().get())

            first.creatorWorkPresentationExclusions().set(setOf("7|42|2F776F726B"))

            val reopened = LibraryPreferences(DesktopPreferenceStore(node))
            assertEquals(setOf("7|42|2F776F726B"), reopened.creatorWorkPresentationExclusions().get())
            assertTrue(reopened.creatorWorkPresentationExclusions().key().startsWith("__APP_STATE_"))
        } finally {
            node.removeNode()
        }
    }
}
