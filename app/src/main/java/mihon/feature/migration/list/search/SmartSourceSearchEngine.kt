package mihon.feature.migration.list.search

import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.guardSourceOperation
import eu.kanade.tachiyomi.network.rethrowSourceControlFailure
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.manga.model.Manga

class SmartSourceSearchEngine(extraSearchParams: String?) : BaseSmartSearchEngine<SManga>(extraSearchParams) {

    override fun getTitle(result: SManga) = guardSourceOperation(SourceOperationPhase.SEARCH) { result.title }

    suspend fun regularSearch(source: CatalogueSource, title: String): Manga? {
        val id = preserveControl { guardSourceOperation(SourceOperationPhase.METADATA) { source.id } }
        return regularSearch(source, id, title)
    }

    suspend fun deepSearch(source: CatalogueSource, title: String): Manga? {
        val id = preserveControl { guardSourceOperation(SourceOperationPhase.METADATA) { source.id } }
        return deepSearch(source, id, title)
    }

    suspend fun regularSearch(source: CatalogueSource, sourceId: Long, title: String): Manga? =
        preserveControl {
            regularSearch(makeSearchAction(source), title)?.toDomainManga(sourceId, SourceOperationPhase.SEARCH)
        }

    suspend fun deepSearch(source: CatalogueSource, sourceId: Long, title: String): Manga? =
        preserveControl {
            deepSearch(makeSearchAction(source), title)?.toDomainManga(sourceId, SourceOperationPhase.SEARCH)
        }

    private suspend inline fun <T> preserveControl(block: () -> T): T = try {
        block()
    } catch (error: Exception) {
        error.rethrowSourceControlFailure()
        throw error
    }

    private fun makeSearchAction(source: CatalogueSource): SearchAction<SManga> = { query ->
        val filters = guardSourceOperation(SourceOperationPhase.FILTERS) { source.getFilterList() }
        guardSourceOperation(SourceOperationPhase.SEARCH) { source.getSearchManga(1, query, filters) }.mangas
    }
}
