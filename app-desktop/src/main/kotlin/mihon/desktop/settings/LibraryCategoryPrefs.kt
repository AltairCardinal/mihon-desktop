package mihon.desktop.settings

import mihon.desktop.domain.SortMode
import mihon.desktop.ui.library.LibraryDisplayMode
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Stores per-category display mode and sort settings.
 *
 * Keys use the pattern `lib_cat_{id}_display` / `lib_cat_{id}_sort` / `lib_cat_{id}_sort_asc`.
 * Category id `null` represents the "All" virtual tab (id = -1L).
 */
class LibraryCategoryPrefs(private val store: PreferenceStore) {

    private val cache = mutableMapOf<String, Preference<*>>()

    private fun keyFor(categoryId: Long?, suffix: String): String {
        val id = categoryId ?: -1L
        return "lib_cat_${id}_$suffix"
    }

    @Suppress("UNCHECKED_CAST")
    private fun displayPref(categoryId: Long?): Preference<String> =
        cache.getOrPut(keyFor(categoryId, "display")) {
            store.getString(keyFor(categoryId, "display"), LibraryDisplayMode.DEFAULT.name)
        } as Preference<String>

    @Suppress("UNCHECKED_CAST")
    private fun sortPref(categoryId: Long?): Preference<String> =
        cache.getOrPut(keyFor(categoryId, "sort")) {
            store.getString(keyFor(categoryId, "sort"), SortMode.TITLE.name)
        } as Preference<String>

    @Suppress("UNCHECKED_CAST")
    private fun sortAscPref(categoryId: Long?): Preference<Boolean> =
        cache.getOrPut(keyFor(categoryId, "sort_asc")) {
            store.getBoolean(keyFor(categoryId, "sort_asc"), true)
        } as Preference<Boolean>

    fun getDisplayMode(categoryId: Long?): LibraryDisplayMode =
        runCatching { LibraryDisplayMode.valueOf(displayPref(categoryId).get()) }
            .getOrDefault(LibraryDisplayMode.DEFAULT)

    fun setDisplayMode(categoryId: Long?, mode: LibraryDisplayMode) =
        displayPref(categoryId).set(mode.name)

    fun getSortMode(categoryId: Long?): SortMode =
        runCatching { SortMode.valueOf(sortPref(categoryId).get()) }
            .getOrDefault(SortMode.TITLE)

    fun setSortMode(categoryId: Long?, mode: SortMode) =
        sortPref(categoryId).set(mode.name)

    fun getSortAscending(categoryId: Long?): Boolean =
        sortAscPref(categoryId).get()

    fun setSortAscending(categoryId: Long?, ascending: Boolean) =
        sortAscPref(categoryId).set(ascending)
}
