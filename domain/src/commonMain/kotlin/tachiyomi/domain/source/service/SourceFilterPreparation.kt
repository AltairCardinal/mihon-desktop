package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.guardSourceOperation
import eu.kanade.tachiyomi.network.rethrowSourceControlFailure
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException

sealed interface SourceFilterResult {
    data class Content(val filters: FilterList) : SourceFilterResult
    data class Failure(val error: AppError) : SourceFilterResult
}

fun prepareSourceFilters(source: Source): SourceFilterResult = try {
    SourceFilterResult.Content(guardSourceOperation(SourceOperationPhase.FILTERS) { source.getFilterList() })
} catch (error: Exception) {
    error.rethrowSourceControlFailure()
    SourceFilterResult.Failure(error.toSourceAppError())
}

internal fun preparedSourceFiltersOrThrow(source: Source): FilterList = when (
    val result = prepareSourceFilters(
        source,
    )
) {
    is SourceFilterResult.Content -> result.filters
    is SourceFilterResult.Failure -> throw AppErrorException(result.error)
}
