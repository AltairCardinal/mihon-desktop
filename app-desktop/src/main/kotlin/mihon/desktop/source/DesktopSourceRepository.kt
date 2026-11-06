package mihon.desktop.source

import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.guardSourceOperation
import eu.kanade.tachiyomi.network.inspectSourceFailure
import eu.kanade.tachiyomi.network.rethrowSourceControlFailure
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import mihon.domain.error.AppError
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.source.model.SourceWithCount
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.prepareSourceMetadata
import tachiyomi.domain.source.service.recordSourceFailureDiagnostics
import tachiyomi.domain.source.service.toSourceAppError
import tachiyomi.domain.source.service.withSourceFailureIdentity
import tachiyomi.domain.source.model.Source as DomainSource

class DesktopSourceRepository(
    private val sourceManager: SourceManager,
    private val handler: DatabaseHandler,
    private val unknownName: () -> String = { "Unknown" },
    private val failureIdentitySnapshot: () -> List<Pair<Source, eu.kanade.tachiyomi.network.SourceFailureIdentity>> = {
        emptyList()
    },
    private val diagnosticWriter: (String) -> Unit = { System.err.println("MihonExtension: $it") },
) : SourceRepository {

    override fun getSources(): Flow<List<DomainSource>> =
        sourceManager.querySources.map { sources ->
            val identities = failureIdentitySnapshot()
            sources.mapNotNull { source ->
                sourceManager.getRegisteredSourceId(source)?.let { id ->
                    mapSourceToDomainSource(source, id, identities, readLatest = true)
                }
            }
        }

    override fun getOnlineSources(): Flow<List<DomainSource>> =
        sourceManager.querySources.map { sources ->
            val identities = failureIdentitySnapshot()
            sources.filterIsInstance<HttpSource>().mapNotNull { source ->
                sourceManager.getRegisteredSourceId(source)?.let { id ->
                    mapSourceToDomainSource(source, id, identities)
                }
            }
        }

    override fun getSourcesWithFavoriteCount(): Flow<List<Pair<DomainSource, Long>>> =
        combine(
            handler.subscribeToList { mangasQueries.getSourceIdWithFavoriteCount() },
            sourceManager.querySources,
        ) { counts, _ -> counts }
            .map { counts ->
                val identities = failureIdentitySnapshot()
                counts.map { (sourceId, count) ->
                    val source = sourceManager.getOrStub(sourceId)
                    mapSourceToDomainSource(source, sourceId, identities) to count
                }
            }

    override fun getSourcesWithNonLibraryManga(): Flow<List<SourceWithCount>> =
        handler.subscribeToList { mangasQueries.getSourceIdsWithNonLibraryManga() }
            .map { rows ->
                val identities = failureIdentitySnapshot()
                rows.map { (sourceId, count) ->
                    val source = sourceManager.getOrStub(sourceId)
                    SourceWithCount(
                        mapSourceToDomainSource(source, sourceId, identities),
                        count,
                    )
                }
            }

    private fun mapSourceToDomainSource(
        source: Source,
        sourceId: Long,
        identities: List<Pair<Source, eu.kanade.tachiyomi.network.SourceFailureIdentity>>,
        readLatest: Boolean = false,
    ): DomainSource {
        val snapshot = prepareSourceMetadata(source, sourceId)
        var failure = snapshot.error
        if (failure != null && failure !is AppError.ExtensionIncompatible) throw requireNotNull(failure.cause)
        val latest = if (readLatest && failure == null) {
            try {
                guardSourceOperation(SourceOperationPhase.METADATA) { source.supportsLatest }
            } catch (error: Exception) {
                error.rethrowSourceControlFailure()
                if (error.inspectSourceFailure().causes.none { it is SourceCompatibilityException }) throw error
                failure = error.toSourceAppError()
                false
            }
        } else {
            false
        }
        val result = DomainSource(
            id = sourceId,
            lang = snapshot.language.orEmpty(),
            name = snapshot.name ?: unknownName(),
            supportsLatest = latest,
            isStub = source is StubSource,
        )
        failure?.withSourceFailureIdentity(
            identities.firstOrNull { it.first === source }?.second?.copy(sourceId = sourceId)
                ?: eu.kanade.tachiyomi.network.SourceFailureIdentity(sourceId = sourceId),
        )?.recordSourceFailureDiagnostics(diagnosticWriter)
        return result
    }
}
