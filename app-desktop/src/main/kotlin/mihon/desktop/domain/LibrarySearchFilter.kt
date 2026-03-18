package mihon.desktop.domain

import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.library.model.LibraryManga

/**
 * Sort modes for the library grid.
 */
enum class SortMode {
    TITLE,
    UNREAD_COUNT,
    DATE_ADDED,
    LAST_READ,
}

/**
 * Pure-function utilities for searching, filtering, sorting and
 * category-slicing the library manga list.
 *
 * Every method is stateless — accepts a list and returns a new list.
 */
object LibrarySearchFilter {

    /** Filter items whose title contains [query] (case-insensitive). */
    fun applySearch(items: List<LibraryManga>, query: String?): List<LibraryManga> {
        if (query.isNullOrBlank()) return items
        val q = query.trim().lowercase()
        return items.filter { q in it.manga.title.lowercase() }
    }

    /**
     * Apply boolean filters. Each flag, when `true`, narrows the list:
     * - [unread]: keep only manga with `unreadCount > 0`
     * - [started]: keep only manga with `readCount > 0`
     * - [completed]: keep only manga with status [Manga.COMPLETED]
     */
    fun applyFilters(
        items: List<LibraryManga>,
        unread: Boolean = false,
        started: Boolean = false,
        completed: Boolean = false,
    ): List<LibraryManga> {
        var result = items
        if (unread) result = result.filter { it.unreadCount > 0 }
        if (started) result = result.filter { it.hasStarted }
        if (completed) result = result.filter { it.manga.status == SManga.COMPLETED.toLong() }
        return result
    }

    /** Sort items by [mode]. When [ascending] is false the order is reversed. */
    fun applySort(
        items: List<LibraryManga>,
        mode: SortMode,
        ascending: Boolean = true,
    ): List<LibraryManga> {
        val comparator: Comparator<LibraryManga> = when (mode) {
            SortMode.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.manga.title }
            SortMode.UNREAD_COUNT -> compareBy { it.unreadCount }
            SortMode.DATE_ADDED -> compareBy { it.manga.dateAdded }
            SortMode.LAST_READ -> compareBy { it.lastRead }
        }
        return items.sortedWith(if (ascending) comparator else comparator.reversed())
    }

    /** Keep only items belonging to [categoryId]. */
    fun applyCategory(items: List<LibraryManga>, categoryId: Long): List<LibraryManga> {
        return items.filter { categoryId in it.categories }
    }

    /**
     * Convenience pipeline: category → search → filter → sort.
     */
    fun apply(
        items: List<LibraryManga>,
        categoryId: Long? = null,
        searchQuery: String? = null,
        unread: Boolean = false,
        started: Boolean = false,
        completed: Boolean = false,
        sortMode: SortMode = SortMode.TITLE,
        ascending: Boolean = true,
    ): List<LibraryManga> {
        var result = items
        if (categoryId != null) result = applyCategory(result, categoryId)
        result = applySearch(result, searchQuery)
        result = applyFilters(result, unread = unread, started = started, completed = completed)
        result = applySort(result, sortMode, ascending)
        return result
    }
}
