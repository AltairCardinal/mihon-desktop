package mihon.data.sync.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import mihon.data.sync.auth.DiscoveredSyncSpace
import mihon.data.sync.auth.EmptySyncRepositoryCandidate
import mihon.data.sync.auth.SyncAppInstallation
import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.data.sync.auth.SyncGitHubAccount
import mihon.data.sync.auth.SyncRepositoryCreationIntent
import mihon.data.sync.auth.SyncRepositoryCreationPermission
import mihon.data.sync.auth.SyncRepositoryRepairTarget
import mihon.data.sync.auth.SyncSpaceCreation
import mihon.data.sync.auth.SyncSpaceDiscovery
import mihon.data.sync.crypto.SyncPasswordInputException
import mihon.data.sync.crypto.SyncPasswordInputIssue
import mihon.data.sync.crypto.SyncSpaceCrypto
import mihon.data.sync.http.SyncRequiredResource
import mihon.data.sync.http.SyncRequiredResourceUnavailable
import mihon.data.sync.inbox.SyncBulkProgress
import mihon.domain.sync.SyncField
import mihon.domain.sync.auth.GitHubDeviceAuthResult
import mihon.domain.sync.auth.GitHubDeviceCode
import mihon.domain.sync.crypto.SyncSpaceMaterial
import mihon.domain.sync.crypto.SyncSpaceProtection
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import tachiyomi.data.DatabaseHandler
import java.util.concurrent.atomic.AtomicBoolean

