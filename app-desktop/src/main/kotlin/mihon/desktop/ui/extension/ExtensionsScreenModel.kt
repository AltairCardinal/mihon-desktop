package mihon.desktop.ui.extension

import kotlinx.coroutines.CancellationException
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

data class DesktopPendingTrust(
    val packageName: String,
    val request: DesktopExtensionInstallStart.TrustRequired,
)

data class DesktopExtensionsState(
    val searchQuery: String = "",
    val projection: DesktopExtensionProjection? = null,
    val presentation: ExtensionPresentationResult<DesktopExtensionItem>? = null,
    val actions: ExtensionPresentationActionState = ExtensionPresentationActionState(),
    val options: ExtensionPresentationOptions,
    val refreshError: Throwable? = null,
    val reloadError: Throwable? = null,
    val rawInstallStates: Map<String, ExtensionInstallState> = emptyMap(),
    val installErrors: Map<String, AppError> = emptyMap(),
    val pendingTrust: DesktopPendingTrust? = null,
    val disabledSourceIds: Set<String> = emptySet(),
)

class ExtensionsScreenModel(
    private val port: DesktopExtensionPresentationPort,
    parentScope: CoroutineScope? = null,
    initialOptions: ExtensionPresentationOptions,
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
    internal val closed get() = synchronized(lock) { isClosed }
    internal val activeJobCount get() = synchronized(lock) { packageJobs.values.count(Job::isActive) }

    init {
        scope.launch {
            combine(port.installedExtensions, options, port.disabledSources) { _, currentOptions, disabledSources ->
                currentOptions to disabledSources
            }.collect { (currentOptions, disabledSources) ->
                mutableState.update { it.copy(disabledSourceIds = disabledSources) }
                publish(currentOptions)
            }
        }
    }

    fun refresh(): Job = synchronized(lock) {
        refreshJob?.takeIf(Job::isActive) ?: scope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext()[Job]!!
            dispatch(ExtensionPresentationAction.RefreshStarted)
            try {
                latestCatalog = port.refresh()
                publish(options.value, clearRefreshError = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(refreshError = error) }
            } finally {
                dispatch(ExtensionPresentationAction.RefreshFinished)
                synchronized(lock) { if (refreshJob === self) refreshJob = null }
            }
        }.also { job ->
            refreshJob = job
            job.start()
        }
    }

    fun setOptions(value: ExtensionPresentationOptions) {
        options.value = value
        mutableState.update { it.copy(options = value) }
    }

    fun search(query: String) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun updateAllCandidates() = latestCatalog?.let(port::canonicalCandidates)?.values.orEmpty().filter { candidate ->
        state.value.projection?.installed.orEmpty().any {
            it.presentation.hasUpdate && it.operationPackageName == candidate.pkgName
        }
    }

    fun install(item: DesktopExtensionItem): Job {
        checkOpen()
        val extension = requireNotNull(item.available)
        return launchPackage(item.operationPackageName) {
            clearEvidence(item.operationPackageName)
            dispatchStep(item.operationPackageName, ExtensionPresentationInstallStep.Pending)
            when (val start = port.beginPresentationInstall(extension)) {
                is DesktopPresentationInstallStart.Started -> collectInstall(item.operationPackageName, start.events)
                is DesktopPresentationInstallStart.TrustRequired -> enqueuePending(
                    DesktopPendingTrust(item.operationPackageName, start.request),
                )
                is DesktopPresentationInstallStart.Rejected -> recordError(item.operationPackageName, start.error)
            }
        }
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
            takePending(packageName)?.let { port.discardTrust(it.request.requestId) }
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
                port.confirmPresentationTrust(pending.request.requestId)?.let {
                    collectInstall(pending.packageName, it)
                }
                completed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            } finally {
                finishActive(pending, completed)
            }
        }
    }

    fun dismissTrust(): Boolean {
        checkOpen()
        val pending = takePending() ?: return false
        return try {
            port.discardTrust(pending.request.requestId)
        } finally {
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

    private fun publish(currentOptions: ExtensionPresentationOptions, clearRefreshError: Boolean = false) {
        val catalog = latestCatalog ?: EMPTY_CATALOG
        val projection = port.project(catalog)
        mutableState.update {
            it.copy(
                projection = projection,
                presentation = port.classify(projection, currentOptions),
                options = currentOptions,
                refreshError = if (clearRefreshError) null else it.refreshError,
            )
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

    private suspend fun collectInstall(packageName: String, events: Flow<DesktopPresentationInstallEvent>) {
        var lastStep: ExtensionPresentationInstallStep? = null
        events.onEach { event ->
            lastStep = event.step
            dispatchStep(packageName, event.step)
            event.raw?.let { raw ->
                mutableState.update { it.copy(rawInstallStates = it.rawInstallStates + (packageName to raw)) }
                (raw as? ExtensionInstallState.Failed)?.error?.let { error ->
                    if (error == AppError.Cancelled) clearEvidence(packageName) else recordError(packageName, error)
                }
            }
        }.takeWhile { desktopExtensionPresentationStore.shouldContinue(it.step) }.collect()
        if (lastStep == ExtensionPresentationInstallStep.Installed || lastStep == ExtensionPresentationInstallStep.Idle) {
            clearTerminal(packageName)
        }
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
        val EMPTY_CATALOG = DesktopExtensionCatalogState(
            catalog = mihon.domain.extension.model.ExtensionCatalogResult(emptyList(), emptyList()),
            available = emptyList(),
        )
    }
}
