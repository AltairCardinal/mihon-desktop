package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga

class SourceMangaSearchService {

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

sealed interface SourceMangaSearchRequest {
    data class Search(
        val query: String,
        val filters: FilterList,
    ) : SourceMangaSearchRequest

    data object Popular : SourceMangaSearchRequest
    data object Latest : SourceMangaSearchRequest
}
