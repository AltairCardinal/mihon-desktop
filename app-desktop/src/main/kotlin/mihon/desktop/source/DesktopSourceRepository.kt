package mihon.desktop.source

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.source.model.SourceWithCount
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.model.Source as DomainSource

class DesktopSourceRepository(
    private val sourceManager: SourceManager,
    private val handler: DatabaseHandler,
) : SourceRepository {

    override fun getSources(): Flow<List<DomainSource>> =
        sourceManager.catalogueSources.map { sources ->
            sources.map {
                mapSourceToDomainSource(it).copy(supportsLatest = it.supportsLatest)
            }
        }

    override fun getOnlineSources(): Flow<List<DomainSource>> =
        sourceManager.catalogueSources.map { sources ->
            sources.filterIsInstance<HttpSource>().map(::mapSourceToDomainSource)
        }

    override fun getSourcesWithFavoriteCount(): Flow<List<Pair<DomainSource, Long>>> =
        combine(
            handler.subscribeToList { mangasQueries.getSourceIdWithFavoriteCount() },
            sourceManager.catalogueSources,
        ) { counts, _ -> counts }
            .map { counts ->
                counts.map { (sourceId, count) ->
                    val source = sourceManager.getOrStub(sourceId)
                    mapSourceToDomainSource(source).copy(isStub = source is StubSource) to count
                }
            }

    override fun getSourcesWithNonLibraryManga(): Flow<List<SourceWithCount>> =
        handler.subscribeToList { mangasQueries.getSourceIdsWithNonLibraryManga() }
            .map { rows ->
                rows.map { (sourceId, count) ->
                    val source = sourceManager.getOrStub(sourceId)
                    SourceWithCount(
                        mapSourceToDomainSource(source).copy(isStub = source is StubSource),
                        count,
                    )
                }
            }

    private fun mapSourceToDomainSource(source: Source): DomainSource = DomainSource(
        id = source.id,
        lang = source.lang,
        name = source.name,
        supportsLatest = false,
        isStub = false,
    )
}
