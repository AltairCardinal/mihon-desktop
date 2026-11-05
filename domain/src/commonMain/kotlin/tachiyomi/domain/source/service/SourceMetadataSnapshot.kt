package tachiyomi.domain.source.service

import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.guardSourceOperation
import eu.kanade.tachiyomi.network.rethrowSourceControlFailure
import eu.kanade.tachiyomi.source.Source
import mihon.domain.error.AppError

/** One operation's host ID and guarded presentation values, keeping the original Source reference. */
class SourceMetadataSnapshot(
    val source: Source,
    val sourceId: Long?,
    val name: String?,
    val language: String?,
    val error: AppError?,
)

/** The host policy is evaluated from a guarded language before unneeded display metadata. */
fun prepareSourceMetadata(
    source: Source,
    registeredSourceId: Long?,
    languageEligibility: (String) -> Boolean,
): SourceMetadataSnapshot = readSourceMetadata(source, registeredSourceId, languageEligibility)

/** A missing registration stays unknown; this function never calls the extension's ID getter. */
fun prepareSourceMetadata(source: Source, registeredSourceId: Long?): SourceMetadataSnapshot =
    readSourceMetadata(source, registeredSourceId, null)

private fun readSourceMetadata(
    source: Source,
    registeredSourceId: Long?,
    languageEligibility: ((String) -> Boolean)?,
): SourceMetadataSnapshot {
    var failure: AppError? = null
    fun read(block: () -> String): String? = try {
        guardSourceOperation(SourceOperationPhase.METADATA, block)
    } catch (error: Exception) {
        error.rethrowSourceControlFailure()
        val mapped = error.toSourceAppError()
        if (failure == null || mapped is AppError.ExtensionIncompatible) failure = mapped
        null
    }
    val name: String?
    val language: String?
    if (languageEligibility == null) {
        name = read { source.name }
        language = read { source.lang }
    } else {
        language = read { source.lang }
        name = if (language == null || languageEligibility(language)) read { source.name } else null
    }
    return SourceMetadataSnapshot(source, registeredSourceId, name, language, failure)
}
