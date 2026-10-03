package mihon.data.sync.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mihon.data.sync.auth.GitHubAuthClient
import mihon.data.sync.auth.GitHubTokenRefresher
import mihon.data.sync.auth.PersistentGitHubCredentialStore
import mihon.data.sync.http.NoopSyncMetrics
import mihon.data.sync.http.SyncMetrics
import mihon.data.sync.inbox.SyncInboxProjector
import mihon.data.sync.journal.SyncBaselineStore
import mihon.data.sync.journal.SyncLocalIdentity
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.projection.SyncRemoteProjectionWriter
import mihon.data.sync.transport.SyncSnapshotManifestStore
import mihon.data.sync.transport.SyncSnapshotWriteOwner
import mihon.domain.sync.auth.GitHubAuthEndpoints
import mihon.domain.sync.auth.GitHubAuthException
import mihon.domain.sync.auth.GitHubAuthFailure
import mihon.domain.sync.auth.GitHubAuthFailureReason
import mihon.domain.sync.runtime.SyncCoordinator
import mihon.domain.sync.runtime.SyncPreferences
import mihon.domain.sync.runtime.SyncRunPort
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunResult
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import mihon.domain.sync.security.SyncSecureStore
import mihon.domain.sync.security.SyncSecureStoreException
import mihon.domain.sync.transport.SyncInitializationStage
import mihon.domain.sync.transport.SyncRepository
import okhttp3.OkHttpClient
import okio.Path
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.creator.repository.CreatorArchiveBootstrap
import tachiyomi.domain.creator.repository.CreatorLibraryIndexWriter
import tachiyomi.domain.creator.repository.CreatorRepository
import java.util.UUID

data class SyncConnection(
    val spaceId: String,
    val generation: Long,
    val repository: SyncRepository,
    val enabled: Boolean,
    val protectionMode: String? = null,
    val accountLogin: String? = null,
    // An enabled legacy binding blocks setup until explicitly disconnected; its local data remains stored.
    val unsupportedFormat: Boolean = false,
)

@Serializable
data class SyncRunRecord(val time: Long, val trigger: SyncTrigger, val result: SyncRunResult)

