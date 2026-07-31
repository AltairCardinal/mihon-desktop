package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import mihon.domain.error.AppError

class SourceMangaSearchService {

    suspend fun loadPageResult(
        source: CatalogueSource,
        request: SourcePageRequest,
    ): SourcePageResult {
        return try {
            val page = loadPage(source, request.page, request.query.toSearchRequest())
            if (page.mangas.isEmpty()) {
                SourcePageResult.Empty(request)
            } else {
                SourcePageResult.Content(request, page.mangas, page.hasNextPage)
            }
        } catch (error: CancellationException) {
            SourcePageResult.Failure(request, AppError.Cancelled, SourceRecoveryAction.None)
        } catch (error: Exception) {
            val appError = error.toSourceAppError()
            SourcePageResult.Failure(
                request,
                appError,
                appError.recoveryAction(),
            )
        }
    }

    suspend fun loadPage(
        source: CatalogueSource,
        page: Int,
        request: SourceMangaSearchRequest,
    ): MangasPage {
        return when (request) {
            is SourceMangaSearchRequest.Search -> source.getSearchManga(page, request.query, request.filters)
            SourceMangaSearchRequest.Latest -> source.getLatestUpdates(page)
            SourceMangaSearchRequest.Popular -> source.getPopularManga(page)
        }
    }

    suspend fun searchAllPages(
        source: CatalogueSource,
        query: String,
        filters: FilterList = source.getFilterList(),
    ): List<SManga> {
        val results = mutableListOf<SManga>()
        var page = 1
        do {
            val mangasPage = loadPage(
                source = source,
                page = page,
                request = SourceMangaSearchRequest.Search(query, filters),
            )
            results += mangasPage.mangas
            page += 1
        } while (mangasPage.hasNextPage)
        return results
    }
}

private fun SourceQuery.toSearchRequest(): SourceMangaSearchRequest = when (this) {
    SourceQuery.Popular -> SourceMangaSearchRequest.Popular
    SourceQuery.Latest -> SourceMangaSearchRequest.Latest
    is SourceQuery.Search -> SourceMangaSearchRequest.Search(query, filters)
}

private fun AppError.recoveryAction(): SourceRecoveryAction = when (this) {
    is AppError.Authentication -> SourceRecoveryAction.OpenLogin
    AppError.Cancelled -> SourceRecoveryAction.None
    else -> SourceRecoveryAction.Retry
}

sealed interface SourceMangaSearchRequest {
    data class Search(
        val query: String,
        val filters: FilterList,
    ) : SourceMangaSearchRequest

    data object Popular : SourceMangaSearchRequest
    data object Latest : SourceMangaSearchRequest
}
