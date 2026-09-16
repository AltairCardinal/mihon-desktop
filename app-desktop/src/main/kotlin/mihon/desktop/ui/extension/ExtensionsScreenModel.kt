package mihon.desktop.ui.extension

import mihon.domain.extension.service.ExtensionInstallBusy
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.suggestion.ObserveExtensionSuggestions
import mihon.domain.extension.suggestion.ExtensionSuggestions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import mihon.domain.extension.model.ExtensionArtifact
import mihon.domain.extension.service.ExtensionInstallLease
import mihon.domain.extension.service.ExtensionInstallInvalidation
import mihon.domain.extension.service.ExtensionInstallInvalidated
import mihon.domain.extension.suggestion.ExtensionSuggestionBatchController
import mihon.domain.extension.suggestion.SuggestionBatchInstallPort
import mihon.domain.extension.suggestion.SuggestionBatchResult
import mihon.domain.extension.suggestion.SuggestionIdentity
import mihon.domain.extension.suggestion.suggestionIdentityKey
import mihon.domain.extension.suggestion.ExtensionInventory
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.desktop.extension.DesktopExtensionInstallStart
import mihon.domain.error.AppError
import mihon.domain.extension.presentation.ExtensionPresentationAction
import mihon.domain.extension.presentation.ExtensionPresentationActionState
import mihon.domain.extension.presentation.ExtensionPresentationInstallStep
import mihon.domain.extension.presentation.ExtensionPresentationOptions
import mihon.domain.extension.presentation.ExtensionPresentationResult
import mihon.domain.extension.service.ExtensionInstallState
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.model.toIdentity
import mihon.domain.extensionrepo.model.ExtensionRepo

data class DesktopPendingTrust(
    val packageName: String,
    val request: DesktopExtensionInstallStart.TrustRequired,
    val batchTransactionId: Long? = null,
)

data class DesktopExtensionsState(
    val suggestions: ExtensionSuggestions = ExtensionSuggestions(),
    val suggestionPanel: mihon.domain.extension.suggestion.SuggestionPanelState = mihon.domain.extension.suggestion.SuggestionPanelState(),
    val searchQuery: String = "",
    val projection: DesktopExtensionProjection? = null,
    val presentation: ExtensionPresentationResult<DesktopExtensionItem>? = null,
    val actions: ExtensionPresentationActionState = ExtensionPresentationActionState(),
    val options: ExtensionPresentationOptions,
    val hasLoadedCatalog: Boolean = false,
    val configuredRepositoryCount: Int? = null,
    val refreshError: Throwable? = null,
    val reloadError: Throwable? = null,
    val rawInstallStates: Map<String, ExtensionInstallState> = emptyMap(),
    val installErrors: Map<String, AppError> = emptyMap(),
    val pendingTrust: DesktopPendingTrust? = null,
    val disabledSourceIds: Set<String> = emptySet(),
    val reservedSteps: Map<String, ExtensionPresentationInstallStep> = emptyMap(),
) {
    val installSteps get() = actions.installSteps + reservedSteps
}

