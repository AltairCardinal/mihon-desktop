package tachiyomi.domain.library

import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga

/** Three-state value used by the batch category editor. */
enum class LibraryCategorySelection {
    NONE,
    ALL,
    MIXED,
}

data class LibraryCategoryDelta(
    val addCategoryIds: Set<Long> = emptySet(),
    val removeCategoryIds: Set<Long> = emptySet(),
)

/**
 * Returns the category tabs represented by a library.
 *
 * The system Uncategorized category is shown only if at least one library manga belongs to it.
 * Empty user categories remain in the result so they can be selected and managed.
 * [showSystemCategory] is supplied by consumers that already have an unfiltered library snapshot,
 * such as Android's state pipeline.
 */
fun projectLibraryCategories(
    categories: List<Category>,
    items: List<LibraryManga>,
    showSystemCategory: Boolean? = null,
): List<Category> {
    val showSystem = showSystemCategory ?: items.any { Category.UNCATEGORIZED_ID in it.categories }
    return categories.filter { category -> showSystem || !category.isSystemCategory }
}

fun libraryCategorySelectionState(
    categoryId: Long,
    currentCategoryIdsByManga: Map<Long, Set<Long>>,
): LibraryCategorySelection {
    if (currentCategoryIdsByManga.isEmpty()) return LibraryCategorySelection.NONE
    val selectedCount = currentCategoryIdsByManga.values.count { categoryId in it }
    return when (selectedCount) {
        0 -> LibraryCategorySelection.NONE
        currentCategoryIdsByManga.size -> LibraryCategorySelection.ALL
        else -> LibraryCategorySelection.MIXED
    }
}

fun initialLibraryCategorySelections(
    categories: List<Category>,
    currentCategoryIdsByManga: Map<Long, Set<Long>>,
): Map<Long, LibraryCategorySelection> = categories.associate { category ->
    category.id to libraryCategorySelectionState(category.id, currentCategoryIdsByManga)
}

fun libraryCategoryDelta(
    currentCategoryIdsByManga: Map<Long, Set<Long>>,
    desiredStates: Map<Long, LibraryCategorySelection>,
): LibraryCategoryDelta {
    val add = mutableSetOf<Long>()
    val remove = mutableSetOf<Long>()
    desiredStates.forEach { (categoryId, desired) ->
        if (categoryId == Category.UNCATEGORIZED_ID) return@forEach
        when (desired) {
            LibraryCategorySelection.ALL -> {
                if (currentCategoryIdsByManga.values.any { categoryId !in it }) add += categoryId
            }
            LibraryCategorySelection.NONE -> {
                if (currentCategoryIdsByManga.values.any { categoryId in it }) remove += categoryId
            }
            LibraryCategorySelection.MIXED -> Unit
        }
    }
    return LibraryCategoryDelta(add, remove)
}

/** Applies a batch category delta while preserving each manga's existing order and membership. */
fun applyLibraryCategoryDelta(
    currentCategoryIds: Iterable<Long>,
    addCategoryIds: Iterable<Long>,
    removeCategoryIds: Iterable<Long>,
): List<Long> {
    val removals = removeCategoryIds.filterNot { it == Category.UNCATEGORIZED_ID }.toSet()
    val additions = addCategoryIds
        .filterNot { it == Category.UNCATEGORIZED_ID }
        .filterNot { it in removals }
    return (currentCategoryIds.filterNot { it in removals } + additions).distinct()
}
