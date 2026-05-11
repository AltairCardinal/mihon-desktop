package mihon.desktop.domain

import tachiyomi.domain.category.interactor.SetMangaCategories

/**
 * Assigns the same set of categories to multiple manga at once.
 */
suspend fun batchSetCategories(
    mangaIds: List<Long>,
    categoryIds: List<Long>,
    setMangaCategories: SetMangaCategories,
) {
    mangaIds.forEach { mangaId ->
        setMangaCategories.await(mangaId, categoryIds)
    }
}
