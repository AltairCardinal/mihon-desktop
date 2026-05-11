package mihon.desktop.ui.library

import cafe.adriel.voyager.core.model.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import mihon.desktop.domain.SortMode
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga

/**
 * Voyager ScreenModel for [LibraryRootScreen].
 *
 * Owns all library UI state and exposes it as [StateFlow<LibraryState>].
 * All state transitions go through explicit mutation methods, enabling
 * JVM unit tests without Compose or DI.
 */
class LibraryScreenModel : ScreenModel {

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    // ── Data loading ──────────────────────────────────────────────────────────

    fun setAllItems(items: List<LibraryManga>) {
        _state.update { it.copy(allItems = items) }
    }

    fun setCategories(categories: List<Category>) {
        _state.update { it.copy(categories = categories) }
    }

    // ── Update status ─────────────────────────────────────────────────────────

    fun setIsUpdating(updating: Boolean) {
        _state.update { it.copy(isUpdating = updating) }
    }

    fun setUpdateStatusText(text: String?) {
        _state.update { it.copy(updateStatusText = text) }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    // ── Sort ──────────────────────────────────────────────────────────────────

    fun setSortMode(mode: SortMode) {
        _state.update { it.copy(sortMode = mode) }
    }

    fun setSortAscending(ascending: Boolean) {
        _state.update { it.copy(sortAscending = ascending) }
    }

    fun setSortModeAndDirection(mode: SortMode, ascending: Boolean) {
        _state.update { it.copy(sortMode = mode, sortAscending = ascending) }
    }

    // ── Filters ───────────────────────────────────────────────────────────────

    fun setFilters(unread: Boolean, started: Boolean, completed: Boolean, downloaded: Boolean) {
        _state.update {
            it.copy(
                filterUnread = unread,
                filterStarted = started,
                filterCompleted = completed,
                filterDownloaded = downloaded,
            )
        }
    }

    // ── Category selection ────────────────────────────────────────────────────

    fun setSelectedCategoryIndex(index: Int) {
        _state.update { it.copy(selectedCategoryIndex = index) }
    }

    // ── Display mode ──────────────────────────────────────────────────────────

    fun setDisplayMode(mode: LibraryDisplayMode) {
        _state.update { it.copy(displayMode = mode) }
    }

    // ── Dialog / menu visibility ──────────────────────────────────────────────

    fun setShowCategoryDialog(show: Boolean) {
        _state.update { it.copy(showCategoryDialog = show) }
    }

    fun setContextMenuManga(manga: LibraryManga?) {
        _state.update { it.copy(contextMenuManga = manga) }
    }

    fun setShowBatchCategoryDialog(show: Boolean) {
        _state.update { it.copy(showBatchCategoryDialog = show) }
    }
}
