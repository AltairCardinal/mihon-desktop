package eu.kanade.domain.extension.interactor

import eu.kanade.domain.extension.model.Extensions
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.guardSourceOperation
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import mihon.domain.extension.presentation.ExtensionPresentationAdapter
import mihon.domain.extension.presentation.ExtensionPresentationClassifier
import mihon.domain.extension.presentation.ExtensionPresentationItem
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.domain.extension.presentation.ExtensionPresentationSource
import mihon.domain.extension.presentation.ExtensionPresentationStore

class GetExtensionsByType(
    private val preferences: SourcePreferences,
    private val extensionManager: ExtensionManager,
    private val classifier: ExtensionPresentationClassifier<Extension> = androidExtensionPresentationStore,
) {

    fun subscribe(): Flow<Extensions> {
        val showNsfwSources = preferences.showNsfwSource().get()

        return combine(
            preferences.enabledLanguages().changes(),
            extensionManager.installedExtensionsFlow,
            extensionManager.untrustedExtensionsFlow,
            extensionManager.availableExtensionsFlow,
            extensionManager.loadFailures,
        ) { enabledLanguages, _installed, _untrusted, _available, loadFailures ->
            val currentFailures = loadFailures.toMutableMap()
            val prepared = _installed.mapNotNull { owner ->
                if (owner.pkgName in currentFailures ||
                    !extensionManager.isCurrentInstalledInstance(owner)
                ) {
                    return@mapNotNull null
                }
                try {
                    if (owner.sources.isEmpty()) {
                        owner
                    } else {
                        owner.copy(
                            presentationSources = readInstalledSourcePresentation(owner),
                        )
                    }
                } catch (error: SourceCompatibilityException) {
                    extensionManager.reportSourceMetadataFailure(owner, error)?.let {
                        currentFailures[owner.pkgName] =
                            it
                    }
                    null
                }
            }
            val result = classifier.classify(
                prepared,
                _untrusted,
                // Keep an explicit upgrade path without presenting an installed failure as a fresh install.
                _available.filter { candidate ->
                    currentFailures[candidate.pkgName]?.let { candidate.versionCode > it.versionCode } ?: true
                },
                ExtensionPresentationOptions(showNsfwSources, enabledLanguages),
            )
            Extensions(
                result.updates.filterIsInstance<Extension.Installed>(),
                result.installed.filterIsInstance<Extension.Installed>(),
                result.available.filterIsInstance<Extension.Available>(),
                result.untrusted.filterIsInstance<Extension.Untrusted>(),
            )
        }
    }
}

internal val androidExtensionPresentationStore =
    ExtensionPresentationStore(AndroidExtensionPresentationAdapter)

private object AndroidExtensionPresentationAdapter : ExtensionPresentationAdapter<Extension> {
    override fun describe(extension: Extension) = ExtensionPresentationItem(
        name = extension.name,
        packageName = extension.pkgName,
        language = extension.lang,
        isNsfw = extension.isNsfw,
        hasUpdate = (extension as? Extension.Installed)?.hasUpdate == true,
        isObsolete = (extension as? Extension.Installed)?.isObsolete == true,
        sources = when (extension) {
            is Extension.Installed -> extension.presentationSources ?: readInstalledSourcePresentation(extension)
            is Extension.Available -> extension.sources.map {
                ExtensionPresentationSource(it.id, it.lang, it.name, it.baseUrl)
            }
            is Extension.Untrusted -> emptyList()
        },
    )

    override fun projectAvailableSource(extension: Extension, source: ExtensionPresentationSource): Extension =
        (extension as Extension.Available).copy(
            name = source.name,
            lang = source.language,
            sources = extension.sources.filter { it.id == source.id },
        )
}

private fun readInstalledSourcePresentation(extension: Extension.Installed) = extension.sources.map { source ->
    val id = guardSourceOperation(SourceOperationPhase.METADATA) { source.id }
    val lang = guardSourceOperation(SourceOperationPhase.METADATA) { source.lang }
    val name = guardSourceOperation(SourceOperationPhase.METADATA) { source.name }
    val baseUrl = if (source is HttpSource) {
        guardSourceOperation(SourceOperationPhase.METADATA) {
            source.baseUrl
        }
    } else {
        null
    }
    ExtensionPresentationSource(id, lang, name, baseUrl)
}
