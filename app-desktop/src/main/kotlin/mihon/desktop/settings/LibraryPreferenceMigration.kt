package mihon.desktop.settings

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Imports the pre-parity Desktop library keys once.
 *
 * The old keys are deliberately retained for backup compatibility. A value is
 * imported only when the shared key is absent or contains an invalid serialized
 * value; a valid shared value always wins, including a value equal to its
 * default. The marker is written last so a failed migration can be retried.
 */
class LibraryPreferenceMigration(
    private val store: PreferenceStore,
    private val preferences: LibraryPreferences,
    private val legacyUpdatePreferences:
    (() -> Triple<Preference<String>, Preference<String>, Preference<LibraryUpdateInterval>>)? = null,
    private val validCategoryIds: (() -> Set<Long>)? = null,
    private val legacyColumns: () -> Preference<Int> = { store.getInt(LEGACY_COLUMNS_KEY, 3) },
) {

    @Synchronized
    fun migrate(categoryIds: Set<Long>? = validCategoryIds?.invoke()): Boolean {
        val marker = store.getInt(MARKER_KEY, 0)
        val journal = store.getString(RECOVERY_KEY, "")
        if (journal.get().isNotEmpty()) {
            try {
                journal.set(journal.get())
                restore(Json.decodeFromString<Map<String, String?>>(journal.get()), categoryIds)
                journal.delete()
            } catch (_: Exception) {
                return false
            }
        }
        if (marker.get() >= VERSION) return false

        // Resolve the lazy desktop/app bridge before freezing the original authority.
        val legacy = legacyUpdatePreferences?.invoke()
        legacyColumns()
        val raw = store.getAll()
        val original = migrationKeys().associateWith { raw[it]?.toString() }
        return try {
            journal.set(Json.encodeToString(original))
            migrateDisplay()
            migrateSort()
            migrateColumns()
            migrateUpdatePolicy(legacy, categoryIds)
            marker.set(VERSION)
            journal.delete()
            true
        } catch (_: Exception) {
            runCatching {
                // A flush failure can remove the record in RAM; acknowledge it again before compensating.
                journal.set(Json.encodeToString(original))
                restore(original, categoryIds)
                journal.delete()
            }
            false
        }
    }

    @Synchronized
    fun isComplete() = store.getInt(MARKER_KEY, 0).get() >= VERSION && store.getString(RECOVERY_KEY, "").get().isEmpty()

    private fun migrationKeys() = listOf(
        preferences.displayMode().key(), preferences.sortingMode().key(),
        preferences.portraitColumns().key(), preferences.landscapeColumns().key(),
        preferences.updateCategories().key(), preferences.updateCategoriesExclude().key(),
        preferences.autoUpdateInterval().key(), preferences.autoUpdateDeviceRestrictions().key(),
        preferences.autoUpdateMangaRestrictions().key(), preferences.autoUpdateMetadata().key(),
        CHOICE_KEY, INTERVAL_INVALID_KEY, MARKER_KEY,
    )

    private fun restore(original: Map<String, String?>, validIds: Set<Long>?) {
        require(original.keys == migrationKeys().toSet()) { "Invalid library migration recovery" }
        val restored = original.toMutableMap()
        if (validIds != null) {
            for (key in listOf(preferences.updateCategories().key(), preferences.updateCategoriesExclude().key())) {
                val raw = restored[key] ?: continue
                val tokens = if (raw.isEmpty()) emptySet() else raw.split('\u001F').toSet()
                val valid = tokens.filter { it.toLongOrNull() in validIds }.toSet()
                if (tokens != valid) restored[CHOICE_KEY] = "true"
                restored[key] = valid.joinToString("\u001F")
            }
        }
        restored.forEach { (key, raw) ->
            val preference = store.getString(key, "")
            if (raw == null) preference.delete() else preference.set(raw)
        }
    }

    private fun migrateUpdatePolicy(
        legacy: Triple<Preference<String>, Preference<String>, Preference<LibraryUpdateInterval>>?,
        validIds: Set<Long>?,
    ) {
        val raw = store.getAll()
        var choiceRequired = store.getBoolean(CHOICE_KEY, false).get()
        listOf(
            preferences.updateCategories() to (legacy?.first ?: store.getString("update_category_includes", "")),
            preferences.updateCategoriesExclude() to (
                legacy?.second ?: store.getString(
                    "update_category_excludes",
                    "",
                )
                ),
        ).forEach { (shared, previous) ->
            val serialized = raw[shared.key()]?.toString()
            val sharedIds = shared.get()
            val validShared = shared.isSet() && serialized != null && sharedIds.all {
                val id = it.toLongOrNull()
                id != null && (validIds == null || id in validIds)
            }
            if (validShared) return@forEach
            if (!previous.isSet()) return@forEach
            val text = previous.get()
            val tokens = text.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
            val usable = tokens.filter {
                val id = it.toLongOrNull()
                id != null && (validIds == null || id in validIds)
            }.toSet()
            if (tokens != usable || (text.isNotBlank() && tokens.isEmpty())) choiceRequired = true
            shared.set(usable)
        }
        store.getBoolean(CHOICE_KEY, false).set(choiceRequired)
        val interval = preferences.autoUpdateInterval()
        val explicit = raw[interval.key()]?.toString()?.toIntOrNull()
        if (!interval.isSet() || explicit !in setOf(0, 6, 12, 24, 48, 72, 168)) {
            val old = legacy?.third
            val name = raw["library_update_interval"]?.toString() ?: old?.takeIf { it.isSet() }?.get()?.name
            val hours = LibraryUpdateInterval.entries.firstOrNull { it.name == name }?.hours
            if (hours != null) {
                interval.set(hours.toInt())
            } else if (name != null || (interval.isSet() && explicit !in setOf(0, 6, 12, 24, 48, 72, 168))) {
                interval.set(0)
                store.getBoolean(INTERVAL_INVALID_KEY, false).set(true)
            }
        }
        // These defaults were not enforced by the old Desktop scheduler.
        if (!preferences.autoUpdateDeviceRestrictions().isSet()) {
            preferences.autoUpdateDeviceRestrictions().set(
                emptySet(),
            )
        }
        if (!preferences.autoUpdateMangaRestrictions().isSet()) {
            preferences.autoUpdateMangaRestrictions().set(
                emptySet(),
            )
        }
        if (!preferences.autoUpdateMetadata().isSet()) preferences.autoUpdateMetadata().set(false)
    }

    private fun migrateColumns() {
        val columns = listOf(preferences.portraitColumns(), preferences.landscapeColumns())
        val raw = store.getAll()
        fun valid(preference: Preference<Int>) =
            raw[preference.key()]?.toString()?.toIntOrNull()?.let { it in 0..10 } == true
        if (columns.all(::valid)) return
        val legacy = legacyColumns()
        if (!legacy.isSet()) return
        val value = store.getAll()[legacy.key()]?.toString()?.toIntOrNull()?.takeIf { it in 0..10 } ?: return
        columns.filterNot(::valid).forEach { it.set(value) }
    }

    private fun migrateDisplay() {
        val shared = preferences.displayMode()
        val rawShared = store.getAll()[shared.key()]
        if (shared.isSet() &&
            (rawShared is LibraryDisplayMode || rawShared?.toString()?.let(::decodeDisplay) != null)
        ) {
            return
        }

        val rawLegacy = store.getAll()[LEGACY_ALL_DISPLAY_KEY]?.toString() ?: return
        decodeDisplay(rawLegacy)?.let(shared::set)
    }

    private fun migrateSort() {
        val shared = preferences.sortingMode()
        val rawShared = store.getAll()[shared.key()]
        if (shared.isSet() && (rawShared is LibrarySort || rawShared?.toString()?.let(::decodeSort) != null)) return

        val rawLegacy = store.getAll()[LEGACY_ALL_SORT_KEY]?.toString()
        val type = decodeLegacySortType(rawLegacy) ?: return
        val ascending = store.getAll()[LEGACY_ALL_SORT_ASC_KEY]?.let(::decodeBoolean) ?: true
        shared.set(
            LibrarySort(type, if (ascending) LibrarySort.Direction.Ascending else LibrarySort.Direction.Descending),
        )
    }

    private fun decodeDisplay(raw: String): LibraryDisplayMode? = when (raw) {
        "COMPACT_GRID" -> LibraryDisplayMode.CompactGrid
        "COMFORTABLE_GRID" -> LibraryDisplayMode.ComfortableGrid
        "COVER_ONLY_GRID" -> LibraryDisplayMode.CoverOnlyGrid
        "LIST" -> LibraryDisplayMode.List
        else -> null
    }

    private fun decodeSort(raw: String): LibrarySort? {
        val parts = raw.split(",")
        if (parts.size != 2) return null
        val type = when (parts[0]) {
            "ALPHABETICAL" -> LibrarySort.Type.Alphabetical
            "LAST_MANGA_UPDATE" -> LibrarySort.Type.LastUpdate
            "LAST_READ" -> LibrarySort.Type.LastRead
            "UNREAD_COUNT" -> LibrarySort.Type.UnreadCount
            "TOTAL_CHAPTERS" -> LibrarySort.Type.TotalChapters
            "LATEST_CHAPTER" -> LibrarySort.Type.LatestChapter
            "CHAPTER_FETCH_DATE" -> LibrarySort.Type.ChapterFetchDate
            "DATE_ADDED" -> LibrarySort.Type.DateAdded
            "TRACKER_MEAN" -> LibrarySort.Type.TrackerMean
            "RANDOM" -> LibrarySort.Type.Random
            else -> return null
        }
        val direction = when (parts[1]) {
            "ASCENDING" -> LibrarySort.Direction.Ascending
            "DESCENDING" -> LibrarySort.Direction.Descending
            else -> return null
        }
        return LibrarySort(type, direction)
    }

    private fun decodeLegacySortType(raw: String?): LibrarySort.Type? = when (raw) {
        "TITLE" -> LibrarySort.Type.Alphabetical
        "LAST_READ" -> LibrarySort.Type.LastRead
        "UNREAD_COUNT" -> LibrarySort.Type.UnreadCount
        "DATE_ADDED" -> LibrarySort.Type.DateAdded
        else -> null
    }

    private fun decodeBoolean(value: Any): Boolean? = when (value) {
        is Boolean -> value
        else -> value.toString().toBooleanStrictOrNull()
    }

    companion object {
        const val VERSION = 3
        const val MARKER_KEY = "library_interaction_parity_migration_version"
        val RECOVERY_KEY = Preference.appStateKey("library_settings_migration_recovery")
        val INTERVAL_INVALID_KEY = Preference.appStateKey("library_update_interval_migration_invalid")
        private val CHOICE_KEY = Preference.appStateKey("library_update_scope_requires_choice")
        private const val LEGACY_COLUMNS_KEY = "library_grid_columns"
        private const val LEGACY_ALL_DISPLAY_KEY = "lib_cat_-1_display"
        private const val LEGACY_ALL_SORT_KEY = "lib_cat_-1_sort"
        private const val LEGACY_ALL_SORT_ASC_KEY = "lib_cat_-1_sort_asc"
    }
}
