package mihon.desktop.ui.library

import mihon.desktop.domain.SortMode
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga

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
    val filterUnread: Boolean = false,
    val filterStarted: Boolean = false,
    val filterCompleted: Boolean = false,
    val filterDownloaded: Boolean = false,

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
)
