package eu.kanade.tachiyomi.ui.browse.migration.search

import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchItemResult
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchScreenModel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMetadataSnapshot
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MigrateSearchScreenModel(
    val mangaId: Long,
    getManga: GetManga = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
) : SearchScreenModel(sourceManager = sourceManager) {

    private val migrationSources by lazy { sourcePreferences.migrationSources().get() }

    override fun sortComparator(
        map: Map<Source, SearchItemResult>,
        metadata: List<SourceMetadataSnapshot>,
    ): Comparator<Source> = compareBy<Source>(
        { (map[it] as? SearchItemResult.Success)?.isEmpty ?: true },
        { source ->
            metadata.firstOrNull { it.source === source }?.sourceId?.let(migrationSources::indexOf)
                ?: Int.MAX_VALUE
        },
    )
        .thenBy { source -> metadata.indexOfFirst { it.source === source } }

    init {
        screenModelScope.launch {
            val manga = getManga.await(mangaId)!!
            mutableState.update {
                it.copy(
                    from = manga,
                    searchQuery = manga.title,
                )
            }
            search()
        }
    }

    override fun sourceCandidates(): List<Source> {
        return migrationSources.mapNotNull(sourceManager::get)
    }

    override fun isSourceEnabled(metadata: SourceMetadataSnapshot): Boolean = true

    override fun isSourceIdEnabled(sourceId: Long?): Boolean = true

    override fun isLanguageEnabled(language: String): Boolean = true
}