class ExtensionsScreenModel(
    private val port: DesktopExtensionPresentationPort,
    parentScope: CoroutineScope? = null,
    initialOptions: ExtensionPresentationOptions,
    private val onShowNsfwChanged: (Boolean) -> Unit = {},
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val catalogFreshnessMillis: Long = DEFAULT_CATALOG_FRESHNESS_MILLIS,
    private val suggestionObserver: ObserveExtensionSuggestions? = null,
    private val suggestionPreferences: mihon.domain.extension.suggestion.ExtensionSuggestionPreferences =
        mihon.domain.extension.suggestion.ExtensionSuggestionPreferences(tachiyomi.core.common.preference.InMemoryPreferenceStore()),
) {
    private val ownerJob = SupervisorJob(parentScope?.coroutineContext?.get(Job))
    private val scope = CoroutineScope((parentScope?.coroutineContext ?: Dispatchers.Default) + ownerJob)
    private val options = MutableStateFlow(initialOptions)
    private val mutableState = MutableStateFlow(DesktopExtensionsState(options = initialOptions))
    val state: StateFlow<DesktopExtensionsState> = mutableState.asStateFlow()
    private val lock = Any()
    private val packageJobs = mutableMapOf<String, Job>()
    private var refreshJob: Job? = null
    private val pendingTrustQueue = mutableListOf<DesktopPendingTrust>()
    private var activeTrust: DesktopPendingTrust? = null
    private var isClosed = false
    private var latestCatalog: DesktopExtensionCatalogState? = null
    private var configuredCatalogIdentities: List<RepositoryIdentity>? = null
    private val suggestionCatalog = MutableStateFlow<ExtensionCatalogResult?>(null)
    private var catalogLoadedAtMillis: Long? = null
    private val currentInventory = MutableStateFlow(ExtensionInventory())
    private val batchOperations = mutableMapOf<Long, BatchOperation>()
    internal val closed get() = synchronized(lock) { isClosed }
    internal val activeJobCount get() = synchronized(lock) { packageJobs.values.count(Job::isActive) }

    val suggestionPanel = mihon.domain.extension.suggestion.ExtensionSuggestionPanel(
        scope, state.map { it.suggestions }.distinctUntilChanged(),
        state.map { it.installSteps }.distinctUntilChanged(), suggestionPreferences,
    )

    val suggestionBatch by lazy {
        ExtensionSuggestionBatchController(
            scope, port.installArbiter,
            SuggestionBatchInstallPort(::installReserved),
            ::suggestionEligibility,
        )
    }

    fun suggestionSnapshot() = suggestionPanel.state.value.rows.filter { it.canInstall }.map { it.suggestion.artifact }

    fun confirmSuggestionBatch(snapshot: List<mihon.domain.extension.model.ExtensionArtifact>) = suggestionBatch.start(snapshot)

    fun updatedBatchSnapshot(previous: List<ExtensionArtifact>): List<ExtensionArtifact> = previous.map { artifact ->
        val candidates = batchReplacementCandidates(artifact)
        val explicitlySelected = suggestionPanel.state.value.selected.values.toSet()
        candidates.singleOrNull { SuggestionIdentity.of(it) in explicitlySelected }
            ?: candidates.firstOrNull { SuggestionIdentity.of(it) == SuggestionIdentity.of(artifact) } ?: artifact
    }

    fun batchReplacementCandidates(artifact: ExtensionArtifact): List<ExtensionArtifact> =
        suggestionCatalog.value?.entries.orEmpty()
            .filter { it.artifact.packageName == artifact.packageName && it.compatibility == mihon.domain.extension.model.ExtensionCompatibility.Compatible }
            .map { it.artifact }.groupBy { SuggestionIdentity.of(it) }
            .values.map { versions -> versions.maxBy { it.versionCode } }

    fun isCurrentBatchArtifact(artifact: ExtensionArtifact) = artifact in batchReplacementCandidates(artifact)

    fun batchSourcesConflict(snapshot: List<ExtensionArtifact>, continuing: Boolean): Boolean {
        val installed = if (continuing) suggestionBatch.state.value.items
            .filter { it.result == SuggestionBatchResult.Installed }.map { it.artifact } else emptyList()
        val sources = (snapshot + installed).flatMap { it.sources.map { source -> source.id }.distinct() }
        return sources.size != sources.distinct().size
    }

    init {
        scope.launch { port.inventory.collect { currentInventory.value = it } }
        scope.launch {
            port.installArbiter.reservations.collect { reservations ->
                mutableState.update { it.copy(reservedSteps = reservations.mapValues { (_, reservation) ->
                    reservation.progress?.presentationStep() ?: ExtensionPresentationInstallStep.Pending
                }) }
            }
        }
        scope.launch { suggestionPanel.state.collect { panel -> mutableState.update { it.copy(suggestionPanel = panel) } } }
        require(catalogFreshnessMillis >= 0) { "Catalog freshness must not be negative" }
        suggestionObserver?.let { observer ->
            scope.launch {
                observer.subscribe(suggestionCatalog, port.inventory, options.map { it.showNsfw }, suggestionPreferences.ignoredIdentities()).collect { result ->
                    mutableState.update { it.copy(suggestions = result) }
                }
            }
        }
        scope.launch {
            combine(port.installedExtensions, options, port.disabledSources) { _, currentOptions, disabledSources ->
                currentOptions to disabledSources
            }.collect { (currentOptions, disabledSources) ->
                mutableState.update { it.copy(disabledSourceIds = disabledSources) }
                publish(currentOptions)
            }
        }
        port.configuredRepositories?.let { repositories ->
            scope.launch {
                repositories.collect(::onRepositoriesChanged)
            }
        }
    }

    fun refresh(): Job = synchronized(lock) {
        refreshJob?.takeIf(Job::isActive) ?: run {
            scope.launch(start = CoroutineStart.LAZY) {
                val self = currentCoroutineContext()[Job]!!
                dispatch(ExtensionPresentationAction.RefreshStarted)
                try {
                    val refreshedCatalog = port.refresh()
                    val accepted = synchronized(lock) {
                        if (isClosed) {
                            false
                        } else {
                            latestCatalog = refreshedCatalog
                            catalogLoadedAtMillis = nowMillis().takeIf { refreshedCatalog.catalog.failures.isEmpty() }
                            true
                        }
                    }
                    if (accepted) publish(options.value, clearRefreshError = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val accepted = synchronized(lock) {
                        (!isClosed).also {
                            if (it) catalogLoadedAtMillis = null
                        }
                    }
                    if (accepted) mutableState.update { it.copy(refreshError = error) }
                } finally {
                    dispatch(ExtensionPresentationAction.RefreshFinished)
                    synchronized(lock) { if (refreshJob === self) refreshJob = null }
                }
            }.also { job ->
                refreshJob = job
                job.start()
            }
        }
    }

    fun refreshIfStale(): Job? {
        val currentTime = nowMillis()
        synchronized(lock) {
            refreshJob?.takeIf(Job::isActive)?.let { return it }
            catalogLoadedAtMillis?.let { loadedAt ->
                if (currentTime >= loadedAt && currentTime - loadedAt < catalogFreshnessMillis) return null
            }
        }
        return refresh()
    }

    fun setOptions(value: ExtensionPresentationOptions) {
        if (options.value.showNsfw != value.showNsfw) {
            onShowNsfwChanged(value.showNsfw)
        }
        options.value = value
        mutableState.update { it.copy(options = value) }
    }

    fun search(query: String) {
        suggestionPanel.search(query)
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun updateAllCandidates() = latestCatalog?.let(port::canonicalCandidates)?.values.orEmpty().filter { candidate ->
        state.value.projection?.installed.orEmpty().any {
            it.presentation.hasUpdate && it.operationPackageName == candidate.pkgName
        }
    }

    fun install(item: DesktopExtensionItem): Job = synchronized(lock) {
        packageJobs[item.operationPackageName]?.takeIf { it.isActive }?.let { return@synchronized it }
        if (state.value.installSteps[item.operationPackageName]?.isCompleted() == false) {
            return@synchronized scope.launch {}
        }
        checkOpen()
        val extension = requireNotNull(item.available)
        return@synchronized launchPackage(item.operationPackageName) {
            clearEvidence(item.operationPackageName)
            dispatchStep(item.operationPackageName, ExtensionPresentationInstallStep.Pending)
            when (val start = port.beginPresentationInstall(extension)) {
                is DesktopPresentationInstallStart.Started -> collectInstall(item.operationPackageName, start.events)
                is DesktopPresentationInstallStart.TrustRequired -> enqueuePending(
                    DesktopPendingTrust(item.operationPackageName, start.request),
                )
                is DesktopPresentationInstallStart.Rejected -> {
                    if (start.error.cause is ExtensionInstallBusy) clearTerminal(item.operationPackageName)
                    else recordError(item.operationPackageName, start.error)
                }
            }
        }
    }

    fun installSuggestion(identity: mihon.domain.extension.suggestion.SuggestionIdentity): Job? {
        val artifact = suggestionPanel.installable(identity) ?: return null
        if (suggestionCatalog.value?.entries?.none { it.artifact == artifact } != false) return null
        val candidate = latestCatalog?.available?.firstOrNull {
            it.pkgName == artifact.packageName && it.repoUrl == artifact.repository.baseUrl &&
                it.repoFingerprint == artifact.repository.signingKeyFingerprint && it.versionCode == artifact.versionCode &&
                it.libVersion == artifact.libVersion
        } ?: return null
        return install(candidate.item())
    }

    fun update(item: DesktopExtensionItem): Job? {
        checkOpen()
        return latestCatalog?.let(port::canonicalCandidates)?.get(item.operationPackageName)?.let { install(it.item()) }
    }

    fun retry(item: DesktopExtensionItem): Job? = if (
        state.value.presentation?.updates.orEmpty().any { it.operationPackageName == item.operationPackageName }
    ) update(item) else install(item)

    fun updateAll(): List<Job> = updateAllCandidates().map { install(it.item()) }

    fun cancel(packageName: String): Job {
        checkOpen()
        return scope.launch {
            synchronized(lock) { packageJobs[packageName] }?.cancelAndJoin()
            takePending(packageName)?.let { pending ->
                port.discardTrust(pending.request.requestId)
                pending.batchTransactionId?.let { synchronized(lock) { batchOperations[it] } }
                    ?.completion?.complete(SuggestionBatchResult.Cancelled)
            }
            clearTerminal(packageName)
            publishPending()
        }
    }

    fun confirmTrust(): Job? {
        checkOpen()
        val pending = activatePending() ?: return null
        return launchPackage(pending.packageName) {
            var completed = false
            try {
                val operation = pending.batchTransactionId?.let { synchronized(lock) { batchOperations[it] } }
                operation?.job = currentCoroutineContext()[Job]
                val terminal = port.confirmPresentationTrust(pending.request.requestId)?.let {
                    collectInstall(pending.packageName, it, operation?.onProgress)
                }
                operation?.completion?.complete(terminal.batchResult())
                completed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                pending.batchTransactionId?.let { synchronized(lock) { batchOperations[it] } }
                    ?.completion?.complete(SuggestionBatchResult.Failed(AppError.Unknown(failure)))
            } finally {
                finishActive(pending, completed)
                pending.batchTransactionId?.let { synchronized(lock) { batchOperations[it] } }
                    ?.completion?.complete(SuggestionBatchResult.Cancelled)
            }
        }
    }

    fun dismissTrust(): Boolean {
        checkOpen()
        val pending = takePending() ?: return false
        return try {
            port.discardTrust(pending.request.requestId)
        } finally {
            pending.batchTransactionId?.let { synchronized(lock) { batchOperations[it] } }
                ?.completion?.complete(SuggestionBatchResult.Cancelled)
            clearTerminal(pending.packageName)
            publishPending()
        }
    }

    fun uninstall(item: DesktopExtensionItem): Boolean = port.uninstall(item)

    fun reloadInstalled(): Job = scope.launch {
        try {
            port.reloadInstalled()
            mutableState.update { it.copy(reloadError = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(reloadError = error) }
        }
    }

    fun acknowledgeReloadError(error: Throwable) {
        mutableState.update { state ->
            if (state.reloadError === error) state.copy(reloadError = null) else state
        }
    }

    fun extensionSources(item: DesktopExtensionItem): List<DesktopExtensionSourceItem> =
        item.installed?.let { port.extensionSources(it, state.value.disabledSourceIds) }.orEmpty()

    fun setSourceEnabled(sourceId: Long, enabled: Boolean) = port.setSourceEnabled(sourceId, enabled)

    fun setSourcesEnabled(item: DesktopExtensionItem, enabled: Boolean) {
        item.installed?.let { port.setSourcesEnabled(it, enabled) }
    }

    suspend fun closeAndJoin() {
        val shouldClose = synchronized(lock) { (!isClosed).also { if (it) isClosed = true } }
        if (!shouldClose) return
        drainPending()
        ownerJob.cancelAndJoin()
        drainPending()
        drainActive()
    }

    private fun publish(currentOptions: ExtensionPresentationOptions, clearRefreshError: Boolean = false) = synchronized(lock) {
        val loadedCatalog = latestCatalog
        suggestionCatalog.value = loadedCatalog?.catalog?.takeIf { catalog ->
            configuredCatalogIdentities?.let { catalog.repositories.matches(it) } != false
        }
        val catalog = loadedCatalog ?: EMPTY_CATALOG
        val projection = port.project(catalog)
        mutableState.update {
            it.copy(
                projection = projection,
                presentation = port.classify(projection, currentOptions),
                options = currentOptions,
                hasLoadedCatalog = loadedCatalog != null,
                refreshError = if (clearRefreshError) null else it.refreshError,
            )
        }
    }

    private suspend fun onRepositoriesChanged(repositories: List<ExtensionRepo>) {
        val snapshot = repositories.toList()
        val identities = snapshot.map { it.toIdentity() }
        synchronized(lock) {
            configuredCatalogIdentities = identities
            if (suggestionCatalog.value?.repositories.matches(identities).not()) suggestionCatalog.value = null
        }
        mutableState.update { it.copy(configuredRepositoryCount = snapshot.size) }
        val activeRefresh = synchronized(lock) { refreshJob?.takeIf(Job::isActive) }

        if (identities.isEmpty()) {
            activeRefresh?.cancelAndJoin()
            synchronized(lock) {
                latestCatalog = EMPTY_CATALOG
                catalogLoadedAtMillis = nowMillis()
            }
            publish(options.value, clearRefreshError = true)
            return
        }

        activeRefresh?.join()
        if (closed) return
        val catalogMatchesConfiguration = synchronized(lock) {
            latestCatalog?.catalog?.repositories.matches(identities)
        }
        if (!catalogMatchesConfiguration) {
            synchronized(lock) {
                latestCatalog = null
                catalogLoadedAtMillis = null
            }
            publish(options.value, clearRefreshError = true)
            refresh()
        }
    }

    private fun dispatch(action: ExtensionPresentationAction) {
        mutableState.update { it.copy(actions = port.reduceActions(it.actions, action)) }
    }

    private fun launchPackage(packageName: String, block: suspend () -> Unit): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val self = currentCoroutineContext()[Job]!!
            val previous = synchronized(lock) { packageJobs.put(packageName, self) }
            previous?.cancelAndJoin()
            try {
                block()
            } finally {
                synchronized(lock) { if (packageJobs[packageName] === self) packageJobs.remove(packageName) }
            }
        }

    private suspend fun collectInstall(packageName: String, events: Flow<DesktopPresentationInstallEvent>, onProgress: ((ExtensionInstallState) -> Unit)? = null): ExtensionInstallState? {
        var lastStep: ExtensionPresentationInstallStep? = null
        var terminal: ExtensionInstallState? = null
        events.onEach { event ->
            val invalidated = (event.raw as? ExtensionInstallState.Failed)?.error?.cause is ExtensionInstallInvalidated
            val step = if (invalidated) ExtensionPresentationInstallStep.Idle else event.step
            lastStep = step
            dispatchStep(packageName, step)
            event.raw?.let { raw ->
                terminal = raw
                onProgress?.invoke(raw)
                mutableState.update { it.copy(rawInstallStates = it.rawInstallStates + (packageName to raw)) }
                (raw as? ExtensionInstallState.Failed)?.error?.let { error ->
                    if (error == AppError.Cancelled || error.cause is ExtensionInstallInvalidated) {
                        clearEvidence(packageName)
                    } else recordError(packageName, error)
                }
            }
        }.takeWhile { desktopExtensionPresentationStore.shouldContinue(it.step) }.collect()
        if (lastStep == ExtensionPresentationInstallStep.Installed || lastStep == ExtensionPresentationInstallStep.Idle) {
            clearTerminal(packageName)
        }
        return terminal
    }

    private fun suggestionEligibility(artifact: ExtensionArtifact): ExtensionInstallInvalidation? {
        if (suggestionIdentityKey(SuggestionIdentity.of(artifact)) in suggestionPreferences.ignored.get().lineSequence()) {
            return ExtensionInstallInvalidation.IGNORED
        }
        val inventory = currentInventory.value
        if (!inventory.initialized || inventory.hasUnknownArtifacts) return ExtensionInstallInvalidation.INVENTORY_UNKNOWN
        if (artifact.packageName in inventory.records) return ExtensionInstallInvalidation.PRESENT
        if (suggestionCatalog.value?.entries?.any { it.artifact == artifact } != true) return ExtensionInstallInvalidation.CATALOG_CHANGED
        val suggestions = state.value.suggestions
        if (suggestions.isLoading) return ExtensionInstallInvalidation.INVENTORY_UNKNOWN
        if (suggestions.suggestions.none { it.identity == SuggestionIdentity.of(artifact) }) return ExtensionInstallInvalidation.INELIGIBLE
        return null
    }

    private suspend fun installReserved(lease: ExtensionInstallLease, onProgress: (Long, ExtensionInstallState) -> Unit): SuggestionBatchResult {
        val packageName = lease.artifact.packageName
        val operation = BatchOperation(CompletableDeferred()) { onProgress(lease.transactionId, it) }
        synchronized(lock) { batchOperations[lease.transactionId] = operation }
        var waitingForTrust = false
        val job = launchPackage(packageName) {
            try {
                clearEvidence(packageName)
                dispatchStep(packageName, ExtensionPresentationInstallStep.Pending)
                when (val start = port.beginReservedInstall(lease.artifact, lease)) {
                    is DesktopPresentationInstallStart.Started -> operation.completion.complete(
                        collectInstall(packageName, start.events, operation.onProgress).batchResult(),
                    )
                    is DesktopPresentationInstallStart.TrustRequired -> {
                        waitingForTrust = true
                        enqueuePending(DesktopPendingTrust(packageName, start.request, lease.transactionId))
                    }
                    is DesktopPresentationInstallStart.Rejected -> operation.completion.complete(SuggestionBatchResult.Failed(start.error))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                operation.completion.complete(SuggestionBatchResult.Failed(AppError.Unknown(failure)))
            } finally {
                if (!waitingForTrust) operation.completion.complete(SuggestionBatchResult.Cancelled)
            }
        }
        return try {
            operation.completion.await()
        } finally {
            withContext(NonCancellable) {
                if (!operation.completion.isCompleted) {
                    job.cancelAndJoin()
                    synchronized(lock) { packageJobs[packageName] }?.cancelAndJoin()
                    takePending(packageName)?.let { port.discardTrust(it.request.requestId) }
                    publishPending()
                    clearTerminal(packageName)
                } else {
                    (operation.job ?: job).join()
                }
                synchronized(lock) { batchOperations.remove(lease.transactionId, operation) }
            }
        }
    }

    private fun ExtensionInstallState?.batchResult(): SuggestionBatchResult = when (this) {
        is ExtensionInstallState.Installed -> SuggestionBatchResult.Installed
        is ExtensionInstallState.Failed -> when (val invalidation = error.cause) {
            is ExtensionInstallInvalidated -> SuggestionBatchResult.Invalidated(invalidation.reason)
            else -> if (error == AppError.Cancelled) SuggestionBatchResult.Cancelled else SuggestionBatchResult.Failed(error)
        }
        else -> SuggestionBatchResult.Failed(AppError.Unknown(IllegalStateException("Installation ended without a result")))
    }

    private class BatchOperation(val completion: CompletableDeferred<SuggestionBatchResult>, val onProgress: (ExtensionInstallState) -> Unit) {
        @Volatile var job: Job? = null
    }

    private fun dispatchStep(packageName: String, step: ExtensionPresentationInstallStep) =
        dispatch(ExtensionPresentationAction.InstallStepChanged(packageName, step))

    private fun recordError(packageName: String, error: AppError) {
        dispatchStep(packageName, ExtensionPresentationInstallStep.Error)
        mutableState.update { it.copy(installErrors = it.installErrors + (packageName to error)) }
    }

    private fun clearEvidence(packageName: String) {
        mutableState.update {
            it.copy(rawInstallStates = it.rawInstallStates - packageName, installErrors = it.installErrors - packageName)
        }
    }

    private fun clearTerminal(packageName: String) {
        dispatch(ExtensionPresentationAction.InstallFinished(packageName))
        clearEvidence(packageName)
    }

    private fun enqueuePending(next: DesktopPendingTrust) {
        var replaced: DesktopPendingTrust? = null
        val accepted = synchronized(lock) {
            if (isClosed) false else {
                val existingIndex = pendingTrustQueue.indexOfFirst { it.packageName == next.packageName }
                if (existingIndex >= 0) {
                    replaced = pendingTrustQueue.set(existingIndex, next)
                } else {
                    pendingTrustQueue += next
                }
                mutableState.update {
                    it.copy(pendingTrust = if (activeTrust == null) pendingTrustQueue.first() else null)
                }
                true
            }
        }
        if (!accepted) {
            port.discardTrust(next.request.requestId)
            clearTerminal(next.packageName)
        } else {
            replaced?.takeIf { it.request.requestId != next.request.requestId }
                ?.let { port.discardTrust(it.request.requestId) }
        }
    }

    private fun takePending(packageName: String? = null): DesktopPendingTrust? {
        val pending = synchronized(lock) {
            val index = if (packageName == null && activeTrust != null) {
                null
            } else if (packageName == null) {
                pendingTrustQueue.indices.firstOrNull()
            } else {
                pendingTrustQueue.indexOfFirst { it.packageName == packageName }.takeIf { it >= 0 }
            }
            index?.let(pendingTrustQueue::removeAt).also { removed ->
                if (removed != null) {
                    mutableState.update {
                        it.copy(
                            pendingTrust = if (activeTrust == null && index != 0) pendingTrustQueue.firstOrNull() else null,
                        )
                    }
                }
            }
        }
        return pending
    }

    private fun activatePending(): DesktopPendingTrust? = synchronized(lock) {
        if (activeTrust != null) return@synchronized null
        pendingTrustQueue.removeFirstOrNull()?.also { pending ->
            activeTrust = pending
            mutableState.update { it.copy(pendingTrust = null) }
        }
    }

    private fun finishActive(pending: DesktopPendingTrust, completed: Boolean) {
        val isActive = synchronized(lock) { activeTrust?.request?.requestId == pending.request.requestId }
        if (!isActive) return
        if (!completed) runCatching { port.discardTrust(pending.request.requestId) }
        if (!completed || state.value.actions.installSteps[pending.packageName] != ExtensionPresentationInstallStep.Error) {
            clearTerminal(pending.packageName)
        }
        synchronized(lock) {
            if (activeTrust?.request?.requestId == pending.request.requestId) activeTrust = null
            mutableState.update {
                it.copy(pendingTrust = if (isClosed) null else pendingTrustQueue.firstOrNull())
            }
        }
    }

    private fun publishPending() = synchronized(lock) {
        mutableState.update {
            it.copy(pendingTrust = if (isClosed || activeTrust != null) null else pendingTrustQueue.firstOrNull())
        }
    }

    private fun drainPending() {
        val pending = synchronized(lock) {
            pendingTrustQueue.toList().also {
                pendingTrustQueue.clear()
                mutableState.update { state -> state.copy(pendingTrust = null) }
            }
        }
        pending.forEach {
            port.discardTrust(it.request.requestId)
            clearTerminal(it.packageName)
        }
    }

    private fun drainActive() {
        val active = synchronized(lock) {
            activeTrust.also {
                activeTrust = null
                mutableState.update { state -> state.copy(pendingTrust = null) }
            }
        }
        active?.let {
            runCatching { port.discardTrust(it.request.requestId) }
            clearTerminal(it.packageName)
        }
    }

    private fun checkOpen() = check(!closed) { "ExtensionsScreenModel is closed" }

    private companion object {
        const val DEFAULT_CATALOG_FRESHNESS_MILLIS = 5 * 60 * 1_000L
        val EMPTY_CATALOG = DesktopExtensionCatalogState(
            catalog = ExtensionCatalogResult(emptyList(), emptyList()),
            available = emptyList(),
        )
    }
}

private fun List<RepositoryIdentity>?.matches(other: List<RepositoryIdentity>): Boolean =
    this?.toSet() == other.toSet()
