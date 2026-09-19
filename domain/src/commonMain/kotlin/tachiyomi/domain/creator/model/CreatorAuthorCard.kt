package tachiyomi.domain.creator.model

data class CreatorCoverRequest(
    val sourceWorkId: Long,
    val mangaId: Long?,
    val sourceId: Long,
    val url: String?,
    val lastModifiedAt: Long,
)

data class CreatorCardWorkCandidate(
    val workKey: String,
    val sourceWorkId: Long,
    val naturalKey: SourceWorkNaturalKey,
    val title: String,
    val coverRequest: CreatorCoverRequest,
    val inLibrary: Boolean,
    val hasCustomCover: Boolean = false,
    val lastReadAt: Long?,
    val relationVerification: CreatorRelationVerification,
    val decisionState: WorkDecisionState? = null,
    val sourceLanguage: String? = null,
)

data class CreatorCardProjection(
    val creator: Creator,
    val followed: Boolean,
    val uniqueWorkCount: Int,
    val unreadWorkCount: Int = 0,
    val representativeWorks: List<CreatorCardWorkCandidate> = emptyList(),
)

data class CreatorSelectedWorkKey(
    val workKey: String,
    val naturalKey: SourceWorkNaturalKey,
)

data class CreatorRepresentativeWorkCache(
    val strategyVersion: Int,
    val selected: List<CreatorSelectedWorkKey>,
)

data class CreatorRepresentativeWorkSelection(
    val representatives: List<CreatorCardWorkCandidate>,
    val cache: CreatorRepresentativeWorkCache,
)

data class CreatorCardProjectionPage(
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
    val creators: List<CreatorCardProjection>,
)
