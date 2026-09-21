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
import mihon.data.sync.auth.SyncSpaceDiscovery
import mihon.data.sync.crypto.SyncPasswordInputException
import mihon.data.sync.crypto.SyncPasswordInputIssue
import mihon.data.sync.crypto.SyncSpaceCrypto
import mihon.data.sync.inbox.SyncBulkProgress
import mihon.domain.sync.SyncField
import mihon.domain.sync.auth.GitHubDeviceAuthResult
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
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val refreshQueued = AtomicBoolean(false)
    private var references = linkedMapOf<Long, String>()
    private var selectedBindings = emptyMap<Long, String>()
    private var anchor: Long? = null
    private var loadedCount = 100
    private var bulkJob: Job? = null
    private var authJob: Job? = null
    private var repositoryJob: Job? = null
    private var setupJob: Job? = null
    private var authVersion = 0L
    private var setupVersion = 0L
    private var panelSession = 0L
    private var setupExchangeCompletion = -1L
    private var setupAccount: SyncGitHubAccount? = null
    private var chosenSpace: DiscoveredSyncSpace? = null
    private var emptyRepositoryCandidate: EmptySyncRepositoryCandidate? = null
    private var legacyPending: StoredLegacySyncSetup? = null
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
                    refresh()
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
                        mutableState.update { it.copy(nowMillis = clock()) }
                        delay(1_000)
                    }
                }
            }
        }
    }

    override fun dispatch(action: SyncPanelAction) {
        enqueue { handle(action) }
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
                refresh()
            }
        }
    }

    private suspend fun refresh() {
        val connection = runtime.connection()
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
        }
        var membership = 0L
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
                        if (it.category == "READING") reading += it.count else membership += it.count
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
        mutableState.update {
            it.copy(
                loaded = true,
                connection = connection,
                queuedMembership = membership,
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
                nextSyncAtMillis = next,
                importRemaining = imports,
                importPaused = prefs.importPaused.get(),
                records = runtime.records().asReversed(),
            )
        }
    }

    private suspend fun handle(action: SyncPanelAction) {
        when (action) {
            SyncPanelAction.Open -> {
                mutableState.update { it.copy(visible = true, page = SyncPanelPage.MAIN, notice = null) }
                refresh()
            }
            SyncPanelAction.Close -> {
                panelSession++
                cancelAuthorization()
                cancelConfirmation()
                clearSelection()
                mutableState.update {
                    it.copy(visible = false, notice = null, question = null, deviceCode = null)
                }
            }
            SyncPanelAction.Back -> if (state.value.page == SyncPanelPage.MAIN) {
                handle(SyncPanelAction.Close)
            } else {
                if (state.value.page == SyncPanelPage.SETUP) cancelAuthorization()
                mutableState.update { it.copy(page = SyncPanelPage.MAIN) }
            }
            is SyncPanelAction.Navigate -> mutableState.update { it.copy(page = action.page) }
            SyncPanelAction.Synchronize -> if (state.value.connection?.enabled == true) {
                scope.launch { runtime.coordinator.synchronize(SyncTrigger.MANUAL) }
            } else {
                beginSetup()
            }
            SyncPanelAction.CancelSync -> scope.launch { runtime.coordinator.cancelAndJoin() }
            SyncPanelAction.PauseImport -> {
                runtime.preferences.importPaused.set(true)
                refresh()
            }
            SyncPanelAction.ResumeImport -> {
                runtime.preferences.importPaused.set(false)
                refresh()
                if (state.value.setupStep == SyncSetupStep.MERGING) {
                    discover()
                } else {
                    scope.launch { runtime.coordinator.synchronize(SyncTrigger.MANUAL) }
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
            SyncPanelAction.DismissNotice -> mutableState.update { it.copy(notice = null) }
            SyncPanelAction.BeginSetup -> beginSetup()
            SyncPanelAction.RetrySetup -> discover()
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
                    SyncPanelQuestion.DISCONNECT -> {
                        setupVersion++
                        setupJob?.cancelAndJoin()
                        setupJob = null
                        cancelAuthorization()
                        bulkJob?.cancelAndJoin()
                        runtime.disconnect()
                        refresh()
                    }
                    SyncPanelQuestion.SWITCH_SPACE -> beginSetup()
                    SyncPanelQuestion.ABANDON_LEGACY -> abandonLegacyPending()
                }
            }
        }
    }

    private suspend fun beginSetup() {
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

    private suspend fun cancelAuthorization() {
        authVersion++
        authJob?.cancelAndJoin()
        authJob = null
        repositoryJob?.cancelAndJoin()
        repositoryJob = null
        mutableState.update { it.copy(deviceCode = null, setupBusy = setupJob?.isActive == true) }
    }

    private suspend fun authorize() {
        setupVersion++
        setupJob?.cancelAndJoin()
        setupJob = null
        runtime.coordinator.cancelAndJoin()
        cancelAuthorization()
        val version = authVersion
        val previous = runtime.credentials.read()?.revision
        mutableState.update {
            it.copy(page = SyncPanelPage.SETUP, setupStep = SyncSetupStep.SIGN_IN, setupBusy = true, authFailure = null)
        }
        authJob = scope.launch {
            val result = runtime.authorization.authorize(SyncRuntime.CLIENT_ID) { code ->
                enqueue {
                    if (version == authVersion && state.value.visible) {
                        mutableState.update { it.copy(deviceCode = code) }
                    }
                }
            }
            enqueue {
                if (version != authVersion || !state.value.visible) return@enqueue
                when (result) {
                    is GitHubDeviceAuthResult.Authorized -> {
                        runtime.acceptAuthorization(previous, result.token)
                        mutableState.update {
                            it.copy(deviceCode = null, setupStep = SyncSetupStep.DISCOVERING, setupBusy = false)
                        }
                        discover()
                    }
                    is GitHubDeviceAuthResult.Failed -> mutableState.update {
                        it.copy(deviceCode = null, setupBusy = false, authFailure = result.failure.reason)
                    }
                }
            }
        }
    }

    private fun discover(autoSelect: Boolean = true) {
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
                when (val pending = runtime.onboarding.pendingForCurrentAccount()) {
                    is SyncPendingSetup.Current -> enqueue {
                        if (version == authVersion) {
                            legacyPending = null
                            mutableState.update { it.copy(legacyRecoveryAvailable = false) }
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

    private fun handleDiscovery(result: SyncSpaceDiscovery, autoSelect: Boolean = true) {
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
            runSetup { runtime.onboarding.join(space, SyncSpaceMaterial(space.descriptor, null)) }
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
            runSetup { runtime.onboarding.create(candidate, password) }
        } else {
            val space = chosenSpace ?: return
            runSetup {
                val material = SyncSpaceCrypto.unlock(space.descriptor, password).getOrElse {
                    throw IncorrectSyncPassword()
                }
                runtime.onboarding.join(space, material)
            }
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
        mutableState.update {
            it.copy(
                setupBusy = false,
                setupStep = SyncSetupStep.ERROR,
                setupProblem = (failure as? SyncSetupException)?.problem ?: SyncDiscoveryProblem.RETRYABLE,
                problem = failure.syncProblem(),
                setupAccountLogin = accountLogin,
                setupInstallation = installation,
            )
        }
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
