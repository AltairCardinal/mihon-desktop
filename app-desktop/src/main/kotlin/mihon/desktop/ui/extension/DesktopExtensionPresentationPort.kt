package mihon.desktop.ui.extension

import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.guardSourceOperation
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import mihon.desktop.extension.DesktopAvailableExtension
import mihon.desktop.extension.DesktopExtensionApi
import mihon.desktop.extension.DesktopExtensionInstallStart
import mihon.desktop.extension.DesktopExtensionPresentationService
import mihon.desktop.extension.DesktopExtensionRuntimeSnapshot
import mihon.desktop.extension.ExtensionFailureCategory
import mihon.desktop.extension.ExtensionLoadDiagnostic
import mihon.desktop.extension.InstalledExtension
import mihon.desktop.extension.projectRuntimeState
import mihon.desktop.settings.DesktopAppPreferences
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.model.extractExtensionLibVersion
import mihon.domain.extension.presentation.ExtensionPresentationAction
import mihon.domain.extension.presentation.ExtensionPresentationActionState
import mihon.domain.extension.presentation.ExtensionPresentationAdapter
import mihon.domain.extension.presentation.ExtensionPresentationInstallStep
import mihon.domain.extension.presentation.ExtensionPresentationItem
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.domain.extension.presentation.ExtensionPresentationResult
import mihon.domain.extension.presentation.ExtensionPresentationSource
import mihon.domain.extension.presentation.ExtensionPresentationStore
import mihon.domain.extension.service.ExtensionInstallLease
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.service.ExtensionUpdatePolicy
import mihon.domain.extension.service.SharedExtensionUpdatePolicy
import mihon.domain.extension.suggestion.ExtensionInventory
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.model.normalizedSigningKeyFingerprint
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.domain.source.service.toSourceAppError
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
    val loadFailures: List<ExtensionLoadDiagnostic> = emptyList(),
)

