package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.guardSourceOperation
import eu.kanade.tachiyomi.network.rethrowSourceControlFailure
import eu.kanade.tachiyomi.source.Source
import tachiyomi.domain.source.service.GlobalSearchSourceFilter
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.SourceMetadataSnapshot
import tachiyomi.domain.source.service.prepareSourceMetadata
import tachiyomi.domain.source.service.toSourceAppError

/** Compatibility entry point without a registry: capture the ID once, never while rendering. */
internal fun prepareLegacySearchSource(source: Source): SourceMetadataSnapshot = try {
    prepareSourceMetadata(source, guardSourceOperation(SourceOperationPhase.METADATA) { source.id })
} catch (error: Exception) {
    error.rethrowSourceControlFailure()
    SourceMetadataSnapshot(source, null, null, null, error.toSourceAppError())
}

internal fun prepareGlobalSearchSources(
    manager: SourceManager,
    enabledLanguages: Set<String>,
    hiddenIds: Set<String>,
    pinnedIds: Set<String>,
    filter: GlobalSearchSourceFilter,
): List<SourceMetadataSnapshot> = manager.getQuerySources().mapNotNull { source ->
    val id = manager.getRegisteredSourceId(source)
    if (id != null && (
            id.toString() in hiddenIds ||
                (filter == GlobalSearchSourceFilter.PinnedOnly && id.toString() !in pinnedIds)
            )
    ) {
        return@mapNotNull null
    }
    prepareSourceMetadata(source, id) { it in enabledLanguages }
        .takeIf { it.language == null || it.language in enabledLanguages }
}.sortedWith(
    compareBy(
        { it.sourceId?.toString() !in pinnedIds },
        { "${it.name.orEmpty().lowercase()} (${it.language.orEmpty()})" },
    ),
)
