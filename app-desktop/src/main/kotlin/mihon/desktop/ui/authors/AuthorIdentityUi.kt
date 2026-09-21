package mihon.desktop.ui.authors

import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.model.SourceWorkNaturalKey
import tachiyomi.domain.creator.service.CreatorLibraryIndexState


internal sealed interface AuthorIndexPresentation {
    data class Indexing(val processedManga: Int, val totalManga: Int) : AuthorIndexPresentation
    data class Failed(val message: String) : AuthorIndexPresentation
    data object EmptyLibrary : AuthorIndexPresentation
    data object NoAuthorMetadata : AuthorIndexPresentation
    data object Content : AuthorIndexPresentation
}

internal fun authorIndexPresentation(
    state: CreatorLibraryIndexState,
    creatorCount: Int,
): AuthorIndexPresentation = when (state) {
    CreatorLibraryIndexState.Idle -> AuthorIndexPresentation.Indexing(0, 0)
    is CreatorLibraryIndexState.Indexing -> AuthorIndexPresentation.Indexing(
        state.processedManga,
        state.totalManga,
    )
    is CreatorLibraryIndexState.Failed -> AuthorIndexPresentation.Failed(state.message)
    CreatorLibraryIndexState.Empty -> AuthorIndexPresentation.EmptyLibrary
    is CreatorLibraryIndexState.Ready -> if (creatorCount == 0) {
        AuthorIndexPresentation.NoAuthorMetadata
    } else {
        AuthorIndexPresentation.Content
    }
}

internal class AuthorIdentityActions(
    val manageCreatorIdentity: ManageCreatorIdentity,
) {
    suspend fun addAlias(creatorId: Long, alias: String) {
        manageCreatorIdentity.addAlias(creatorId, alias)
    }

    suspend fun getManualAliases(creatorId: Long): List<String> =
        manageCreatorIdentity.getManualAliases(creatorId)

    suspend fun removeAlias(creatorId: Long, alias: String) {
        manageCreatorIdentity.removeAlias(creatorId, alias)
    }

    suspend fun merge(sourceCreatorId: Long, targetCreatorId: Long) {
        manageCreatorIdentity.merge(sourceCreatorId, targetCreatorId)
    }

    suspend fun split(
        sourceCreatorId: Long,
        mangaIds: Set<Long>,
        newDisplayName: String,
        sourceWorks: Set<SourceWorkNaturalKey> = emptySet(),
    ): Long = manageCreatorIdentity.split(sourceCreatorId, mangaIds, newDisplayName, sourceWorks)
}
