package mihon.desktop.ui.extension

import mihon.domain.error.AppError
import mihon.domain.extension.service.ExtensionInstallLease
import mihon.domain.extension.suggestion.ExtensionInventory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import mihon.desktop.extension.DesktopAvailableExtension
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionInstallStart
import mihon.desktop.extension.DesktopExtensionPresentationService
import mihon.desktop.extension.InstalledExtension
import mihon.desktop.settings.DesktopAppPreferences
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.model.RepositoryIdentity
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
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.model.normalizedSigningKeyFingerprint
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.i18n.MR
import java.util.Locale

data class DesktopExtensionItem(
    val presentation: ExtensionPresentationItem,
    val operationPackageName: String,
    val installed: InstalledExtension? = null,
    val available: DesktopAvailableExtension? = null,
    val compatibility: ExtensionCompatibility? = available?.compatibility,
)

data class DesktopExtensionProjection(
    val installed: List<DesktopExtensionItem>,
    val available: List<DesktopExtensionItem>,
    val failures: List<mihon.domain.extension.model.RepositoryCatalogFailure>,
)

data class DesktopExtensionSourceItem(
    val source: Source,
    val enabled: Boolean,
    val labelAsName: Boolean,
) {
    fun displayName(locale: Locale): String = source.name.takeIf { labelAsName }
        ?: when (source.lang) {
            "all" -> MR.strings.multi_lang.localized(locale)
            "other" -> MR.strings.other_source.localized(locale)
            else -> Locale.forLanguageTag(source.lang.normalizedLanguageTag())
                .getDisplayName(locale)
                .ifBlank { source.lang }
        }
}

class DesktopExtensionSourcePreferenceAdapter(
    preferences: DesktopAppPreferences,
) {
    private val disabledSourcePreference = preferences.disabledSources
    val disabledSources: Flow<Set<String>> = disabledSourcePreference.changes()

    fun setEnabled(sourceId: Long, enabled: Boolean) {
        setAllEnabled(listOf(sourceId), enabled)
    }

    fun setAllEnabled(sourceIds: List<Long>, enabled: Boolean) {
        val ids = sourceIds.mapTo(mutableSetOf()) { it.toString() }
        disabledSourcePreference.getAndSet { disabled ->
            if (enabled) disabled - ids else disabled + ids
        }
    }
}

data class DesktopPresentationInstallEvent(
    val step: ExtensionPresentationInstallStep,
    val raw: ExtensionInstallState?,
)

sealed interface DesktopPresentationInstallStart {
    data class Started(val events: Flow<DesktopPresentationInstallEvent>) : DesktopPresentationInstallStart
    data class TrustRequired(val request: DesktopExtensionInstallStart.TrustRequired) : DesktopPresentationInstallStart
    data class Rejected(val error: AppError) : DesktopPresentationInstallStart
}

data class DesktopExtensionCatalogState(
    val catalog: ExtensionCatalogResult,
    val available: List<DesktopAvailableExtension>,
)

