package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import mihon.domain.error.AppError

sealed interface SourceQuery {
    data object Popular : SourceQuery
    data object Latest : SourceQuery
    data class Search(
        val query: String,
        val filters: FilterList,
    ) : SourceQuery
}

data class SourcePageRequest(
    val sourceId: Long,
    val page: Int,
    val generation: Long,
    val query: SourceQuery,
)

enum class SourceRecoveryAction {
    OpenLogin,
    Retry,
    None,
}

sealed interface SourcePageResult {
    val request: SourcePageRequest

    data class Content(
        override val request: SourcePageRequest,
        val items: List<SManga>,
        val hasNextPage: Boolean,
    ) : SourcePageResult

    data class Empty(
        override val request: SourcePageRequest,
    ) : SourcePageResult

    data class Failure(
        override val request: SourcePageRequest,
        val error: AppError,
        val recoveryAction: SourceRecoveryAction,
    ) : SourcePageResult
}

data class SourcePageError(
    val error: AppError,
    val recoveryAction: SourceRecoveryAction,
)

sealed interface SourceQueryState {
    val request: SourcePageRequest
    val items: List<SManga>
    val isLoading: Boolean

    data class Loading(
        override val request: SourcePageRequest,
        override val items: List<SManga> = emptyList(),
    ) : SourceQueryState {
        override val isLoading = true
    }

    data class Content(
        override val request: SourcePageRequest,
        override val items: List<SManga>,
        val hasNextPage: Boolean,
        override val isLoading: Boolean = false,
        val pageError: SourcePageError? = null,
    ) : SourceQueryState

    data class Empty(
        override val request: SourcePageRequest,
    ) : SourceQueryState {
        override val items = emptyList<SManga>()
        override val isLoading = false
    }

    data class Failure(
        override val request: SourcePageRequest,
        val error: AppError,
        val recoveryAction: SourceRecoveryAction,
    ) : SourceQueryState {
        override val items = emptyList<SManga>()
        override val isLoading = false
    }
}

class SourceQueryReducer {
    fun start(
        request: SourcePageRequest,
        previous: SourceQueryState? = null,
    ): SourceQueryState {
        return if (request.page > 1 && previous is SourceQueryState.Content &&
            previous.request.generation == request.generation
        ) {
            previous.copy(request = request, isLoading = true, pageError = null)
        } else {
            SourceQueryState.Loading(request)
        }
    }

    fun reduce(
        current: SourceQueryState,
        result: SourcePageResult,
    ): SourceQueryState {
        if (result.request.sourceId != current.request.sourceId ||
            result.request.generation != current.request.generation ||
            result.request.page != current.request.page
        ) {
            return current
        }

        return when (result) {
            is SourcePageResult.Content -> SourceQueryState.Content(
                request = result.request,
                items = (current.items + result.items).distinctBy { it.url },
                hasNextPage = result.hasNextPage,
            )
            is SourcePageResult.Empty -> if (current.items.isEmpty()) {
                SourceQueryState.Empty(result.request)
            } else {
                SourceQueryState.Content(
                    request = result.request,
                    items = current.items,
                    hasNextPage = false,
                    pageError = SourcePageError(AppError.NoResults, SourceRecoveryAction.Retry),
                )
            }
            is SourcePageResult.Failure -> if (current.items.isEmpty()) {
                SourceQueryState.Failure(result.request, result.error, result.recoveryAction)
            } else {
                SourceQueryState.Content(
                    request = result.request,
                    items = current.items,
                    hasNextPage = false,
                    pageError = SourcePageError(result.error, result.recoveryAction),
                )
            }
        }
    }
}
