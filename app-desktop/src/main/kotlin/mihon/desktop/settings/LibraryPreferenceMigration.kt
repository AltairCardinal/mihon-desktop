package mihon.desktop.settings

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
    private val legacyColumns: () -> Preference<Int> = { store.getInt(LEGACY_COLUMNS_KEY, 3) },
) {

    fun migrate(): Boolean {
        val marker = store.getInt(MARKER_KEY, 0)
        if (marker.get() >= VERSION) return false

        val previousMarker = marker.get() to marker.isSet()
        return try {
            migrateDisplay()
            migrateSort()
            migrateColumns()
            marker.set(VERSION)
            true
        } catch (_: Exception) {
            runCatching { if (previousMarker.second) marker.set(previousMarker.first) else marker.delete() }
            false
        }
    }

    private fun migrateColumns() {
        val columns = listOf(preferences.portraitColumns(), preferences.landscapeColumns())
        val raw = store.getAll()
        fun valid(preference: Preference<Int>) = raw[preference.key()]?.toString()?.toIntOrNull()?.let { it in 0..10 } == true
        if (columns.all(::valid)) return
        val legacy = legacyColumns()
        if (!legacy.isSet()) return
        val value = store.getAll()[legacy.key()]?.toString()?.toIntOrNull()?.takeIf { it in 0..10 } ?: return
        columns.filterNot(::valid).forEach { it.set(value) }
    }

    private fun migrateDisplay() {
        val shared = preferences.displayMode()
        val rawShared = store.getAll()[shared.key()]
        if (shared.isSet() && (rawShared is LibraryDisplayMode || rawShared?.toString()?.let(::decodeDisplay) != null)) return

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
        shared.set(LibrarySort(type, if (ascending) LibrarySort.Direction.Ascending else LibrarySort.Direction.Descending))
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
        const val VERSION = 2
        const val MARKER_KEY = "library_interaction_parity_migration_version"
        private const val LEGACY_COLUMNS_KEY = "library_grid_columns"
        private const val LEGACY_ALL_DISPLAY_KEY = "lib_cat_-1_display"
        private const val LEGACY_ALL_SORT_KEY = "lib_cat_-1_sort"
        private const val LEGACY_ALL_SORT_ASC_KEY = "lib_cat_-1_sort_asc"
    }
}
