package mihon.desktop.domain

/**
 * Filters manga IDs for library update based on category include/exclude rules.
 *
 * - If [includeCategories] is non-empty, only manga in at least one included category pass.
 * - If [excludeCategories] is non-empty, manga in any excluded category are removed
 *   (unless they also match an include rule).
 * - If both are empty, all manga pass.
 */
fun filterMangaForUpdate(
    mangaIds: List<Long>,
    mangaCategoryLookup: (Long) -> List<Long>,
    includeCategories: Set<Long>,
    excludeCategories: Set<Long>,
): List<Long> {
    if (includeCategories.isEmpty() && excludeCategories.isEmpty()) return mangaIds
    return mangaIds.filter { mangaId ->
        val cats = mangaCategoryLookup(mangaId)
        when {
            includeCategories.isNotEmpty() -> cats.any { it in includeCategories }
            else -> cats.none { it in excludeCategories }
        }
    }
}
