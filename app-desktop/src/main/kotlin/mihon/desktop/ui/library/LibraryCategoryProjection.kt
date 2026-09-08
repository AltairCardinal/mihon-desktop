package mihon.desktop.ui.library

import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.LibraryCategoryDelta as SharedLibraryCategoryDelta
import tachiyomi.domain.library.LibraryCategorySelection as SharedLibraryCategorySelection
import tachiyomi.domain.library.initialLibraryCategorySelections as sharedInitialLibraryCategorySelections
import tachiyomi.domain.library.libraryCategoryDelta as sharedLibraryCategoryDelta
import tachiyomi.domain.library.libraryCategorySelectionState as sharedLibraryCategorySelectionState
import tachiyomi.domain.library.projectLibraryCategories
import tachiyomi.domain.library.model.LibraryManga

/** The three values represented by a batch category checkbox. */
internal typealias LibraryCategorySelection = SharedLibraryCategorySelection
internal typealias LibraryCategoryDelta = SharedLibraryCategoryDelta

/**
 * Projects the database category list to the library tabs.
 *
 * The system Uncategorized tab is meaningful only while at least one
 * favorite is actually assigned to it. Custom empty categories remain visible
 * so users can still manage and populate them.
 */
internal fun libraryCategoryTabs(
    categories: List<Category>,
    items: List<LibraryManga>,
): List<Category> = projectLibraryCategories(categories, items)
    .mapIndexed { index, category -> category.copy(order = index.toLong()) }

internal fun libraryCategorySelectionState(
    categoryId: Long,
    currentCategoryIdsByManga: Map<Long, Set<Long>>,
): LibraryCategorySelection {
    return sharedLibraryCategorySelectionState(categoryId, currentCategoryIdsByManga)
}

internal fun initialLibraryCategorySelections(
    categories: List<Category>,
    currentCategoryIdsByManga: Map<Long, Set<Long>>,
): Map<Long, LibraryCategorySelection> =
    sharedInitialLibraryCategorySelections(categories, currentCategoryIdsByManga)

/**
 * Converts user-facing desired checkbox states to add/remove operations.
 * MIXED deliberately produces no delta, preserving each manga's untouched
 * category membership instead of overwriting it with the first item's state.
 */
internal fun libraryCategoryDelta(
    currentCategoryIdsByManga: Map<Long, Set<Long>>,
    desiredStates: Map<Long, LibraryCategorySelection>,
): LibraryCategoryDelta {
    return sharedLibraryCategoryDelta(currentCategoryIdsByManga, desiredStates)
}
