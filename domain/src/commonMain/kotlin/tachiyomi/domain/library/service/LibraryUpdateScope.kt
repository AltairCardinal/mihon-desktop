package tachiyomi.domain.library.service

import tachiyomi.domain.library.model.LibraryManga

/** Selects the fixed workset for one manual or whole-library update request. */
fun selectLibraryMangaForUpdate(
    library: List<LibraryManga>,
    categoryId: Long?,
    includeCategories: Set<Long>,
    excludeCategories: Set<Long>,
): List<LibraryManga> {
    if (categoryId != null) return library.filter { categoryId in it.categories }
    return library.filter { entry ->
        val included = includeCategories.isEmpty() || entry.categories.any(includeCategories::contains)
        val excluded = entry.categories.any(excludeCategories::contains)
        included && !excluded
    }
}
