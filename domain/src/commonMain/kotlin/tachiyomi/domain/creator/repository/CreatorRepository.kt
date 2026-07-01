package tachiyomi.domain.creator.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.creator.model.CanonicalWork
import tachiyomi.domain.creator.model.Creator
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.model.CreatorWatch
import tachiyomi.domain.creator.model.DiscoveryCandidate
import tachiyomi.domain.creator.model.DiscoveryCandidateCreator
import tachiyomi.domain.creator.model.DiscoveryCandidateState
import tachiyomi.domain.creator.model.MangaCreator
import tachiyomi.domain.creator.model.MangaWorkMatch
import tachiyomi.domain.creator.model.WorkMatchState

interface CreatorRepository {

    suspend fun upsertCreator(displayName: String, aliases: List<String> = emptyList()): Creator

    suspend fun getCreator(id: Long): Creator?

    fun getCreatorsAsFlow(): Flow<List<Creator>>

    suspend fun linkMangaCreator(
        mangaId: Long,
        creatorId: Long,
        role: CreatorRole,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    )

    suspend fun linkDiscoveryCandidateCreator(
        candidateId: Long,
        creatorId: Long,
        role: CreatorRole,
        sourceText: String?,
        confidence: Double,
        evidence: String,
    )

    suspend fun followCreator(
        creatorId: Long,
        sourceIds: List<Long> = emptyList(),
        languageTags: List<String> = emptyList(),
    ): CreatorWatch

    suspend fun unfollowCreator(creatorId: Long)

    suspend fun getFollowedCreators(): List<CreatorWatch>

    fun getFollowedCreatorsAsFlow(): Flow<List<CreatorWatch>>

    suspend fun updateWatchCheckResult(creatorId: Long, checkedAt: Long, success: Boolean, error: String?)

    suspend fun upsertDiscoveryCandidate(
        source: Long,
        url: String,
        title: String,
        authorText: String?,
        artistText: String?,
        languageTag: String,
        languageConfidence: Double,
        languageEvidence: String,
        thumbnailUrl: String?,
        detailsFetchedAt: Long?,
        state: DiscoveryCandidateState = DiscoveryCandidateState.NEW,
    ): DiscoveryCandidate

    suspend fun getDiscoveryCandidatesForCreator(creatorId: Long): List<DiscoveryCandidate>

    suspend fun getDiscoveryCandidate(id: Long): DiscoveryCandidate?

    suspend fun getMangaCreatorsForCreator(creatorId: Long): List<MangaCreator>

    suspend fun getDiscoveryCandidateCreatorsForCreator(creatorId: Long): List<DiscoveryCandidateCreator>

    suspend fun createCanonicalWork(
        primaryTitle: String,
        primaryCreatorId: Long?,
        originalLanguage: String?,
    ): CanonicalWork

    suspend fun upsertMangaWorkMatch(
        mangaId: Long,
        workId: Long,
        confidence: Double,
        matchReason: String,
        state: WorkMatchState,
        manuallyConfirmed: Boolean,
    ): MangaWorkMatch
}
