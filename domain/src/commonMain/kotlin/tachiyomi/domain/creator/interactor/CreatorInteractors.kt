package tachiyomi.domain.creator.interactor

import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.CreatorWatch
import tachiyomi.domain.creator.model.DiscoveryCandidate
import tachiyomi.domain.creator.model.MangaCreator
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.creator.service.CreatorDiscoveryService

class GetCreators(
    private val repository: CreatorRepository,
) {
    fun subscribe(): Flow<List<Creator>> = repository.getCreatorsAsFlow()

    fun subscribeFollowed(): Flow<List<CreatorWatch>> = repository.getFollowedCreatorsAsFlow()
}

data class CreatorDetails(
    val creator: Creator?,
    val candidates: List<DiscoveryCandidate>,
    val mangaLinks: List<MangaCreator>,
)

class GetCreatorDetails(
    private val repository: CreatorRepository,
) {
    suspend fun await(creatorId: Long): CreatorDetails = CreatorDetails(
        creator = repository.getCreator(creatorId),
        candidates = repository.getDiscoveryCandidatesForCreator(creatorId),
        mangaLinks = repository.getMangaCreatorsForCreator(creatorId),
    )

    suspend fun awaitCandidate(candidateId: Long): DiscoveryCandidate? =
        repository.getDiscoveryCandidate(candidateId)
}

class SetCreatorFollow(
    private val repository: CreatorRepository,
) {
    suspend fun await(creatorId: Long, followed: Boolean) {
        if (followed) {
            repository.followCreator(creatorId)
        } else {
            repository.unfollowCreator(creatorId)
        }
    }
}

class DiscoverCreatorWorks(
    private val discoveryService: CreatorDiscoveryService,
    private val getCreatorDetails: GetCreatorDetails,
) {
    suspend fun await(creatorId: Long, sources: List<CatalogueSource>): CreatorDetails {
        discoveryService.discoverCreator(creatorId, sources)
        return getCreatorDetails.await(creatorId)
    }
}
