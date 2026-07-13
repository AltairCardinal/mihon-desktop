package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

class UpdateLibraryMembership(
    private val update: suspend (MangaUpdate) -> Boolean,
    private val setCategories: suspend (Long, List<Long>) -> Unit,
) {
    constructor(repository: MangaRepository) : this(repository::update, repository::setMangaCategories)

    suspend fun await(
        manga: Manga,
        favorite: Boolean,
        categoryIds: List<Long> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
    ): LibraryMembershipResult = try {
        val changed = update(
            MangaUpdate(
                id = manga.id,
                favorite = favorite,
                dateAdded = if (favorite && !manga.favorite) nowMillis else manga.dateAdded,
            ),
        )
        if (!changed) return LibraryMembershipResult.Failure(manga.id, "Manga update was rejected")
        setCategories(manga.id, if (favorite) categoryIds.distinct() else emptyList())
        LibraryMembershipResult.Success(manga.id, favorite)
    } catch (e: Exception) {
        LibraryMembershipResult.Failure(manga.id, e.message ?: e::class.simpleName ?: "Unknown error")
    }
}

sealed interface LibraryMembershipResult {
    data class Success(val mangaId: Long, val favorite: Boolean) : LibraryMembershipResult
    data class Failure(val mangaId: Long, val message: String) : LibraryMembershipResult
}
