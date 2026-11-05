package eu.kanade.domain.extension.interactor

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.guardSourceOperation
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import mihon.domain.extension.presentation.ExtensionPresentationSource

class GetExtensionSources(
    private val preferences: SourcePreferences,
) {

    fun subscribe(extension: Extension.Installed): Flow<List<ExtensionSourceItem>> = flow {
        val descriptors = extension.presentationSources ?: extension.sources.map(::readSourceRowPresentation)
        check(descriptors.size == extension.sources.size)
        val isMultiSource = extension.sources.size > 1
        val isMultiLangSingleSource =
            isMultiSource && descriptors.map { it.name }.distinct().size == 1
        val labelAsName = isMultiSource && !isMultiLangSingleSource
        val prepared = extension.sources.zip(descriptors).map { (source, descriptor) ->
            ExtensionSourceItem(
                source,
                true,
                labelAsName,
                descriptor,
                if (labelAsName) {
                    guardSourceOperation(SourceOperationPhase.METADATA) {
                        source.toString()
                    }
                } else {
                    descriptor.name
                },
            )
        }

        emitAll(
            preferences.disabledSources().changes().map { disabledSources ->
                prepared.map { it.copy(enabled = it.presentation.id.toString() !in disabledSources) }
            },
        )
    }
}

data class ExtensionSourceItem(
    val source: Source,
    val enabled: Boolean,
    val labelAsName: Boolean,
    val presentation: ExtensionPresentationSource = readSourceRowPresentation(source),
    val displayName: String = presentation.name,
)

private fun readSourceRowPresentation(source: Source) = ExtensionPresentationSource(
    guardSourceOperation(SourceOperationPhase.METADATA) { source.id },
    guardSourceOperation(SourceOperationPhase.METADATA) { source.lang },
    guardSourceOperation(SourceOperationPhase.METADATA) { source.name },
    null,
)
