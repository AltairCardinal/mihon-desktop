package mihon.domain.extension.suggestion

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import mihon.domain.extension.model.ExtensionCatalogResult
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.service.SourceManager

/** Both platform screen models observe the real library query and registration authority here. */
class ObserveExtensionSuggestions(
    private val repository: SourceRepository,
    private val sourceManager: SourceManager,
) {
    fun subscribe(
        catalog: Flow<ExtensionCatalogResult?>,
        inventory: Flow<ExtensionInventory>,
        showNsfw: Flow<Boolean>,
        ignored: Flow<Set<SuggestionIdentity>> = flowOf(emptySet()),
    ): Flow<ExtensionSuggestions> {
        val engine = ExtensionSuggestionEngine()
        val registered = combine(sourceManager.isInitialized, sourceManager.querySources) { ready, sources ->
            if (ready) sources.mapTo(mutableSetOf()) { it.id } else null
        }
        val options = combine(showNsfw, ignored) { nsfw, ignoredIdentities -> nsfw to ignoredIdentities }
        return combine(repository.getSourcesWithFavoriteCount(), registered, catalog, inventory, options) {
                counts,
                sourceIds,
                snapshot,
                installed,
                display,
            ->
            if (sourceIds == null) {
                ExtensionSuggestions()
            } else {
                engine.project(
                    counts.map { (source, count) -> LibrarySourceCount(source.id, count) },
                    sourceIds,
                    snapshot,
                    installed,
                    display.second,
                    display.first,
                )
            }
        }
    }
}
