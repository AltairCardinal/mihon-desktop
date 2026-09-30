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
import java.util.prefs.AbstractPreferences
import java.util.prefs.BackingStoreException
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
        assertEquals(LibraryPreferenceMigration.VERSION, store.getInt(LibraryPreferenceMigration.MARKER_KEY, 0).get())
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

    @Test
    fun `columns migration protects explicit shared zero and upgrades the previous marker`() {
        val store = storeOf("library_grid_columns" to "6")
        val preferences = LibraryPreferences(store)
        preferences.portraitColumns().set(0)
        store.getInt(LibraryPreferenceMigration.MARKER_KEY, 0).set(1)

        assertTrue(LibraryPreferenceMigration(store, preferences).migrate())
        assertEquals(0, preferences.portraitColumns().get())
        assertEquals(6, preferences.landscapeColumns().get())
        assertEquals(6, store.getInt("library_grid_columns", 3).get())
        assertFalse(LibraryPreferenceMigration(store, preferences).migrate())
    }

    @Test
    fun `lazy old app columns import into both shared orientations without deleting old value`() {
        val store = DesktopPreferenceStore(node)
        val oldAppNode = node.node("desktop/app")
        oldAppNode.putInt("library_grid_columns", 7)
        val app = DesktopAppPreferences(store, oldAppNode)
        val preferences = LibraryPreferences(store)
        assertFalse(store.getInt("library_grid_columns", 3).isSet())

        assertTrue(LibraryPreferenceMigration(store, preferences) { app.libraryGridColumns }.migrate())
        assertEquals(7, preferences.portraitColumns().get())
        assertEquals(7, preferences.landscapeColumns().get())
        assertEquals(7, oldAppNode.getInt("library_grid_columns", -1))
        assertEquals(7, app.libraryGridColumns.get())
    }

    @Test
    fun `each explicit shared zero wins and malformed legacy does not invent columns`() {
        for (orientation in listOf("portrait", "landscape")) {
            node.clear()
            val store = storeOf("library_grid_columns" to "9")
            val preferences = LibraryPreferences(store)
            val explicit = if (orientation == "portrait") preferences.portraitColumns() else preferences.landscapeColumns()
            val other = if (orientation == "portrait") preferences.landscapeColumns() else preferences.portraitColumns()
            explicit.set(0)
            assertTrue(LibraryPreferenceMigration(store, preferences).migrate())
            assertEquals(0, explicit.get())
            assertEquals(9, other.get())
        }
        for (raw in listOf(null, "BROKEN", "-1", "11")) {
            node.clear()
            val store = if (raw == null) DesktopPreferenceStore(node) else storeOf("library_grid_columns" to raw)
            val preferences = LibraryPreferences(store)
            assertTrue(LibraryPreferenceMigration(store, preferences).migrate())
            assertFalse(preferences.portraitColumns().isSet(), "legacy $raw must not fabricate portrait")
            assertFalse(preferences.landscapeColumns().isSet(), "legacy $raw must not fabricate landscape")
        }
    }

    @Test
    fun `column and marker failures before or after writes remain retryable with old keys retained`() {
        for (target in listOf("portrait", "landscape", "marker")) {
            for (afterWrite in listOf(false, true)) {
                val backend = FaultPreferences()
                val store = DesktopPreferenceStore(backend)
                val preferences = LibraryPreferences(store)
                store.getInt("library_grid_columns", 3).set(6)
                val marker = store.getInt(LibraryPreferenceMigration.MARKER_KEY, 0)
                marker.set(1)
                backend.failureKey = when (target) {
                    "portrait" -> preferences.portraitColumns().key()
                    "landscape" -> preferences.landscapeColumns().key()
                    else -> marker.key()
                }
                backend.afterWrite = afterWrite
                val migration = LibraryPreferenceMigration(store, preferences)
                assertFalse(migration.migrate(), "$target after=$afterWrite must report failure")
                assertEquals(1, marker.get(), "failed marker must not suppress retry")
                assertEquals(6, store.getInt("library_grid_columns", 3).get())
                assertTrue(migration.migrate(), "$target after=$afterWrite retry")
                assertEquals(6, preferences.portraitColumns().get())
                assertEquals(6, preferences.landscapeColumns().get())
                assertEquals(LibraryPreferenceMigration.VERSION, marker.get())
            }
        }
    }

    @Test
    fun `malformed shared columns import valid legacy while real explicit zero is protected`() {
        val store = storeOf("library_grid_columns" to "6")
        val preferences = LibraryPreferences(store)
        node.put(preferences.portraitColumns().key(), "BROKEN")
        preferences.landscapeColumns().set(0)
        assertTrue(LibraryPreferenceMigration(store, preferences).migrate())
        assertEquals(6, preferences.portraitColumns().get(), "malformed raw shared value cannot pretend to be explicit automatic zero")
        assertEquals(0, preferences.landscapeColumns().get())
    }

    private class FaultPreferences : AbstractPreferences(null, "") {
        private val values = linkedMapOf<String, String>()
        var failureKey: String? = null
        var afterWrite = false
        private var writtenKey: String? = null

        override fun putSpi(key: String, value: String) {
            if (key == failureKey && !afterWrite) {
                failureKey = null
                throw SecurityException("write blocked")
            }
            values[key] = value
            writtenKey = key
        }
        override fun getSpi(key: String): String? = values[key]
        override fun removeSpi(key: String) { values.remove(key) }
        override fun removeNodeSpi() { values.clear() }
        override fun keysSpi(): Array<String> = values.keys.toTypedArray()
        override fun childrenNamesSpi(): Array<String> = emptyArray()
        override fun childSpi(name: String): AbstractPreferences = error("no child requested")
        override fun syncSpi() = Unit
        override fun flushSpi() {
            if (writtenKey == failureKey && afterWrite) {
                failureKey = null
                throw BackingStoreException("flush blocked after write")
            }
        }
    }

    private fun storeOf(vararg entries: Pair<String, String>): DesktopPreferenceStore {
        entries.forEach { (key, value) -> node.put(key, value) }
        node.flush()
        return DesktopPreferenceStore(node)
    }
}
