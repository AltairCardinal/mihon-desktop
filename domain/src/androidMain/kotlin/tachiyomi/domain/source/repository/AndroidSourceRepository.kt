package tachiyomi.domain.source.repository

import androidx.paging.PagingSource
import eu.kanade.tachiyomi.source.model.FilterList
import tachiyomi.domain.manga.model.Manga

typealias SourcePagingSource = PagingSource<Long, Manga>

interface AndroidSourceRepository : SourceRepository {

    fun search(sourceId: Long, query: String, filterList: FilterList): SourcePagingSource

    fun getPopular(sourceId: Long): SourcePagingSource

    fun getLatest(sourceId: Long): SourcePagingSource
}