class SyncPanelController(
    private val runtime: SyncRuntime,
    private val handler: DatabaseHandler,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
) : SyncPanel {
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow(SyncPanelState())
    override val state: StateFlow<SyncPanelState> = mutableState
    override val diagnosticDirectory: String? get() = runtime.diagnosticDirectory?.toString()
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val refreshQueued = AtomicBoolean(false)
    private val deviceBrowserOpened = AtomicBoolean(false)
    private var references = linkedMapOf<Long, String>()
    private var selectedBindings = emptyMap<Long, String>()
    private var anchor: Long? = null
    private var loadedCount = 100
    private var logLimit = 5L
    private var bulkJob: Job? = null
    private var authJob: Job? = null
    private var recoveryJob: Job? = null
    private var recoveryVersion = 0L
    private var repositoryJob: Job? = null
    private var setupJob: Job? = null
    private var authVersion = 0L
    private var setupVersion = 0L
    private var panelSession = 0L
    private var diagnosticReturnPage = SyncPanelPage.SETTINGS
    private var setupExchangeCompletion = -1L
    private var setupAccount: SyncGitHubAccount? = null
    private var officialIntent: Pair<SyncRecoveryAction, Long?>? = null
    private var repositoryProposal: SyncRepositoryCreationIntent? = null
    private var repositoryProposalRevision: Long? = null
    private var propertyTarget: SyncRepositoryRepairTarget? = null
    private var propertyRevision: Long? = null
    private var chosenSpace: DiscoveredSyncSpace? = null
    private var emptyRepositoryCandidate: EmptySyncRepositoryCandidate? = null
    private var legacyPending: StoredLegacySyncSetup? = null
    private var switchIntent: StoredSyncSpaceSwitch? = null
    private var switchConfirmation: StoredSyncSpaceSwitch? = null
    private var observedCompletion = runtime.coordinator.activity.value.completion

    init {
        scope.launch {
            for (command in commands) {
                try {
                    command()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    mutableState.update { it.copy(problem = failure.syncProblem(), setupBusy = false, loading = false) }
                }
            }
        }
        scope.launch {
            handler.subscribeToOneOrNull { sync_journalQueries.getActiveSpace() }.collectLatest { connection ->
                queueRefresh()
                if (connection != null) {
                    combine(
                        handler.subscribeToList {
                            sync_inboxQueries.getPendingReferences(connection.space_id, connection.generation)
                        },
                        handler.subscribeToList {
                            sync_journalQueries.getPendingCategoryCounts(connection.space_id, connection.generation)
                        },
                        handler.subscribeToOne {
                            sync_importQueries.countPendingImports(connection.space_id, connection.generation)
                        },
                    ) { _, _, _ -> Unit }.collect { queueRefresh() }
                }
            }
        }
        scope.launch {
            runtime.coordinator.activity.collect { activity ->
                runtime.diagnostics.recordCoordinator(activity)
                enqueue {
                    val completed = activity.completion != observedCompletion
                    observedCompletion = activity.completion
                    mutableState.update {
                        it.copy(
                            busy = activity.running,
                            problem = if (completed) activity.result?.problem else it.problem,
                            notice = if (completed && it.visible && activity.completion != setupExchangeCompletion &&
                                activity.result?.status != SyncRunStatus.SKIPPED
                            ) {
                                SyncPanelNotice(exchange = activity.result)
                            } else {
                                it.notice
                            },
                        )
                    }
                    refresh(source = SyncDiagnosticRefreshSource.COORDINATOR)
                }
            }
        }
        scope.launch {
            runtime.liveProgress.collect { fact ->
                mutableState.update { current ->
                    if (current.visible && fact != null &&
                        current.run?.runId?.let { fact.scope.startsWith("$it:") } == true
                    ) {
                        current.copy(progress = fact)
                    } else {
                        current
                    }
                }
            }
        }
        scope.launch {
            state.map { current -> current.run?.runId?.takeIf { current.visible } }
                .distinctUntilChanged().collectLatest { runId ->
                    if (runId != null) {
                        runtime.runStore.observe(runId).collect { observed ->
                            mutableState.update { current ->
                                val selected = current.run
                                if (current.visible && selected?.runId == runId && observed != null) {
                                    val counts = observed.withObservedCounts(selected)
                                    current.copy(
                                        run = selected.copy(
                                            plannedItems = counts.plannedItems,
                                            confirmedItems = counts.confirmedItems,
                                        ),
                                    )
                                } else {
                                    current
                                }
                            }
                        }
                    }
                }
        }
        scope.launch {
            val prefs = runtime.preferences
            merge(
                prefs.startup.changes().map { Unit },
                prefs.periodMinutes.changes().map { Unit },
                prefs.lastAttempt.changes().map { Unit },
                prefs.lastSuccess.changes().map { Unit },
                prefs.scheduleAnchor.changes().map { Unit },
                prefs.deviceName.changes().map { Unit },
            ).collect { queueRefresh() }
        }
        scope.launch {
            state.map { it.visible }.distinctUntilChanged().collectLatest { visible ->
                if (visible) {
                    while (true) {
                        val fact = state.value.run?.let { runtime.progressFor(it.runId) }
                        mutableState.update { it.copy(nowMillis = clock(), progress = fact ?: it.progress) }
                        delay(1_000)
                    }
                }
            }
        }
    }

    override fun dispatch(action: SyncPanelAction) {
        enqueue { handle(action) }
    }

    override fun claimDeviceCodeBrowser(code: GitHubDeviceCode): Boolean =
        state.value.let { current ->
            current.visible && current.page == SyncPanelPage.SETUP &&
                current.setupStep == SyncSetupStep.SIGN_IN && current.deviceCode?.deviceCode == code.deviceCode &&
                deviceBrowserOpened.compareAndSet(false, true)
        }

    override fun claimRecoveryPlatform(requestId: String): Boolean {
        while (true) {
            val before = state.value
            if (!before.visible || !before.recoveryPlatformLaunchPending ||
                before.recoveryPlatformRequest?.requestId != requestId || before.recoveryPlatformRequest.failed
            ) {
                return false
            }
            if (mutableState.compareAndSet(before, before.copy(recoveryPlatformLaunchPending = false))) return true
        }
    }

    suspend fun stop() {
        lifetime.cancelAndJoin()
        commands.cancel()
        mutableState.value = SyncPanelState()
    }

    internal suspend fun awaitIdle() {
        val ready = CompletableDeferred<Unit>()
        enqueue { ready.complete(Unit) }
        ready.await()
    }

    internal suspend fun awaitBulkIdle() {
        bulkJob?.join()
    }

    private fun enqueue(command: suspend () -> Unit) {
        commands.trySend(command)
    }

    private fun queueRefresh() {
        if (refreshQueued.compareAndSet(false, true)) {
            enqueue {
                refreshQueued.set(false)
                refresh(source = SyncDiagnosticRefreshSource.SUBSCRIPTION)
            }
        }
    }

    private suspend fun refresh(
        forceFailureLog: Boolean = false,
        source: SyncDiagnosticRefreshSource = SyncDiagnosticRefreshSource.ACTION,
    ) {
        runtime.diagnostics.record(SyncDiagnosticEventKind.REFRESH_BEGIN, source)
        try {
            refreshFacts(forceFailureLog)
            runtime.diagnostics.observe(lastDiagnosticConnection, state.value.run)
            runtime.diagnostics.record(SyncDiagnosticEventKind.REFRESH_END, source)
        } catch (failure: Exception) {
            runtime.diagnostics.record(SyncDiagnosticEventKind.REFRESH_END, source, failed = true)
            throw failure
        }
    }

    private var lastDiagnosticConnection = SyncConnectionFacts(null, null, SyncBindingDecode.UNKNOWN)

    private suspend fun refreshFacts(forceFailureLog: Boolean) {
        // Replacement stays disabled until all critical local recovery facts have been read.
        mutableState.update { it.copy(canChangeSpace = false) }
        val connectionFacts = runtime.connectionFacts()
        lastDiagnosticConnection = connectionFacts
        val connection = connectionFacts.projection
        val credentialAvailable = try {
            runtime.credentials.read() != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        val canChangeSpace = if (connectionFacts.decode == SyncBindingDecode.OK &&
            connection?.enabled == true && !connection.unsupportedFormat
        ) {
            try {
                runtime.credentials.read() != null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        if (connection?.unsupportedFormat == true) {
            mutableState.update {
                it.copy(
                    setupStep = SyncSetupStep.ERROR,
                    setupProblem = SyncDiscoveryProblem.INCOMPATIBLE,
                    problem = SyncRunProblem.INVALID_DATA,
                )
            }
        } else if (connection != null && state.value.setupStep == SyncSetupStep.SIGN_IN && !state.value.setupBusy) {
            val pending = try {
                runtime.pendingSetup(connection)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (pending != null) {
                mutableState.update {
                    it.copy(
                        setupStep = SyncSetupStep.MERGING,
                        setupAccountLogin = pending.accountLogin,
                        setupRepository = pending.repository(),
                    )
                }
            }
        }
        if (state.value.connection?.spaceId != connection?.spaceId ||
            state.value.connection?.generation != connection?.generation
        ) {
            bulkJob?.cancelAndJoin()
            bulkJob = null
            cancelConfirmation()
            selectedBindings = emptyMap()
            anchor = null
            loadedCount = 100
            logLimit = 5
            recoveryVersion++
            mutableState.update {
                it.copy(
                    recoveryOutcome = null,
                    recoveryExternalScopes = emptyList(),
                    recoveryRepairMadeNoProgress = false,
                    recoveryConditionsVerified = false,
                    recoveryFailure = null,
                    recoveryRepairReport = null,
                    recoveryOldScopes = emptyList(),
                    recoveryPlatformRequest = null,
                    recoveryPlatformLaunchPending = false,
                )
            }
        }
        var membership = 0L
        var favorites = 0L
        var follows = 0L
        var reading = 0L
        var imports = 0L
        references = linkedMapOf()
        val items = mutableListOf<mihon.data.sync.inbox.SyncPendingItem>()
        if (connection != null) {
            handler.await {
                sync_inboxQueries.getPendingReferences(connection.spaceId, connection.generation)
                    .executeAsList().forEach { references[it._id] = it.binding }
                sync_journalQueries.getPendingCategoryCounts(connection.spaceId, connection.generation)
                    .executeAsList().forEach {
                        when (it.category) {
                            "FAVORITE" -> {
                                favorites += it.count
                                membership += it.count
                            }
                            "FOLLOW" -> {
                                follows += it.count
                                membership += it.count
                            }
                            "READING" -> reading += it.count
                            else -> membership += it.count
                        }
                    }
                imports = sync_importQueries.countPendingImports(connection.spaceId, connection.generation)
                    .executeAsOne()
            }
            while (items.size < minOf(loadedCount, references.size)) {
                val page = runtime.projector.pending(
                    connection.spaceId,
                    connection.generation,
                    100,
                    items.size.toLong(),
                )
                if (page.isEmpty()) break
                items.addAll(page)
            }
        }
        selectedBindings = selectedBindings.filter { (id, binding) -> references[id] == binding }
        val recovery = recoveryRead { runtime.spaceRecovery() }
        val recoveryObservation = recoveryRead { runtime.recoveryObservation() }
        val pendingSwitch = try {
            runtime.activeSwitch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update { it.copy(canChangeSpace = false) }
            throw failure
        }
        val workflow = if (connectionFacts.decode == SyncBindingDecode.OK && connection?.enabled == true &&
            (
                state.value.page == SyncPanelPage.RECOVERY ||
                    state.value.recoveryPlatformRequest != null ||
                    state.value.recoveryOutcome != null
                )
        ) {
            recoveryRead { runtime.recoveryWorkflow.facts(state.value.recoveryRepairOffset) }
        } else {
            null
        }
        val prefs = runtime.preferences
        val period = prefs.intervalMinutes()
        val next = if (period == 0) {
            0
        } else {
            maxOf(prefs.scheduleAnchor.get(), prefs.lastAttempt.get(), prefs.lastSuccess.get()) + period * 60_000L
        }
        val savedBulk = connection?.let { prefs.activeBulkJob(it.spaceId, it.generation).get() }
            ?.takeIf { it.isNotEmpty() }?.let { id ->
                handler.await { sync_inboxQueries.getBulkJob(id).executeAsOneOrNull() }
                    ?.takeIf { it.space_id == connection?.spaceId && it.generation == connection.generation }
                    ?.let { runtime.projector.bulkProgress(id).toStatus(id, bulkJob?.isActive == true) }
            }
        val activeRun = connection?.let { runtime.runStore.active(it.spaceId, it.generation) }
        val run = activeRun ?: connection?.let {
            runtime.runStore.latest(it.spaceId, it.generation)?.takeIf { latest ->
                latest.state in setOf(
                    SyncRunState.SUCCEEDED,
                    SyncRunState.PARTIAL,
                    SyncRunState.FAILED,
                    SyncRunState.BLOCKED,
                    SyncRunState.CANCELLED,
                )
            }
        }
        val logs = run?.let { runtime.runStore.logs(it.runId, limit = logLimit) }.orEmpty()
        val terminalSummary = run?.takeIf {
            it.state in setOf(
                SyncRunState.SUCCEEDED,
                SyncRunState.PARTIAL,
                SyncRunState.FAILED,
                SyncRunState.BLOCKED,
                SyncRunState.CANCELLED,
            )
        }?.let { runtime.runStore.terminalSummary(it.runId) }
        val failureLog = run?.let { runtime.failureLogFor(it, forceFailureLog) }
        val persistedProblem = run?.takeIf { it.state in setOf(SyncRunState.FAILED, SyncRunState.BLOCKED) }
            ?.let(::persistedProblem)
        mutableState.update {
            it.copy(
                loaded = true,
                recoveryBindingStatus = connectionFacts.decode,
                recoveryCredentialAvailable = credentialAvailable,
                externalRecoveryOrigin = runtime.externalRecoveryOrigin(),
                authRetryAtMillis = runtime.authorizationNotBeforeMillis(),
                recoveryStep = workflow?.flow?.step ?: it.recoveryStep,
                recoveryOutcome = workflow?.flow?.outcome ?: it.recoveryOutcome,
                recoveryFailure = if (
                    workflow?.flow?.outcome in setOf(
                        SyncRecoveryOutcome.ORIGINAL_VERIFIED,
                        SyncRecoveryOutcome.NEW_SCOPE_VERIFIED_WITH_OLD_REMAINING,
                    )
                ) {
                    null
                } else {
                    workflow?.flow?.failure
                        ?: it.recoveryFailure
                },
                recoveryRepairReport = workflow?.report ?: it.recoveryRepairReport,
                recoveryStepFailure = workflow?.flow?.secondaryFailure ?: it.recoveryStepFailure,
                recoveryOfficialAction = workflow?.flow?.officialAction ?: it.recoveryOfficialAction,
                recoveryPlatformResult = workflow?.flow?.request?.result ?: it.recoveryPlatformResult,
                recoveryConditionsVerified = workflow?.flow?.conditionsVerified ?: it.recoveryConditionsVerified,
                recoveryExternalScopes = workflow?.externalScopes ?: it.recoveryExternalScopes,
                recoveryRepairMadeNoProgress = workflow?.flow?.repairMadeNoProgress ?: it.recoveryRepairMadeNoProgress,
                recoveryOldScopes = workflow?.oldScopes ?: it.recoveryOldScopes,
                recoveryPlatformRequest = if (
                    workflow?.flow != null
                ) {
                    workflow.flow.request
                } else {
                    it.recoveryPlatformRequest
                },
                recoveryRestartRequired = workflow?.flow?.request?.restartRequired == true,
                connection = connection,
                queuedMembership = membership,
                queuedFavorites = favorites,
                queuedFollows = follows,
                queuedReading = reading,
                pendingTotal = references.size.toLong(),
                pending = items,
                hasMore = items.size < references.size,
                loading = false,
                selected = selectedBindings.keys,
                bulk = savedBulk,
                startup = prefs.startup.get(),
                periodMinutes = period,
                deviceName = prefs.deviceName.get(),
                lastSuccessMillis = prefs.lastSuccess.get(),
                nowMillis = clock(),
                nextSyncAtMillis = if (recovery != null) 0L else next,
                recovery = recovery?.copy(busy = recoveryJob?.isActive == true),
                recoveryAuthorization = if (authJob?.isActive == true) {
                    it.recoveryAuthorization
                } else {
                    recoveryObservation?.authorization ?: SyncRecoveryAuthorization.IDLE
                },
                pendingRecoveryPurpose = pendingSwitch?.let { intent ->
                    if (intent.purpose == SyncSpaceSwitchPurpose.CREATE) {
                        SyncRecoveryContinuation.CREATE
                    } else {
                        SyncRecoveryContinuation.CONNECT
                    }
                },
                canCancelRecoverySwitch = pendingSwitch?.stage == SyncSpaceSwitchStage.PREPARING,
                canChangeSpace = canChangeSpace,
                importRemaining = imports,
                importPaused = prefs.importPaused.get(),
                records = runtime.records().asReversed(),
                run = run?.withObservedCounts(it.run),
                runSource = when {
                    activeRun != null -> SyncPanelRunSource.ACTIVE
                    run != null -> SyncPanelRunSource.LATEST
                    else -> null
                },
                terminalSummary = terminalSummary,
                failureLog = failureLog,
                progress = run?.let { runtime.progressFor(it.runId) ?: restoredProgress(it) },
                logs = logs,
                logsHasMore = run != null && logLimit < 500L && logs.size.toLong() == logLimit,
                problem = persistedProblem ?: it.problem,
            )
        }
    }

    /** Same-run query races cannot erase an immutable plan or regress committed confirmations. */
    private fun SyncRunSnapshot.withObservedCounts(previous: SyncRunSnapshot?): SyncRunSnapshot {
        if (previous?.runId != runId || previous.spaceId != spaceId || previous.generation != generation) return this
        return copy(
            plannedItems = plannedItems ?: previous.plannedItems,
            confirmedItems = maxOf(confirmedItems, previous.confirmedItems),
        )
    }

    private fun restoredProgress(run: SyncRunSnapshot): SyncProgressFact {
        val stage = when (run.phase) {
            SyncRunPhase.CHECKING, SyncRunPhase.IMPORTING -> SyncProgressStage.PREPARING
            SyncRunPhase.DOWNLOADING, SyncRunPhase.UPLOADING -> SyncProgressStage.TRANSFERRING
            SyncRunPhase.MERGING, SyncRunPhase.CONFIRMING, SyncRunPhase.COMPLETE -> SyncProgressStage.CONFIRMING
        }
        val direction = if (run.phase in setOf(SyncRunPhase.DOWNLOADING, SyncRunPhase.MERGING) ||
            (run.phase == SyncRunPhase.COMPLETE && run.uploaded == 0L)
        ) {
            SyncProgressDirection.DOWNLOAD
        } else {
            SyncProgressDirection.UPLOAD
        }
        val succeeded = run.state == SyncRunState.SUCCEEDED
        val completed = if (succeeded && stage == SyncProgressStage.CONFIRMING) {
            run.uploaded + run.downloaded
        } else {
            0L
        }
        return SyncProgressFact(
            scope = "${run.runId}:restored",
            stage = stage,
            direction = direction,
            completedItems = completed,
            totalItems = if (succeeded) completed else null,
            effectiveBytes = 0,
            networkBytes = 0,
            totalBytes = null,
            elapsedSeconds = if (run.plannedItems == null) {
                0
            } else {
                val end = if (run.state in setOf(
                        SyncRunState.SUCCEEDED,
                        SyncRunState.PARTIAL,
                        SyncRunState.FAILED,
                        SyncRunState.BLOCKED,
                        SyncRunState.CANCELLED,
                    )
                ) {
                    run.updatedAt
                } else {
                    run.pausedAt ?: clock()
                }
                (
                    (
                        end - (run.planStartedAt ?: run.createdAt) -
                            (run.pausedMillis - run.planPausedMillis).coerceAtLeast(0)
                        ).coerceAtLeast(0) / 1_000
                    )
            },
            hold = when (run.state) {
                SyncRunState.PAUSED_USER -> SyncProgressHold.PAUSED
                SyncRunState.WAITING_NETWORK -> SyncProgressHold.OFFLINE
                SyncRunState.WAITING_RETRY, SyncRunState.WAITING_SYSTEM -> SyncProgressHold.WAITING
                SyncRunState.SUCCEEDED, SyncRunState.PARTIAL, SyncRunState.FAILED,
                SyncRunState.BLOCKED, SyncRunState.CANCELLED,
                ->
                    SyncProgressHold.ACTIVE
                else -> SyncProgressHold.RECOVERING
            },
            stageEtaSeconds = null,
            wholeEtaSeconds = null,
            confirmedThisRun = run.confirmedItems.takeIf { it > 0L || run.downloaded == 0L || succeeded },
        )
    }

    private suspend fun captureDiagnostics() {
        mutableState.update { it.copy(diagnosticBusy = true, diagnosticFeedback = null, diagnosticPath = null) }
        val snapshot = runtime.diagnostics.capture { state.value }
        mutableState.update {
            it.copy(
                diagnosticBusy = false,
                diagnosticSnapshot = snapshot,
                diagnosticFeedback = when (snapshot.status) {
                    SyncDiagnosticStatus.OK -> SyncDiagnosticFeedback.CAPTURED
                    SyncDiagnosticStatus.INCONSISTENT -> SyncDiagnosticFeedback.INCONSISTENT
                    else -> SyncDiagnosticFeedback.READ_FAILED
                },
            )
        }
    }

    private suspend fun handle(action: SyncPanelAction) {
        when (action) {
            is SyncPanelAction.ExecuteRecoveryAction -> executeRecoveryAction(action.action)
            is SyncPanelAction.RecoveryPlatformCompleted -> completeRecoveryPlatform(action)
            is SyncPanelAction.RecoveryOfficialOpened -> recoveryOfficialOpened(action.action)
            SyncPanelAction.RecoveryOfficialReturned -> {
                if (state.value.visible && state.value.recoveryOfficialAction != null &&
                    !state.value.recoveryOfficialCheckAttempted &&
                    !state.value.setupBusy && !state.value.recoveryBusy
                ) {
                    mutableState.update { it.copy(recoveryOfficialCheckAttempted = true) }
                    if (state.value.page == SyncPanelPage.SETUP) {
                        // Returning from GitHub only verifies facts. Never resume a pending remote write here.
                        discover(autoSelect = false, inspectPending = false)
                    } else {
                        recheckSpace()
                    }
                }
            }
            SyncPanelAction.VerifyRecovery -> verifyRecovery()
            is SyncPanelAction.RepairData -> verifyRecovery(action.offset)
            SyncPanelAction.LoadMoreRecoveryFailures -> {
                val offset = state.value.recoveryRepairReport?.nextOffset ?: return
                mutableState.update { it.copy(recoveryRepairOffset = offset) }
                refresh()
            }
            is SyncPanelAction.RetryFailedBulk -> prepareFailedBulkRetry(action.jobId)
            is SyncPanelAction.OpenRecoveryPlatform -> openRecoveryPlatform(action)
            is SyncPanelAction.RecoveryPlatformReturned -> {
                val accepted = recoveryRead {
                    runtime.recoveryWorkflow.platformReturned(
                        action.requestId,
                        action.restartRequired,
                    )
                } == true
                if (accepted) {
                    verifyRecovery()
                } else {
                    refreshRecoveryDisplay()
                }
            }
            is SyncPanelAction.RecoveryPlatformFailed -> {
                recoveryRead { runtime.recoveryWorkflow.platformFailed(action.requestId) }
                mutableState.update { current ->
                    if (current.recoveryPlatformRequest?.requestId == action.requestId) {
                        current.copy(
                            recoveryPlatformRequest = current.recoveryPlatformRequest.copy(failed = true),
                            recoveryPlatformLaunchPending = false,
                            recoveryOutcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
                        )
                    } else {
                        current
                    }
                }
                refreshRecoveryDisplay()
            }
            SyncPanelAction.CheckRepositoryCreationPermission -> checkRepositoryCreationPermission()
            is SyncPanelAction.PrepareRepositoryCreation -> prepareRepositoryCreation(action.name)
            is SyncPanelAction.PrepareManualRepository -> {
                prepareRepositoryCreation(action.name, manual = true)
            }
            is SyncPanelAction.RepairRepositoryProperties ->
                prepareRepositoryProperties(
                    action.makePrivate,
                    action.unarchive,
                )
            SyncPanelAction.AuthorizeRepositoryScope -> prepareRepositoryProperties(false, false, authorize = true)
            SyncPanelAction.OpenRecovery -> {
                val source = state.value.takeIf {
                    it.page == SyncPanelPage.SETUP && it.setupStep == SyncSetupStep.ERROR
                }?.page ?: state.value.recoveryReturnPage
                mutableState.update {
                    it.copy(visible = true, page = SyncPanelPage.RECOVERY, recoveryReturnPage = source)
                }
                val facts = try {
                    runtime.connectionFacts()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    openSafeRecovery(SyncRunProblem.STORAGE)
                    return
                }
                val connection = facts.projection
                if (
                    facts.decode == SyncBindingDecode.MISSING &&
                    connection == null &&
                    runtime.externalRecoveryOrigin() == null
                ) {
                    restoreUnboundRecovery()
                    mutableState.update {
                        it.copy(
                            page = SyncPanelPage.RECOVERY,
                            recoveryBindingStatus = facts.decode,
                        )
                    }
                } else if (facts.decode == SyncBindingDecode.OK && connection != null &&
                    !connection.unsupportedFormat
                ) {
                    try {
                        refresh()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        openSafeRecovery(SyncRunProblem.STORAGE)
                        return
                    }
                    mutableState.update { it.copy(page = SyncPanelPage.RECOVERY, recoveryReturnPage = source) }
                } else {
                    openSafeRecovery(
                        if (facts.decode == SyncBindingDecode.UNSUPPORTED) {
                            SyncRunProblem.INVALID_DATA
                        } else {
                            SyncRunProblem.STORAGE
                        },
                    )
                }
            }
            SyncPanelAction.RecheckSpace -> recheckSpace()
            SyncPanelAction.CheckAuthorization -> checkAuthorization()
            SyncPanelAction.ManageAuthorization -> authorize()
            SyncPanelAction.ContinueRecovery -> runtime.activeSwitch()?.let { resumeSwitch(it) }
            SyncPanelAction.ConnectOtherSpace -> beginSwitch(SyncSpaceSwitchPurpose.CONNECT)
            SyncPanelAction.CreateNewSpace -> {
                cancelRecovery()
                mutableState.update {
                    it.copy(
                        question = SyncPanelQuestion.CREATE_NEW_SPACE,
                        switchPendingDecisions = it.pendingTotal,
                    )
                }
            }
            SyncPanelAction.Open -> {
                runtime.diagnostics.record(SyncDiagnosticEventKind.OPEN)
                mutableState.update { it.copy(visible = true, page = SyncPanelPage.MAIN, notice = null) }
                refresh(forceFailureLog = true, source = SyncDiagnosticRefreshSource.OPEN)
            }
            SyncPanelAction.Close -> {
                runtime.diagnostics.record(SyncDiagnosticEventKind.CLOSE)
                panelSession++
                cancelRecovery()
                cancelSwitchPreparation()
                cancelAuthorization()
                cancelConfirmation()
                clearSelection()
                mutableState.update {
                    it.copy(
                        visible = false,
                        notice = null,
                        question = null,
                        deviceCode = null,
                        recoveryPlatformLaunchPending = false,
                        diagnosticSnapshot = null,
                        diagnosticPath = null,
                        diagnosticFeedback = null,
                    )
                }
            }
            SyncPanelAction.Back -> {
                if (state.value.question != null) {
                    mutableState.update { it.copy(question = null) }
                } else if (state.value.page == SyncPanelPage.RECOVERY && state.value.recoveryReturnPage != null) {
                    cancelRecovery()
                    mutableState.update {
                        it.copy(page = requireNotNull(it.recoveryReturnPage), recoveryReturnPage = null)
                    }
                } else if (state.value.page == SyncPanelPage.SETUP && switchIntent != null) {
                    val intent = requireNotNull(switchIntent)
                    if (state.value.setupStep == SyncSetupStep.UNLOCK) {
                        chosenSpace = null
                        mutableState.update { it.copy(passwordProblem = null) }
                        discover(autoSelect = false)
                    } else if (state.value.setupStep == SyncSetupStep.NEW_PASSWORD &&
                        intent.purpose == SyncSpaceSwitchPurpose.CREATE
                    ) {
                        emptyRepositoryCandidate = null
                        mutableState.update {
                            it.copy(setupStep = SyncSetupStep.PREPARE_REPOSITORY, passwordProblem = null)
                        }
                    } else {
                        cancelSwitchPreparation()
                        cancelAuthorization()
                        mutableState.update { it.copy(page = SyncPanelPage.RECOVERY) }
                        refresh()
                    }
                } else if (state.value.page == SyncPanelPage.MAIN) {
                    handle(SyncPanelAction.Close)
                } else {
                    cancelRecovery()
                    if (state.value.page == SyncPanelPage.SETUP) cancelAuthorization()
                    mutableState.update {
                        it.copy(
                            page = if (it.page == SyncPanelPage.DIAGNOSTICS) {
                                diagnosticReturnPage
                            } else {
                                SyncPanelPage.MAIN
                            },
                        )
                    }
                }
            }
            SyncPanelAction.CaptureDiagnostics -> captureDiagnostics()
            SyncPanelAction.ExportDiagnostics -> {
                val snapshot = state.value.diagnosticSnapshot
                if (snapshot != null) {
                    mutableState.update { it.copy(diagnosticBusy = true, diagnosticFeedback = null) }
                    val path = runtime.diagnostics.export(snapshot)
                    mutableState.update {
                        it.copy(
                            diagnosticBusy = false,
                            diagnosticPath = path,
                            diagnosticFeedback = if (path == null) {
                                SyncDiagnosticFeedback.SAVE_FAILED
                            } else {
                                SyncDiagnosticFeedback.EXPORTED
                            },
                        )
                    }
                }
            }
            SyncPanelAction.BeginDiagnosticSession, SyncPanelAction.EndDiagnosticSession -> {
                val started = action == SyncPanelAction.BeginDiagnosticSession
                mutableState.update { it.copy(diagnosticBusy = true, diagnosticFeedback = null) }
                val success = if (started) runtime.diagnostics.beginSession() else runtime.diagnostics.endSession()
                if (success) captureDiagnostics()
                mutableState.update {
                    it.copy(
                        diagnosticBusy = false,
                        diagnosticFeedback = if (!success) {
                            SyncDiagnosticFeedback.SAVE_FAILED
                        } else if (started) {
                            SyncDiagnosticFeedback.SESSION_STARTED
                        } else {
                            SyncDiagnosticFeedback.SESSION_ENDED
                        },
                    )
                }
            }
            is SyncPanelAction.Navigate -> {
                if (action.page == SyncPanelPage.DIAGNOSTICS) {
                    diagnosticReturnPage = if (state.value.page == SyncPanelPage.SETUP &&
                        state.value.setupStep == SyncSetupStep.ERROR
                    ) {
                        SyncPanelPage.SETUP
                    } else {
                        SyncPanelPage.SETTINGS
                    }
                }
                mutableState.update { it.copy(page = action.page) }
            }
            SyncPanelAction.Synchronize -> if (state.value.run?.state != SyncRunState.PAUSED_USER) {
                if (state.value.connection?.enabled == true) {
                    scope.launch { runtime.coordinator.synchronize(SyncTrigger.MANUAL) }
                } else {
                    beginSetup()
                }
            }
            SyncPanelAction.RetrySync -> if (state.value.run?.state in
                setOf(SyncRunState.FAILED, SyncRunState.PARTIAL)
            ) {
                mutableState.update { it.copy(problem = null) }
                scope.launch {
                    runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                    refresh()
                }
            }
            SyncPanelAction.CancelSync -> scope.launch {
                runtime.cancelSync()
                refresh()
            }
            SyncPanelAction.PauseSync -> scope.launch {
                runtime.pauseSync()
                refresh()
            }
            SyncPanelAction.ResumeSync -> scope.launch {
                val resumed = state.value.run?.let { runtime.runStore.resumeIfAllowed(it.runId) } == true
                if (resumed) runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                refresh()
            }
            SyncPanelAction.PauseImport -> {
                runtime.preferences.importPaused.set(true)
                refresh()
            }
            SyncPanelAction.ResumeImport -> {
                runtime.preferences.importPaused.set(false)
                refresh()
                // A subtask preference must not create another round over a durable user pause.
                val userPaused = runtime.connection()?.let { connection ->
                    runtime.runStore.active(connection.spaceId, connection.generation)?.state
                } == SyncRunState.PAUSED_USER
                if (!userPaused) {
                    if (state.value.setupStep == SyncSetupStep.MERGING) {
                        discover()
                    } else {
                        scope.launch { runtime.coordinator.synchronize(SyncTrigger.MANUAL) }
                    }
                }
            }
            is SyncPanelAction.SetPeriod -> {
                runtime.preferences.setInterval(action.minutes)
                runtime.preferences.scheduleAnchor.set(clock())
                refresh()
            }
            is SyncPanelAction.SetStartup -> {
                runtime.preferences.startup.set(action.enabled)
                refresh()
            }
            is SyncPanelAction.SetDeviceName -> {
                runtime.preferences.deviceName.set(action.name.take(80))
                refresh()
            }
            is SyncPanelAction.SelectionMode -> {
                if (!action.enabled) clearSelection()
                mutableState.update { it.copy(selecting = action.enabled) }
            }
            is SyncPanelAction.ToggleItem -> {
                val binding = references[action.id] ?: return
                val ids = references.keys.toList()
                val previous = anchor?.let(ids::indexOf)?.takeIf { it >= 0 }
                selectedBindings = if (action.range && previous != null) {
                    val next = ids.indexOf(action.id)
                    selectedBindings + ids.subList(minOf(previous, next), maxOf(previous, next) + 1)
                        .associateWith { references.getValue(it) }
                } else if (action.id in selectedBindings) {
                    selectedBindings - action.id
                } else {
                    selectedBindings + (action.id to binding)
                }
                anchor = action.id
                mutableState.update { it.copy(selecting = true, selected = selectedBindings.keys) }
            }
            SyncPanelAction.SelectAll -> {
                selectedBindings = references.toMap()
                mutableState.update { it.copy(selecting = true, selected = selectedBindings.keys) }
            }
            SyncPanelAction.InvertSelection -> {
                selectedBindings = references.filterKeys { it !in selectedBindings }
                mutableState.update { it.copy(selecting = true, selected = selectedBindings.keys) }
            }
            is SyncPanelAction.PrepareDecision -> prepareDecision(action)
            SyncPanelAction.CancelDecision -> cancelConfirmation()
            SyncPanelAction.ConfirmDecision -> {
                val confirmation = state.value.confirmation ?: return
                val job = handler.await { sync_inboxQueries.getBulkJob(confirmation.jobId).executeAsOne() }
                val connection = runtime.connection()
                if (job.space_id != connection?.spaceId || job.generation != connection.generation) {
                    cancelConfirmation()
                    return
                }
                runtime.preferences.activeBulkJob(job.space_id, job.generation).set(confirmation.jobId)
                mutableState.update { it.copy(confirmation = null) }
                clearSelection()
                startBulk(confirmation.jobId)
            }
            SyncPanelAction.PauseBulk -> {
                bulkJob?.cancelAndJoin()
                refresh()
            }
            SyncPanelAction.ResumeBulk -> state.value.bulk?.let { startBulk(it.jobId) }
            SyncPanelAction.LoadMore -> {
                loadedCount += 100
                refresh()
            }
            SyncPanelAction.LoadMoreLogs -> {
                logLimit = (logLimit + 20).coerceAtMost(500L)
                refresh()
            }
            SyncPanelAction.DismissNotice -> mutableState.update { it.copy(notice = null) }
            SyncPanelAction.BeginSetup -> beginSetup()
            SyncPanelAction.RetrySetup -> {
                if (state.value.setupBusy || setupJob?.isActive == true || repositoryJob?.isActive == true ||
                    authJob?.isActive == true || recoveryJob?.isActive == true
                ) {
                    return
                }
                mutableState.update { it.copy(setupRetryAttempted = true, setupRetryFailed = false) }
                if (resumePendingRepositoryCreation()) return
                val intent = runtime.activeSwitch()
                if (intent != null) {
                    resumeSwitch(intent)
                } else {
                    switchIntent = null
                    discover()
                }
            }
            SyncPanelAction.AbandonLegacyPending -> abandonLegacyPending()
            is SyncPanelAction.ChooseSpace -> if (action.space in state.value.spaces) selectSpace(action.space)
            is SyncPanelAction.SubmitPassword -> submitPassword(action.password)
            SyncPanelAction.Authorize -> authorize()
            SyncPanelAction.CancelAuthorization -> cancelAuthorization()
            is SyncPanelAction.Ask -> mutableState.update { it.copy(question = action.question) }
            SyncPanelAction.CancelQuestion -> mutableState.update { it.copy(question = null) }
            SyncPanelAction.ConfirmQuestion -> {
                val question = state.value.question ?: return
                mutableState.update { it.copy(question = null) }
                when (question) {
                    SyncPanelQuestion.CREATE_REPOSITORY -> startRepositoryCreation()
                    SyncPanelQuestion.CONNECT_MANUAL_REPOSITORY -> startRepositoryCreation(manual = true)
                    SyncPanelQuestion.REPAIR_REPOSITORY_PROPERTIES -> runRepositoryManagement(authorize = false)
                    SyncPanelQuestion.AUTHORIZE_REPOSITORY_SCOPE -> runRepositoryManagement(authorize = true)
                    SyncPanelQuestion.CREATE_NEW_SPACE -> beginSwitch(SyncSpaceSwitchPurpose.CREATE)
                    SyncPanelQuestion.CONNECT_SPACE -> {
                        val intent = switchConfirmation ?: return
                        switchConfirmation = null
                        bulkJob?.cancelAndJoin()
                        bulkJob = null
                        runSetup { runtime.confirmSwitch(intent) }
                    }
                    SyncPanelQuestion.DISCONNECT -> {
                        setupVersion++
                        setupJob?.cancelAndJoin()
                        setupJob = null
                        cancelAuthorization()
                        bulkJob?.cancelAndJoin()
                        runtime.disconnect()
                        refresh()
                    }
                    SyncPanelQuestion.SWITCH_SPACE -> beginSwitch(SyncSpaceSwitchPurpose.CONNECT)
                    SyncPanelQuestion.ABANDON_LEGACY -> abandonLegacyPending()
                    SyncPanelQuestion.CANCEL_RECOVERY_SWITCH -> {
                        cancelSwitchPreparation()
                        cancelAuthorization()
                        runtime.cancelRecoverySwitch()
                        mutableState.update { it.copy(page = SyncPanelPage.RECOVERY) }
                        refresh()
                    }
                }
            }
        }
    }

    private fun persistedProblem(run: SyncRunSnapshot): SyncRunProblem = when (run.stopReason) {
        "retry_exhausted",
        "network",
        -> SyncRunProblem.NETWORK
        else -> run.stopReason?.let { reason ->
            runCatching { SyncRunProblem.valueOf(reason) }.getOrNull()
        } ?: SyncRunProblem.UNKNOWN
    }

    private suspend fun beginSetup() {
        mutableState.update {
            it.copy(setupRetryAttempted = false, setupRetryFailed = false, recoveryReturnPage = null)
        }
        runtime.activeSwitch()?.let {
            resumeSwitch(it)
            return
        }
        switchIntent = null
        switchConfirmation = null
        cancelRecovery()
        mutableState.update { it.copy(page = SyncPanelPage.SETUP, passwordProblem = null) }
        if (setupJob?.isActive == true) return
        cancelAuthorization()
        if (runtime.connection()?.unsupportedFormat == true) {
            mutableState.update {
                it.copy(
                    setupStep = SyncSetupStep.ERROR,
                    setupProblem = SyncDiscoveryProblem.INCOMPATIBLE,
                    problem = SyncRunProblem.INVALID_DATA,
                    setupBusy = false,
                    setupAccountLogin = null,
                    setupInstallation = null,
                )
            }
            return
        }
        val authorized = runtime.credentials.read() != null
        if (authorized) {
            try {
                val session = runtime.onboarding.session()
                runtime.onboarding.storage.repositoryCreation(session.account.id)?.let { pending ->
                    setupAccount = session.account
                    repositoryProposal = pending
                    repositoryProposalRevision = runtime.credentials.read()?.revision
                    startRepositoryCreation()
                    return
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                setupFailed(error)
                return
            }
        }
        mutableState.update {
            it.copy(
                setupStep = if (authorized) SyncSetupStep.DISCOVERING else SyncSetupStep.SIGN_IN,
                setupRepository = null,
                setupAccountLogin = null,
                setupInstallation = null,
                setupProblem = null,
                authFailure = null,
                problem = null,
            )
        }
        if (authorized) discover()
    }

    private fun recoveryEntryFailed(problem: SyncRunProblem) {
        mutableState.update {
            it.copy(
                canChangeSpace = false,
                page = SyncPanelPage.SETUP,
                setupStep = SyncSetupStep.ERROR,
                setupBusy = false,
                recoveryReturnPage = null,
                setupProblem = when (problem) {
                    SyncRunProblem.INVALID_DATA -> SyncDiscoveryProblem.INCOMPATIBLE
                    SyncRunProblem.AUTHORIZATION -> SyncDiscoveryProblem.AUTHORIZATION_REQUIRED
                    else -> SyncDiscoveryProblem.RETRYABLE
                },
                problem = problem,
            )
        }
    }

    private suspend fun cancelAuthorization() {
        val interrupted = state.value.recoveryAuthorization in setOf(
            SyncRecoveryAuthorization.CHECKING,
            SyncRecoveryAuthorization.WAITING,
            SyncRecoveryAuthorization.VERIFYING,
        )
        authVersion++
        authJob?.cancelAndJoin()
        authJob = null
        repositoryJob?.cancelAndJoin()
        repositoryJob = null
        mutableState.update {
            it.copy(deviceCode = null, setupBusy = setupJob?.isActive == true, authRequestStartedAtMillis = null)
        }
        deviceBrowserOpened.set(false)
        if (interrupted) {
            mutableState.update { it.copy(recoveryAuthorization = SyncRecoveryAuthorization.CANCELLED) }
            runtime.recordRecoveryAuthorization(SyncRecoveryAuthorization.CANCELLED)
        }
    }

    private suspend fun checkAuthorization() {
        if (authJob?.isActive == true || recoveryJob?.isActive == true) return
        cancelAuthorization()
        val version = authVersion
        val session = panelSession
        mutableState.update { it.copy(recoveryAuthorization = SyncRecoveryAuthorization.CHECKING, authFailure = null) }
        runtime.recordRecoveryAuthorization(SyncRecoveryAuthorization.CHECKING)
        authJob = scope.launch {
            val checked = runtime.checkRecoveryAuthorization()
            enqueue {
                if (version != authVersion || session != panelSession || !state.value.visible) return@enqueue
                authJob = null
                when {
                    checked.confirmed -> {
                        mutableState.update { it.copy(recoveryAuthorization = SyncRecoveryAuthorization.CONFIRMED) }
                        refresh()
                        recheckSpace()
                    }
                    checked.required -> {
                        runtime.recordRecoveryAuthorization(SyncRecoveryAuthorization.IDLE, clearConfirmation = true)
                        mutableState.update { it.copy(recoveryAuthorization = SyncRecoveryAuthorization.IDLE) }
                        refresh()
                        authorize()
                    }
                    else -> {
                        runtime.recordRecoveryAuthorization(SyncRecoveryAuthorization.FAILED)
                        mutableState.update {
                            it.copy(recoveryAuthorization = SyncRecoveryAuthorization.FAILED, problem = checked.problem)
                        }
                        refresh()
                    }
                }
            }
        }
    }

    private suspend fun authorize() {
        if (authJob?.isActive == true) return
        cancelRecovery()
        setupVersion++
        setupJob?.cancelAndJoin()
        setupJob = null
        runtime.coordinator.cancelAndJoin()
        cancelAuthorization()
        val version = authVersion
        val previous = runtime.credentials.read()?.revision
        val startedAt = clock()
        mutableState.update {
            it.copy(
                page = SyncPanelPage.SETUP,
                setupStep = SyncSetupStep.SIGN_IN,
                setupBusy = true,
                authFailure = null,
                authRequestStartedAtMillis = startedAt,
                nowMillis = startedAt,
                recoveryAuthorization = SyncRecoveryAuthorization.CHECKING,
            )
        }
        runtime.recordRecoveryAuthorization(SyncRecoveryAuthorization.CHECKING)
        val session = panelSession
        authJob = scope.launch {
            val result = runtime.authorization.authorize(SyncRuntime.CLIENT_ID) { code ->
                enqueue {
                    if (version == authVersion && session == panelSession && state.value.visible) {
                        runtime.recordRecoveryAuthorization(SyncRecoveryAuthorization.WAITING)
                        mutableState.update {
                            it.copy(deviceCode = code, recoveryAuthorization = SyncRecoveryAuthorization.WAITING)
                        }
                    }
                }
            }
            enqueue {
                if (version != authVersion || session != panelSession || !state.value.visible) return@enqueue
                authJob = null
                when (result) {
                    is GitHubDeviceAuthResult.Authorized -> {
                        mutableState.update { it.copy(recoveryAuthorization = SyncRecoveryAuthorization.VERIFYING) }
                        try {
                            runtime.acceptAuthorization(previous, result.token)
                            // Recovery eligibility must reflect the newly accepted credential before setup resumes.
                            refresh()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            runtime.recordRecoveryAuthorization(SyncRecoveryAuthorization.FAILED)
                            mutableState.update {
                                it.copy(
                                    deviceCode = null,
                                    setupBusy = false,
                                    authRequestStartedAtMillis = null,
                                    recoveryAuthorization = SyncRecoveryAuthorization.FAILED,
                                    problem = failure.syncProblem(),
                                    setupProblem = (failure as? SyncSetupException)?.problem,
                                )
                            }
                            return@enqueue
                        }
                        mutableState.update {
                            it.copy(
                                deviceCode = null,
                                setupStep = SyncSetupStep.DISCOVERING,
                                setupBusy = false,
                                authRequestStartedAtMillis = null,
                                recoveryAuthorization = SyncRecoveryAuthorization.CONFIRMED,
                            )
                        }
                        if (resumePendingRepositoryCreation()) {
                            // The already created fixed id and name resume before broad discovery.
                        } else if (runtime.activeSwitch() != null) {
                            resumeSwitch(requireNotNull(runtime.activeSwitch()))
                        } else if (runtime.spaceRecovery() != null) {
                            mutableState.update { it.copy(page = SyncPanelPage.RECOVERY) }
                            recheckSpace()
                        } else {
                            discover()
                        }
                    }
                    is GitHubDeviceAuthResult.Failed -> {
                        runtime.recordRecoveryAuthorization(SyncRecoveryAuthorization.FAILED)
                        mutableState.update {
                            it.copy(
                                deviceCode = null,
                                setupBusy = false,
                                authFailure = result.failure.reason,
                                authRequestStartedAtMillis = null,
                                recoveryAuthorization = SyncRecoveryAuthorization.FAILED,
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun restoreUnboundRecovery() {
        try {
            val saved = runtime.onboarding.storage.unboundRecoveryFlow() ?: return
            val revision = runtime.credentials.read()?.revision
            val current = revision == saved.credentialRevision
            if (current && saved.accountId != null && saved.accountLogin != null) {
                setupAccount = SyncGitHubAccount(saved.accountId, saved.accountLogin)
            }
            mutableState.update {
                it.copy(
                    recoveryFailure = it.recoveryFailure ?: saved.failure,
                    setupAccountLogin = if (current) {
                        saved.accountLogin ?: it.setupAccountLogin
                    } else {
                        it.setupAccountLogin
                    },
                    creationRepositoryId = saved.repositoryId ?: it.creationRepositoryId,
                    recoveryOfficialAction = saved.officialAction,
                    recoveryPlatformRequest = saved.request?.copy(
                        failed = saved.request.failed || !current,
                        restartRequired = saved.request.restartRequired &&
                            saved.request.runtimeInstanceId == runtime.instanceId,
                    ),
                    recoveryPlatformResult = saved.request?.result,
                    recoveryExternalScopes = saved.externalScopes,
                    recoveryPlatformLaunchPending = false,
                    recoveryOutcome = saved.request?.let { SyncRecoveryOutcome.WAITING_EXTERNAL } ?: it.recoveryOutcome,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update {
                it.copy(
                    recoveryPersistenceFailed = true,
                    recoveryStepFailure = error.recoveryFailure(),
                )
            }
        }
    }

    private suspend fun saveUnboundRecovery(
        request: SyncRecoveryPlatformRequest? = state.value.recoveryPlatformRequest,
    ) {
        val before = runtime.onboarding.storage.unboundRecoveryFlow()
        val account = setupAccount
        val creation = account?.let { runtime.onboarding.storage.repositoryCreation(it.id) }
        runtime.onboarding.storage.saveUnboundRecoveryFlow(
            StoredUnboundSyncRecoveryFlow(
                sourceStage = state.value.setupStep.name,
                purpose = state.value.pendingRecoveryPurpose?.name ?: before?.purpose,
                accountId = account?.id ?: before?.accountId,
                accountLogin = account?.login ?: before?.accountLogin,
                credentialRevision = runtime.credentials.read()?.revision,
                creationAttemptId = creation?.attemptId ?: before?.creationAttemptId,
                repositoryId = creation?.repositoryId ?: state.value.creationRepositoryId ?: before?.repositoryId,
                failure = state.value.recoveryFailure ?: state.value.setupProblem?.let {
                    SyncRecoveryFailure(discovery = it, initialization = state.value.initializationFailure)
                } ?: before?.failure,
                request = request,
                officialAction = state.value.recoveryOfficialAction,
                archivedSetupIds = before?.archivedSetupIds.orEmpty(),
                archivedSetupsTruncated = before?.archivedSetupsTruncated ?: false,
                externalScopes = if (request?.result != null) {
                    before?.externalScopes.orEmpty().afterPlatformResult(request, request.result)
                } else {
                    before?.externalScopes.orEmpty()
                },
            ).let { value ->
                value.copy(request = value.request?.copy(result = value.request.result?.boundedMetadata()))
            },
            before,
        )
    }

    private suspend fun recoveryOfficialOpened(action: SyncRecoveryAction) {
        if (action !in setOf(
                SyncRecoveryAction.INSTALL_APP,
                SyncRecoveryAction.AUTHORIZE_REPOSITORY,
                SyncRecoveryAction.MANAGE_AUTHORIZATION,
                SyncRecoveryAction.OFFICIAL_CREATE,
                SyncRecoveryAction.RESTORE_REPOSITORY,
                SyncRecoveryAction.RESTORE_INSTALLATION,
            )
        ) {
            return
        }
        val expected = officialIntent?.takeIf { it.first == action } ?: return
        if (expected.second != runtime.credentials.read()?.revision) return
        officialIntent = null
        mutableState.update {
            it.copy(
                recoveryOfficialAction = action,
                recoveryOfficialCheckAttempted = false,
                recoveryOutcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
            )
        }
        try {
            if (runtime.connectionFacts().decode == SyncBindingDecode.MISSING) {
                saveUnboundRecovery()
            } else {
                runtime.recoveryWorkflow.officialOpened(action)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update {
                it.copy(
                    recoveryPersistenceFailed = true,
                    recoveryStepFailure = error.recoveryFailure(),
                )
            }
        }
    }

    private suspend fun executeRecoveryAction(action: SyncRecoveryAction) {
        val decision = state.value.recoveryPrimaryAction.takeIf { it.action == action }
            ?: state.value.recoveryAlternativeActions.firstOrNull { it.action == action }
            ?: return
        when (val availability = decision.availability) {
            is SyncRecoveryActionAvailability.Waiting, SyncRecoveryActionAvailability.NotApplicable -> return
            is SyncRecoveryActionAvailability.NeedsStep -> {
                if (availability.step == SyncRecoveryAction.CONTINUE_SETUP &&
                    action == SyncRecoveryAction.CREATE_SPACE
                ) {
                    handle(SyncPanelAction.CreateNewSpace)
                    return
                }
                executeRecoveryStep(availability.step)
            }
            SyncRecoveryActionAvailability.Ready -> executeRecoveryStep(action)
        }
    }

    private suspend fun executeRecoveryStep(action: SyncRecoveryAction) {
        when (action) {
            SyncRecoveryAction.CONNECT_GITHUB -> authorize()
            SyncRecoveryAction.INSTALL_APP, SyncRecoveryAction.MANAGE_AUTHORIZATION,
            SyncRecoveryAction.OFFICIAL_CREATE, SyncRecoveryAction.RESTORE_REPOSITORY,
            SyncRecoveryAction.RESTORE_INSTALLATION,
            -> {
                officialIntent = action to runtime.credentials.read()?.revision
                mutableState.update { it.copy(recoveryOutcome = SyncRecoveryOutcome.WAITING_EXTERNAL) }
            }
            SyncRecoveryAction.AUTHORIZE_REPOSITORY -> prepareRepositoryProperties(false, false, authorize = true)
            SyncRecoveryAction.CREATE_SPACE -> handle(SyncPanelAction.CreateNewSpace)
            SyncRecoveryAction.CHOOSE_SPACE -> beginSwitch(SyncSpaceSwitchPurpose.CONNECT)
            SyncRecoveryAction.CHECK_CONDITIONS -> {
                mutableState.update { it.copy(recoveryOfficialCheckAttempted = it.recoveryOfficialAction != null) }
                if (state.value.page == SyncPanelPage.SETUP ||
                    runtime.connectionFacts().decode == SyncBindingDecode.MISSING
                ) {
                    discover()
                } else {
                    recheckSpace()
                }
            }
            SyncRecoveryAction.CONTINUE_SETUP -> handle(SyncPanelAction.RetrySetup)
            SyncRecoveryAction.EDIT_REPOSITORY_NAME -> {
                mutableState.update {
                    it.copy(page = SyncPanelPage.SETUP, setupStep = SyncSetupStep.PREPARE_REPOSITORY)
                }
                checkRepositoryCreationPermission()
            }
            SyncRecoveryAction.REPAIR_REPOSITORY_PROPERTIES -> {
                val problem = state.value.recoveryStepFailure?.discovery ?: state.value.setupProblem
                    ?: state.value.recoveryFailure?.discovery
                prepareRepositoryProperties(
                    problem == SyncDiscoveryProblem.REPOSITORY_NOT_PRIVATE,
                    problem == SyncDiscoveryProblem.REPOSITORY_ARCHIVED,
                )
            }
            SyncRecoveryAction.REPAIR_DATA -> verifyRecovery(state.value.recoveryRepairOffset)
            SyncRecoveryAction.VERIFY_SYNC -> verifyRecovery()
            SyncRecoveryAction.RESUME_SYNC -> handle(SyncPanelAction.ResumeSync)
            SyncRecoveryAction.RESUME_IMPORT -> handle(SyncPanelAction.ResumeImport)
            SyncRecoveryAction.ENABLE_SYNC -> {
                runtime.restoreDisabledConnection()
                refreshRecoveryDisplay()
            }
            SyncRecoveryAction.WAIT_EXTERNAL, SyncRecoveryAction.WAIT_SERVICE -> Unit
            else -> {
                val platform = when (action) {
                    SyncRecoveryAction.NETWORK -> SyncRecoveryPlatformAction.NETWORK
                    SyncRecoveryAction.STORAGE -> SyncRecoveryPlatformAction.STORAGE
                    SyncRecoveryAction.UPDATE -> SyncRecoveryPlatformAction.UPDATE
                    SyncRecoveryAction.BACKUP -> SyncRecoveryPlatformAction.BACKUP
                    SyncRecoveryAction.EXTENSIONS -> SyncRecoveryPlatformAction.EXTENSIONS
                    SyncRecoveryAction.MIGRATION -> SyncRecoveryPlatformAction.MIGRATION
                    SyncRecoveryAction.READER -> SyncRecoveryPlatformAction.READER
                    else -> return
                }
                val key = state.value.recoveryRepairReport?.fields?.firstOrNull()?.objectKey
                    .takeIf {
                        platform in setOf(
                            SyncRecoveryPlatformAction.MIGRATION,
                            SyncRecoveryPlatformAction.READER,
                        )
                    }
                openRecoveryPlatform(SyncPanelAction.OpenRecoveryPlatform(platform, key))
            }
        }
    }

    private suspend fun completeRecoveryPlatform(action: SyncPanelAction.RecoveryPlatformCompleted) {
        var request = state.value.recoveryPlatformRequest?.takeIf { it.requestId == action.requestId }
        if (request == null) {
            val local = recoveryRead { runtime.connectionFacts() } ?: return
            if (local.decode == SyncBindingDecode.OK && local.projection != null) {
                val flow = recoveryRead { runtime.recoveryWorkflow.facts() }?.flow ?: return
                val restored = flow.request?.takeIf { it.requestId == action.requestId && !it.failed } ?: return
                mutableState.update {
                    it.copy(
                        connection = local.projection,
                        recoveryPlatformRequest = restored,
                        recoveryPlatformLaunchPending = false,
                    )
                }
                request = restored
            } else if (local.decode == SyncBindingDecode.MISSING && local.projection == null) {
                restoreUnboundRecovery()
                request = state.value.recoveryPlatformRequest?.takeIf { it.requestId == action.requestId && !it.failed }
            }
        }
        val currentRequest = request ?: return
        if (currentRequest.originSpaceId != null && (
                currentRequest.originSpaceId != state.value.connection?.spaceId ||
                    currentRequest.originGeneration != state.value.connection?.generation
                )
        ) {
            return
        }
        val failed = action.result is SyncRecoveryPlatformResult.Failed
        val restart = action.result == SyncRecoveryPlatformResult.RestartRequired
        if (currentRequest.originSpaceId != null) {
            val accepted = try {
                runtime.recoveryWorkflow.platformCompleted(action.requestId, action.result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        recoveryPersistenceFailed = true,
                        recoveryStepFailure = error.recoveryFailure(),
                    )
                }
                return
            }
            if (!accepted) return
        } else {
            try {
                val before = runtime.onboarding.storage.unboundRecoveryFlow() ?: return
                if (before.request?.requestId != action.requestId ||
                    before.credentialRevision != runtime.credentials.read()?.revision
                ) {
                    return
                }
                if (before.accountId != null && setupAccount != null && before.accountId != setupAccount?.id) return
                if (before.creationAttemptId != null) {
                    val creation = before.accountId?.let { runtime.onboarding.storage.repositoryCreation(it) }
                    if (creation?.attemptId != before.creationAttemptId ||
                        creation.repositoryId != before.repositoryId
                    ) {
                        return
                    }
                }
                saveUnboundRecovery(
                    currentRequest.copy(
                        result = action.result,
                        failed = failed,
                        restartRequired = restart,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        recoveryPersistenceFailed = true,
                        recoveryStepFailure = error.recoveryFailure(),
                    )
                }
            }
        }
        val external = recoveryRead {
            if (currentRequest.originSpaceId != null) {
                runtime.recoveryWorkflow.facts().externalScopes
            } else {
                runtime.onboarding.storage.unboundRecoveryFlow()?.externalScopes.orEmpty()
            }
        }
        mutableState.update {
            it.copy(
                recoveryPlatformLaunchPending = false,
                recoveryPlatformRequest = currentRequest.copy(
                    failed = failed,
                    restartRequired = restart,
                    result = action.result,
                ),
                recoveryPlatformResult = action.result,
                recoveryExternalScopes = external ?: it.recoveryExternalScopes,
                recoveryRestartRequired = restart,
                recoveryOutcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
                recoveryStep = if (restart) {
                    SyncRecoveryFlowStep.EXTERNAL_ACTION
                } else {
                    SyncRecoveryFlowStep.CHECK_CONDITIONS
                },
            )
        }
        // Returning from a page, even with changed facts, is not a completed synchronization run.
    }

    private fun recheckSpace(preserveSetupError: Boolean = false) {
        if (recoveryJob?.isActive == true || authJob?.isActive == true) return
        val session = panelSession
        val version = recoveryVersion
        val page = state.value.page
        mutableState.update { it.copy(recovery = it.recovery?.copy(busy = true)) }
        recoveryJob = scope.launch {
            val binding = recoveryRead { runtime.recoveryBinding() }
            val result = try {
                runtime.recheckSpace()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                SyncSpaceRecoveryCheck(problem = failure.syncProblem())
            }
            enqueue {
                if (session != panelSession || version != recoveryVersion || !state.value.visible) {
                    return@enqueue
                }
                recoveryJob = null
                val checked = result.recovery == null && result.problem == null && binding != null &&
                    runtime.recoveryWorkflow.conditionsChecked(true, binding)
                refresh()
                mutableState.update {
                    it.copy(
                        recovery = result.recovery ?: it.recovery.takeIf { result.problem != null },
                        problem = if (preserveSetupError) it.problem else result.problem,
                        recoveryConditionsVerified = checked,
                        recoveryOfficialAction = if (checked) null else it.recoveryOfficialAction,
                        recoveryStepFailure = if (checked) null else it.recoveryStepFailure,
                        notice = if (result.spaceAddressUpdated) {
                            SyncPanelNotice(spaceAddressUpdated = true)
                        } else {
                            it.notice
                        },
                        page = if (preserveSetupError || it.page != page) {
                            it.page
                        } else if (result.recovery == null && result.problem == null) {
                            if (page == SyncPanelPage.RECOVERY) SyncPanelPage.RECOVERY else SyncPanelPage.MAIN
                        } else {
                            SyncPanelPage.RECOVERY
                        },
                    )
                }
            }
        }
    }

    private suspend fun cancelRecovery() {
        recoveryVersion++
        recoveryJob?.cancelAndJoin()
        recoveryJob = null
        mutableState.update { it.copy(recovery = it.recovery?.copy(busy = false), recoveryBusy = false) }
    }

    private suspend fun <T> recoveryRead(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun openSafeRecovery(problem: SyncRunProblem) {
        mutableState.update {
            it.copy(
                visible = true,
                page = SyncPanelPage.RECOVERY,
                loaded = true,
                canChangeSpace = false,
                recoveryBindingStatus = if (problem == SyncRunProblem.INVALID_DATA) {
                    SyncBindingDecode.UNSUPPORTED
                } else {
                    SyncBindingDecode.READ_FAILED
                },
                recoveryConditionsVerified = false,
                recoveryFailure = it.recoveryFailure ?: SyncRecoveryFailure(problem),
                recoveryOutcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
                externalRecoveryOrigin = runtime.externalRecoveryOrigin(),
            )
        }
    }

    private suspend fun refreshRecoveryDisplay() {
        try {
            refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update {
                it.copy(
                    recoveryPersistenceFailed = true,
                    recoveryFailure = it.recoveryFailure
                        ?: error.recoveryFailure(),
                )
            }
        }
    }

    private suspend fun openRecoveryPlatform(action: SyncPanelAction.OpenRecoveryPlatform) {
        if (state.value.recoveryBusy) return
        val independent = action.objectKey == null && action.action in setOf(
            SyncRecoveryPlatformAction.NETWORK,
            SyncRecoveryPlatformAction.STORAGE,
            SyncRecoveryPlatformAction.UPDATE,
            SyncRecoveryPlatformAction.BACKUP,
        )
        val local = if (independent) recoveryRead { runtime.connectionFacts() } else null
        if (independent && local != null && (local.decode != SyncBindingDecode.OK || local.projection == null)) {
            val request = SyncRecoveryPlatformRequest(
                java.util.UUID.randomUUID().toString(),
                action.action,
                runtimeInstanceId = runtime.instanceId,
            )
            try {
                saveUnboundRecovery(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        recoveryPersistenceFailed = true,
                        recoveryStepFailure = error.recoveryFailure(),
                    )
                }
            }
            mutableState.update {
                it.copy(
                    recoveryPlatformRequest = request,
                    recoveryPlatformResult = null,
                    recoveryPlatformLaunchPending = true,
                    recoveryOutcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
                    recoveryStep = SyncRecoveryFlowStep.EXTERNAL_ACTION,
                )
            }
            return
        }
        val request = try {
            runtime.recoveryWorkflow.openPlatform(action.action, action.objectKey, state.value.recoveryRepairOffset)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update {
                it.copy(
                    recoveryPersistenceFailed = true,
                    recoveryFailure = it.recoveryFailure
                        ?: error.recoveryFailure(),
                )
            }
            if (action.objectKey != null || action.action !in setOf(
                    SyncRecoveryPlatformAction.STORAGE,
                    SyncRecoveryPlatformAction.NETWORK,
                    SyncRecoveryPlatformAction.UPDATE,
                    SyncRecoveryPlatformAction.BACKUP,
                )
            ) {
                return
            }
            // This exit contains no account, object, token or target claim and never writes a broken store.
            SyncRecoveryPlatformRequest(
                java.util.UUID.randomUUID().toString(),
                action.action,
                runtimeInstanceId = runtime.instanceId,
            )
        }
        mutableState.update {
            it.copy(
                recoveryPlatformRequest = request,
                recoveryPlatformLaunchPending = true,
                recoveryOutcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
                recoveryStep = SyncRecoveryFlowStep.EXTERNAL_ACTION,
            )
        }
    }

    private fun verifyRecovery(repairOffset: Long? = null) {
        if (recoveryJob?.isActive == true || state.value.recoveryBusy) return
        val session = panelSession
        val version = recoveryVersion
        mutableState.update { it.copy(recoveryBusy = true, recoveryOutcome = null, recoveryPersistenceFailed = false) }
        recoveryJob = scope.launch {
            try {
                val facts = runtime.recoveryWorkflow.verify(repairOffset)
                enqueue {
                    recoveryJob = null
                    if (session != panelSession || version != recoveryVersion) return@enqueue
                    mutableState.update {
                        it.copy(
                            page = SyncPanelPage.RECOVERY, recoveryBusy = false,
                            recoveryStep = facts.flow?.step ?: SyncRecoveryFlowStep.CHECK_CONDITIONS,
                            recoveryOutcome = facts.flow?.outcome,
                            recoveryFailure = facts.flow?.failure,
                            recoveryRepairReport = facts.report,
                            recoveryRepairOffset = repairOffset ?: 0,
                            recoveryOldScopes = facts.oldScopes,
                            recoveryExternalScopes = facts.externalScopes,
                            recoveryRepairMadeNoProgress = facts.flow?.repairMadeNoProgress ?: false,
                            recoveryPlatformRequest = facts.flow?.request,
                            recoveryPlatformLaunchPending = false,
                        )
                    }
                    refreshRecoveryDisplay()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                enqueue {
                    recoveryJob = null
                    if (session == panelSession && version == recoveryVersion) {
                        mutableState.update {
                            it.copy(
                                recoveryBusy = false,
                                recoveryOutcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
                                recoveryFailure = it.recoveryFailure ?: error.recoveryFailure(),
                                recoveryStepFailure = error.recoveryFailure(),
                                recoveryPersistenceFailed = true,
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun prepareFailedBulkRetry(jobId: String) {
        if (bulkJob?.isActive == true || state.value.recoveryBusy) return
        cancelConfirmation()
        val connection = state.value.connection ?: return
        val previous = handler.await { sync_inboxQueries.getBulkJob(jobId).executeAsOneOrNull() } ?: return
        if (previous.space_id != connection.spaceId || previous.generation != connection.generation) return
        val next = runtime.projector.prepareFailedBulkRetry(jobId)
        val counts = handler.await {
            sync_inboxQueries.countBulkTypes(next).executeAsList().associate { it.field_ to it.count }
        }
        mutableState.update {
            it.copy(
                confirmation = SyncBulkConfirmation(
                    next,
                    mihon.domain.sync.SyncCancellationDecision.valueOf(
                        previous.decision,
                    ),
                    counts[SyncField.FAVORITE.name]
                        ?: 0,
                    counts[SyncField.FOLLOWING.name]
                        ?: 0,
                ),
            )
        }
    }

    private suspend fun checkRepositoryCreationPermission(onGranted: (suspend () -> Unit)? = null) {
        if (repositoryJob?.isActive == true) return
        val revision = runtime.credentials.read()?.revision
        val expected = setupAccount?.id ?: recoveryRead { runtime.recoveryBinding() }?.stored?.accountId
        val version = authVersion
        val session = panelSession
        val page = state.value.page
        val step = state.value.setupStep
        mutableState.update { it.copy(setupBusy = true, setupInstallation = null, creationPermissionProblem = null) }
        repositoryJob = scope.launch {
            val checked = try {
                val permission = runtime.onboarding.checkRepositoryCreationPermission(expected)
                if (runtime.credentials.read()?.revision != revision) {
                    throw SyncSetupException(SyncDiscoveryProblem.ACCOUNT_CHANGED)
                }
                permission
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                SyncRepositoryCreationPermission(
                    problem = (error as? SyncSetupException)?.problem ?: SyncDiscoveryProblem.RETRYABLE,
                )
            }
            enqueue {
                if (version != authVersion || session != panelSession || !state.value.visible ||
                    state.value.page != page || state.value.setupStep != step
                ) {
                    return@enqueue
                }
                repositoryJob = null
                if (runtime.credentials.read()?.revision != revision) {
                    mutableState.update {
                        it.copy(
                            setupBusy = false,
                            setupInstallation = null,
                            creationPermissionProblem = SyncDiscoveryProblem.ACCOUNT_CHANGED,
                        )
                    }
                    return@enqueue
                }
                checked.account?.let { setupAccount = it }
                mutableState.update {
                    it.copy(
                        setupBusy = false,
                        setupInstallation = checked.installation,
                        setupAccountLogin = checked.account?.login ?: it.setupAccountLogin,
                        creationPermissionProblem = checked.problem,
                    )
                }
                if (checked.problem == null && checked.installation?.canCreateRepository == true) onGranted?.invoke()
            }
        }
    }

    private suspend fun prepareRepositoryCreation(
        name: String,
        manual: Boolean = false,
        permissionChecked: Boolean = false,
    ) {
        if (state.value.setupBusy || repositoryJob?.isActive == true) return
        val normalized = name.trim()
        if (
            runCatching {
                mihon.domain.sync.transport.SyncRepository(
                    "validated-owner",
                    normalized,
                    mihon.data.sync.auth.GitHubSyncSpaceClient.BRANCH,
                )
            }.isFailure
        ) {
            setupFailed(SyncSetupException(SyncDiscoveryProblem.MALFORMED))
            return
        }
        try {
            if (!manual && !permissionChecked) {
                checkRepositoryCreationPermission { prepareRepositoryCreation(name, permissionChecked = true) }
                return
            }
            val binding = recoveryRead { runtime.recoveryBinding() }
            val account = setupAccount ?: binding?.stored?.let { SyncGitHubAccount(it.accountId, it.accountLogin) }
                ?: runtime.onboarding.session().account
            setupAccount = account
            val existing = runtime.onboarding.storage.repositoryCreation(account.id)
            repositoryProposal = existing?.takeIf {
                it.repositoryName == normalized
            }
                ?: SyncRepositoryCreationIntent(
                    account,
                    normalized,
                    java.util.UUID.randomUUID().toString(),
                )
            repositoryProposalRevision = runtime.credentials.read()?.revision
            mutableState.update {
                it.copy(
                    repositoryCreationName = normalized,
                    setupAccountLogin = account.login,
                    setupRepository = mihon.domain.sync.transport.SyncRepository(
                        account.login,
                        normalized,
                        mihon.data.sync.auth.GitHubSyncSpaceClient.BRANCH,
                    ),
                    question = if (manual) {
                        SyncPanelQuestion.CONNECT_MANUAL_REPOSITORY
                    } else {
                        SyncPanelQuestion.CREATE_REPOSITORY
                    },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            setupFailed(error)
        }
    }

    private suspend fun resumePendingRepositoryCreation(): Boolean {
        try {
            if (runtime.credentials.read() == null) return false
            val expected = setupAccount?.id ?: recoveryRead { runtime.recoveryBinding() }?.stored?.accountId
            if (expected != null && runtime.onboarding.storage.repositoryCreation(expected) == null) return false
            val session = runtime.onboarding.session(expected)
            val pending = runtime.onboarding.storage.repositoryCreation(session.account.id) ?: return false
            setupAccount = session.account
            switchIntent = runtime.activeSwitch()
            repositoryProposal = pending
            repositoryProposalRevision = runtime.credentials.read()?.revision
            startRepositoryCreation()
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            setupFailed(error)
            return true
        }
    }

    private fun startRepositoryCreation(manual: Boolean = false) {
        val proposed = repositoryProposal ?: return
        if (repositoryJob?.isActive == true || setupJob?.isActive == true) return
        val sessionVersion = panelSession
        val version = authVersion
        mutableState.update { it.copy(setupBusy = true, setupStep = SyncSetupStep.CREATING, setupProblem = null) }
        repositoryJob = scope.launch {
            try {
                val session = runtime.onboarding.session(proposed.account.id)
                if (
                    runtime.credentials.read()
                        ?.revision != repositoryProposalRevision
                ) {
                    throw SyncSetupException(
                        SyncDiscoveryProblem.ACCOUNT_CHANGED,
                    )
                }
                if (!manual && !proposed.submitted && proposed.repositoryId == null) {
                    val permission = runtime.onboarding.checkRepositoryCreationPermission(proposed.account.id)
                    permission.problem?.let { throw SyncSetupException(it) }
                    if (runtime.credentials.read()?.revision != repositoryProposalRevision) {
                        throw SyncSetupException(SyncDiscoveryProblem.ACCOUNT_CHANGED)
                    }
                }
                val storage = runtime.onboarding.storage
                var current = storage.repositoryCreation(proposed.account.id)
                if (current != null && current.attemptId != proposed.attemptId) {
                    storage.archiveRepositoryCreation(current)
                    current = null
                }
                if (current == null) {
                    storage.saveRepositoryCreation(proposed, null)
                    current = proposed
                }
                val persist: suspend (SyncRepositoryCreationIntent) -> Unit = { next ->
                    storage.saveRepositoryCreation(next, current)
                    current = next
                }
                val manager = runtime.onboarding.repositoryManager(session.token, session.account.id)
                val result = if (manual) {
                    manager.confirmManualSelection(requireNotNull(current), persist)
                } else {
                    manager.createOrResume(requireNotNull(current), persist)
                }
                val recorded = requireNotNull(current)
                enqueue {
                    repositoryJob = null
                    if (sessionVersion != panelSession || version != authVersion || !state.value.visible) return@enqueue
                    mutableState.update {
                        it.copy(
                            creationRepositoryId = recorded.repositoryId,
                            creationSubmitted = recorded.submitted,
                            repositoryCreationName = recorded.repositoryName,
                        )
                    }
                    when (result) {
                        is SyncSpaceCreation.Ready -> {
                            setupAccount = session.account
                            emptyRepositoryCandidate = EmptySyncRepositoryCandidate(
                                session.account,
                                result.repositoryId,
                                result.repository,
                                result.defaultBranch,
                                state.value.setupInstallation,
                                recorded.attemptId,
                            )
                            mutableState.update {
                                it.copy(
                                    setupBusy = false,
                                    setupStep = SyncSetupStep.NEW_PASSWORD,
                                    setupRepository = result.repository,
                                    setupAccountLogin = session.account.login,
                                    setupProblem = null,
                                )
                            }
                        }
                        is SyncSpaceCreation.Existing -> selectSpace(result.space)
                        is SyncSpaceCreation.Failed ->
                            setupFailed(
                                SyncSetupException(
                                    result.problem,
                                ),
                                session.account.login,
                                state.value.setupInstallation,
                            )
                    }
                    refreshRecoveryDisplay()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                enqueue {
                    repositoryJob = null
                    if (
                        sessionVersion == panelSession &&
                        version == authVersion
                    ) {
                        setupFailed(
                            error,
                            proposed.account.login,
                            state.value.setupInstallation,
                        )
                    }
                }
            }
        }
    }

    private suspend fun prepareRepositoryProperties(
        makePrivate: Boolean,
        unarchive: Boolean,
        authorize: Boolean = false,
    ) {
        if (state.value.recoveryBusy || repositoryJob?.isActive == true) return
        try {
            val binding = recoveryRead { runtime.recoveryBinding() }
            val account = setupAccount ?: binding?.stored?.let { SyncGitHubAccount(it.accountId, it.accountLogin) }
                ?: runtime.onboarding.session().account
            val creation = runtime.onboarding.storage.repositoryCreation(account.id)
            val pending = runtime.onboarding.storage.pending(account.id)
            propertyTarget = when {
                creation?.repositoryId != null ->
                    SyncRepositoryRepairTarget(
                        account,
                        mihon.domain.sync.transport.SyncRepository(
                            account.login,
                            creation.repositoryName,
                            mihon.data.sync.auth.GitHubSyncSpaceClient.BRANCH,
                        ),
                        creation.repositoryId,
                    )
                pending != null -> SyncRepositoryRepairTarget(account, pending.repository(), pending.repositoryId)
                binding != null ->
                    SyncRepositoryRepairTarget(
                        account,
                        binding.stored.repository(),
                        binding.stored.repositoryId,
                    )
                else -> throw SyncSetupException(SyncDiscoveryProblem.CREATION_UNCONFIRMED)
            }
            propertyRevision = runtime.credentials.read()?.revision
            mutableState.update {
                it.copy(
                    setupRepository = propertyTarget?.repository,
                    repairMakePrivate = makePrivate,
                    repairUnarchive = unarchive,
                    question = if (authorize) {
                        SyncPanelQuestion.AUTHORIZE_REPOSITORY_SCOPE
                    } else {
                        SyncPanelQuestion.REPAIR_REPOSITORY_PROPERTIES
                    },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableState.update {
                it.copy(
                    recoveryFailure = error.recoveryFailure(),
                    recoveryOutcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
                )
            }
        }
    }

    private fun runRepositoryManagement(authorize: Boolean) {
        val target = propertyTarget ?: return
        if (repositoryJob?.isActive == true) return
        val makePrivate = state.value.repairMakePrivate
        val unarchive = state.value.repairUnarchive
        val version = authVersion
        val visibleSession = panelSession
        mutableState.update { it.copy(recoveryBusy = true) }
        repositoryJob = scope.launch {
            try {
                val session = runtime.onboarding.session(target.account.id)
                if (
                    runtime.credentials.read()
                        ?.revision != propertyRevision
                ) {
                    throw SyncSetupException(
                        SyncDiscoveryProblem.ACCOUNT_CHANGED,
                    )
                }
                val manager = runtime.onboarding.repositoryManager(session.token, session.account.id)
                val creation = runtime.onboarding.storage.repositoryCreation(target.account.id)
                val failure = if (authorize) {
                    manager.authorizeRepository(target, creation)
                } else {
                    manager.repairProperties(target, makePrivate, unarchive)
                }
                enqueue {
                    repositoryJob = null
                    if (visibleSession != panelSession || version != authVersion) return@enqueue
                    mutableState.update { it.copy(recoveryBusy = false) }
                    if (failure != null) {
                        mutableState.update {
                            it.copy(
                                recoveryFailure = SyncSetupException(
                                    failure,
                                ).recoveryFailure(),
                                recoveryOutcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
                            )
                        }
                    } else if (creation != null) {
                        repositoryProposal = creation
                        repositoryProposalRevision = runtime.credentials.read()?.revision
                        startRepositoryCreation()
                    } else {
                        verifyRecovery()
                    }
                    refreshRecoveryDisplay()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                enqueue {
                    repositoryJob = null
                    if (visibleSession == panelSession && version == authVersion) {
                        mutableState.update {
                            it.copy(
                                recoveryBusy = false,
                                recoveryFailure = error.recoveryFailure(),
                                recoveryOutcome = SyncRecoveryOutcome.WAITING_EXTERNAL,
                            )
                        }
                    }
                }
            }
        }
    }

    internal suspend fun awaitRecoveryIdle() {
        awaitIdle()
        recoveryJob?.join()
        awaitIdle()
    }

    private fun discover(autoSelect: Boolean = true, inspectPending: Boolean = true) {
        if (setupJob?.isActive == true || repositoryJob?.isActive == true) return
        val version = authVersion
        mutableState.update {
            it.copy(
                setupStep = SyncSetupStep.DISCOVERING,
                setupBusy = true,
                setupProblem = null,
                passwordProblem = null,
            )
        }
        repositoryJob = scope.launch {
            try {
                if (switchIntent != null) {
                    val intent = requireNotNull(switchIntent)
                    runtime.verifySwitch(intent)
                    val found = runtime.onboarding.discover()
                    enqueue { if (version == authVersion) handleSwitchDiscovery(found, intent) }
                    return@launch
                }
                when (
                    val pending = if (inspectPending) {
                        runtime.onboarding.pendingForCurrentAccount()
                    } else {
                        SyncPendingSetup.None
                    }
                ) {
                    is SyncPendingSetup.Current -> enqueue {
                        if (version == authVersion) {
                            setupAccount = SyncGitHubAccount(pending.setup.accountId, pending.setup.accountLogin)
                            legacyPending = null
                            mutableState.update {
                                it.copy(
                                    legacyRecoveryAvailable = false,
                                    setupAccountLogin = pending.setup.accountLogin,
                                    setupRepository = pending.setup.repository(),
                                )
                            }
                            if (runtime.connectionFacts().projection == null) saveUnboundRecovery()
                            if (autoSelect) {
                                runSetup { pending.setup }
                            } else {
                                mutableState.update {
                                    it.copy(setupStep = SyncSetupStep.MERGING, setupBusy = false)
                                }
                            }
                        }
                    }
                    is SyncPendingSetup.Legacy -> {
                        legacyPending = pending.setup
                        enqueue {
                            if (version == authVersion) {
                                mutableState.update { it.copy(legacyRecoveryAvailable = true) }
                            }
                        }
                        val recheck = runtime.onboarding.recheckLegacyPending(pending.setup)
                        if (pending.setup.newSpace) {
                            enqueue {
                                if (version == authVersion) {
                                    setupFailed(SyncSetupException(SyncDiscoveryProblem.CREATION_UNCONFIRMED))
                                }
                            }
                        } else {
                            enqueue {
                                if (version == authVersion) {
                                    runSetup { runtime.onboarding.migrateLegacyJoin(recheck) }
                                }
                            }
                        }
                    }
                    SyncPendingSetup.None -> {
                        legacyPending = null
                        val found = runtime.onboarding.discover()
                        enqueue {
                            if (version == authVersion) handleDiscovery(found, autoSelect)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                enqueue { if (version == authVersion) setupFailed(failure) }
            }
        }
    }

    private suspend fun abandonLegacyPending() {
        val pending = legacyPending ?: return discover()
        mutableState.update { it.copy(setupBusy = true, setupProblem = null) }
        try {
            runtime.onboarding.abandonLegacyPending(pending)
            legacyPending = null
            mutableState.update {
                it.copy(setupBusy = false, setupProblem = null, legacyRecoveryAvailable = false)
            }
            discover(autoSelect = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update {
                it.copy(
                    setupBusy = false,
                    setupProblem = (failure as? SyncSetupException)?.problem ?: SyncDiscoveryProblem.RETRYABLE,
                )
            }
        }
    }

    private suspend fun recordDiscoveryFacts(result: SyncSpaceDiscovery) {
        val officialAction = state.value.recoveryOfficialAction
        val completedOfficial = when (state.value.recoveryOfficialAction) {
            SyncRecoveryAction.INSTALL_APP, SyncRecoveryAction.RESTORE_INSTALLATION ->
                result !is SyncSpaceDiscovery.NeedsInstallation &&
                    result !is SyncSpaceDiscovery.InstallationSuspended &&
                    result !is SyncSpaceDiscovery.Failed
            SyncRecoveryAction.OFFICIAL_CREATE ->
                result is SyncSpaceDiscovery.EmptyRepository ||
                    result is SyncSpaceDiscovery.Found
            else -> false
        }
        if (completedOfficial) {
            mutableState.update {
                it.copy(
                    recoveryOfficialAction = null,
                    recoveryOfficialCheckAttempted = false,
                    recoveryPlatformResult = null,
                )
            }
        }
        setupAccount = when (result) {
            is SyncSpaceDiscovery.Found -> result.space.account
            is SyncSpaceDiscovery.Multiple -> result.spaces.firstOrNull()?.account
            is SyncSpaceDiscovery.EmptyRepository -> result.candidate.account
            is SyncSpaceDiscovery.NoVisibleSpace -> result.account
            is SyncSpaceDiscovery.NeedsInstallation -> result.account
            is SyncSpaceDiscovery.NeedsRepositoryAccess -> result.account
            is SyncSpaceDiscovery.NeedsContentsPermission -> result.account
            is SyncSpaceDiscovery.InstallationSuspended -> result.account
            is SyncSpaceDiscovery.Failed -> result.account ?: setupAccount
        }
        val installation = when (result) {
            is SyncSpaceDiscovery.Found -> result.space.installation
            is SyncSpaceDiscovery.Multiple -> result.spaces.firstOrNull()?.installation
            is SyncSpaceDiscovery.EmptyRepository -> result.candidate.installation
            is SyncSpaceDiscovery.NoVisibleSpace -> result.installation
            is SyncSpaceDiscovery.NeedsInstallation -> null
            is SyncSpaceDiscovery.NeedsRepositoryAccess -> result.installation
            is SyncSpaceDiscovery.NeedsContentsPermission -> result.installation
            is SyncSpaceDiscovery.InstallationSuspended -> result.installation
            is SyncSpaceDiscovery.Failed -> result.installation ?: state.value.setupInstallation
        }
        mutableState.update { it.copy(setupInstallation = installation) }
        if (completedOfficial) {
            try {
                if (runtime.connectionFacts().decode == SyncBindingDecode.OK) {
                    runtime.recoveryWorkflow.officialCompleted(requireNotNull(officialAction))
                }
                saveUnboundRecovery()
            } catch (
                cancelled: CancellationException,
            ) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        recoveryPersistenceFailed = true,
                        recoveryStepFailure = error.recoveryFailure(),
                    )
                }
            }
        }
    }

    private suspend fun handleDiscovery(result: SyncSpaceDiscovery, autoSelect: Boolean = true) {
        recordDiscoveryFacts(result)
        when (result) {
            is SyncSpaceDiscovery.Found -> {
                if (autoSelect) {
                    selectSpace(result.space)
                } else {
                    mutableState.update {
                        it.copy(
                            setupStep = SyncSetupStep.CHOOSE_SPACE,
                            setupBusy = false,
                            spaces = listOf(result.space),
                            setupAccountLogin = result.space.account.login,
                            setupInstallation = result.space.installation,
                        )
                    }
                }
            }
            is SyncSpaceDiscovery.Multiple -> mutableState.update {
                it.copy(
                    setupStep = SyncSetupStep.CHOOSE_SPACE,
                    setupBusy = false,
                    spaces = result.spaces,
                    setupAccountLogin = result.spaces.firstOrNull()?.account?.login,
                    setupInstallation = result.spaces.firstOrNull()?.installation,
                )
            }
            is SyncSpaceDiscovery.NoVisibleSpace -> {
                emptyRepositoryCandidate = null
                setupFailed(
                    SyncSetupException(SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS),
                    result.account.login,
                    result.installation,
                )
            }
            is SyncSpaceDiscovery.EmptyRepository -> {
                emptyRepositoryCandidate = result.candidate
                setupAccount = result.candidate.account
                chosenSpace = null
                mutableState.update {
                    it.copy(
                        setupStep = SyncSetupStep.NEW_PASSWORD,
                        setupBusy = false,
                        setupAccountLogin = result.candidate.account.login,
                        setupInstallation = result.candidate.installation,
                        setupRepository = result.candidate.repository,
                        spaces = emptyList(),
                    )
                }
            }
            is SyncSpaceDiscovery.NeedsInstallation -> setupFailed(
                SyncSetupException(SyncDiscoveryProblem.NEEDS_INSTALLATION),
                result.account.login,
                null,
            )
            is SyncSpaceDiscovery.NeedsRepositoryAccess -> setupFailed(
                SyncSetupException(SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS),
                result.account.login,
                result.installation,
            )
            is SyncSpaceDiscovery.NeedsContentsPermission -> setupFailed(
                SyncSetupException(SyncDiscoveryProblem.NEEDS_CONTENTS_PERMISSION),
                result.account.login,
                result.installation,
            )
            is SyncSpaceDiscovery.InstallationSuspended -> setupFailed(
                SyncSetupException(SyncDiscoveryProblem.INSTALLATION_SUSPENDED),
                result.account.login,
                result.installation,
            )
            is SyncSpaceDiscovery.Failed -> setupFailed(
                SyncSetupException(result.problem),
                result.account?.login,
                result.installation,
            )
        }
    }

    private fun selectSpace(space: DiscoveredSyncSpace) {
        if (setupJob?.isActive == true) return
        setupAccount = space.account
        chosenSpace = space
        mutableState.update {
            it.copy(
                setupRepository = space.repository,
                setupAccountLogin = space.account.login,
                setupInstallation = space.installation,
                spaces = emptyList(),
                setupProblem = null,
                passwordProblem = null,
                setupStep = if (space.descriptor.protection == SyncSpaceProtection.None) {
                    SyncSetupStep.MERGING
                } else {
                    SyncSetupStep.UNLOCK
                },
                setupBusy = space.descriptor.protection == SyncSpaceProtection.None,
            )
        }
        if (space.descriptor.protection == SyncSpaceProtection.None) {
            if (switchIntent != null) {
                prepareSwitchTarget {
                    runtime.onboarding.join(
                        space,
                        SyncSpaceMaterial(space.descriptor, null),
                        requireNotNull(switchIntent),
                    )
                }
            } else {
                runSetup { runtime.onboarding.join(space, SyncSpaceMaterial(space.descriptor, null)) }
            }
        }
    }

    private fun submitPassword(password: String) {
        if (setupJob?.isActive == true || state.value.setupBusy) return
        val step = state.value.setupStep
        if (step != SyncSetupStep.NEW_PASSWORD && step != SyncSetupStep.UNLOCK) return
        try {
            SyncSpaceCrypto.validatePassword(password)
        } catch (failure: SyncPasswordInputException) {
            mutableState.update {
                it.copy(
                    passwordProblem = if (failure.issue == SyncPasswordInputIssue.TOO_LONG) {
                        SyncPasswordProblem.TOO_LONG
                    } else {
                        SyncPasswordProblem.INVALID
                    },
                )
            }
            return
        }
        if (step == SyncSetupStep.NEW_PASSWORD) {
            val candidate = emptyRepositoryCandidate ?: return
            if (switchIntent != null) {
                prepareSwitchTarget {
                    runtime.onboarding.create(candidate, password, requireNotNull(switchIntent))
                }
            } else {
                runSetup { runtime.onboarding.create(candidate, password) }
            }
        } else {
            val space = chosenSpace ?: return
            val prepare: suspend () -> StoredSyncSetup = {
                val material = SyncSpaceCrypto.unlock(space.descriptor, password).getOrElse {
                    throw IncorrectSyncPassword()
                }
                runtime.onboarding.join(space, material, switchIntent)
            }
            if (switchIntent != null) prepareSwitchTarget(prepare) else runSetup(prepare)
        }
    }

    private suspend fun cancelSwitchPreparation() {
        if (switchIntent?.stage == SyncSpaceSwitchStage.PREPARING) {
            setupVersion++
            setupJob?.cancelAndJoin()
            setupJob = null
            switchIntent = null
            switchConfirmation = null
            mutableState.update { it.copy(question = null, switchTargetRepository = null, setupBusy = false) }
        }
    }

    private suspend fun beginSwitch(purpose: SyncSpaceSwitchPurpose) {
        val facts = runtime.connectionFacts()
        if (facts.projection == null && facts.decode == SyncBindingDecode.MISSING) {
            cancelRecovery()
            cancelAuthorization()
            if (purpose == SyncSpaceSwitchPurpose.CREATE) {
                val session = runtime.onboarding.session(setupAccount?.id)
                val pending = runtime.onboarding.storage.pending(session.account.id)
                if (pending != null) {
                    val before = runtime.onboarding.storage.unboundRecoveryFlow()
                    val ids = (before?.archivedSetupIds.orEmpty() + pending.attemptId).distinct()
                    runtime.onboarding.storage.saveUnboundRecoveryFlow(
                        (before ?: StoredUnboundSyncRecoveryFlow()).copy(
                            accountId = session.account.id,
                            accountLogin = session.account.login,
                            credentialRevision = runtime.credentials.read()?.revision,
                            purpose = SyncRecoveryContinuation.CREATE.name,
                            archivedSetupIds = ids.takeLast(64),
                            archivedSetupsTruncated = before?.archivedSetupsTruncated == true || ids.size > 64,
                        ),
                        before,
                    )
                    runtime.onboarding.storage.archivePending(pending)
                }
            }
            switchIntent = null
            switchConfirmation = null
            mutableState.update {
                it.copy(
                    page = SyncPanelPage.SETUP,
                    setupStep = if (purpose == SyncSpaceSwitchPurpose.CREATE) {
                        SyncSetupStep.PREPARE_REPOSITORY
                    } else {
                        SyncSetupStep.SIGN_IN
                    },
                    setupBusy = false,
                    setupProblem = null,
                    question = null,
                )
            }
            if (purpose == SyncSpaceSwitchPurpose.CONNECT) beginSetup() else checkRepositoryCreationPermission()
            return
        }
        if (facts.decode != SyncBindingDecode.OK || facts.projection?.unsupportedFormat == true) {
            val problem = if (facts.decode == SyncBindingDecode.UNSUPPORTED) {
                SyncRunProblem.INVALID_DATA
            } else {
                SyncRunProblem.STORAGE
            }
            openSafeRecovery(problem)
            return
        }
        runtime.activeSwitch()?.let {
            resumeSwitch(it)
            return
        }
        bulkJob?.cancelAndJoin()
        bulkJob = null
        cancelRecovery()
        cancelAuthorization()
        val intent = runtime.beginSwitch(purpose)
        resumeSwitch(intent, newlyCreated = true)
    }

    private suspend fun resumeSwitch(intent: StoredSyncSpaceSwitch, newlyCreated: Boolean = false) {
        cancelRecovery()
        cancelAuthorization()
        switchIntent = intent
        switchConfirmation = null
        chosenSpace = null
        emptyRepositoryCandidate = null
        mutableState.update {
            it.copy(
                page = SyncPanelPage.SETUP,
                setupBusy = false,
                setupProblem = null,
                spaces = emptyList(),
                switchTargetRepository = intent.target?.repository(),
                switchPendingDecisions = it.pendingTotal,
            )
        }
        val target = intent.target?.let { runtime.onboarding.storage.setupFor(it) }
        if (target != null) {
            if (intent.stage == SyncSpaceSwitchStage.ACTIVATING) {
                runSetup { target }
            } else {
                showSwitchConfirmation(requireNotNull(runtime.onboarding.storage.activeSwitch(intent.accountId)))
            }
        } else if (intent.purpose == SyncSpaceSwitchPurpose.CREATE && newlyCreated) {
            mutableState.update { it.copy(setupStep = SyncSetupStep.PREPARE_REPOSITORY) }
            checkRepositoryCreationPermission()
        } else {
            discover(autoSelect = false)
        }
    }

    private suspend fun handleSwitchDiscovery(found: SyncSpaceDiscovery, intent: StoredSyncSpaceSwitch) {
        val choices = when (found) {
            is SyncSpaceDiscovery.Found -> listOf(found.space)
            is SyncSpaceDiscovery.Multiple -> found.spaces
            else -> null
        }
        if (choices != null || found is SyncSpaceDiscovery.NoVisibleSpace ||
            found is SyncSpaceDiscovery.NeedsRepositoryAccess
        ) {
            recordDiscoveryFacts(found)
            val old = intent.oldConnection.material.material().descriptor
            val candidates = choices.orEmpty().filter {
                it.account.id == intent.accountId &&
                    !(
                        it.repositoryId == intent.oldConnection.repositoryId && it.descriptor.spaceId == old.spaceId &&
                            it.descriptor.generation == old.generation
                        )
            }
            mutableState.update {
                it.copy(
                    setupStep = SyncSetupStep.CHOOSE_SPACE,
                    setupBusy = false,
                    spaces = candidates,
                    setupAccountLogin = intent.oldConnection.accountLogin,
                )
            }
        } else if (found is SyncSpaceDiscovery.EmptyRepository && intent.purpose == SyncSpaceSwitchPurpose.CONNECT) {
            recordDiscoveryFacts(found)
            mutableState.update {
                it.copy(
                    setupStep = SyncSetupStep.CHOOSE_SPACE,
                    setupBusy = false,
                    spaces = emptyList(),
                    setupAccountLogin = found.candidate.account.login,
                    setupInstallation = found.candidate.installation,
                )
            }
        } else {
            handleDiscovery(found, autoSelect = false)
        }
    }

    private fun prepareSwitchTarget(prepare: suspend () -> StoredSyncSetup) {
        val version = setupVersion
        val session = panelSession
        val previousStep = state.value.setupStep
        mutableState.update { it.copy(setupBusy = true) }
        setupJob = scope.launch {
            try {
                val target = prepare()
                val intent = requireNotNull(runtime.onboarding.storage.activeSwitch(target.accountId))
                enqueue {
                    if (version == setupVersion && session == panelSession && state.value.visible) {
                        showSwitchConfirmation(intent)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IncorrectSyncPassword) {
                enqueue {
                    if (version == setupVersion && session == panelSession) {
                        mutableState.update {
                            it.copy(
                                setupBusy = false,
                                setupStep = previousStep,
                                passwordProblem = SyncPasswordProblem.INCORRECT,
                            )
                        }
                    }
                }
            } catch (failure: Exception) {
                enqueue { if (version == setupVersion && session == panelSession) setupFailed(failure) }
            }
        }
    }

    private suspend fun showSwitchConfirmation(intent: StoredSyncSpaceSwitch) {
        switchIntent = intent
        switchConfirmation = intent
        val old = intent.oldConnection.material.material().descriptor
        val pending = handler.await {
            sync_inboxQueries.countPendingDecisions(old.spaceId, old.generation).executeAsOne()
        }
        mutableState.update {
            it.copy(
                setupBusy = false,
                setupStep = SyncSetupStep.CHOOSE_SPACE,
                question = SyncPanelQuestion.CONNECT_SPACE,
                switchTargetRepository = requireNotNull(intent.target).repository(),
                switchPendingDecisions = pending,
            )
        }
    }

    private fun runSetup(prepare: suspend () -> StoredSyncSetup) {
        if (setupJob?.isActive == true) return
        val version = setupVersion
        val previousStep = state.value.setupStep
        val session = panelSession
        mutableState.update {
            it.copy(setupBusy = true, setupStep = SyncSetupStep.CREATING, setupProblem = null, passwordProblem = null)
        }
        setupJob = scope.launch {
            try {
                val outcome = runtime.onboarding.resume(prepare())
                when (outcome) {
                    is SyncSetupOutcome.Existing -> enqueue {
                        if (version == setupVersion) {
                            setupJob = null
                            selectSpace(outcome.space)
                        }
                    }
                    is SyncSetupOutcome.Connected -> {
                        switchIntent = null
                        switchConfirmation = null
                        enqueue {
                            if (version == setupVersion) {
                                mutableState.update { it.copy(setupStep = SyncSetupStep.MERGING) }
                                refresh()
                            }
                        }
                        setupExchangeCompletion = runtime.coordinator.activity.value.completion + 1
                        val result = runtime.coordinator.synchronize(SyncTrigger.MANUAL)
                        val material = outcome.setup.material.material()
                        val remaining = handler.await {
                            sync_importQueries.countPendingImports(
                                material.descriptor.spaceId,
                                material.descriptor.generation,
                            ).executeAsOne()
                        }
                        val complete = result.status == SyncRunStatus.SUCCESS && remaining == 0L
                        if (complete) runtime.onboarding.complete(outcome.setup)
                        enqueue {
                            if (version == setupVersion) {
                                mutableState.update {
                                    it.copy(
                                        setupBusy = false,
                                        setupRetryAttempted = if (complete) false else it.setupRetryAttempted,
                                        setupRetryFailed = if (complete) false else it.setupRetryFailed,
                                        setupStep = if (complete) SyncSetupStep.COMPLETE else SyncSetupStep.MERGING,
                                        page = if (complete && it.visible && it.page == SyncPanelPage.SETUP) {
                                            SyncPanelPage.MAIN
                                        } else {
                                            it.page
                                        },
                                        problem = result.problem,
                                        notice = if (complete && it.visible && session == panelSession) {
                                            SyncPanelNotice(setupCompleted = true)
                                        } else {
                                            it.notice
                                        },
                                    )
                                }
                                refresh()
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IncorrectSyncPassword) {
                enqueue {
                    if (version == setupVersion) {
                        mutableState.update {
                            it.copy(
                                setupBusy = false,
                                setupStep = previousStep,
                                passwordProblem = SyncPasswordProblem.INCORRECT,
                            )
                        }
                    }
                }
            } catch (failure: Exception) {
                enqueue {
                    if (version == setupVersion) {
                        setupFailed(failure, state.value.setupAccountLogin, state.value.setupInstallation)
                    }
                }
            }
        }
    }

    private fun setupFailed(
        failure: Exception,
        accountLogin: String? = null,
        installation: SyncAppInstallation? = null,
    ) {
        val problem = when {
            failure is SyncSetupException -> failure.problem
            failure is SyncRequiredResourceUnavailable && failure.resource == SyncRequiredResource.REPOSITORY ->
                SyncDiscoveryProblem.REPOSITORY_UNAVAILABLE
            else -> SyncDiscoveryProblem.RETRYABLE
        }
        mutableState.update {
            it.copy(
                setupBusy = false,
                setupRetryFailed = it.setupRetryAttempted,
                setupStep = SyncSetupStep.ERROR,
                setupProblem = problem,
                recoveryStepFailure = null,
                recoveryConditionsVerified = false,
                initializationFailure = (failure as? SyncSetupException)?.initialization,
                problem = failure.syncProblem(),
                setupAccountLogin = accountLogin ?: it.setupAccountLogin,
                setupInstallation = installation ?: it.setupInstallation,
            )
        }
        val requiresCheck =
            failure.syncProblem() in setOf(SyncRunProblem.AUTHORIZATION, SyncRunProblem.SPACE_UNAVAILABLE) ||
                problem in setOf(
                    SyncDiscoveryProblem.REPOSITORY_UNAVAILABLE,
                    SyncDiscoveryProblem.NEEDS_INSTALLATION,
                    SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS,
                    SyncDiscoveryProblem.NEEDS_CONTENTS_PERMISSION,
                    SyncDiscoveryProblem.INSTALLATION_SUSPENDED,
                )
        if (requiresCheck && state.value.canChangeSpace) recheckSpace(preserveSetupError = true)
    }

    private class IncorrectSyncPassword : IllegalArgumentException("sync password is incorrect")

    private fun clearSelection() {
        selectedBindings = emptyMap()
        anchor = null
        mutableState.update { it.copy(selecting = false, selected = emptySet()) }
    }

    private suspend fun prepareDecision(action: SyncPanelAction.PrepareDecision) {
        if (bulkJob?.isActive == true || (state.value.bulk?.remaining ?: 0) > 0) return
        val connection = state.value.connection ?: return
        cancelConfirmation()
        val expected = when (action.scope) {
            SyncDecisionScope.ALL -> null
            SyncDecisionScope.SELECTED -> selectedBindings
            SyncDecisionScope.ITEM -> action.itemId?.let { id ->
                state.value.pending.firstOrNull { it.id == id }?.let { mapOf(id to it.binding) }
            } ?: emptyMap()
        }
        if (expected != null && expected.isEmpty()) return
        val job = runtime.projector.startBulk(
            connection.spaceId,
            connection.generation,
            action.decision,
            expectedBindings = expected,
        )
        val counts = handler.await {
            sync_inboxQueries.countBulkTypes(job).executeAsList().associate { it.field_ to it.count }
        }
        mutableState.update {
            it.copy(
                confirmation = SyncBulkConfirmation(
                    job,
                    action.decision,
                    counts[SyncField.FAVORITE.name] ?: 0,
                    counts[SyncField.FOLLOWING.name] ?: 0,
                ),
            )
        }
    }

    private suspend fun cancelConfirmation() {
        state.value.confirmation?.let {
            handler.await(inTransaction = true) {
                sync_inboxQueries.deleteBulkItems(it.jobId)
                sync_inboxQueries.deleteBulkJob(it.jobId)
            }
        }
        mutableState.update { it.copy(confirmation = null) }
    }

    private fun startBulk(id: String) {
        if (bulkJob?.isActive == true) return
        bulkJob = scope.launch {
            try {
                val job = handler.await { sync_inboxQueries.getBulkJob(id).executeAsOne() }
                if (runtime.activeSwitch() != null) return@launch
                do {
                    val connection = runtime.connection()
                    if (job.space_id != connection?.spaceId || job.generation != connection.generation) break
                    val progress = runtime.projector.processBulk(id)
                    enqueue {
                        refresh()
                        if (progress.queued == 0L && state.value.visible &&
                            state.value.connection?.spaceId == job.space_id &&
                            state.value.connection?.generation == job.generation
                        ) {
                            mutableState.update { it.copy(notice = SyncPanelNotice(bulk = progress.toStatus(id))) }
                        }
                    }
                    yield()
                } while (progress.queued > 0)
            } finally {
                queueRefresh()
            }
        }
    }

    private fun SyncBulkProgress.toStatus(id: String, running: Boolean = false) = SyncBulkStatus(
        id,
        total,
        queued,
        (outcomes["APPLIED"] ?: 0) + (outcomes["KEPT_LOCAL"] ?: 0),
        outcomes["INVALIDATED"] ?: 0,
        outcomes["FAILED"] ?: 0,
        running,
    )
}
