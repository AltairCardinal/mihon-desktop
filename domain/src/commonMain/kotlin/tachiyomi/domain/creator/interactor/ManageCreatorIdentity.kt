package tachiyomi.domain.creator.interactor

import tachiyomi.domain.creator.model.CreatorMention
import tachiyomi.domain.creator.model.CreatorMentionResolution
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
import tachiyomi.domain.manga.model.Manga

class ManageCreatorIdentity(
    private val repository: CreatorArchiveRepository,
) {
    suspend fun addAlias(creatorId: Long, alias: String) = repository.addManualCreatorAlias(creatorId, alias)

    suspend fun getManualAliases(creatorId: Long): List<String> = repository.getManualCreatorAliases(creatorId)

    suspend fun removeAlias(creatorId: Long, alias: String) = repository.removeManualCreatorAlias(creatorId, alias)

    suspend fun merge(sourceCreatorId: Long, targetCreatorId: Long) =
        repository.mergeCreatorIdentities(sourceCreatorId, targetCreatorId)

    suspend fun split(sourceCreatorId: Long, mangaIds: Set<Long>, newDisplayName: String): Long =
        repository.splitCreatorIdentity(sourceCreatorId, mangaIds, newDisplayName)

    suspend fun resolve(manga: Manga, mention: CreatorMention): CreatorMentionResolution =
        CreatorMentionResolution.Resolved(repository.createAndBindMangaCreatorIdentity(manga, mention))

    suspend fun select(manga: Manga, mention: CreatorMention, creatorId: Long) =
        repository.bindMangaCreatorIdentity(manga, mention, creatorId)

    suspend fun createDistinct(manga: Manga, mention: CreatorMention): Long =
        repository.createAndBindMangaCreatorIdentity(manga, mention)
}
