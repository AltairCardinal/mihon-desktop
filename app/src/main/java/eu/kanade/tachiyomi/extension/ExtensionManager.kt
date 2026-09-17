package eu.kanade.tachiyomi.extension

import android.content.Context
import android.graphics.drawable.Drawable
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.api.ExtensionApi
import eu.kanade.tachiyomi.extension.api.ExtensionDiscoveryResult
import eu.kanade.tachiyomi.extension.api.ExtensionUpdateNotifier
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.model.toArtifact
import eu.kanade.tachiyomi.extension.model.toAvailable
import eu.kanade.tachiyomi.extension.util.AndroidInstallPaused
import eu.kanade.tachiyomi.extension.util.ExtensionInstallReceiver
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.model.ExtensionCatalogEntry
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.ExtensionCompatibility
import mihon.domain.extension.model.RepositoryCatalogFailure
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.model.toIdentity
import mihon.domain.extension.service.ExtensionInstallArbiter
import mihon.domain.extension.service.ExtensionInstallBusy
import mihon.domain.extension.service.ExtensionInstallFailure
import mihon.domain.extension.service.ExtensionInstallInvalidated
import mihon.domain.extension.service.ExtensionInstallLease
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.service.ExtensionRemovalLease
import mihon.domain.extension.service.ExtensionSystemWindowLease
import mihon.domain.extension.service.ExtensionUpdatePolicy
import mihon.domain.extension.service.SharedExtensionUpdatePolicy
import mihon.domain.extension.suggestion.ExtensionInventory
import mihon.domain.extension.suggestion.ExtensionInventoryRecord
import mihon.domain.extension.suggestion.ExtensionPresence
import mihon.domain.extension.suggestion.SuggestionBatchPause
import mihon.domain.extension.suggestion.SuggestionBatchResult
import mihon.domain.extensionrepo.model.ExtensionRepo
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import java.util.UUID

/**
 * The manager of extensions installed as another apk which extend the available sources. It handles
 * the retrieval of remotely available extensions as well as installing, updating and removing them.
 * To avoid malicious distribution, every extension must be signed and it will only be loaded if its
 * signature is trusted, otherwise the user will be prompted with a warning to trust it before being
 * loaded.
 */