/** The same production application graph is used by platform scheduling and the synchronization UI. */
class SyncRuntime(
    private val handler: DatabaseHandler,
    bootstrap: CreatorArchiveBootstrap,
    creatorIndexWriter: CreatorLibraryIndexWriter,
    creatorRepository: CreatorRepository,
    sourceAvailable: (Long) -> Boolean,
    private val secureStore: SyncSecureStore,
    preferenceStore: PreferenceStore,
    private val productionClient: OkHttpClient,
    private val endpoints: GitHubAuthEndpoints = GitHubAuthEndpoints(),
    private val clock: () -> Long = System::currentTimeMillis,
    internal val persistentObjectCacheDirectory: Path? = null,
    internal val failureLogDirectory: Path? = null,
    private val syncMetrics: SyncMetrics = NoopSyncMetrics,
    private val progressTelemetryEnabled: Boolean = true,
    val diagnosticDirectory: Path? = null,
    diagnosticEnvironment: SyncDiagnosticEnvironment = SyncDiagnosticEnvironment(),
) : SyncRunPort {
    val preferences = SyncPreferences(preferenceStore)
    val credentials = PersistentGitHubCredentialStore(secureStore)
    val authorization = GitHubAuthClient(productionClient, endpoints, nowMillis = clock)
    val coordinator = SyncCoordinator(this)
    val runStore = SyncRunStore(handler, clock)
    val diagnostics = SyncDiagnostics(this, diagnosticDirectory, diagnosticEnvironment, clock)
    private val failureReportStore = failureLogDirectory?.let { SyncFailureReportStore(handler, it) }
    private val failureReportMutex = Mutex()
    private val failureReports = mutableMapOf<String, Pair<FailureReportVersion, SyncFailureLogStatus?>>()
    private val mutableLiveProgress = MutableStateFlow<SyncProgressFact?>(null)
    val liveProgress: StateFlow<SyncProgressFact?> = mutableLiveProgress

    @Volatile private var liveSession: SyncLiveProgressSession? = null
    internal fun progressFor(runId: String): SyncProgressFact? =
        liveSession?.takeIf { liveProgress.value?.scope?.startsWith("$runId:") == true }
            ?.let { runCatching { it.snapshot() }.getOrNull() }
    private val panelDelegate = lazy { SyncPanelController(this, handler, clock = clock) }
    val panel: SyncPanel get() = panelDelegate.value
    val baseline = SyncBaselineStore(handler, bootstrap)
    val projector =
        SyncInboxProjector(
            handler,
            SyncRemoteProjectionWriter(
                handler,
                creatorIndexWriter,
                creatorRepository,
                bootstrap,
                sourceAvailable,
                clock,
            ),
        )
    private val refresher = GitHubTokenRefresher(authorization, credentials, clock)
    private val connectionMutex = Mutex()
    private data class FailureReportVersion(val state: SyncRunState, val attemptId: Long, val updatedAt: Long)

    internal suspend fun failureLogFor(run: SyncRunSnapshot, force: Boolean = false): SyncFailureLogStatus? {
        val store = failureReportStore ?: return null
        if (run.state !in setOf(
                SyncRunState.SUCCEEDED,
                SyncRunState.PARTIAL,
                SyncRunState.FAILED,
                SyncRunState.BLOCKED,
                SyncRunState.CANCELLED,
            )
        ) {
            return null
        }
        val version = FailureReportVersion(run.state, run.attemptId, run.updatedAt)
        return failureReportMutex.withLock {
            val cached = failureReports[run.runId]
            val readyExists = (cached?.second as? SyncFailureLogStatus.Ready)?.let { ready ->
                runCatching { store.exists(ready.path) }.getOrDefault(false)
            } != false
            if (cached?.first == version && readyExists &&
                (!force || cached.second !is SyncFailureLogStatus.SaveFailed)
            ) {
                return@withLock cached.second
            }
            val report = try {
                store.generate(run)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SyncFailureLogStatus.SaveFailed(run.runId, 0)
            }
            failureReports[run.runId] = version to report
            report
        }
    }
    internal val onboarding =
        SyncOnboarding(
            this,
            productionClient,
            endpoints.apiBaseUrl,
            SyncSetupStorage(secureStore),
            SyncSnapshotManifestStore(handler),
        )

    internal fun accountHttpRequestGate(accountId: Long) = SyncAccountHttpRequestGate(runStore, accountId, clock)

    internal suspend fun acceptAuthorization(revision: Long?, token: mihon.domain.sync.auth.GitHubAccessToken) {
        coordinator.cancelAndJoin()
        connectionMutex.withLock {
            val connection = rawConnection()
            val stored = connection?.let { onboarding.storage.connection(it.spaceId, it.generation) }
            // A browser response cannot replace the original account's credential before identity verification.
            onboarding.session(stored?.accountId, token.accessToken)
            val credential = credentials.replace(revision, token)
            if (stored != null) {
                onboarding.storage.updateRecoveryObservation(stored) {
                    it.copy(
                        credentialRevision = credential.revision,
                        authorization = SyncRecoveryAuthorization.CONFIRMED,
                        authorizationConfirmedAtMillis = clock(),
                    )
                }
            }
        }
    }

    internal suspend fun recoveryObservation(): StoredSyncRecoveryObservation? {
        val connection = rawConnection() ?: return null
        if (!connection.enabled || connection.unsupportedFormat) return null
        val stored = onboarding.storage.connection(connection.spaceId, connection.generation) ?: return null
        val observation = onboarding.storage.recoveryObservation(stored) ?: return null
        if (observation.credentialRevision != credentials.read()?.revision) {
            return observation.copy(
                authorizationConfirmedAtMillis = null,
                authorization = SyncRecoveryAuthorization.IDLE,
            )
        }
        return observation.copy(
            authorization = when (observation.authorization) {
                SyncRecoveryAuthorization.CHECKING,
                SyncRecoveryAuthorization.WAITING,
                SyncRecoveryAuthorization.VERIFYING,
                -> SyncRecoveryAuthorization.CANCELLED
                else -> observation.authorization
            },
        )
    }

    internal suspend fun recordRecoveryAuthorization(
        status: SyncRecoveryAuthorization,
        clearConfirmation: Boolean = false,
    ) = connectionMutex.withLock {
        val connection = rawConnection() ?: return@withLock
        val stored = onboarding.storage.connection(connection.spaceId, connection.generation) ?: return@withLock
        val revision = credentials.read()?.revision
        onboarding.storage.updateRecoveryObservation(stored) {
            it.copy(
                credentialRevision = revision,
                authorization = status,
                authorizationConfirmedAtMillis = when {
                    clearConfirmation -> null
                    status == SyncRecoveryAuthorization.CONFIRMED -> clock()
                    it.credentialRevision != revision -> null
                    else -> it.authorizationConfirmedAtMillis
                },
            )
        }
    }

    internal suspend fun checkRecoveryAuthorization(): SyncRecoveryAuthorizationCheck {
        return try {
            connectionMutex.withLock {
                val connection = rawConnection()
                val stored = connection?.let { onboarding.storage.connection(it.spaceId, it.generation) }
                if (credentials.read() == null) return@withLock SyncRecoveryAuthorizationCheck(required = true)
                val session = onboarding.session(stored?.accountId)
                val credential = requireNotNull(credentials.read())
                require(credential.credential.accessToken == session.token) { "sync credential changed" }
                if (stored != null) {
                    onboarding.storage.updateRecoveryObservation(stored) {
                        it.copy(
                            credentialRevision = credential.revision,
                            authorization = SyncRecoveryAuthorization.CONFIRMED,
                            authorizationConfirmedAtMillis = clock(),
                        )
                    }
                }
                SyncRecoveryAuthorizationCheck(confirmed = true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val required = (failure is mihon.data.sync.http.SyncHttpException && failure.code == 401) ||
                (failure is GitHubAuthException && !failure.failure.retryable)
            SyncRecoveryAuthorizationCheck(required = required, problem = failure.syncProblem())
        }
    }

    internal suspend fun cancelRecoverySwitch() = connectionMutex.withLock {
        val intent = activeSwitch() ?: return@withLock
        require(intent.stage == SyncSpaceSwitchStage.PREPARING) { "sync switch already committed" }
        val active = requireNotNull(rawConnection())
        val current = requireNotNull(onboarding.storage.connection(active.spaceId, active.generation))
        require(current.snapshotManifestBinding().connectionRevision == intent.oldBindingRevision)
        onboarding.storage.saveSwitch(intent.copy(stage = SyncSpaceSwitchStage.CANCELLED), intent)
    }

    internal suspend fun bindSetup(setup: StoredSyncSetup) {
        coordinator.cancelAndJoin()
        connectionMutex.withLock {
            if (setup.switchIntentId != null) {
                activateSwitch(setup)
                return@withLock
            }
            val material = setup.material.material()
            val descriptor = material.descriptor
            val stored = onboarding.storage.connection(descriptor.spaceId, descriptor.generation)
            require(
                stored == null || (
                    stored.accountId == setup.accountId &&
                        stored.repositoryId == setup.repositoryId &&
                        stored.material.material().descriptor == setup.material.material().descriptor
                    ),
            ) {
                "sync binding cannot be replaced"
            }
            val current = handler.await {
                sync_journalQueries.getCurrentActor(descriptor.spaceId, descriptor.generation).executeAsOneOrNull()
            }
            val identity = when {
                current == null -> SyncLocalIdentity(UUID.randomUUID().toString(), 1)
                stored?.actorId == current.actor_id && stored.epoch == current.epoch ->
                    SyncLocalIdentity(current.actor_id, current.epoch)
                else -> SyncLocalJournal(handler).renewIdentity(descriptor.spaceId, descriptor.generation)
            }
            onboarding.storage.bind(
                StoredSyncConnection(
                    accountId = setup.accountId,
                    accountLogin = setup.accountLogin,
                    repositoryId = requireNotNull(setup.repositoryId),
                    owner = setup.owner,
                    repository = setup.repository,
                    branch = setup.branch,
                    material = setup.material,
                    actorId = identity.actorId,
                    epoch = identity.epoch,
                ),
                stored,
            )
            baseline.connectAndImport(
                descriptor.spaceId,
                descriptor.generation,
                setup.repository(),
                identity.actorId,
                identity.epoch,
            )
        }
    }

    internal suspend fun activeSwitch(): StoredSyncSpaceSwitch? {
        val connection = rawConnection() ?: return null
        if (!connection.enabled || connection.unsupportedFormat) return null
        val stored = onboarding.storage.connection(connection.spaceId, connection.generation) ?: return null
        return onboarding.storage.activeSwitch(stored.accountId)?.takeIf {
            it.stage in setOf(SyncSpaceSwitchStage.PREPARING, SyncSpaceSwitchStage.ACTIVATING)
        }
    }

    internal suspend fun beginSwitch(purpose: SyncSpaceSwitchPurpose): StoredSyncSpaceSwitch {
        coordinator.cancelAndJoin()
        return connectionMutex.withLock {
            recoverSwitchActivation()
            val connection = requireNotNull(rawConnection())
            val stored = requireNotNull(onboarding.storage.connection(connection.spaceId, connection.generation))
            onboarding.session(stored.accountId)
            val previous = onboarding.storage.activeSwitch(stored.accountId)
            if (previous?.stage in setOf(SyncSpaceSwitchStage.PREPARING, SyncSpaceSwitchStage.ACTIVATING)) {
                // A new explicit choice archives its own checkpoint; it never retargets an initialized attempt.
                require(previous?.targetConnection == null)
                verifySwitch(requireNotNull(previous))
                onboarding.storage.saveSwitch(previous.copy(stage = SyncSpaceSwitchStage.CANCELLED), previous)
            }
            StoredSyncSpaceSwitch(
                intentId = UUID.randomUUID().toString(),
                accountId = stored.accountId,
                oldConnection = stored,
                oldBindingRevision = stored.snapshotManifestBinding().connectionRevision,
                oldPending = onboarding.storage.pendingForConnection(stored),
                purpose = purpose,
                previousIntentId = previous?.intentId,
            ).also { onboarding.storage.startSwitch(it) }
        }
    }

    internal suspend fun verifySwitch(intent: StoredSyncSpaceSwitch) {
        require(onboarding.storage.activeSwitch(intent.accountId) == intent) { "sync switch changed" }
        onboarding.session(intent.accountId)
        val active = requireNotNull(rawConnection())
        val old = intent.oldConnection.material.material().descriptor
        require(active.spaceId == old.spaceId && active.generation == old.generation)
        val current = requireNotNull(onboarding.storage.connection(old.spaceId, old.generation))
        require(current.snapshotManifestBinding().connectionRevision == intent.oldBindingRevision)
    }

    internal suspend fun confirmSwitch(intent: StoredSyncSpaceSwitch): StoredSyncSetup = connectionMutex.withLock {
        verifySwitch(intent)
        require(intent.stage == SyncSpaceSwitchStage.PREPARING)
        val setup = requireNotNull(intent.target)
        require(onboarding.storage.setupFor(setup) == setup)
        onboarding.storage.saveSwitch(intent.copy(stage = SyncSpaceSwitchStage.ACTIVATING), intent)
        setup
    }

    /** No remote requests here: a verified activation can only finish its frozen local transaction. */
    private suspend fun recoverSwitchActivation() {
        recoverAddressUpdate()
        val active = rawConnection() ?: return
        if (!active.enabled || active.unsupportedFormat) return
        val stored = onboarding.storage.connection(active.spaceId, active.generation) ?: return
        val intent = onboarding.storage.activeSwitch(stored.accountId) ?: return
        if (intent.stage == SyncSpaceSwitchStage.ACTIVATING && intent.targetConnection != null) {
            finishSwitchActivation(intent)
        }
    }

    private suspend fun recoverAddressUpdate() {
        val active = handler.await { sync_journalQueries.getActiveSpace().executeAsOneOrNull() } ?: return
        val update = onboarding.storage.addressUpdate(active.space_id, active.generation) ?: return
        if (update.complete) return
        val before = update.before
        val after = update.after
        require(
            before.accountId == after.accountId && before.repositoryId == after.repositoryId &&
                before.material == after.material && before.actorId == after.actorId && before.epoch == after.epoch,
        )
        val stored = onboarding.storage.connection(active.space_id, active.generation)
        require(stored == before || stored == after)
        require(active.repository_branch == before.branch && after.branch == before.branch)
        require(
            (active.repository_owner == before.owner && active.repository_name == before.repository) ||
                (active.repository_owner == after.owner && active.repository_name == after.repository),
        )
        if (stored != after) onboarding.storage.bind(after, before)
        handler.await(inTransaction = true) {
            sync_journalQueries.updateRepositoryAddress(
                after.owner,
                after.repository,
                active.space_id,
                active.generation,
                before.owner,
                before.repository,
                before.branch,
            )
            val changed = sync_journalQueries.getActiveSpace().executeAsOne()
            require(changed.repository_owner == after.owner && changed.repository_name == after.repository)
        }
        update.pendingBefore?.let { pending ->
            val current = requireNotNull(onboarding.storage.setupFor(pending))
            val next = pending.copy(owner = after.owner, repository = after.repository)
            require(current == pending || current == next)
            if (current != next) onboarding.storage.save(next, pending)
        }
        onboarding.storage.saveAddressUpdate(update.copy(complete = true), update)
    }

    private suspend fun activateSwitch(setup: StoredSyncSetup) {
        var intent = requireNotNull(onboarding.storage.activeSwitch(setup.accountId))
        require(intent.intentId == setup.switchIntentId && intent.stage == SyncSpaceSwitchStage.ACTIVATING)
        val frozen = requireNotNull(intent.target)
        require(
            frozen.copy(
                stage = setup.stage,
                confirmedBootstrapCommitSha = setup.confirmedBootstrapCommitSha,
                confirmedBootstrapTreeSha = setup.confirmedBootstrapTreeSha,
            ) == setup,
        ) { "sync target changed" }
        if (intent.targetConnection == null) {
            verifySwitch(intent)
            val descriptor = setup.material.material().descriptor
            val existing = onboarding.storage.connection(descriptor.spaceId, descriptor.generation)
            require(
                existing == null || (
                    existing.accountId == setup.accountId &&
                        existing.repositoryId == setup.repositoryId && existing.material == setup.material &&
                        existing.repository() == setup.repository()
                    ),
            )
            val actor = handler.await {
                sync_journalQueries.getCurrentActor(descriptor.spaceId, descriptor.generation).executeAsOneOrNull()
            }
            require(actor == null || (actor.actor_id == existing?.actorId && actor.epoch == existing.epoch))
            val target = StoredSyncConnection(
                accountId = setup.accountId, accountLogin = setup.accountLogin,
                repositoryId = setup.repositoryId, owner = setup.owner, repository = setup.repository,
                branch = setup.branch, material = setup.material,
                actorId = actor?.actor_id ?: UUID.randomUUID().toString(), epoch = actor?.epoch ?: 1,
                switchIntentId = intent.intentId,
            )
            val next = intent.copy(targetConnection = target)
            onboarding.storage.saveSwitch(next, intent)
            intent = next
        }
        finishSwitchActivation(intent)
    }

    private suspend fun finishSwitchActivation(intent: StoredSyncSpaceSwitch) {
        val target = requireNotNull(intent.targetConnection)
        val setup = requireNotNull(intent.target)
        val old = intent.oldConnection.material.material().descriptor
        val descriptor = target.material.material().descriptor
        val active = requireNotNull(rawConnection())
        require(
            (active.spaceId == old.spaceId && active.generation == old.generation) ||
                (active.spaceId == descriptor.spaceId && active.generation == descriptor.generation),
        )
        val currentOld = requireNotNull(onboarding.storage.connection(old.spaceId, old.generation))
        require(currentOld.snapshotManifestBinding().connectionRevision == intent.oldBindingRevision)
        val existing = onboarding.storage.connection(descriptor.spaceId, descriptor.generation)
        if (existing != target) {
            require(
                existing == null || (
                    existing.accountId == target.accountId &&
                        existing.repositoryId == target.repositoryId && existing.material == target.material &&
                        existing.repository() == target.repository()
                    ),
            )
            onboarding.storage.bind(target, existing)
        }
        baseline.switchAndImport(
            intent.intentId,
            old.spaceId,
            old.generation,
            descriptor.spaceId,
            descriptor.generation,
            target.repository(),
            target.actorId,
            target.epoch,
        )
        val checkpoint = requireNotNull(onboarding.storage.setupFor(setup))
        if (checkpoint.stage != SyncInitializationStage.CONNECTED) {
            onboarding.storage.save(
                checkpoint.copy(stage = SyncInitializationStage.CONNECTED),
                checkpoint,
            )
        }
        val completed = intent.copy(stage = SyncSpaceSwitchStage.COMPLETE)
        try {
            onboarding.storage.saveSwitch(completed, intent)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (onboarding.storage.switchIntent(intent.intentId) != completed) throw failure
        }
    }

    init {
        if (preferences.scheduleAnchor.get() == 0L) preferences.scheduleAnchor.set(clock())
    }

    suspend fun stopPanel() {
        if (panelDelegate.isInitialized()) panelDelegate.value.stop()
    }

    private suspend fun isResumable(run: SyncRunSnapshot): Boolean = run.state in RESUMABLE_STATES ||
        (run.state == SyncRunState.PARTIAL && runStore.hasPlannedWork(run.runId))

    /** Resumes only an accepted system-interrupted run; a user pause remains paused. */
    suspend fun hasResumableRun(): Boolean {
        val connection = connection() ?: return false
        if (recoveryBlocked(connection)) return false
        return runStore.active(connection.spaceId, connection.generation)?.let {
            isResumable(it)
        } == true
    }

    /** Returns whether an accepted automatic run is allowed to make its next attempt now. */
    suspend fun isRecoveryDue(): Boolean {
        val connection = connection() ?: return false
        if (recoveryBlocked(connection)) return false
        return runStore.active(connection.spaceId, connection.generation)?.let {
            isResumable(it) && maxOf(it.nextRetryAt, scheduledAccountHttpNotBefore(connection)) <= clock()
        } == true
    }

    /** Delay used when the platform schedules the durable recovery wake-up. */
    suspend fun recoveryDelayMillis(): Long {
        val connection = connection() ?: return 0L
        if (recoveryBlocked(connection)) return 0L
        return runStore.active(connection.spaceId, connection.generation)?.let {
            if (isResumable(it)) {
                (maxOf(it.nextRetryAt, scheduledAccountHttpNotBefore(connection)) - clock()).coerceAtLeast(0L)
            } else {
                0L
            }
        } ?: 0L
    }

    private suspend fun accountHttpNotBefore(connection: SyncConnection): Long =
        try {
            onboarding.storage.connection(connection.spaceId, connection.generation)
                ?.let { runStore.accountHttpNotBefore(it.accountId) }
                ?: 0L
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw SyncSecureStoreException()
        }

    private suspend fun scheduledAccountHttpNotBefore(connection: SyncConnection): Long =
        try {
            accountHttpNotBefore(connection)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            0L
        }

    /** Resumes only an accepted system-interrupted run; a user pause remains paused. */
    suspend fun resumeIfNeeded(): Boolean {
        val connection = connection() ?: return false
        if (recoveryBlocked(connection)) return false
        if (runStore.active(connection.spaceId, connection.generation) == null) {
            completePendingSetupIfSettled()
        }
        val run = runStore.active(connection.spaceId, connection.generation) ?: return false
        if (!isResumable(run)) return false
        if (maxOf(run.nextRetryAt, scheduledAccountHttpNotBefore(connection)) > clock()) return true
        if (!coordinator.activity.value.running && run.state in setOf(
                SyncRunState.RUNNING,
                SyncRunState.WAITING_NETWORK,
                SyncRunState.WAITING_RETRY,
            )
        ) {
            runStore.releaseForRecovery(run.runId)
        }
        coordinator.synchronize(SyncTrigger.RECOVERY)
        return true
    }

    suspend fun cancelSync() {
        connection()?.let { runStore.active(it.spaceId, it.generation)?.let { runStore.cancel(it.runId) } }
        coordinator.cancelAndJoin()
    }

    suspend fun pauseSync() {
        val run = connection()?.let { runStore.active(it.spaceId, it.generation) }
        if (run != null) liveSession?.hold(SyncProgressHold.PAUSING)
        coordinator.cancelAndJoin()
        if (run != null && runStore.get(run.runId)?.state in RESUMABLE_STATES + SyncRunState.RUNNING) {
            runStore.pause(run.runId)
            liveSession?.hold(SyncProgressHold.PAUSED)
        }
    }

    suspend fun accessToken(): String {
        if (credentials.read() == null) {
            throw GitHubAuthException(
                GitHubAuthFailure(GitHubAuthFailureReason.REVOKED, "GitHub authorization required", false),
            )
        }
        return refresher.refreshIfNeeded(CLIENT_ID).getOrElse {
            if (it is CancellationException) throw it
            if (it is GitHubAuthException) throw it
            throw SyncSecureStoreException()
        }.credential.accessToken
    }

    suspend fun connection(): SyncConnection? = connectionFacts().projection

    private suspend fun rawConnection(): SyncConnection? = rawConnectionFacts().projection

    /** Shares the production raw lookup and decoder without reading authorization credentials. */
    internal suspend fun connectionFacts(): SyncConnectionFacts {
        recoverLocalConnectionIfIdle()
        return rawConnectionFacts()
    }

    private suspend fun recoverLocalConnectionIfIdle() {
        if (!connectionMutex.tryLock()) return
        try {
            recoverSwitchActivation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the old local projection readable. Recovery checks still fail closed and report storage trouble.
        } finally {
            connectionMutex.unlock()
        }
    }

    /** Pure lookup for diagnostics: capture the active identity before decoding its sealed binding. */
    internal suspend fun rawConnectionFacts(): SyncConnectionFacts {
        val active = handler.await { sync_journalQueries.getActiveSpace().executeAsOneOrNull() }
            ?: return SyncConnectionFacts(null, null, SyncBindingDecode.MISSING)
        var decode = SyncBindingDecode.MISSING
        val stored = try {
            onboarding.storage.connection(active.space_id, active.generation)?.also { decode = SyncBindingDecode.OK }
        } catch (_: UnsupportedSyncSpace) {
            decode = SyncBindingDecode.UNSUPPORTED
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            decode = SyncBindingDecode.READ_FAILED
            null
        }
        val unsupported = decode == SyncBindingDecode.UNSUPPORTED
        val projection = SyncConnection(
            active.space_id,
            active.generation,
            SyncRepository(active.repository_owner, active.repository_name, active.repository_branch),
            active.exchange_enabled && !unsupported,
            stored?.material?.material()?.descriptor?.mode,
            stored?.accountLogin,
            unsupportedFormat = unsupported && active.exchange_enabled,
        )
        return SyncConnectionFacts(projection, active.exchange_enabled, decode)
    }

    internal suspend fun diagnosticPending(connection: SyncConnection): Pair<Long, Long> = handler.await {
        sync_inboxQueries.countPendingDecisions(connection.spaceId, connection.generation).executeAsOne() to
            sync_importQueries.countPendingImports(connection.spaceId, connection.generation).executeAsOne()
    }

    /** Reads local durable facts only; network authorization is rechecked when work resumes. */
    internal suspend fun pendingSetup(connection: SyncConnection): StoredSyncSetup? {
        if (!connection.enabled || connection.unsupportedFormat) return null
        val stored = onboarding.storage.connection(connection.spaceId, connection.generation) ?: return null
        if (stored.repository() != connection.repository) return null
        return onboarding.storage.pendingForConnection(stored)?.takeIf {
            // A crash can follow the DB commit but precede the final connected-marker CAS.
            it.repositoryId == stored.repositoryId && it.repository() == stored.repository() &&
                it.material == stored.material
        }
    }

    /** Completes a connected setup after its durable import queue has drained. */
    internal suspend fun completePendingSetupIfSettled(): Boolean {
        val connection = connection() ?: return false
        return completePendingSetupIfSettled(connection)
    }

    private suspend fun completePendingSetupIfSettled(connection: SyncConnection): Boolean {
        val setup = pendingSetup(connection) ?: return false
        val latest = runStore.latest(connection.spaceId, connection.generation)
        if (latest?.state != SyncRunState.SUCCEEDED) return false
        val remaining = handler.await {
            sync_importQueries.countPendingImports(connection.spaceId, connection.generation).executeAsOne()
        }
        if (remaining != 0L) return false
        onboarding.complete(setup)
        return true
    }

    suspend fun disconnect() {
        coordinator.cancelAndJoin()
        liveSession = null
        mutableLiveProgress.value = null
        connectionMutex.withLock {
            rawConnection()?.let {
                runStore.active(it.spaceId, it.generation)?.let { run -> runStore.cancel(run.runId) }
                SyncLocalJournal(handler).disconnect(it.spaceId, it.generation)
            }
            credentials.clear()
        }
    }

    /** A persisted decision belongs to the exact sealed binding revision, never merely a repository name. */
    suspend fun spaceRecovery(): SyncSpaceRecovery? {
        recoverLocalConnectionIfIdle()
        return rawConnection()?.let { recoveryFor(it) }
    }

    private suspend fun recoveryFor(connection: SyncConnection): SyncSpaceRecovery? {
        if (!connection.enabled || connection.unsupportedFormat) return null
        val stored = onboarding.storage.connection(connection.spaceId, connection.generation) ?: return null
        onboarding.storage.addressUpdate(connection.spaceId, connection.generation)?.let {
            if (!it.complete) throw SyncSecureStoreException()
        }
        val switching = onboarding.storage.activeSwitch(stored.accountId)
        val recovery = if (switching?.stage in setOf(SyncSpaceSwitchStage.PREPARING, SyncSpaceSwitchStage.ACTIVATING)) {
            SyncSpaceRecovery(SyncSpaceRecoveryReason.SWITCH_PENDING)
        } else {
            onboarding.storage.recovery(stored)
        } ?: return null
        val observation = recoveryObservation() ?: return recovery
        return recovery.copy(
            lastCheckedAtMillis = observation.lastCheckedAtMillis,
            lastCheckProblem = observation.lastCheckProblem,
            lastCheckReason = observation.lastCheckReason,
            lastCheckSucceeded = observation.lastCheckSucceeded,
            authorizationConfirmedAtMillis = observation.authorizationConfirmedAtMillis,
        )
    }

    private suspend fun recoveryBlocked(connection: SyncConnection): Boolean = try {
        recoveryFor(connection) != null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A corrupt/future secure decision must not become permission to start new work.
        true
    }

    /** Explicit recovery is read-only: authenticate, inspect the current App grant, then validate the fixed space. */
    suspend fun recheckSpace(): SyncSpaceRecoveryCheck {
        coordinator.cancelAndJoin()
        return connectionMutex.withLock {
            recoverSwitchActivation()
            val connection = rawConnection() ?: return@withLock SyncSpaceRecoveryCheck()
            val stored = onboarding.storage.connection(connection.spaceId, connection.generation)
                ?: throw SyncSecureStoreException()
            try {
                onboarding.storage.recovery(stored)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock SyncSpaceRecoveryCheck(problem = SyncRunProblem.STORAGE)
            }
            val switching = onboarding.storage.activeSwitch(stored.accountId)
            if (switching?.stage == SyncSpaceSwitchStage.ACTIVATING) {
                return@withLock SyncSpaceRecoveryCheck(recoveryFor(connection), SyncRunProblem.STORAGE)
            }
            val checked = checkSpace(connection, stored, allowRename = true)
            if (checked.problem == null) {
                val current = requireNotNull(onboarding.storage.connection(connection.spaceId, connection.generation))
                onboarding.storage.setRecovery(current, checked.recovery?.reason)
            }
            val current = requireNotNull(onboarding.storage.connection(connection.spaceId, connection.generation))
            val credentialRevision = credentials.read()?.revision
            val authorizationConfirmed = checked.authorizationConfirmed &&
                checked.authorizationCredentialRevision != null &&
                checked.authorizationCredentialRevision == credentialRevision
            try {
                onboarding.storage.updateRecoveryObservation(current) {
                    val authorizationRequired =
                        checked.recovery?.reason == SyncSpaceRecoveryReason.AUTHORIZATION_REQUIRED
                    it.copy(
                        lastCheckedAtMillis = clock(),
                        lastCheckSucceeded = checked.problem == null,
                        lastCheckProblem = checked.problem,
                        lastCheckReason = if (checked.problem == null) checked.recovery?.reason else it.lastCheckReason,
                        credentialRevision = credentialRevision,
                        authorizationConfirmedAtMillis = when {
                            authorizationRequired -> null
                            authorizationConfirmed -> clock()
                            it.credentialRevision != credentialRevision -> null
                            else -> it.authorizationConfirmedAtMillis
                        },
                        authorization = when {
                            authorizationRequired -> SyncRecoveryAuthorization.IDLE
                            authorizationConfirmed -> SyncRecoveryAuthorization.CONFIRMED
                            it.credentialRevision != credentialRevision -> SyncRecoveryAuthorization.IDLE
                            else -> it.authorization
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock SyncSpaceRecoveryCheck(problem = SyncRunProblem.STORAGE)
            }
            val recovery = try {
                recoveryFor(requireNotNull(rawConnection()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock SyncSpaceRecoveryCheck(problem = SyncRunProblem.STORAGE)
            }
            SyncSpaceRecoveryCheck(recovery, checked.problem, checked.spaceAddressUpdated, authorizationConfirmed)
        }
    }

    private suspend fun checkSpace(
        connection: SyncConnection,
        stored: StoredSyncConnection,
        allowRename: Boolean = false,
    ): SyncSpaceRecoveryCheck {
        var authorizationConfirmed = false
        var authorizationCredentialRevision: Long? = null
        return try {
            val session = onboarding.session(stored.accountId)
            authorizationCredentialRevision =
                credentials.read()?.takeIf { it.credential.accessToken == session.token }?.revision
            authorizationConfirmed = authorizationCredentialRevision != null
            val inventory = session.http.authorizedRepositoryObjects(stored.accountId)
            val matches = inventory.filter { it["id"]?.jsonPrimitive?.longOrNull == stored.repositoryId }
            require(matches.size <= 1)
            val repository = matches.singleOrNull()
                ?: return SyncSpaceRecoveryCheck(
                    SyncSpaceRecovery(SyncSpaceRecoveryReason.SPACE_UNAVAILABLE),
                    authorizationConfirmed = true,
                    authorizationCredentialRevision = authorizationCredentialRevision,
                )
            if (allowRename) {
                val name = repository["full_name"]?.jsonPrimitive?.content?.split('/')
                if (name != null && name.size == 2) {
                    val candidate = SyncRepository(name[0], name[1], connection.repository.branch)
                    if (candidate != connection.repository) {
                        // Repository identity and full authenticated content are checked before either local write.
                        onboarding.verifyRepository(session, candidate, stored.repositoryId)
                        val material = stored.material.material()
                        onboarding.transport(
                            session.token,
                            material,
                            repositoryId = stored.repositoryId,
                            requestGate = accountHttpRequestGate(stored.accountId),
                        ).readSnapshot(
                            candidate,
                            material.descriptor.spaceId,
                            material.descriptor.generation,
                        ).getOrThrow()
                        require(candidate.owner.equals(session.account.login, ignoreCase = true))
                        val pending = onboarding.storage.pendingForConnection(stored)
                        require(pending == null || pending.stage == SyncInitializationStage.CONNECTED)
                        val update = StoredSyncAddressUpdate(
                            before = stored,
                            after = stored.copy(owner = candidate.owner, repository = candidate.name),
                            pendingBefore = pending,
                        )
                        val previous = onboarding.storage.addressUpdate(connection.spaceId, connection.generation)
                        require(previous == null || previous.complete)
                        onboarding.storage.saveAddressUpdate(update, previous)
                        recoverAddressUpdate()
                        return SyncSpaceRecoveryCheck(
                            spaceAddressUpdated = true,
                            authorizationConfirmed = authorizationConfirmed,
                            authorizationCredentialRevision = authorizationCredentialRevision,
                        )
                    }
                }
            }
            onboarding.verifyRepository(session, connection.repository, stored.repositoryId)
            onboarding.transport(
                session.token,
                stored.material.material(),
                repositoryId = stored.repositoryId,
                requestGate = accountHttpRequestGate(stored.accountId),
            ).readSnapshot(connection.repository, connection.spaceId, connection.generation).getOrThrow()
            SyncSpaceRecoveryCheck(
                authorizationConfirmed = authorizationConfirmed,
                authorizationCredentialRevision = authorizationCredentialRevision,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val reason = when (failure) {
                is mihon.data.sync.http.SyncRequiredResourceUnavailable -> when (failure.resource) {
                    mihon.data.sync.http.SyncRequiredResource.REPOSITORY -> SyncSpaceRecoveryReason.SPACE_UNAVAILABLE
                    mihon.data.sync.http.SyncRequiredResource.SPACE_DATA -> SyncSpaceRecoveryReason.SPACE_DATA_INVALID
                }
                is mihon.data.sync.http.SyncHttpException -> when {
                    failure.code == 401 -> SyncSpaceRecoveryReason.AUTHORIZATION_REQUIRED
                    failure.code == 403 && !failure.retryable &&
                        failure.failureClass != mihon.data.sync.http.SyncHttpFailureClass.RATE_LIMITED ->
                        SyncSpaceRecoveryReason.SPACE_UNAVAILABLE
                    else -> null
                }
                is mihon.data.sync.transport.SyncRemoteDataInvalid,
                is mihon.domain.sync.crypto.SyncCryptoException,
                -> SyncSpaceRecoveryReason.SPACE_DATA_INVALID
                is GitHubAuthException -> if (failure.failure.retryable) {
                    null
                } else {
                    SyncSpaceRecoveryReason.AUTHORIZATION_REQUIRED
                }
                else -> null
            }
            if (reason != null) {
                SyncSpaceRecoveryCheck(
                    SyncSpaceRecovery(reason),
                    authorizationConfirmed =
                    authorizationConfirmed && reason != SyncSpaceRecoveryReason.AUTHORIZATION_REQUIRED,
                    authorizationCredentialRevision = authorizationCredentialRevision,
                )
            } else {
                SyncSpaceRecoveryCheck(
                    problem = failure.syncProblem(),
                    authorizationConfirmed = authorizationConfirmed,
                    authorizationCredentialRevision = authorizationCredentialRevision,
                )
            }
        }
    }

    override suspend fun exchange(trigger: SyncTrigger): SyncRunResult = connectionMutex.withLock {
        try {
            recoverSwitchActivation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Preserve an unavailable binding's storage feedback while unknown intents remain a closed gate.
            val problem = if (rawConnectionFacts().decode == SyncBindingDecode.READ_FAILED) {
                SyncRunProblem.STORAGE
            } else {
                null
            }
            return@withLock SyncRunResult(
                if (problem == null) SyncRunStatus.SKIPPED else SyncRunStatus.FAILED,
                problem = problem,
            )
        }
        val connection = rawConnection()?.takeIf { it.enabled || it.unsupportedFormat }
            ?: return@withLock SyncRunResult(SyncRunStatus.SKIPPED)
        if (recoveryBlocked(connection)) return@withLock SyncRunResult(SyncRunStatus.SKIPPED)
        val active = runStore.active(connection.spaceId, connection.generation)
        if (trigger == SyncTrigger.STARTUP && active != null) {
            // Startup is a recovery probe. It must never create a second run over a
            // paused, blocked, or still owned run; the scheduler will resume an
            // eligible run separately.
            return@withLock SyncRunResult(SyncRunStatus.SKIPPED)
        }
        if (trigger == SyncTrigger.STARTUP && active == null &&
            runStore.latest(connection.spaceId, connection.generation)?.let {
                it.state == SyncRunState.FAILED && it.stopReason == "retry_exhausted"
            } == true
        ) {
            return@withLock SyncRunResult(SyncRunStatus.SKIPPED)
        }
        if (trigger == SyncTrigger.RECOVERY && (active == null || !isResumable(active))) {
            return@withLock SyncRunResult(SyncRunStatus.SKIPPED)
        }
        if (trigger in setOf(SyncTrigger.RECOVERY, SyncTrigger.PERIODIC) &&
            active?.state in setOf(SyncRunState.PAUSED_USER, SyncRunState.BLOCKED)
        ) {
            return@withLock SyncRunResult(SyncRunStatus.SKIPPED)
        }
        if (trigger in setOf(SyncTrigger.RECOVERY, SyncTrigger.PERIODIC) &&
            active?.nextRetryAt?.takeIf { it > 0 }?.let { it > clock() } == true
        ) {
            return@withLock SyncRunResult(SyncRunStatus.SKIPPED)
        }
        if (trigger in setOf(SyncTrigger.RECOVERY, SyncTrigger.PERIODIC) && active == null &&
            runStore.latest(connection.spaceId, connection.generation)?.let {
                it.state == SyncRunState.FAILED && it.stopReason == "retry_exhausted"
            } == true
        ) {
            return@withLock SyncRunResult(SyncRunStatus.SKIPPED)
        }
        if (trigger == SyncTrigger.MANUAL && active?.state == SyncRunState.BLOCKED) {
            runStore.finish(active.runId, SyncRunState.CANCELLED, "superseded_by_manual_retry")
        }
        val existing = active?.takeIf {
            it.state == SyncRunState.QUEUED ||
                (trigger in setOf(SyncTrigger.RECOVERY, SyncTrigger.PERIODIC) && isResumable(it)) ||
                (
                    trigger in setOf(SyncTrigger.MANUAL, SyncTrigger.PERIODIC) &&
                        it.state == SyncRunState.PARTIAL
                    ) ||
                (trigger == SyncTrigger.MANUAL && it.state == SyncRunState.RUNNING)
        }
        val run = existing ?: runStore.start(connection.spaceId, connection.generation, trigger)
        val accountNotBefore = try {
            if (connection.unsupportedFormat) 0L else accountHttpNotBefore(connection)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val problem = failure.syncProblem()
            runStore.finish(run.runId, SyncRunState.BLOCKED, problem.name)
            return@withLock SyncRunResult(SyncRunStatus.FAILED, problem = problem)
        }
        if (accountNotBefore > clock() && runStore.deferUntilAccountHttpGate(run.runId, accountNotBefore)) {
            return@withLock SyncRunResult(
                SyncRunStatus.FAILED,
                problem = SyncRunProblem.NETWORK,
                retryAfterMillis = accountNotBefore - clock(),
            )
        }
        val ownerSession = UUID.randomUUID().toString()
        val automatic = trigger in setOf(SyncTrigger.RECOVERY, SyncTrigger.PERIODIC)
        val exhausted = automatic && run.networkFailureCount >= MAX_NETWORK_FAILURES
        val attemptId = if (exhausted) run.attemptId else run.attemptId + 1
        if (!runStore.claim(run.runId, ownerSession, attemptId)) {
            return@withLock SyncRunResult(SyncRunStatus.SKIPPED)
        }
        if (exhausted) {
            runStore.finish(run.runId, SyncRunState.FAILED, "retry_exhausted", ownerSession)
            return@withLock SyncRunResult(SyncRunStatus.FAILED, problem = SyncRunProblem.NETWORK)
        }
        val progressSession = if (progressTelemetryEnabled) {
            lateinit var current: SyncLiveProgressSession
            current = SyncLiveProgressSession(
                run.runId,
                mutableLiveProgress,
                canPublish = { liveSession === current },
                confirmedBaseline = run.confirmedItems.takeUnless {
                    existing != null && run.confirmedItems == 0L && run.downloaded > 0L
                },
            )
            liveSession = current
            runCatching { current.activate() }
            current
        } else {
            liveSession = null
            mutableLiveProgress.value = null
            null
        }
        if (existing != null && existing.state != SyncRunState.QUEUED) {
            progressSession?.hold(SyncProgressHold.RECOVERING)
        }
        val resumeProgress = runStore.get(run.runId)
        runStore.progress(
            run.runId,
            SyncRunPhase.CHECKING,
            run.processed,
            run.total,
            run.completed,
            run.skipped,
            run.failed,
            attemptId = attemptId,
            ownerSession = ownerSession,
        )
        var result = try {
            preferences.lastAttempt.set(clock())
            val stored = onboarding.storage.connection(connection.spaceId, connection.generation)
                ?: throw SyncSecureStoreException()
            require(stored.repository() == connection.repository) { "sync repository binding changed" }
            val session = onboarding.session(stored.accountId)
            onboarding.verifyRepository(session, connection.repository, stored.repositoryId)
            val actor = handler.await {
                sync_journalQueries.getCurrentActor(connection.spaceId, connection.generation).executeAsOne()
            }
            if (stored.actorId != actor.actor_id || stored.epoch != actor.epoch) throw SyncSecureStoreException()
            val material = stored.material.material()
            SyncDatabaseExchange(
                handler,
                baseline,
                projector,
                onboarding.transport(
                    session.token,
                    material,
                    repositoryId = stored.repositoryId,
                    manifestStore = SyncSnapshotManifestStore(handler),
                    manifestBinding = stored.snapshotManifestBinding(),
                    persistentObjectCacheDirectory = persistentObjectCacheDirectory,
                    requestGate = accountHttpRequestGate(stored.accountId),
                    bodyObserver = progressSession,
                ),
                spaceMaterial = material,
                allowImport = { !preferences.importPaused.get() },
                progress = runStore.reporter(run.runId, ownerSession),
                initialUploaded = resumeProgress?.uploaded ?: 0,
                initialDownloaded = resumeProgress?.downloaded ?: 0,
                uploadedBaseline = resumeProgress?.uploadedBaseline ?: 0,
                downloadedBaseline = resumeProgress?.downloadedBaseline ?: 0,
                reconcileTotalsOnStart = existing != null && existing.state != SyncRunState.QUEUED,
                metrics = syncMetrics,
                snapshotOwner = SyncSnapshotWriteOwner(run.runId, ownerSession, attemptId),
                liveProgress = progressSession,
            )
                .exchange(connection.spaceId, connection.generation, connection.repository)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val current = runStore.get(run.runId)
                if (current?.state !in setOf(SyncRunState.PAUSED_USER, SyncRunState.CANCELLED)) {
                    runStore.finish(run.runId, SyncRunState.WAITING_SYSTEM, "cancelled", ownerSession)
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            SyncRunResult(SyncRunStatus.FAILED, problem = failure.syncProblem())
        }
        if (!connection.unsupportedFormat && result.problem in setOf(
                SyncRunProblem.SPACE_UNAVAILABLE,
                SyncRunProblem.AUTHORIZATION,
                SyncRunProblem.INVALID_DATA,
            )
        ) {
            val stored = onboarding.storage.connection(connection.spaceId, connection.generation)
                ?: throw SyncSecureStoreException()
            val checked = checkSpace(connection, stored)
            if (checked.recovery != null) {
                onboarding.storage.setRecovery(stored, checked.recovery.reason)
                result = result.copy(
                    problem = when (checked.recovery.reason) {
                        SyncSpaceRecoveryReason.AUTHORIZATION_REQUIRED -> SyncRunProblem.AUTHORIZATION
                        SyncSpaceRecoveryReason.SPACE_DATA_INVALID -> SyncRunProblem.INVALID_DATA
                        else -> SyncRunProblem.SPACE_UNAVAILABLE
                    },
                )
            } else if (result.problem == SyncRunProblem.SPACE_UNAVAILABLE) {
                // A missing-resource response alone is insufficient to create a durable block.
                result = result.copy(problem = checked.problem ?: SyncRunProblem.NETWORK)
            }
        }
        val networkFailureCount = if (result.problem == SyncRunProblem.NETWORK && !exhausted) {
            runStore.recordNetworkFailure(run.runId, ownerSession)
        } else {
            runStore.get(run.runId)?.networkFailureCount ?: run.networkFailureCount
        }
        val retryAvailable = result.problem == SyncRunProblem.NETWORK &&
            networkFailureCount < MAX_NETWORK_FAILURES
        if (result.status == SyncRunStatus.SUCCESS) {
            val completedSnapshot = runStore.get(run.runId)
            val completedItems = (completedSnapshot?.uploaded ?: result.uploaded.toLong()) +
                (completedSnapshot?.downloaded ?: result.downloaded.toLong())
            if (completedItems == 0L) {
                progressSession?.begin(
                    progressSession.scope("empty"),
                    SyncProgressStage.CONFIRMING,
                    SyncProgressDirection.UPLOAD,
                    totalItems = 0,
                )
            }
            runStore.progress(
                run.runId,
                SyncRunPhase.COMPLETE,
                completedItems,
                completedItems,
                completed = completedItems,
                ownerSession = ownerSession,
            )
            if (progressSession != null) {
                progressSession.completeConfirmed(completedSnapshot?.confirmedItems ?: 0L)
            }
        } else if (result.status == SyncRunStatus.PARTIAL && result.problem == null) {
            progressSession?.completeConfirmed(runStore.get(run.runId)?.confirmedItems ?: 0L)
        } else if (retryAvailable) {
            val snapshot = runStore.get(run.runId)
            if (snapshot != null) {
                val delayIndex = (networkFailureCount - 1)
                    .coerceIn(0L, RETRY_DELAYS.lastIndex.toLong())
                    .toInt()
                runStore.progress(
                    run.runId,
                    snapshot.phase,
                    snapshot.processed,
                    snapshot.total,
                    snapshot.completed,
                    snapshot.skipped,
                    snapshot.failed,
                    attemptId = snapshot.attemptId,
                    nextRetryAt = clock() + maxOf(
                        RETRY_DELAYS[delayIndex],
                        result.retryAfterMillis?.coerceAtLeast(0L) ?: 0L,
                    ),
                    state = SyncRunState.WAITING_RETRY,
                    reason = "network",
                    ownerSession = ownerSession,
                )
            }
        }
        runStore.finish(
            run.runId,
            when {
                result.status == SyncRunStatus.SUCCESS -> SyncRunState.SUCCEEDED
                result.status == SyncRunStatus.PARTIAL && result.problem == null -> SyncRunState.PARTIAL
                retryAvailable ->
                    SyncRunState.WAITING_RETRY
                result.problem == SyncRunProblem.NETWORK -> SyncRunState.FAILED
                else -> SyncRunState.BLOCKED
            },
            if (result.problem == SyncRunProblem.NETWORK && !retryAvailable) {
                "retry_exhausted"
            } else if (result.status == SyncRunStatus.PARTIAL && result.problem == null) {
                if (result.pending > 0) "pending_decision" else "projection_pending"
            } else {
                result.problem?.name
            },
            ownerSession,
        )
        runStore.get(run.runId)?.let { failureLogFor(it) }
        if (result.status == SyncRunStatus.SUCCESS) completePendingSetupIfSettled(connection)
        if (result.status == SyncRunStatus.SUCCESS) preferences.lastSuccess.set(clock())
        preferences.history.set(Json.encodeToString((records() + SyncRunRecord(clock(), trigger, result)).takeLast(20)))
        result
    }

    fun records(): List<SyncRunRecord> = runCatching {
        Json.decodeFromString<List<SyncRunRecord>>(preferences.history.get())
    }.getOrDefault(emptyList())

    companion object {
        const val CLIENT_ID = "Iv23liNtj6rhGAXJEwCS"
        const val APP_SLUG = "mihon-desktop"

        /** Four network failures: the initial attempt plus three automatic retries. */
        private const val MAX_NETWORK_FAILURES = 4L
        private val RETRY_DELAYS = longArrayOf(10_000L, 30_000L, 120_000L)

        private val RESUMABLE_STATES = setOf(
            SyncRunState.QUEUED,
            SyncRunState.RUNNING,
            SyncRunState.WAITING_NETWORK,
            SyncRunState.WAITING_RETRY,
            SyncRunState.WAITING_SYSTEM,
        )
    }
}
