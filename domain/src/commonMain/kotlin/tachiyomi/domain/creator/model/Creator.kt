package tachiyomi.domain.creator.model

data class Creator(
    val id: Long,
    val displayName: String,
    val normalizedName: String,
    val sortName: String?,
    val aliases: List<String>,
    val createdAt: Long,
    val lastModifiedAt: Long,
)

enum class CreatorRole {
    AUTHOR,
    ARTIST,
    BOTH,
    UNKNOWN,
}

data class MangaCreator(
    val mangaId: Long,
    val creatorId: Long,
    val role: CreatorRole,
    val sourceText: String?,
    val confidence: Double,
    val evidence: String,
)

data class DiscoveryCandidateCreator(
    val candidateId: Long,
    val creatorId: Long,
    val role: CreatorRole,
    val sourceText: String?,
    val confidence: Double,
    val evidence: String,
)

data class CreatorWatch(
    val creatorId: Long,
    val enabled: Boolean,
    val sourceIds: List<Long>,
    val languageTags: List<String>,
    val lastCheckedAt: Long?,
    val lastSuccessAt: Long?,
    val lastError: String?,
    val createdAt: Long,
)

data class CanonicalWork(
    val id: Long,
    val primaryTitle: String,
    val normalizedTitle: String,
    val primaryCreatorId: Long?,
    val originalLanguage: String?,
    val createdAt: Long,
    val lastModifiedAt: Long,
)

enum class WorkMatchState {
    CANDIDATE,
    CONFIRMED,
    REJECTED,
}

data class MangaWorkMatch(
    val mangaId: Long,
    val workId: Long,
    val confidence: Double,
    val matchReason: String,
    val state: WorkMatchState,
    val manuallyConfirmed: Boolean,
    val createdAt: Long,
    val lastModifiedAt: Long,
)

enum class DiscoveryCandidateState {
    NEW,
    ACCEPTED,
    IGNORED,
    MERGED,
}

data class DiscoveryCandidate(
    val id: Long,
    val source: Long,
    val url: String,
    val title: String,
    val normalizedTitle: String,
    val authorText: String?,
    val artistText: String?,
    val languageTag: String,
    val languageConfidence: Double,
    val languageEvidence: String,
    val thumbnailUrl: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val detailsFetchedAt: Long?,
    val state: DiscoveryCandidateState,
)