class ExtensionManager internal constructor(
    private val context: Context,
    private val preferences: SourcePreferences = Injekt.get(),
    private val trustExtension: TrustExtension = Injekt.get(),
    private val updatePolicy: ExtensionUpdatePolicy = SharedExtensionUpdatePolicy,
    private val installedExtensionsLoader: suspend (Context) -> List<LoadResult> = ExtensionLoader::loadExtensions,
    private val extensionLoader: suspend (Context, String) -> LoadResult = ExtensionLoader::loadExtensionFromPkgName,
    private val availableExtensionsProvider: (suspend () -> List<Extension.Available>)? = null,
    private val installerFactory: (((suspend (String) -> Unit)) -> ExtensionInstaller)? = null,
    private val installReceiverRegistrar: (ExtensionInstallReceiver.Listener) -> Unit = { listener ->
        ExtensionInstallReceiver(listener).register(context)
    },
    val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val catalogFailuresProvider: (suspend () -> List<RepositoryCatalogFailure>)? = null,
    private val discoveryProvider: (suspend () -> ExtensionDiscoveryResult)? = null,
    private val inventoryProvider: suspend (Context) -> ExtensionInventory =
        { ExtensionLoader.scanInventory(it) },
    private val repositoryUpdates: Flow<List<ExtensionRepo>>? = null,
    val installArbiter: ExtensionInstallArbiter = ExtensionInstallArbiter(),
) {

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    private val mutableInventory = MutableStateFlow(ExtensionInventory())
    val inventory = mutableInventory.asStateFlow()
    private val mutableSuggestionCatalog = MutableStateFlow<ExtensionCatalogResult?>(null)
    val suggestionCatalog = mutableSuggestionCatalog.asStateFlow()
    private val inventoryMutex = Mutex()
    private var inventoryRevision = 0L
    private val catalogRefreshMutex = Mutex()
    private val catalogStateLock = Any()
    private var configuredCatalogIdentities: Set<RepositoryIdentity>? = null

    /**
     * API where all the available extensions can be found.
     */
    private val api = ExtensionApi()

    /**
     * The installer which installs, updates and uninstalls the extensions.
     */
    private val installer by lazy {
        installerFactory?.invoke(::reloadInstalledExtension) ?: ExtensionInstaller(context, ::reloadInstalledExtension)
    }

    private val iconMap = mutableMapOf<String, Drawable>()

    private val installedExtensionMapFlow = MutableStateFlow(emptyMap<String, Extension.Installed>())
    val installedExtensionsFlow = installedExtensionMapFlow.mapValues()

    private val availableExtensionMapFlow = MutableStateFlow(emptyMap<String, Extension.Available>())
    val availableExtensionsFlow = availableExtensionMapFlow.mapValues()

    private val _repositoryFailures = MutableStateFlow(emptyList<RepositoryCatalogFailure>())
    val repositoryFailures: StateFlow<List<RepositoryCatalogFailure>> = _repositoryFailures.asStateFlow()

    private val untrustedExtensionMapFlow = MutableStateFlow(emptyMap<String, Extension.Untrusted>())
    val untrustedExtensionsFlow = untrustedExtensionMapFlow.mapValues()

    private val installationStateLock = Any()
    private val pendingInstallationEvents = ArrayDeque<InstallationEvent>()
    private var installationEventsLive = false

    private val pendingPlatformSessions = eu.kanade.tachiyomi.extension.util.PendingExtensionInstallSessions(
        context.packageManager.packageInstaller,
        context.packageName,
        installArbiter,
        ::requestInventoryRefresh,
    )

    init {
        pendingPlatformSessions.start()
        installReceiverRegistrar(InstallationListener())
        initExtensions()
        repositoryUpdates?.let { updates ->
            scope.launch {
                updates.collectLatest { repositories ->
                    val identities = repositories.map { it.toIdentity() }.toSet()
                    synchronized(catalogStateLock) {
                        configuredCatalogIdentities = identities
                        if (mutableSuggestionCatalog.value?.repositories?.toSet() != identities) {
                            mutableSuggestionCatalog.value = null
                        }
                    }
                    // Reuse the manager's serialized refresh, including changes received through restore/sync.
                    findAvailableExtensions()
                }
            }
        }
    }

    private var subLanguagesEnabledOnFirstRun = preferences.enabledLanguages().isSet()

    fun getExtensionPackage(sourceId: Long): String? {
        return installedExtensionsFlow.value.find { extension ->
            extension.sources.any { it.id == sourceId }
        }
            ?.pkgName
    }

    fun getExtensionPackageAsFlow(sourceId: Long): Flow<String?> {
        return installedExtensionsFlow.map { extensions ->
            extensions.find { extension ->
                extension.sources.any { it.id == sourceId }
            }
                ?.pkgName
        }
    }

    fun getAppIconForSource(sourceId: Long): Drawable? {
        val pkgName = getExtensionPackage(sourceId) ?: return null

        return iconMap[pkgName] ?: iconMap.getOrPut(pkgName) {
            val applicationInfo = ExtensionLoader.getExtensionPackageInfoFromPkgName(context, pkgName)
                ?.applicationInfo ?: return null
            applicationInfo.loadIcon(context.packageManager)
        }
    }

    private var availableExtensionsSourcesData: Map<Long, StubSource> = emptyMap()

    private fun setupAvailableExtensionsSourcesDataMap(extensions: List<Extension.Available>) {
        availableExtensionsSourcesData = extensions
            .flatMap { ext -> ext.sources.map { it.toStubSource() } }
            .associateBy { it.id }
    }

    fun getSourceData(id: Long) = availableExtensionsSourcesData[id]

    /**
     * Loads and registers the installed extensions.
     */
    private fun initExtensions() {
        scope.launch {
            val extensions = installedExtensionsLoader(context)

            var installedExtensions = extensions
                .filterIsInstance<LoadResult.Success>()
                .associate { it.extension.pkgName to it.extension }
            var untrustedExtensions = extensions
                .filterIsInstance<LoadResult.Untrusted>()
                .associate { it.extension.pkgName to it.extension }

            val replayedEvents = synchronized(installationStateLock) {
                pendingInstallationEvents.forEach { event ->
                    installedExtensions = event.applyToInstalled(installedExtensions)
                    untrustedExtensions = event.applyToUntrusted(untrustedExtensions)
                }
                val replayedEvents = pendingInstallationEvents.isNotEmpty()
                pendingInstallationEvents.clear()
                installedExtensionMapFlow.value = installedExtensions
                untrustedExtensionMapFlow.value = untrustedExtensions
                installationEventsLive = true
                _isInitialized.value = true
                replayedEvents
            }
            if (replayedEvents) updatePendingUpdatesCount()
            refreshInventory()
        }
    }

    /** Re-read package presence only; this does not install or reload extension code. */
    fun recheckInstalledInventory() = requestInventoryRefresh()

    private fun requestInventoryRefresh(): kotlinx.coroutines.Job {
        synchronized(installationStateLock) {
            inventoryRevision++
            mutableInventory.value = mutableInventory.value.copy(initialized = false)
        }
        return scope.launch { refreshInventory() }
    }

    private suspend fun refreshInventory() = inventoryMutex.withLock {
        do {
            val revision = synchronized(installationStateLock) { inventoryRevision }
            val scanned = try {
                inventoryProvider(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ExtensionInventory(initialized = true, hasUnknownArtifacts = true)
            }
            val published = synchronized(installationStateLock) {
                if (revision != inventoryRevision) {
                    false
                } else {
                    val packages = scanned.packages.mapValues { (name, presence) ->
                        when {
                            presence == ExtensionPresence.UNKNOWN -> presence
                            name in untrustedExtensionMapFlow.value -> ExtensionPresence.UNTRUSTED
                            name in installedExtensionMapFlow.value -> ExtensionPresence.PRESENT
                            else -> ExtensionPresence.LOAD_FAILED
                        }
                    } + installedExtensionMapFlow.value.mapValues { ExtensionPresence.PRESENT } +
                        untrustedExtensionMapFlow.value.mapValues { ExtensionPresence.UNTRUSTED }
                    mutableInventory.value = scanned.copy(
                        hasUnknownArtifacts = scanned.hasUnknownArtifacts || pendingPlatformSessions.hasUnknownSessions,
                        records = packages.mapValues { (name, presence) ->
                            (scanned.records[name] ?: ExtensionInventoryRecord(presence)).copy(
                                presence = presence,
                                runtimeLoaded = presence == ExtensionPresence.PRESENT,
                            )
                        },
                    )
                    true
                }
            }
        } while (!published)
    }

    /**
     * Finds the available extensions in the [api] and updates [availableExtensionMapFlow].
     */
    suspend fun findAvailableExtensions() = catalogRefreshMutex.withLock {
        val discovery: ExtensionDiscoveryResult = try {
            if (discoveryProvider != null) {
                discoveryProvider.invoke()
            } else if (availableExtensionsProvider != null) {
                ExtensionDiscoveryResult(
                    extensions = availableExtensionsProvider.invoke(),
                    failures = catalogFailuresProvider?.invoke().orEmpty(),
                    repositories = emptyList(),
                )
            } else {
                api.findExtensionsWithFailures()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            withUIContext { context.toast(MR.strings.extension_api_error) }
            return@withLock
        }
        val extensions = discovery.extensions
        _repositoryFailures.value = discovery.failures
        val suggestionCatalog = discovery.catalog ?: ExtensionCatalogResult(
            extensions.map { ExtensionCatalogEntry(it.toArtifact(), it.compatibility) },
            discovery.failures,
            discovery.repositories,
        )
        synchronized(catalogStateLock) {
            if (configuredCatalogIdentities == null ||
                suggestionCatalog.repositories.toSet() == configuredCatalogIdentities
            ) {
                mutableSuggestionCatalog.value = suggestionCatalog
            }
        }

        enableAdditionalSubLanguages(extensions)

        availableExtensionMapFlow.value = extensions.groupBy { it.pkgName }.mapValues { (pkgName, candidates) ->
            val owner = installedExtensionMapFlow.value[pkgName]?.repoUrl?.normalizedRepo()
            candidates.find { it.repoUrl.normalizedRepo() == owner } ?: candidates.last()
        }
        updatedInstalledExtensionsStatuses(
            availableExtensions = extensions,
            failedRepositories = discovery.failures.mapTo(mutableSetOf()) { it.repository.baseUrl.normalizedRepo() },
            successfulRepositories = discovery.repositories.mapTo(mutableSetOf()) { it.baseUrl.normalizedRepo() },
        )
        setupAvailableExtensionsSourcesDataMap(extensions)
    }

    /**
     * Enables the additional sub-languages in the app first run. This addresses
     * the issue where users still need to enable some specific languages even when
     * the device language is inside that major group. As an example, if a user
     * has a zh device language, the app will also enable zh-Hans and zh-Hant.
     *
     * If the user have already changed the enabledLanguages preference value once,
     * the new languages will not be added to respect the user enabled choices.
     */
    private fun enableAdditionalSubLanguages(extensions: List<Extension.Available>) {
        if (subLanguagesEnabledOnFirstRun || extensions.isEmpty()) {
            return
        }

        // Use the source lang as some aren't present on the extension level.
        val availableLanguages = extensions
            .flatMap(Extension.Available::sources)
            .distinctBy(Extension.Available.Source::lang)
            .map(Extension.Available.Source::lang)

        val deviceLanguage = Locale.getDefault().language
        val defaultLanguages = preferences.enabledLanguages().defaultValue()
        val languagesToEnable = availableLanguages.filter {
            it != deviceLanguage && it.startsWith(deviceLanguage)
        }

        preferences.enabledLanguages().set(defaultLanguages + languagesToEnable)
        subLanguagesEnabledOnFirstRun = true
    }

    /**
     * Sets the update field of the installed extensions with the given [availableExtensions].
     *
     * @param availableExtensions The list of extensions given by the [api].
     */
    private fun updatedInstalledExtensionsStatuses(
        availableExtensions: List<Extension.Available>,
        failedRepositories: Set<String>,
        successfulRepositories: Set<String>,
    ) {
        installedExtensionMapFlow.update { installedExtensions ->
            installedExtensions.mapValues { (pkgName, extension) ->
                val repository = extension.repoUrl?.normalizedRepo()
                val availableExt = availableExtensions.find {
                    it.pkgName == pkgName && (repository == null || it.repoUrl.normalizedRepo() == repository)
                }
                val repositoryFailed = repository in failedRepositories
                val repositorySucceeded = repository in successfulRepositories

                when {
                    repositoryFailed -> extension
                    availableExt == null && repositorySucceeded ->
                        extension.copy(isObsolete = true, hasUpdate = false, availableCompatibility = null)
                    availableExt != null -> extension.copy(
                        hasUpdate = extension.updateExists(availableExt),
                        repoUrl = availableExt.repoUrl,
                        isObsolete = false,
                        availableCompatibility = availableExt.compatibility,
                    )
                    else -> extension
                }
            }
        }
        updatePendingUpdatesCount()
    }

    /** Current-session failures for the extension list; InstallStep remains compatible with existing callers. */
    val installErrors get() = installer.installErrors
    val pendingSystemPauses get() = installer.pendingSystemPauses
    fun reportPendingInstallPause(transactionId: String, reason: SuggestionBatchPause?) =
        installer.reportPendingInstallPause(transactionId, reason)
    val originConfirmations get() = installer.originConfirmations

    fun answerOriginConfirmation(id: String, accepted: Boolean) = installer.answerOriginConfirmation(id, accepted)

    /**
     * Returns a flow of the installation process for the given extension. It will complete
     * once the extension is installed or throws an error. The process will be canceled if
     * unsubscribed before its completion.
     *
     * @param extension The extension to be installed.
     */
    fun installExtension(extension: Extension.Available): Flow<InstallStep> {
        if (extension.compatibility != ExtensionCompatibility.Compatible) return flowOf(InstallStep.Error)
        val url = api.getApkUrl(extension)
        val artifact = extension.toArtifact(url)
        val lease = installArbiter.reserve(artifact)
            ?: throw ExtensionInstallBusy(extension.pkgName)
        check(installArbiter.activate(lease, artifact))
        return startReserved(extension, artifact, lease)
    }

    private fun startReserved(
        extension: Extension.Available,
        artifact: ExtensionArtifact,
        lease: ExtensionInstallLease,
        releaseOnFinished: Boolean = true,
        onSystemInterruption: ((SuggestionBatchPause) -> Unit)? = null,
        onFinished: () -> Unit = {},
    ): Flow<InstallStep> = try {
        val finished: () -> Unit = {
            if (releaseOnFinished) installArbiter.release(lease)
            try {
                val refresh = requestInventoryRefresh()
                if (releaseOnFinished) onFinished() else refresh.invokeOnCompletion { onFinished() }
            } catch (failure: Throwable) {
                onFinished()
                throw failure
            }
        }
        val guard = { installArbiter.enterCommit(lease) }
        val download = if (onSystemInterruption == null) {
            installer.downloadAndInstall(artifact.apkUrl ?: artifact.downloadUrl, extension, guard, finished)
        } else {
            installer.downloadAndInstallObserved(
                artifact.apkUrl ?: artifact.downloadUrl,
                extension,
                guard,
                finished,
                onSystemInterruption,
            )
        }
        download.onEach { step ->
            val progress = when (step) {
                InstallStep.Pending -> ExtensionInstallState.Queued
                InstallStep.Downloading -> ExtensionInstallState.Preparing
                InstallStep.Installing -> ExtensionInstallState.Committing
                InstallStep.Installed -> ExtensionInstallState.Installed(artifact)
                InstallStep.Idle -> ExtensionInstallState.Failed(AppError.Cancelled)
                InstallStep.Error -> ExtensionInstallState.Failed(
                    installer.installErrors.value[extension.pkgName] ?: AppError.Unknown(),
                )
            }
            installArbiter.progress(lease, progress)
        }
    } catch (failure: Throwable) {
        if (releaseOnFinished) installArbiter.release(lease)
        onFinished()
        throw failure
    }

    internal suspend fun installReservedObserved(
        lease: ExtensionInstallLease,
        onProgress: (Long, ExtensionInstallState) -> Unit,
        onSystemInterruption: (SuggestionBatchPause) -> Unit,
    ): SuggestionBatchResult = installReservedInternal(lease, onProgress, onSystemInterruption)

    internal suspend fun installReserved(
        lease: ExtensionInstallLease,
        onProgress: (Long, ExtensionInstallState) -> Unit,
    ): SuggestionBatchResult = installReservedInternal(lease, onProgress, null)

    private suspend fun installReservedInternal(
        lease: ExtensionInstallLease,
        onProgress: (Long, ExtensionInstallState) -> Unit,
        onSystemInterruption: ((SuggestionBatchPause) -> Unit)?,
    ): SuggestionBatchResult {
        val artifact = lease.artifact
        if (!installArbiter.activate(lease, artifact)) return SuggestionBatchResult.Busy
        val finished = CompletableDeferred<Unit>()
        return try {
            val result = startReserved(
                artifact.toAvailable(),
                artifact,
                lease,
                releaseOnFinished = false,
                onSystemInterruption = onSystemInterruption,
            ) { finished.complete(Unit) }
                .onEach {
                    installArbiter.reservations.value[artifact.packageName]?.progress?.let { state ->
                        onProgress(lease.transactionId, state)
                    }
                }
                .first { it.isCompleted() }
            when (result) {
                InstallStep.Installed -> SuggestionBatchResult.Installed
                InstallStep.Idle -> SuggestionBatchResult.Cancelled
                else -> {
                    val error = installErrors.value[artifact.packageName] ?: AppError.Unknown()
                    when (val cause = error.cause) {
                        is AndroidInstallPaused -> SuggestionBatchResult.Paused(cause.reason)
                        is ExtensionInstallInvalidated -> SuggestionBatchResult.Invalidated(cause.reason)
                        else -> SuggestionBatchResult.Failed(error)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { finished.await() }
            installArbiter.release(lease)
        }
    }

    /**
     * Returns a flow of the installation process for the given extension. It will complete
     * once the extension is updated or throws an error. The process will be canceled if
     * unsubscribed before its completion.
     *
     * @param extension The extension to be updated.
     */
    fun updateExtension(extension: Extension.Installed): Flow<InstallStep> {
        val availableExt = availableExtensionMapFlow.value[extension.pkgName] ?: return emptyFlow()
        if (extension.repoUrl != null && extension.repoUrl.normalizedRepo() != availableExt.repoUrl.normalizedRepo()) {
            return flowOf(InstallStep.Error)
        }
        return installExtension(availableExt)
    }

    fun cancelInstallUpdateExtension(extension: Extension) {
        installer.cancelInstall(extension.pkgName)
    }

    /**
     * Sets to "installing" status of an extension installation.
     *
     * @param transactionId The id of the install transaction.
     */
    fun setInstalling(transactionId: String) {
        installer.updateInstallStep(transactionId, InstallStep.Installing)
    }

    fun pauseInstall(transactionId: String, reason: SuggestionBatchPause) =
        installer.pauseInstall(transactionId, reason)

    suspend fun verifyInstalledTransaction(transactionId: String): Boolean = installer.verifyInstalledTransaction(
        transactionId,
    )

    fun updateInstallStep(transactionId: String, step: InstallStep) {
        installer.updateInstallStep(transactionId, step)
    }

    /**
     * Uninstalls the extension that matches the given package name.
     *
     * @param extension The extension to uninstall.
     */
    private val pendingInstallWindows = mutableMapOf<String, ExtensionSystemWindowLease>()
    private val completedInstallWindows = mutableSetOf<String>()

    /** Recover only exclusion for the original OS window, never its install request or batch. */
    fun restoreInstallWindow(
        transactionId: String,
        packageName: String,
    ): Boolean = synchronized(pendingInstallWindows) {
        if (transactionId.isBlank() || packageName.isBlank() || transactionId in completedInstallWindows) {
            return@synchronized false
        }
        pendingInstallWindows[transactionId]?.let { return@synchronized it.packageName == packageName }
        installer.pendingSystemPackage(transactionId)?.let { return@synchronized it == packageName }
        val lease = installArbiter.reserveSystemWindow(packageName) ?: return@synchronized false
        pendingInstallWindows[transactionId] = lease
        true
    }

    fun completeInstallWindow(transactionId: String, step: InstallStep) {
        val restored = synchronized(pendingInstallWindows) {
            if (!completedInstallWindows.add(transactionId)) return
            pendingInstallWindows.remove(transactionId)
        }
        if (restored == null) {
            installer.updateInstallStep(transactionId, step)
        } else {
            // A dead process cannot authenticate/finish the old install transaction. Reconcile actual inventory.
            requestInventoryRefresh().invokeOnCompletion { installArbiter.releaseSystemWindow(restored) }
        }
    }

    private val pendingRemovals = mutableMapOf<String, ExtensionRemovalLease>()
    private val completedRemovals = mutableSetOf<String>()

    fun restoreUninstall(transactionId: String, packageName: String): Boolean = synchronized(pendingRemovals) {
        if (transactionId.isBlank() || packageName.isBlank() || transactionId in completedRemovals) {
            return@synchronized false
        }
        pendingRemovals[transactionId]?.let { return@synchronized it.packageName == packageName }
        if (installer.hasSystemRemoval(transactionId)) {
            return@synchronized installer.restoreSystemRemoval(transactionId, packageName) == true
        }
        val lease = installArbiter.reserveRemoval(packageName) ?: return@synchronized false
        pendingRemovals[transactionId] = lease
        true
    }

    fun completeUninstall(transactionId: String, resultCode: Int = android.app.Activity.RESULT_CANCELED) {
        synchronized(pendingRemovals) { completedRemovals += transactionId }
        if (!installer.completeSystemRemoval(transactionId, resultCode)) {
            synchronized(pendingRemovals) {
                pendingRemovals.remove(transactionId)?.let(installArbiter::releaseRemoval)
            }
        }
        requestInventoryRefresh()
    }

    fun uninstallExtension(extension: Extension) {
        val transactionId = UUID.randomUUID().toString()
        if (!restoreUninstall(transactionId, extension.pkgName)) return
        try {
            if (!installer.uninstallApk(extension.pkgName, transactionId)) completeUninstall(transactionId)
        } catch (failure: Throwable) {
            completeUninstall(transactionId)
            throw failure
        }
    }

    /**
     * Adds the given extension to the list of trusted extensions. It also loads in background the
     * now trusted extensions.
     *
     * @param extension the extension to trust
     */
    suspend fun trust(extension: Extension.Untrusted) {
        untrustedExtensionMapFlow.value[extension.pkgName] ?: return

        trustExtension.trust(extension.pkgName, extension.versionCode, extension.signatureHash)

        untrustedExtensionMapFlow.update { it - extension.pkgName }

        extensionLoader(context, extension.pkgName)
            .let { it as? LoadResult.Success }
            ?.let { registerNewExtension(it.extension) }
        requestInventoryRefresh()
    }

    /**
     * Registers the given extension in this and the source managers.
     *
     * @param extension The extension to be registered.
     */
    private fun registerNewExtension(extension: Extension.Installed) {
        installedExtensionMapFlow.update { it + extension }
    }

    /**
     * Registers the given updated extension in this and the source managers previously removing
     * the outdated ones.
     *
     * @param extension The extension to be registered.
     */
    private fun registerUpdatedExtension(extension: Extension.Installed) {
        installedExtensionMapFlow.update { it + extension }
    }

    private suspend fun reloadInstalledExtension(pkgName: String) {
        when (val result = extensionLoader(context, pkgName)) {
            is LoadResult.Success -> {
                acceptInstallationEvent(InstallationEvent.Reloaded(result.extension.withUpdateCheck()))
            }
            is LoadResult.Untrusted -> {
                throw ExtensionInstallFailure(
                    AppError.Authentication(
                        IllegalStateException("Installed extension requires explicit trust confirmation: $pkgName"),
                    ),
                )
            }
            else -> throw ExtensionInstallFailure(
                AppError.MalformedData(IllegalStateException("Installed extension could not be reloaded: $pkgName")),
            )
        }
    }

    /**
     * Unregisters the extension in this and the source managers given its package name. Note this
     * method is called for every uninstalled application in the system.
     *
     * @param pkgName The package name of the uninstalled application.
     */
    private fun unregisterExtension(pkgName: String) {
        installedExtensionMapFlow.update { it - pkgName }
        untrustedExtensionMapFlow.update { it - pkgName }
    }

    /**
     * Listener which receives events of the extensions being installed, updated or removed.
     */
    private inner class InstallationListener : ExtensionInstallReceiver.Listener {

        override fun onPackageChanged(pkgName: String) {
            if (!installer.isInstallTransactionActive(pkgName)) requestInventoryRefresh()
        }

        override fun onExtensionInstalled(extension: Extension.Installed) {
            if (installer.isInstallTransactionActive(extension.pkgName)) return
            acceptInstallationEvent(InstallationEvent.Installed(extension.withUpdateCheck()))
        }

        override fun onExtensionUpdated(extension: Extension.Installed) {
            if (installer.isInstallTransactionActive(extension.pkgName)) return
            acceptInstallationEvent(InstallationEvent.Updated(extension.withUpdateCheck()))
        }

        override fun onExtensionUntrusted(extension: Extension.Untrusted) {
            if (installer.isInstallTransactionActive(extension.pkgName)) return
            acceptInstallationEvent(InstallationEvent.Untrusted(extension))
        }

        override fun onPackageUninstalled(pkgName: String) {
            if (installer.isInstallTransactionActive(pkgName)) return
            ExtensionLoader.uninstallPrivateExtension(context, pkgName)
            acceptInstallationEvent(InstallationEvent.Uninstalled(pkgName))
        }
    }

    private fun acceptInstallationEvent(event: InstallationEvent) {
        val applied = synchronized(installationStateLock) {
            if (!installationEventsLive) {
                pendingInstallationEvents += event
                false
            } else {
                installedExtensionMapFlow.update(event::applyToInstalled)
                untrustedExtensionMapFlow.update(event::applyToUntrusted)
                true
            }
        }
        if (applied) {
            updatePendingUpdatesCount()
        }
        requestInventoryRefresh()
    }

    private sealed interface InstallationEvent {
        fun applyToInstalled(
            extensions: Map<String, Extension.Installed>,
        ): Map<String, Extension.Installed>

        fun applyToUntrusted(
            extensions: Map<String, Extension.Untrusted>,
        ): Map<String, Extension.Untrusted>

        data class Installed(val extension: Extension.Installed) : InstallationEvent {
            override fun applyToInstalled(extensions: Map<String, Extension.Installed>) =
                extensions + (extension.pkgName to extension)
            override fun applyToUntrusted(extensions: Map<String, Extension.Untrusted>) = extensions
        }

        data class Updated(val extension: Extension.Installed) : InstallationEvent {
            override fun applyToInstalled(extensions: Map<String, Extension.Installed>) =
                extensions + (extension.pkgName to extension)
            override fun applyToUntrusted(extensions: Map<String, Extension.Untrusted>) = extensions
        }

        data class Reloaded(val extension: Extension.Installed) : InstallationEvent {
            override fun applyToInstalled(extensions: Map<String, Extension.Installed>) =
                extensions + (extension.pkgName to extension)

            override fun applyToUntrusted(extensions: Map<String, Extension.Untrusted>) =
                extensions - extension.pkgName
        }

        data class Untrusted(val extension: Extension.Untrusted) : InstallationEvent {
            override fun applyToInstalled(extensions: Map<String, Extension.Installed>) =
                extensions - extension.pkgName

            override fun applyToUntrusted(extensions: Map<String, Extension.Untrusted>) =
                extensions + (extension.pkgName to extension)
        }

        data class Uninstalled(val pkgName: String) : InstallationEvent {
            override fun applyToInstalled(extensions: Map<String, Extension.Installed>) = extensions - pkgName
            override fun applyToUntrusted(extensions: Map<String, Extension.Untrusted>) = extensions - pkgName
        }
    }

    /**
     * Extension method to set the update field of an installed extension.
     */
    private fun Extension.Installed.withUpdateCheck(): Extension.Installed {
        return if (updateExists()) {
            copy(hasUpdate = true)
        } else {
            this
        }
    }

    private fun Extension.Installed.updateExists(availableExtension: Extension.Available? = null): Boolean {
        val availableExt = availableExtension
            ?: availableExtensionMapFlow.value[pkgName]
            ?: return false

        if (availableExt.compatibility != ExtensionCompatibility.Compatible) return false

        return updatePolicy.isUpdateAvailable(
            availableVersionCode = availableExt.versionCode,
            availableLibVersion = availableExt.libVersion,
            installedVersionCode = versionCode,
            installedLibVersion = libVersion,
        )
    }

    private fun updatePendingUpdatesCount() {
        val pendingUpdateCount = installedExtensionMapFlow.value.values.count { it.hasUpdate }
        preferences.extensionUpdatesCount().set(pendingUpdateCount)
        if (pendingUpdateCount == 0) {
            ExtensionUpdateNotifier(context).dismiss()
        }
    }

    private operator fun <T : Extension> Map<String, T>.plus(extension: T) = plus(extension.pkgName to extension)
}

private fun String.normalizedRepo(): String = trim().trimEnd('/')

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private fun <T : Extension> StateFlow<Map<String, T>>.mapValues(): StateFlow<List<T>> {
    val source = this
    return object : StateFlow<List<T>> {
        override val replayCache: List<List<T>>
            get() = listOf(value)

        override val value: List<T>
            get() = source.value.values.toList()

        override suspend fun collect(collector: FlowCollector<List<T>>): Nothing {
            source.collect { collector.emit(it.values.toList()) }
            awaitCancellation()
        }
    }
}
