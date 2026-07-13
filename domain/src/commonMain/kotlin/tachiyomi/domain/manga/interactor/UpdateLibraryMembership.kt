package tachiyomi.domain.manga.interactor

import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.LibraryMembershipRepository
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate

class UpdateLibraryMembership(
    private val repository: LibraryMembershipRepository,
) {
    constructor(updateAtomically: suspend (LibraryMembershipUpdate) -> Unit) : this(
        object : LibraryMembershipRepository {
            override suspend fun updateAtomically(update: LibraryMembershipUpdate) = updateAtomically(update)
        },
    )

    suspend fun await(
        manga: Manga,
        favorite: Boolean,
        categoryIds: List<Long> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
    ): LibraryMembershipResult = try {
        repository.updateAtomically(
            LibraryMembershipUpdate(
                mangaId = manga.id,
                favorite = favorite,
                dateAdded = if (favorite) {
                    if (!manga.favorite) nowMillis else manga.dateAdded
                } else {
                    0L
                },
                categoryIds = if (favorite) categoryIds.distinct() else emptyList(),
            ),
        )
        LibraryMembershipResult.Success(manga.id, favorite)
    } catch (e: Exception) {
        LibraryMembershipResult.Failure(manga.id, e.message ?: e::class.simpleName ?: "Unknown error")
    }
}

sealed interface LibraryMembershipResult {
    data class Success(val mangaId: Long, val favorite: Boolean) : LibraryMembershipResult
    data class Failure(val mangaId: Long, val message: String) : LibraryMembershipResult
}
