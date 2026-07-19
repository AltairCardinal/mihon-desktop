package mihon.desktop.ui.extension

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import eu.kanade.tachiyomi.source.online.HttpSource
import mihon.desktop.extension.DesktopAvailableExtension
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionInstallStart
import mihon.desktop.extension.DesktopExtensionManager
import mihon.desktop.extension.InstalledExtension
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.extractExtensionLibVersion
import mihon.domain.extension.presentation.ExtensionPresentationAdapter
import mihon.domain.extension.presentation.ExtensionPresentationAction
import mihon.domain.extension.presentation.ExtensionPresentationActionState
import mihon.domain.extension.presentation.ExtensionPresentationInstallStep
import mihon.domain.extension.presentation.ExtensionPresentationItem
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.domain.extension.presentation.ExtensionPresentationResult
import mihon.domain.extension.presentation.ExtensionPresentationSource
import mihon.domain.extension.presentation.ExtensionPresentationStore
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.service.ExtensionUpdatePolicy
import mihon.domain.extension.service.SharedExtensionUpdatePolicy

data class DesktopExtensionItem(
    val presentation: ExtensionPresentationItem,
    val operationPackageName: String,
    val installed: InstalledExtension? = null,
    val available: DesktopAvailableExtension? = null,
)

data class DesktopExtensionProjection(
    val installed: List<DesktopExtensionItem>,
    val available: List<DesktopExtensionItem>,
    val failures: List<mihon.domain.extension.model.RepositoryCatalogFailure>,
)

data class DesktopPresentationInstallEvent(
    val step: ExtensionPresentationInstallStep,
    val raw: ExtensionInstallState?,
)

sealed interface DesktopPresentationInstallStart {
    data class Started(val events: Flow<DesktopPresentationInstallEvent>) : DesktopPresentationInstallStart
    data class TrustRequired(val request: DesktopExtensionInstallStart.TrustRequired) : DesktopPresentationInstallStart
    data class Rejected(val error: mihon.domain.error.AppError) : DesktopPresentationInstallStart
}

data class DesktopExtensionCatalogState(
    val catalog: ExtensionCatalogResult,
    val available: List<DesktopAvailableExtension>,
)

class DesktopExtensionPresentationPort(
    private val api: DesktopExtensionApi,
    private val manager: DesktopExtensionManager,
    installedExtensions: StateFlow<List<InstalledExtension>> = manager.installedExtensions,
    private val updatePolicy: ExtensionUpdatePolicy = SharedExtensionUpdatePolicy,
) {
    val installedExtensions: StateFlow<List<InstalledExtension>> = installedExtensions

    suspend fun refresh(): DesktopExtensionCatalogState {
        val catalog = api.refreshCatalog()
        return DesktopExtensionCatalogState(catalog, api.availableExtensions(catalog))
    }

    suspend fun beginInstall(extension: DesktopAvailableExtension): DesktopExtensionInstallStart =
        api.beginInstall(extension, manager)

    suspend fun beginPresentationInstall(extension: DesktopAvailableExtension): DesktopPresentationInstallStart =
        beginInstall(extension).toPresentation()

    fun confirmTrust(requestId: String): Flow<ExtensionInstallState>? = api.confirmTrust(requestId, manager)

    fun confirmPresentationTrust(requestId: String): Flow<DesktopPresentationInstallEvent>? =
        confirmTrust(requestId)?.toPresentationEvents()

    fun discardTrust(requestId: String): Boolean = api.discardTrust(requestId)

    fun searchPredicate(query: String) = desktopExtensionPresentationStore.searchPredicate(query, true)

    fun reduceActions(state: ExtensionPresentationActionState, action: ExtensionPresentationAction) =
        desktopExtensionPresentationStore.reduce(state, action)

    fun classify(
        projection: DesktopExtensionProjection,
        options: ExtensionPresentationOptions,
    ): ExtensionPresentationResult<DesktopExtensionItem> = desktopExtensionPresentationStore.classify(
        projection.installed,
        emptyList(),
        projection.available,
        options,
    )

    fun uninstall(item: DesktopExtensionItem): Boolean =
        item.installed?.let(manager::removeExtensionWithMeta) == true

    fun canonicalCandidates(catalog: DesktopExtensionCatalogState): Map<String, DesktopAvailableExtension> =
        catalog.available.associateBy(DesktopAvailableExtension::pkgName)

    fun project(catalog: DesktopExtensionCatalogState): DesktopExtensionProjection {
        val candidates = canonicalCandidates(catalog)
        val successfulRepos = (catalog.available.map { it.repoUrl } +
            catalog.catalog.entries.map { it.artifact.repository.baseUrl }).mapTo(mutableSetOf(), String::normalizedRepo)
        val failedRepos = catalog.catalog.failures.mapTo(mutableSetOf()) { it.repository.baseUrl.normalizedRepo() }
        val installed = installedExtensions.value.map { extension ->
            val candidate = candidates[extension.pkgName]
            val bundled = extension.pkgName == BUNDLED_MANGADEX
            extension.item(
                hasUpdate = !bundled && candidate != null && updatePolicy.isUpdateAvailable(
                    candidate.versionCode,
                    candidate.libVersion,
                    extension.versionCode,
                    extractExtensionLibVersion(extension.versionName) ?: 0.0,
                ),
                isObsolete = !bundled && candidate == null && extension.repoUrl.isNotBlank() &&
                    extension.repoUrl.normalizedRepo() in successfulRepos &&
                    extension.repoUrl.normalizedRepo() !in failedRepos,
            )
        }
        return DesktopExtensionProjection(
            installed,
            candidates.values.filterNot { it.pkgName == BUNDLED_MANGADEX }.map(DesktopAvailableExtension::item),
            catalog.catalog.failures,
        )
    }
}

