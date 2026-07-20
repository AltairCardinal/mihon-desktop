package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.source.CatalogueSource

enum class GlobalSearchSourceFilter {
    PinnedOnly,
    All,
}

object GlobalSearchSourcePolicy {
    fun select(
        sources: List<CatalogueSource>,
        enabledLanguages: Set<String>,
        hiddenSourceIds: Set<String>,
        pinnedSourceIds: Set<String>,
        filter: GlobalSearchSourceFilter = GlobalSearchSourceFilter.PinnedOnly,
    ): List<CatalogueSource> = sources
        .filter { source ->
            source.lang in enabledLanguages &&
                source.id.toString() !in hiddenSourceIds &&
                (filter == GlobalSearchSourceFilter.All || source.id.toString() in pinnedSourceIds)
        }
        .sortedWith(
            compareBy(
                { source -> source.id.toString() !in pinnedSourceIds },
                { source -> "${source.name.lowercase()} (${source.lang})" },
            ),
        )
}