class DesktopExtensionPresentationPort(
    private val api: DesktopExtensionApi,
    private val service: DesktopExtensionPresentationService,
    installedExtensions: StateFlow<List<InstalledExtension>> = service.installedExtensions,
    private val updatePolicy: ExtensionUpdatePolicy = SharedExtensionUpdatePolicy,
    private val sourcePreferences: DesktopExtensionSourcePreferenceAdapter? = null,
    internal val configuredRepositories: Flow<List<ExtensionRepo>>? = null,
    val inventory: Flow<ExtensionInventory> =
        flowOf(ExtensionInventory()),
) {
    val installArbiter get() = api.installArbiter
    val installedExtensions: StateFlow<List<InstalledExtension>> = installedExtensions
    val disabledSources: Flow<Set<String>> = sourcePreferences?.disabledSources ?: flowOf(emptySet())

    suspend fun refresh(): DesktopExtensionCatalogState {
        val catalog = api.refreshCatalog()
        return DesktopExtensionCatalogState(catalog, api.availableExtensions(catalog))
    }

    suspend fun beginInstall(extension: DesktopAvailableExtension): DesktopExtensionInstallStart =
        api.beginInstall(extension, service)

    suspend fun beginPresentationInstall(extension: DesktopAvailableExtension): DesktopPresentationInstallStart =
        beginInstall(extension).toPresentation()

    suspend fun beginReservedInstall(
        artifact: mihon.domain.extension.model.ExtensionArtifact,
        lease: ExtensionInstallLease,
    ): DesktopPresentationInstallStart {
        val catalog = ExtensionCatalogResult(
            listOf(mihon.domain.extension.model.ExtensionCatalogEntry(artifact, artifact.compatibility())),
            emptyList(),
        )
        val extension = api.availableExtensions(catalog).single()
        return api.beginInstall(extension, service, lease).toPresentation()
    }

    fun confirmTrust(requestId: String): Flow<ExtensionInstallState>? = api.confirmTrust(requestId, service)

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
        installArbiter.withRemoval(item.operationPackageName) { item.installed?.let(service::removeExtensionWithMeta) == true } == true

    suspend fun reloadInstalled() = withContext(Dispatchers.IO) { service.reloadAll() }

    fun extensionSources(extension: InstalledExtension, disabledSources: Set<String>): List<DesktopExtensionSourceItem> {
        val isMultiSource = extension.sources.size > 1
        val labelAsName = isMultiSource && extension.sources.map(Source::name).distinct().size > 1
        return desktopExtensionPresentationStore.enabledFirst(
            extension.sources.map { source ->
                DesktopExtensionSourceItem(source, source.id.toString() !in disabledSources, labelAsName)
            },
            DesktopExtensionSourceItem::enabled,
        ) { it.displayName(Locale.getDefault()).lowercase() }
    }

    fun setSourceEnabled(sourceId: Long, enabled: Boolean) = sourcePreferences?.setEnabled(sourceId, enabled)

    fun setSourcesEnabled(extension: InstalledExtension, enabled: Boolean) =
        sourcePreferences?.setAllEnabled(extension.sources.map(Source::id), enabled)

    fun canonicalCandidates(catalog: DesktopExtensionCatalogState): Map<String, DesktopAvailableExtension> =
        catalog.available.groupBy(DesktopAvailableExtension::pkgName).mapValues { (packageName, candidates) ->
            val installed = installedExtensions.value.find { it.pkgName == packageName }
            candidates.find {
                installed != null && it.repoUrl.normalizedRepo() == installed.repoUrl.normalizedRepo() &&
                    (installed.repoFingerprint.isBlank() ||
                        it.repoFingerprint.normalizedSigningKeyFingerprint() == installed.repoFingerprint.normalizedSigningKeyFingerprint())
            } ?: candidates.last()
        }

    fun project(catalog: DesktopExtensionCatalogState): DesktopExtensionProjection {
        val candidates = canonicalCandidates(catalog)
        val successfulRepos = catalog.catalog.repositories +
            catalog.catalog.entries.map { it.artifact.repository } +
            catalog.available.map { RepositoryIdentity(it.repoUrl, it.repoName, it.repoFingerprint) }
        val failedRepos = catalog.catalog.failures.mapTo(mutableSetOf()) { it.repository.baseUrl.normalizedRepo() }
        val installed = installedExtensions.value.map { extension ->
            val candidate = catalog.available.find {
                it.pkgName == extension.pkgName &&
                    (extension.repoUrl.isBlank() || it.repoUrl.normalizedRepo() == extension.repoUrl.normalizedRepo()) &&
                    (extension.repoFingerprint.isBlank() ||
                        it.repoFingerprint.normalizedSigningKeyFingerprint() == extension.repoFingerprint.normalizedSigningKeyFingerprint())
            }
            val bundled = extension.pkgName == BUNDLED_MANGADEX
            extension.item(
                hasUpdate = !bundled && candidate != null && candidate.compatibility == ExtensionCompatibility.Compatible && (
                    extension.requiresApkReconversion ||
                        updatePolicy.isUpdateAvailable(
                            candidate.versionCode,
                            candidate.libVersion,
                            extension.versionCode,
                            extractExtensionLibVersion(extension.versionName) ?: 0.0,
                        )
                    ),
                isObsolete = !bundled && candidate == null && extension.repoUrl.isNotBlank() &&
                    successfulRepos.any {
                        it.baseUrl.normalizedRepo() == extension.repoUrl.normalizedRepo() &&
                            (extension.repoFingerprint.isBlank() ||
                                it.signingKeyFingerprint.normalizedSigningKeyFingerprint() == extension.repoFingerprint.normalizedSigningKeyFingerprint())
                    } &&
                    extension.repoUrl.normalizedRepo() !in failedRepos,
            ).copy(compatibility = candidate?.compatibility)
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

internal fun ExtensionInstallState.presentationStep() = when (this) {
    ExtensionInstallState.Queued -> ExtensionPresentationInstallStep.Pending
    ExtensionInstallState.Preparing -> ExtensionPresentationInstallStep.Downloading
    is ExtensionInstallState.Installed -> ExtensionPresentationInstallStep.Installed
    is ExtensionInstallState.Failed ->
        if (error == AppError.Cancelled) ExtensionPresentationInstallStep.Idle
        else ExtensionPresentationInstallStep.Error
    else -> ExtensionPresentationInstallStep.Installing
}

private fun String.normalizedRepo() = trim().removeSuffix("/")

private fun String.normalizedLanguageTag() = when (this) {
    "zh-CN" -> "zh-Hans"
    "zh-TW" -> "zh-Hant"
    else -> this
}

private const val BUNDLED_MANGADEX = "eu.kanade.tachiyomi.extension.all.mangadex"