data class DesktopExtensionSourceItem(
    val source: Source,
    val enabled: Boolean,
    val labelAsName: Boolean,
    val presentation: ExtensionPresentationSource,
) {
    fun displayName(locale: Locale): String = presentation.name.takeIf { labelAsName }
        ?: when (presentation.language) {
            "all" -> MR.strings.multi_lang.localized(locale)
            "other" -> MR.strings.other_source.localized(locale)
            else -> Locale.forLanguageTag(presentation.language.normalizedLanguageTag())
                .getDisplayName(locale)
                .ifBlank { presentation.language }
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
    installedExtensions: StateFlow<List<InstalledExtension>>? = null,
    private val updatePolicy: ExtensionUpdatePolicy = SharedExtensionUpdatePolicy,
    private val sourcePreferences: DesktopExtensionSourcePreferenceAdapter? = null,
    internal val configuredRepositories: Flow<List<ExtensionRepo>>? = null,
    val inventory: Flow<ExtensionInventory> =
        flowOf(ExtensionInventory()),
) {
    val installArbiter get() = api.installArbiter
    val installedExtensions: StateFlow<List<InstalledExtension>> = installedExtensions ?: service.installedExtensions
    val runtimeSnapshot: StateFlow<DesktopExtensionRuntimeSnapshot> = installedExtensions?.projectRuntimeState {
        DesktopExtensionRuntimeSnapshot(installed = it)
    } ?: service.runtimeSnapshot
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
        installArbiter.withRemoval(item.operationPackageName) {
            item.installed?.let(service::removeExtensionWithMeta) == true
        } == true

    suspend fun reloadInstalled() = withContext(Dispatchers.IO) { service.reloadAll() }

    fun extensionSources(item: DesktopExtensionItem, disabledSources: Set<String>): List<DesktopExtensionSourceItem> {
        val extension = item.installed ?: return emptyList()
        val descriptors = item.presentation.sources
        check(descriptors.size == extension.sources.size)
        val isMultiSource = extension.sources.size > 1
        val labelAsName = isMultiSource && descriptors.map { it.name }.distinct().size > 1
        return desktopExtensionPresentationStore.enabledFirst(
            extension.sources.zip(descriptors).map { (source, descriptor) ->
                DesktopExtensionSourceItem(
                    source,
                    descriptor.id.toString() !in disabledSources,
                    labelAsName,
                    descriptor,
                )
            },
            DesktopExtensionSourceItem::enabled,
        ) { it.displayName(Locale.getDefault()).lowercase() }
    }

    fun setSourceEnabled(sourceId: Long, enabled: Boolean) = sourcePreferences?.setEnabled(sourceId, enabled)

    fun setSourcesEnabled(item: DesktopExtensionItem, enabled: Boolean) =
        sourcePreferences?.setAllEnabled(item.presentation.sources.map { it.id }, enabled)

    fun canonicalCandidates(
        catalog: DesktopExtensionCatalogState,
        installed: List<InstalledExtension> = runtimeSnapshot.value.installed,
    ): Map<String, DesktopAvailableExtension> =
        catalog.available.groupBy(DesktopAvailableExtension::pkgName).mapValues { (packageName, candidates) ->
            val owner = installed.find { it.pkgName == packageName }
            candidates.find {
                owner != null && it.repoUrl.normalizedRepo() == owner.repoUrl.normalizedRepo() &&
                    (
                        owner.repoFingerprint.isBlank() ||
                            it.repoFingerprint.normalizedSigningKeyFingerprint() ==
                            owner.repoFingerprint.normalizedSigningKeyFingerprint()
                        )
            } ?: candidates.last()
        }

    fun project(
        catalog: DesktopExtensionCatalogState,
        runtime: DesktopExtensionRuntimeSnapshot = runtimeSnapshot.value,
    ): DesktopExtensionProjection {
        val candidates = canonicalCandidates(catalog, runtime.installed)
        val loadFailures = runtime.loadFailures.toMutableList()
        val successfulRepos = catalog.catalog.repositories +
            catalog.catalog.entries.map { it.artifact.repository } +
            catalog.available.map { RepositoryIdentity(it.repoUrl, it.repoName, it.repoFingerprint) }
        val failedRepos = catalog.catalog.failures.mapTo(mutableSetOf()) { it.repository.baseUrl.normalizedRepo() }
        val installed = runtime.installed.mapNotNull { extension ->
            val installedFingerprint = extension.repoFingerprint.normalizedSigningKeyFingerprint()
            val installedRepo = extension.repoUrl.normalizedRepo()
            val candidate = catalog.available.find {
                it.pkgName == extension.pkgName &&
                    (extension.repoUrl.isBlank() || it.repoUrl.normalizedRepo() == installedRepo) &&
                    (
                        extension.repoFingerprint.isBlank() ||
                            it.repoFingerprint.normalizedSigningKeyFingerprint() ==
                            installedFingerprint
                        )
            }
            val bundled = extension.pkgName == BUNDLED_MANGADEX
            try {
                extension.item(
                    hasUpdate = !bundled && candidate != null &&
                        candidate.compatibility == ExtensionCompatibility.Compatible && (
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
                            it.baseUrl.normalizedRepo() == installedRepo &&
                                (
                                    extension.repoFingerprint.isBlank() ||
                                        it.signingKeyFingerprint.normalizedSigningKeyFingerprint() ==
                                        installedFingerprint
                                    )
                        } &&
                        installedRepo !in failedRepos,
                ).copy(compatibility = candidate?.compatibility)
            } catch (error: SourceCompatibilityException) {
                loadFailures += ExtensionLoadDiagnostic(
                    jarName = extension.jarFile.name,
                    category = ExtensionFailureCategory.from(error),
                    errorType = error.javaClass.simpleName,
                    message = "Source metadata could not be read",
                    cause = error,
                    error = error.toSourceAppError(),
                    metadata = extension.metadataSnapshot,
                    phase = error.phase,
                )
                null
            }
        }
        val failedVersions = loadFailures.mapNotNull { it.metadata }
            .groupBy { it.pkgName }.mapValues { (_, values) -> values.maxOf { it.versionCode } }
        val available = candidates.values.filterNot { it.pkgName == BUNDLED_MANGADEX }.mapNotNull { candidate ->
            val failedVersion = failedVersions[candidate.pkgName]
            when {
                failedVersion == null -> candidate.item()
                candidate.versionCode <= failedVersion -> null
                else -> candidate.item().let { it.copy(presentation = it.presentation.copy(hasUpdate = true)) }
            }
        }
        return DesktopExtensionProjection(
            installed,
            available,
            catalog.catalog.failures,
            loadFailures,
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
        sources.map { source ->
            ExtensionPresentationSource(
                guardSourceOperation(SourceOperationPhase.METADATA) { source.id },
                guardSourceOperation(SourceOperationPhase.METADATA) { source.lang },
                guardSourceOperation(SourceOperationPhase.METADATA) { source.name },
                (source as? HttpSource)?.let {
                    guardSourceOperation(SourceOperationPhase.METADATA) { it.baseUrl }
                },
            )
        },
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
        if (error == AppError.Cancelled) {
            ExtensionPresentationInstallStep.Idle
        } else {
            ExtensionPresentationInstallStep.Error
        }
    else -> ExtensionPresentationInstallStep.Installing
}

private fun String.normalizedRepo() = trim().removeSuffix("/")

private fun String.normalizedLanguageTag() = when (this) {
    "zh-CN" -> "zh-Hans"
    "zh-TW" -> "zh-Hant"
    else -> this
}

private const val BUNDLED_MANGADEX = "eu.kanade.tachiyomi.extension.all.mangadex"
