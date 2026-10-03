package mihon.desktop.settings

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import java.util.UUID
import java.util.prefs.AbstractPreferences
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences

class LibraryPreferenceMigrationTest {
    @Test
    fun `v3 reads the actual lazy old app scope and interval bridge while preserving shared explicit values`() {
        val store = DesktopPreferenceStore(node)
        val oldApp = node.node("desktop/app")
        oldApp.put("update_category_includes", "2,3")
        oldApp.put("update_category_excludes", "3")
        oldApp.put("library_update_interval", "EVERY_6H")
        oldApp.flush()
        val app = DesktopAppPreferences(store, oldApp)
        val preferences = LibraryPreferences(store)
        preferences.updateCategoriesExclude().set(emptySet())
        assertFalse(store.getString("update_category_includes", "").isSet())
        val migration = LibraryPreferenceMigration(
            store,
            preferences,
            legacyUpdatePreferences = {
                Triple(app.updateCategoryIncludes, app.updateCategoryExcludes, app.libraryUpdateInterval)
            },
            validCategoryIds = { setOf(0, 2, 3) },
        )
        assertTrue(migration.migrate())
        assertEquals(setOf("2", "3"), preferences.updateCategories().get())
        assertEquals(emptySet<String>(), preferences.updateCategoriesExclude().get())
        assertEquals(6, preferences.autoUpdateInterval().get())
        assertEquals("2,3", oldApp.get("update_category_includes", ""))
        assertEquals("EVERY_6H", oldApp.get("library_update_interval", ""))
    }

    @Test
    fun `a journal reaching only RAM must be acknowledged before migration restoration or any new writes`() {
        val actual = storeOf("update_category_includes" to "2", "library_update_interval" to "EVERY_6H")
        val original = actual.getAll()
        var refuse = true
        val guarded = object : tachiyomi.core.common.preference.PreferenceStore by actual {
            override fun getString(
                key: String,
                defaultValue: String,
            ): tachiyomi.core.common.preference.Preference<String> {
                val preference = actual.getString(key, defaultValue)
                return object : tachiyomi.core.common.preference.Preference<String> by preference {
                    override fun set(value: String) {
                        preference.set(value)
                        if (key == LibraryPreferenceMigration.RECOVERY_KEY &&
                            refuse
                        ) {
                            throw BackingStoreException("Journal flush rejected after put")
                        }
                    }
                }
            }
        }
        val preferences = LibraryPreferences(guarded)
        repeat(2) {
            assertFalse(LibraryPreferenceMigration(guarded, preferences).migrate())
            assertEquals(original, actual.getAll().filterKeys { it != LibraryPreferenceMigration.RECOVERY_KEY })
            assertTrue(actual.getString(LibraryPreferenceMigration.RECOVERY_KEY, "").get().isNotEmpty())
            assertFalse(preferences.updateCategories().isSet())
        }
        refuse = false
        assertTrue(LibraryPreferenceMigration(guarded, preferences).migrate())
        assertEquals(setOf("2"), preferences.updateCategories().get())
        assertEquals(6, preferences.autoUpdateInterval().get())
    }

