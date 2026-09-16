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
import mihon.data.sync.crypto.SyncRecoveryFactory
import mihon.data.sync.inbox.SyncBulkProgress
import mihon.domain.sync.SyncField
import mihon.domain.sync.auth.GitHubDeviceAuthResult
import mihon.domain.sync.crypto.SyncRecoveryCodec
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.transport.SyncInitializationResult
import tachiyomi.data.DatabaseHandler
import java.util.UUID
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
                            notice = if (completed && it.visible && activity.result?.status != SyncRunStatus.SKIPPED) {
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
                cancelAuthorization()
                cancelConfirmation()
                clearSelection()
                mutableState.update {
                    it.copy(visible = false, notice = null, question = null, recoveryText = "", deviceCode = null)
                }
            }
            SyncPanelAction.Back -> if (state.value.page == SyncPanelPage.MAIN) {
                handle(SyncPanelAction.Close)
            } else {
                if (state.value.page == SyncPanelPage.SETUP) cancelAuthorization()
                mutableState.update { it.copy(page = SyncPanelPage.MAIN, recoveryText = "") }
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
                scope.launch { runtime.coordinator.synchronize(SyncTrigger.MANUAL) }
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
            SyncPanelAction.Authorize -> authorize()
            SyncPanelAction.CancelAuthorization -> cancelAuthorization()
            SyncPanelAction.RefreshRepositories -> loadRepositories()
            is SyncPanelAction.ChooseRepository -> {
                if (state.value.repositories.none { it.repository == action.repository }) return
                val recovery = if (action.newSpace) {
                    SyncRecoveryCodec.encode(
                        SyncRecoveryFactory.generate(
                            UUID.randomUUID().toString(),
                            1,
                            UUID.randomUUID().toString(),
                            clock(),
                        ).data,
                    )
                } else {
                    ""
                }
                mutableState.update {
                    it.copy(
                        setupRepository = action.repository,
                        newSpace = action.newSpace,
                        setupStep = SyncSetupStep.RECOVERY,
                        recoveryText = recovery,
                        recoverySaved = false,
                        recoveryInvalid = false,
                        problem = null,
                    )
                }
            }
            is SyncPanelAction.SetRecovery -> if (!state.value.newSpace) {
                mutableState.update {
                    it.copy(
                        recoveryText = action.text.take(16 * 1024),
                        recoveryInvalid = action.text.length > 16 * 1024,
                    )
                }
            }
            is SyncPanelAction.RecoverySaved -> if (
                action.text.isNotEmpty() && action.text == state.value.recoveryText
            ) {
                mutableState.update { it.copy(recoverySaved = true) }
            }
            SyncPanelAction.PrepareMerge -> {
                val current = state.value
                if (current.newSpace && !current.recoverySaved) return
                if (SyncRecoveryCodec.decode(current.recoveryText).isFailure) {
                    mutableState.update { it.copy(recoveryInvalid = true) }
                } else {
                    mutableState.update { it.copy(setupStep = SyncSetupStep.MERGE, recoveryInvalid = false) }
                }
            }
            SyncPanelAction.ConfirmMerge -> connect()
            SyncPanelAction.ShowRecovery -> {
                val recovery = SyncRecoveryCodec.encode(runtime.recoveryData())
                mutableState.update { it.copy(page = SyncPanelPage.RECOVERY, recoveryText = recovery) }
            }
            is SyncPanelAction.Ask -> mutableState.update { it.copy(question = action.question) }
            SyncPanelAction.CancelQuestion -> mutableState.update { it.copy(question = null) }
            SyncPanelAction.ConfirmQuestion -> {
                val question = state.value.question ?: return
                mutableState.update { it.copy(question = null) }
                if (question == SyncPanelQuestion.DISCONNECT) {
                    cancelAuthorization()
                    bulkJob?.cancelAndJoin()
                    runtime.disconnect()
                    refresh()
                } else {
                    beginSetup()
                }
            }
        }
    }

    private suspend fun beginSetup() {
        cancelAuthorization()
        val authorized = runtime.credentials.read() != null
        mutableState.update {
            it.copy(
                page = SyncPanelPage.SETUP,
                setupStep = if (authorized) SyncSetupStep.REPOSITORY else SyncSetupStep.SIGN_IN,
                setupRepository = null,
                recoveryText = "",
                recoverySaved = false,
                recoveryInvalid = false,
                authFailure = null,
                problem = null,
            )
        }
        if (authorized) loadRepositories()
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
                        runtime.credentials.replace(previous, result.token)
                        mutableState.update {
                            it.copy(deviceCode = null, setupStep = SyncSetupStep.REPOSITORY, setupBusy = false)
                        }
                        loadRepositories()
                    }
                    is GitHubDeviceAuthResult.Failed -> mutableState.update {
                        it.copy(deviceCode = null, setupBusy = false, authFailure = result.failure.reason)
                    }
                }
            }
        }
    }

    private fun loadRepositories() {
        if (setupJob?.isActive == true || repositoryJob?.isActive == true) return
        val version = authVersion
        mutableState.update { it.copy(setupBusy = true, problem = null) }
        repositoryJob = scope.launch {
            try {
                val repositories = runtime.repositories.select("mihon-sync-v1")
                enqueue {
                    if (version == authVersion) {
                        mutableState.update { it.copy(repositories = repositories, setupBusy = false) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                enqueue {
                    if (version == authVersion) {
                        mutableState.update { it.copy(problem = failure.syncProblem(), setupBusy = false) }
                    }
                }
            }
        }
    }

    private fun connect() {
        if (setupJob?.isActive == true) return
        val current = state.value
        if (current.setupStep != SyncSetupStep.MERGE || (current.newSpace && !current.recoverySaved)) return
        val repository = current.setupRepository ?: return
        val recovery = SyncRecoveryCodec.decode(current.recoveryText).getOrNull() ?: return
        mutableState.update { it.copy(setupBusy = true, problem = null) }
        setupJob = scope.launch {
            try {
                if (current.newSpace) {
                    val secret = SyncRecoveryCodec.importSecret(
                        recovery,
                        recovery.spaceId,
                        recovery.generation,
                    ).getOrThrow()
                    val initialized = runtime.transport(
                        secret,
                    ).initialize(repository, recovery.spaceId, recovery.generation)
                    val initializedSpace = when (initialized) {
                        is SyncInitializationResult.Initialized -> initialized.spaceId
                        is SyncInitializationResult.Adopted -> initialized.spaceId
                        else -> null
                    }
                    if (initializedSpace != recovery.spaceId) {
                        enqueue {
                            mutableState.update {
                                it.copy(
                                    setupBusy = false,
                                    setupStep = SyncSetupStep.RECOVERY,
                                    newSpace = false,
                                    recoveryText = "",
                                    recoverySaved = false,
                                    problem = SyncRunProblem.REMOTE_CHANGED,
                                )
                            }
                        }
                        return@launch
                    }
                }
                runtime.connect(repository, recovery)
                enqueue {
                    mutableState.update {
                        it.copy(page = SyncPanelPage.MAIN, setupBusy = false, recoveryText = "", recoverySaved = false)
                    }
                    refresh()
                }
                runtime.coordinator.synchronize(SyncTrigger.MANUAL)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                enqueue { mutableState.update { it.copy(setupBusy = false, problem = failure.syncProblem()) } }
            }
        }
    }

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