internal val desktopExtensionPresentationStore = ExtensionPresentationStore(DesktopItemAdapter)

private object DesktopItemAdapter : ExtensionPresentationAdapter<DesktopExtensionItem> {
    override fun describe(extension: DesktopExtensionItem) = extension.presentation

    override fun projectAvailableSource(extension: DesktopExtensionItem, source: ExtensionPresentationSource) =
        extension.copy(
            presentation = extension.presentation.copy(
                name = source.name,
                packageName = "${extension.presentation.packageName}-${source.id}",
                language = source.language,
                sources = listOf(source),
            ),
        )
}

internal fun InstalledExtension.item(hasUpdate: Boolean, isObsolete: Boolean) = DesktopExtensionItem(
    ExtensionPresentationItem(
        name,
        pkgName,
        language,
        isNsfw,
        hasUpdate,
        isObsolete,
        sources.map { ExtensionPresentationSource(it.id, it.lang, it.name, (it as? HttpSource)?.baseUrl) },
    ),
    pkgName,
    installed = this,
)

internal fun DesktopAvailableExtension.item() = DesktopExtensionItem(
    ExtensionPresentationItem(
        name,
        pkgName,
        lang,
        isNsfw,
        sources = sources.map { ExtensionPresentationSource(it.id, it.lang, it.name, it.baseUrl) },
    ),
    pkgName,
    available = this,
)

private fun DesktopExtensionInstallStart.toPresentation(): DesktopPresentationInstallStart = when (this) {
    is DesktopExtensionInstallStart.Started -> DesktopPresentationInstallStart.Started(states.toPresentationEvents())
    is DesktopExtensionInstallStart.TrustRequired -> DesktopPresentationInstallStart.TrustRequired(this)
    is DesktopExtensionInstallStart.Rejected -> DesktopPresentationInstallStart.Rejected(error)
}

private fun Flow<ExtensionInstallState>.toPresentationEvents() =
    map { DesktopPresentationInstallEvent(it.presentationStep(), it) }
        .onStart { emit(DesktopPresentationInstallEvent(ExtensionPresentationInstallStep.Pending, null)) }

private fun ExtensionInstallState.presentationStep() = when (this) {
    ExtensionInstallState.Preparing -> ExtensionPresentationInstallStep.Downloading
    is ExtensionInstallState.Installed -> ExtensionPresentationInstallStep.Installed
    is ExtensionInstallState.Failed ->
        if (error == mihon.domain.error.AppError.Cancelled) ExtensionPresentationInstallStep.Idle
        else ExtensionPresentationInstallStep.Error
    else -> ExtensionPresentationInstallStep.Installing
}

private fun String.normalizedRepo() = trim().removeSuffix("/")

private const val BUNDLED_MANGADEX = "eu.kanade.tachiyomi.extension.all.mangadex"
