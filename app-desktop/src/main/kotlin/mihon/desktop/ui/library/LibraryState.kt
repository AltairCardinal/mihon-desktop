package mihon.desktop.ui.library

import mihon.desktop.domain.SortMode
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.interactor.LibraryFilter
import tachiyomi.domain.library.model.LibraryManga

/**
 * All state for [LibraryRootScreen], owned by [LibraryScreenModel].
 * Pure data — no Compose dependencies, fully testable on the JVM.
 */
data class LibraryState(
    // ── Loaded data ──────────────────────────────────────────────────────────
    val allItems: List<LibraryManga> = emptyList(),
    val syncedResumeMangaIds: Set<Long> = emptySet(),
    val allCategories: List<Category> = emptyList(),
    val categories: List<Category> = emptyList(),

    // ── Search ────────────────────────────────────────────────────────────────
    val searchQuery: String? = null,

    // ── Sort state ────────────────────────────────────────────────────────────
    val sortMode: SortMode = SortMode.TITLE,
    val sortAscending: Boolean = true,

    // ── Filter state ──────────────────────────────────────────────────────────
    val filter: LibraryFilter = LibraryFilter(),
    val downloadedMangaIds: Set<Long> = emptySet(),
    val downloadCountsByManga: Map<Long, Long> = emptyMap(),
    val localMangaIds: Set<Long> = emptySet(),
    val sourceLanguagesByManga: Map<Long, String> = emptyMap(),
    val trackerIdsByManga: Map<Long, Set<Long>> = emptyMap(),
    val trackerMeansByManga: Map<Long, Double> = emptyMap(),
    val availableTrackerIds: Set<Long> = emptySet(),

    // ── Category tab ──────────────────────────────────────────────────────────
    val selectedCategoryIndex: Int = 0,

    // ── Update status ─────────────────────────────────────────────────────────
    val isUpdating: Boolean = false,
    val updateStatusText: String? = null,
    val isLoading: Boolean = true,
    val loadError: String? = null,

    // ── Display ───────────────────────────────────────────────────────────────
    val displayMode: LibraryDisplayMode = LibraryDisplayMode.DEFAULT,
    val portraitColumns: Int = 0,
    val landscapeColumns: Int = 0,
    val showContinueReadingButton: Boolean = false,
    val showDownloadBadge: Boolean = false,
    val showUnreadBadge: Boolean = true,
    val showLocalBadge: Boolean = true,
    val showLanguageBadge: Boolean = false,
    val showCategoryTabs: Boolean = true,
    val showCategoryItemCounts: Boolean = false,
    val categorizedDisplaySettings: Boolean = false,

    // ── Dialog / menu visibility ──────────────────────────────────────────────
    val showCategoryDialog: Boolean = false,
    val contextMenuManga: LibraryManga? = null,
    val showBatchCategoryDialog: Boolean = false,
    val batchCategoryResultMessage: String? = null,
    val operationFeedback: String? = null,
) {
    val filterUnread get() = filter.unread == TriState.ENABLED_IS
    val filterStarted get() = filter.started == TriState.ENABLED_IS
    val filterCompleted get() = filter.completed == TriState.ENABLED_IS
    val filterDownloaded get() = filter.downloaded == TriState.ENABLED_IS
    val hasActiveFilters get() =
        filter.downloaded != TriState.DISABLED ||
            filter.unread != TriState.DISABLED ||
            filter.started != TriState.DISABLED ||
            filter.bookmarked != TriState.DISABLED ||
            filter.completed != TriState.DISABLED ||
            filter.intervalCustom != TriState.DISABLED ||
            filter.tracking.any { (trackerId, value) -> trackerId in availableTrackerIds && value != TriState.DISABLED }
}
