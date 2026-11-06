package tachiyomi.data.source

import androidx.paging.PagingState
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.guardSourceOperation
import eu.kanade.tachiyomi.network.rethrowSourceControlFailure
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.repository.SourcePagingSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SourceSearchPagingSource(
    source: Source,
    private val query: String,
    private val filters: FilterList,
    registeredSourceId: Long?,
) : BaseSourcePagingSource(source, registeredSourceId = registeredSourceId, phase = SourceOperationPhase.SEARCH) {
    constructor(source: Source, query: String, filters: FilterList) : this(source, query, filters, null)
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getSearchManga(currentPage, query, filters)
    }
}

class SourcePopularPagingSource(source: Source, registeredSourceId: Long?) :
    BaseSourcePagingSource(source, registeredSourceId = registeredSourceId, phase = SourceOperationPhase.POPULAR) {
    constructor(source: Source) : this(source, null)
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getPopularManga(currentPage)
    }
}

class SourceLatestPagingSource(source: Source, registeredSourceId: Long?) :
    BaseSourcePagingSource(source, registeredSourceId = registeredSourceId, phase = SourceOperationPhase.LATEST) {
    constructor(source: Source) : this(source, null)
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getLatestUpdates(currentPage)
    }
}

abstract class BaseSourcePagingSource(
    protected val source: Source,
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val registeredSourceId: Long?,
    private val phase: SourceOperationPhase,
) : SourcePagingSource() {
    constructor(source: Source, networkToLocalManga: NetworkToLocalManga = Injekt.get()) :
        this(source, networkToLocalManga, null, SourceOperationPhase.METADATA)

    private val seenManga = hashSetOf<String>()

    abstract suspend fun requestNextPage(currentPage: Int): MangasPage

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Manga> {
        val page = params.key ?: 1

        return try {
            // Only legacy standalone callers lack a host registration; do not probe the ID per item.
            val sourceId = registeredSourceId ?: guardSourceOperation(SourceOperationPhase.METADATA) { source.id }
            val mangasPage = withIOContext {
                guardSourceOperation(phase) { requestNextPage(page.toInt()) }
                    .takeIf { it.mangas.isNotEmpty() }
                    ?: throw NoResultsException()
            }

            val manga = mangasPage.mangas
                .map { it.toDomainManga(sourceId, phase) }
                .filter { seenManga.add(it.url) }
                .let { networkToLocalManga(it) }

            LoadResult.Page(
                data = manga,
                prevKey = null,
                nextKey = if (mangasPage.hasNextPage) page + 1 else null,
            )
        } catch (e: Exception) {
            e.rethrowSourceControlFailure()
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Long, Manga>): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey ?: anchorPage?.nextKey
        }
    }
}

class NoResultsException : Exception()
