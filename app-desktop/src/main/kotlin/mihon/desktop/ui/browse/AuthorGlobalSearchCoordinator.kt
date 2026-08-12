package mihon.desktop.ui.browse

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tachiyomi.domain.creator.model.CreatorRelationVerification
import tachiyomi.domain.creator.service.BoundedAuthorSearchPageRequest
import tachiyomi.domain.creator.service.CreatorDiscoverySourcePort
import tachiyomi.domain.creator.service.CreatorIdentityEvidence
import tachiyomi.domain.creator.service.CreatorIdentityEvidenceEvaluator
import tachiyomi.domain.creator.service.CreatorSourceFailure
import tachiyomi.domain.creator.service.CreatorSourcePageResult
import tachiyomi.domain.creator.service.CreatorSourceWorkSnapshot
import tachiyomi.domain.creator.service.EnabledCreatorSource

enum class GlobalSearchMode { MANGA, AUTHOR }

enum class AuthorIdentityFilter { ALL, VERIFIED, POSSIBLE }

data class AuthorGlobalSearchItem(
    val work: CreatorSourceWorkSnapshot,
    val identity: CreatorIdentityEvidence,
)

data class AuthorGlobalSearchSourceResult(
    val source: EnabledCreatorSource,
    val loading: Boolean = true,
    val items: List<AuthorGlobalSearchItem> = emptyList(),
    val failure: CreatorSourceFailure? = null,
)

data class AuthorGlobalSearchState(
    val query: String = "",
    val rows: List<AuthorGlobalSearchSourceResult> = emptyList(),
) {
    val searching: Boolean get() = rows.any(AuthorGlobalSearchSourceResult::loading)
    val verifiedCount: Int
        get() = rows.sumOf { row ->
            row.items.count { it.identity.verification == CreatorRelationVerification.VERIFIED }
        }
    val possibleCount: Int
        get() = rows.sumOf { row ->
            row.items.count { it.identity.verification == CreatorRelationVerification.POSSIBLE }
        }
}

class AuthorGlobalSearchCoordinator(
    private val sourcePort: CreatorDiscoverySourcePort,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(AuthorGlobalSearchState())
    val state: StateFlow<AuthorGlobalSearchState> = mutableState.asStateFlow()

    suspend fun search(query: String) = coroutineScope {
        val alias = query.trim()
        if (alias.isEmpty()) return@coroutineScope
        val sources = sourcePort.enabledSourcesSnapshot()
        mutableState.value = AuthorGlobalSearchState(alias, sources.map(::AuthorGlobalSearchSourceResult))
        sources.map { source ->
            async {
                val result = sourcePort.searchPage(
                    BoundedAuthorSearchPageRequest(
                        sourceId = source.sourceId,
                        alias = alias,
                        page = 1,
                        pageLimit = 1,
                        deadlineAtMillis = clock() + SEARCH_TIMEOUT_MILLIS,
                    ),
                )
                val row = when (result) {
                    is CreatorSourcePageResult.Content -> AuthorGlobalSearchSourceResult(
                        source = source,
                        loading = false,
                        items = result.works.distinctBy { it.key }.map { work ->
                            AuthorGlobalSearchItem(work, CreatorIdentityEvidenceEvaluator.evaluate(listOf(alias), work))
                        },
                    )
                    CreatorSourcePageResult.Empty -> AuthorGlobalSearchSourceResult(source, loading = false)
                    is CreatorSourcePageResult.Failure -> AuthorGlobalSearchSourceResult(
                        source,
                        loading = false,
                        failure = result.error,
                    )
                }
                mutableState.update { current ->
                    current.copy(rows = current.rows.map { if (it.source.sourceId == source.sourceId) row else it })
                }
            }
        }.awaitAll()
    }

    private companion object {
        const val SEARCH_TIMEOUT_MILLIS = 15_000L
    }
}
