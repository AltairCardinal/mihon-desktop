package mihon.desktop.ui.library

import mihon.desktop.domain.SortMode
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.interactor.LibraryFilter

/**
 * All state for [LibraryRootScreen], owned by [LibraryScreenModel].
 * Pure data — no Compose dependencies, fully testable on the JVM.
 */
data class LibraryState(
    // ── Loaded data ──────────────────────────────────────────────────────────
    val allItems: List<LibraryManga> = emptyList(),
    val categories: List<Category> = emptyList(),

    // ── Search ────────────────────────────────────────────────────────────────
    val searchQuery: String = "",

    // ── Sort state ────────────────────────────────────────────────────────────
    val sortMode: SortMode = SortMode.TITLE,
    val sortAscending: Boolean = true,

    // ── Filter state ──────────────────────────────────────────────────────────
    val filter: LibraryFilter = LibraryFilter(),
    val downloadedMangaIds: Set<Long> = emptySet(),
    val localMangaIds: Set<Long> = emptySet(),
    val trackerIdsByManga: Map<Long, Set<Long>> = emptyMap(),
    val availableTrackerIds: Set<Long> = emptySet(),

    // ── Category tab ──────────────────────────────────────────────────────────
    val selectedCategoryIndex: Int = 0,

    // ── Update status ─────────────────────────────────────────────────────────
    val isUpdating: Boolean = false,
    val updateStatusText: String? = null,

    // ── Display ───────────────────────────────────────────────────────────────
    val displayMode: LibraryDisplayMode = LibraryDisplayMode.DEFAULT,

    // ── Dialog / menu visibility ──────────────────────────────────────────────
    val showCategoryDialog: Boolean = false,
    val contextMenuManga: LibraryManga? = null,
    val showBatchCategoryDialog: Boolean = false,
    val batchCategoryResultMessage: String? = null,
) {
    val filterUnread get() = filter.unread == TriState.ENABLED_IS
    val filterStarted get() = filter.started == TriState.ENABLED_IS
    val filterCompleted get() = filter.completed == TriState.ENABLED_IS
    val filterDownloaded get() = filter.downloaded == TriState.ENABLED_IS
}
