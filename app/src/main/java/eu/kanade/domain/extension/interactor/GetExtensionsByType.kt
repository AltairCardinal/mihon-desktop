package eu.kanade.domain.extension.interactor

import eu.kanade.domain.extension.model.Extensions
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
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
        ) { enabledLanguages, _installed, _untrusted, _available ->
            val result = classifier.classify(
                _installed,
                _untrusted,
                _available,
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

internal val androidExtensionPresentationStore: ExtensionPresentationClassifier<Extension> =
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
            is Extension.Installed -> extension.sources.map {
                ExtensionPresentationSource(it.id, it.lang, it.name, (it as? HttpSource)?.baseUrl)
            }
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
            pkgName = "${extension.pkgName}-${source.id}",
            sources = extension.sources.filter { it.id == source.id },
        )
}
