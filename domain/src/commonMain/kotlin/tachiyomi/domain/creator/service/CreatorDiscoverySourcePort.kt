package tachiyomi.domain.creator.service

import tachiyomi.domain.creator.model.SourceWorkNaturalKey

enum class CreatorSourceCapability {
    AUTHOR_SEARCH,
    CATALOGUE_SEARCH_FALLBACK,
    DETAILS,
    STRUCTURED_READING_LANGUAGE,
    STRUCTURED_ORIGINAL_LANGUAGE,
}

data class EnabledCreatorSource(
    val sourceId: Long,
    val displayName: String,
    val capabilities: Set<CreatorSourceCapability>,
)

data class BoundedAuthorSearchPageRequest(
    val sourceId: Long,
    val alias: String,
    val page: Int,
    val pageLimit: Int,
    val deadlineAtMillis: Long,
) {
    init {
        require(alias.isNotBlank())
        require(page > 0)
        require(pageLimit > 0)
        require(page <= pageLimit)
        require(deadlineAtMillis > 0)
    }
}

data class CreatorSourceWorkSnapshot(
    val key: SourceWorkNaturalKey,
    val title: String,
    val authorText: String?,
    val artistText: String?,
    val thumbnailUrl: String?,
)

sealed interface CreatorSourcePageResult {
    data class Content(
        val works: List<CreatorSourceWorkSnapshot>,
        val hasNextPage: Boolean,
    ) : CreatorSourcePageResult

    data object Empty : CreatorSourcePageResult

    data class Failure(val error: CreatorSourceFailure) : CreatorSourcePageResult
}

data class CreatorSourceDetails(
    val work: CreatorSourceWorkSnapshot,
    val readingLanguageTag: String?,
    val originalLanguageTag: String?,
    val metadata: Map<String, String>,
)

sealed interface CreatorSourceDetailsResult {
    data class Content(val details: CreatorSourceDetails) : CreatorSourceDetailsResult

    data class Failure(val error: CreatorSourceFailure) : CreatorSourceDetailsResult
}

sealed interface CreatorSourceFailure {
    data class AuthenticationRequired(val loginUrl: String?) : CreatorSourceFailure

    data class RateLimited(val retryAfterMillis: Long?) : CreatorSourceFailure

    data class Http(val statusCode: Int) : CreatorSourceFailure

    data object Timeout : CreatorSourceFailure

    data class Network(val safeMessage: String?) : CreatorSourceFailure

    data class MalformedResponse(val safeMessage: String?) : CreatorSourceFailure

    data object MissingSource : CreatorSourceFailure

    data object UnsupportedCapability : CreatorSourceFailure
}

/**
 * Narrow source boundary for author discovery. Implementations must let
 * [kotlinx.coroutines.CancellationException] propagate instead of converting it into a failure value.
 * Query reduction, total page/concurrency budgets, leases, and user decisions belong to the orchestrator.
 */
interface CreatorDiscoverySourcePort {
    suspend fun enabledSourcesSnapshot(): List<EnabledCreatorSource>

    suspend fun searchPage(request: BoundedAuthorSearchPageRequest): CreatorSourcePageResult

    suspend fun loadDetails(key: SourceWorkNaturalKey): CreatorSourceDetailsResult
}