    @Test
    fun `concurrent migration cannot restore the journal of a still running first migration`() {
        val actual = storeOf(
            "update_category_includes" to "2",
            "update_category_excludes" to "3",
            "library_update_interval" to "EVERY_6H",
        )
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val once = java.util.concurrent.atomic.AtomicBoolean(true)
        val store = object : tachiyomi.core.common.preference.PreferenceStore by actual {
            override fun getStringSet(
                key: String,
                defaultValue: Set<String>,
            ): tachiyomi.core.common.preference.Preference<Set<String>> {
                val preference = actual.getStringSet(key, defaultValue)
                return object : tachiyomi.core.common.preference.Preference<Set<String>> by preference {
                    override fun set(value: Set<String>) {
                        preference.set(value)
                        if (key == "library_update_categories" && once.getAndSet(false)) {
                            started.countDown()
                            check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                        }
                    }
                }
            }
        }
        val migration = LibraryPreferenceMigration(store, LibraryPreferences(store))
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Boolean> { migration.migrate() }
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val second = executor.submit<Boolean> { migration.migrate() }
            assertThrows(java.util.concurrent.TimeoutException::class.java) {
                second.get(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            release.countDown()
            assertTrue(first.get(3, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(second.get(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(migration.isComplete())
            assertEquals(setOf("2"), LibraryPreferences(store).updateCategories().get())
            assertEquals(setOf("3"), LibraryPreferences(store).updateCategoriesExclude().get())
        } finally {
            release.countDown()
            executor.shutdown()
            if (!executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) executor.shutdownNow()
        }
    }

    @Test
    fun `v3 marker failures restore full original values and retain all legacy inputs`() {
        for (afterWrite in listOf(false, true)) {
            val backend = FaultPreferences()
            val store = DesktopPreferenceStore(backend)
            store.getString("update_category_includes", "").set("2")
            store.getString("update_category_excludes", "").set("3")
            store.getString("library_update_interval", "").set("EVERY_6H")
            store.getInt(LibraryPreferenceMigration.MARKER_KEY, 0).set(2)
            val before = store.getAll()
            backend.failureKey = LibraryPreferenceMigration.MARKER_KEY
            backend.afterWrite = afterWrite
            assertFalse(LibraryPreferenceMigration(store, LibraryPreferences(store)).migrate())
            assertEquals(before, store.getAll(), "No new explicit key may survive a rejected marker")
            assertTrue(LibraryPreferenceMigration(store, LibraryPreferences(store)).migrate())
            assertEquals(6, LibraryPreferences(store).autoUpdateInterval().get())
        }
    }

    @Test
    fun `unknown legacy interval is explicitly disabled and remains visible for correction`() {
        val store = storeOf("library_update_interval" to "BROKEN")
        val preferences = LibraryPreferences(store)
        assertTrue(LibraryPreferenceMigration(store, preferences).migrate())
        assertTrue(preferences.autoUpdateInterval().isSet(), "Unknown interval must have an explicit safe value")
        assertEquals(0, preferences.autoUpdateInterval().get())
        assertTrue(
            store.getBoolean(
                tachiyomi.core.common.preference.Preference.appStateKey("library_update_interval_migration_invalid"),
                false,
            ).get(),
        )
    }

    @Test
    fun `v3 imports complete legacy scope and six hour interval without imposing old unused restrictions`() {
        val store = storeOf(
            "update_category_includes" to "2, 3",
            "update_category_excludes" to "3",
            "library_update_interval" to "EVERY_6H",
        )
        val preferences = LibraryPreferences(store)
        store.getInt(LibraryPreferenceMigration.MARKER_KEY, 0).set(2)
        assertTrue(LibraryPreferenceMigration(store, preferences).migrate())
        assertEquals(setOf("2", "3"), preferences.updateCategories().get())
        assertEquals(setOf("3"), preferences.updateCategoriesExclude().get())
        assertEquals(6, preferences.autoUpdateInterval().get())
        assertEquals(emptySet<String>(), preferences.autoUpdateDeviceRestrictions().get())
        assertEquals(emptySet<String>(), preferences.autoUpdateMangaRestrictions().get())
        assertFalse(preferences.autoUpdateMetadata().get())
        assertEquals("2, 3", store.getString("update_category_includes", "").get())
    }

    @Test
    fun `v3 preserves explicit shared empty scope and extended interval over conflicting legacy`() {
        val store = storeOf(
            "update_category_includes" to "9",
            "update_category_excludes" to "9",
            "library_update_interval" to "EVERY_6H",
        )
        val preferences = LibraryPreferences(store)
        preferences.updateCategories().set(emptySet())
        preferences.updateCategoriesExclude().set(emptySet())
        preferences.autoUpdateInterval().set(72)
        preferences.autoUpdateDeviceRestrictions().set(setOf(LibraryPreferences.DEVICE_ONLY_ON_WIFI))
        assertTrue(LibraryPreferenceMigration(store, preferences).migrate())
        assertEquals(emptySet<String>(), preferences.updateCategories().get())
        assertEquals(emptySet<String>(), preferences.updateCategoriesExclude().get())
        assertEquals(72, preferences.autoUpdateInterval().get())
        assertEquals(setOf(LibraryPreferences.DEVICE_ONLY_ON_WIFI), preferences.autoUpdateDeviceRestrictions().get())
    }

    @Test
    fun `v3 failed scope writes restore original raw authority before retry or restart`() {
        for (afterWrite in listOf(false, true)) {
            val backend = FaultPreferences()
            val store = DesktopPreferenceStore(backend)
            store.getString("update_category_includes", "").set("2")
            store.getString("update_category_excludes", "").set("3")
            store.getString("library_update_interval", "").set("EVERY_6H")
            val preferences = LibraryPreferences(store)
            val marker = store.getInt(LibraryPreferenceMigration.MARKER_KEY, 0)
            marker.set(2)
            backend.failureKey = preferences.updateCategoriesExclude().key()
            backend.afterWrite = afterWrite
            assertFalse(LibraryPreferenceMigration(store, preferences).migrate())
            assertEquals(2, marker.get())
            assertFalse(
                preferences.updateCategories().isSet(),
                "A partial new include must not become a user supplied value",
            )
            assertFalse(preferences.updateCategoriesExclude().isSet())
            assertTrue(LibraryPreferenceMigration(store, preferences).migrate())
            assertEquals(setOf("2"), preferences.updateCategories().get())
            assertEquals(setOf("3"), preferences.updateCategoriesExclude().get())
            assertEquals(6, preferences.autoUpdateInterval().get())
        }
    }

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
            val explicit = if (orientation ==
                "portrait"
            ) {
                preferences.portraitColumns()
            } else {
                preferences.landscapeColumns()
            }
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
        assertEquals(
            6,
            preferences.portraitColumns().get(),
            "malformed raw shared value cannot pretend to be explicit automatic zero",
        )
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
        override fun removeSpi(key: String) {
            values.remove(key)
        }
        override fun removeNodeSpi() {
            values.clear()
        }
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
