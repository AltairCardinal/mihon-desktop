package eu.kanade.tachiyomi.ui.browse.source.globalsearch

class GlobalSearchScreenModel(
    initialQuery: String = "",
    initialExtensionFilter: String? = null,
) : SearchScreenModel(State(searchQuery = initialQuery)) {

    init {
        extensionFilter = initialExtensionFilter
        if (initialQuery.isNotBlank() || !initialExtensionFilter.isNullOrBlank()) {
            if (extensionFilter != null) {
                // we're going to use custom extension filter instead
                setSourceFilter(SourceFilter.All)
            }
            search()
        }
    }

    override fun isSourceIdEnabled(sourceId: Long?): Boolean = super.isSourceIdEnabled(sourceId) &&
        (
            state.value.sourceFilter != SourceFilter.PinnedOnly || sourceId == null ||
                sourceId.toString() in pinnedSources
            )
}
