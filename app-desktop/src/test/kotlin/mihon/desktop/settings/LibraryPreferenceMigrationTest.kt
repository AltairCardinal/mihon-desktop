package mihon.desktop.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import java.util.UUID
import java.util.prefs.Preferences

class LibraryPreferenceMigrationTest {

    private lateinit var node: Preferences

    @BeforeEach
    fun setUp() {
        node = Preferences.userRoot().node("/mihon-test/library-migration-${UUID.randomUUID()}")
    }

    @AfterEach
    fun tearDown() {
        node.removeNode()
    }

    @Test
    fun `legacy all keys migrate to shared preferences`() {
        val store = storeOf(
            "lib_cat_-1_display" to "LIST",
            "lib_cat_-1_sort" to "UNREAD_COUNT",
        ).also { node.putBoolean("lib_cat_-1_sort_asc", false) }
        val preferences = LibraryPreferences(store)

        assertTrue(LibraryPreferenceMigration(store, preferences).migrate())
        assertEquals(LibraryDisplayMode.List, preferences.displayMode().get())
        assertEquals(
            LibrarySort(LibrarySort.Type.UnreadCount, LibrarySort.Direction.Descending),
            preferences.sortingMode().get(),
        )
        assertEquals(1, store.getInt(LibraryPreferenceMigration.MARKER_KEY, 0).get())
    }

    @Test
    fun `valid shared default wins over conflicting legacy value and migration is idempotent`() {
        val store = storeOf(
            "pref_display_mode_library" to "COMPACT_GRID",
            "lib_cat_-1_display" to "LIST",
        )
        val preferences = LibraryPreferences(store)
        val migration = LibraryPreferenceMigration(store, preferences)

        assertTrue(migration.migrate())
        assertEquals(LibraryDisplayMode.CompactGrid, preferences.displayMode().get())
        assertFalse(migration.migrate())
    }

    @Test
    fun `invalid legacy values do not fabricate a shared setting`() {
        val store = storeOf(
            "lib_cat_-1_display" to "BROKEN",
            "lib_cat_-1_sort" to "BROKEN",
        )
        val preferences = LibraryPreferences(store)

        LibraryPreferenceMigration(store, preferences).migrate()

        assertEquals(LibraryDisplayMode.default, preferences.displayMode().get())
        assertEquals(LibrarySort.default, preferences.sortingMode().get())
    }

    private fun storeOf(vararg entries: Pair<String, String>): DesktopPreferenceStore {
        entries.forEach { (key, value) -> node.put(key, value) }
        node.flush()
        return DesktopPreferenceStore(node)
    }
}
