package mihon.data.sync.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.data.sync.auth.GitHubAuthClient
import mihon.data.sync.auth.GitHubTokenRefresher
import mihon.data.sync.auth.PersistentGitHubCredentialStore
import mihon.data.sync.inbox.SyncInboxProjector
import mihon.data.sync.journal.SyncBaselineStore
import mihon.data.sync.journal.SyncLocalIdentity
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.projection.SyncRemoteProjectionWriter
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
import mihon.domain.sync.transport.SyncRepository
import okhttp3.OkHttpClient
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
) : SyncRunPort {
    val preferences = SyncPreferences(preferenceStore)
    val credentials = PersistentGitHubCredentialStore(secureStore)
    val authorization = GitHubAuthClient(productionClient, endpoints, nowMillis = clock)
    val coordinator = SyncCoordinator(this)
    val runStore = SyncRunStore(handler, clock)
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
    internal val onboarding =
        SyncOnboarding(this, productionClient, endpoints.apiBaseUrl, SyncSetupStorage(secureStore))

    internal suspend fun acceptAuthorization(revision: Long?, token: mihon.domain.sync.auth.GitHubAccessToken) {
        coordinator.cancelAndJoin()
        connectionMutex.withLock { credentials.replace(revision, token) }
    }

    internal suspend fun bindSetup(setup: StoredSyncSetup) {
        coordinator.cancelAndJoin()
        connectionMutex.withLock {
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

    init {
        if (preferences.scheduleAnchor.get() == 0L) preferences.scheduleAnchor.set(clock())
    }

    suspend fun stopPanel() {
        if (panelDelegate.isInitialized()) panelDelegate.value.stop()
    }

    /** Resumes only an accepted system-interrupted run; a user pause remains paused. */
    suspend fun hasResumableRun(): Boolean {
        val connection = connection() ?: return false
        return runStore.active(connection.spaceId, connection.generation)?.let {
            it.state in RESUMABLE_STATES
        } == true
    }

    /** Returns whether an accepted automatic run is allowed to make its next attempt now. */
    suspend fun isRecoveryDue(): Boolean {
        val connection = connection() ?: return false
        return runStore.active(connection.spaceId, connection.generation)?.let {
            it.state in RESUMABLE_STATES && (it.nextRetryAt == 0L || it.nextRetryAt <= clock())
        } == true
    }

    /** Delay used when the platform schedules the durable recovery wake-up. */
    suspend fun recoveryDelayMillis(): Long {
        val connection = connection() ?: return 0L
        return runStore.active(connection.spaceId, connection.generation)?.let {
            if (it.state in RESUMABLE_STATES) (it.nextRetryAt - clock()).coerceAtLeast(0L) else 0L
        } ?: 0L
    }

    /** Resumes only an accepted system-interrupted run; a user pause remains paused. */
    suspend fun resumeIfNeeded(): Boolean {
        val connection = connection() ?: return false
        val run = runStore.active(connection.spaceId, connection.generation) ?: return false
        if (run.state !in RESUMABLE_STATES) return false
        if (run.nextRetryAt > clock()) return true
        if (!coordinator.activity.value.running && run.state == SyncRunState.RUNNING) {
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
        connection()?.let { runStore.active(it.spaceId, it.generation)?.let { runStore.pause(it.runId) } }
        coordinator.cancelAndJoin()
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

    suspend fun connection(): SyncConnection? {
        val active = handler.await { sync_journalQueries.getActiveSpace().executeAsOneOrNull() } ?: return null
        var unsupported = false
        val stored = try {
            onboarding.storage.connection(active.space_id, active.generation)
        } catch (_: UnsupportedSyncSpace) {
            unsupported = true
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return SyncConnection(
            active.space_id,
            active.generation,
            SyncRepository(active.repository_owner, active.repository_name, active.repository_branch),
            active.exchange_enabled && !unsupported,
            stored?.material?.material()?.descriptor?.mode,
            stored?.accountLogin,
            unsupportedFormat = unsupported && active.exchange_enabled,
        )
    }

    /** Reads local durable facts only; network authorization is rechecked when work resumes. */
    internal suspend fun pendingSetup(connection: SyncConnection): StoredSyncSetup? {
        if (!connection.enabled || connection.unsupportedFormat) return null
        val stored = onboarding.storage.connection(connection.spaceId, connection.generation) ?: return null
        if (stored.repository() != connection.repository) return null
        return onboarding.storage.pending(stored.accountId)?.takeIf {
            // A crash can follow the DB commit but precede the final connected-marker CAS.
            it.repositoryId == stored.repositoryId && it.repository() == stored.repository() &&
                it.material == stored.material
        }
    }

    suspend fun disconnect() {
        coordinator.cancelAndJoin()
        connectionMutex.withLock {
            connection()?.let {
                runStore.active(it.spaceId, it.generation)?.let { run -> runStore.cancel(run.runId) }
                SyncLocalJournal(handler).disconnect(it.spaceId, it.generation)
            }
            credentials.clear()
        }
    }

    override suspend fun exchange(trigger: SyncTrigger): SyncRunResult = connectionMutex.withLock {
        val connection = connection()?.takeIf { it.enabled || it.unsupportedFormat }
            ?: return@withLock SyncRunResult(SyncRunStatus.SKIPPED)
        val active = runStore.active(connection.spaceId, connection.generation)
        if (trigger == SyncTrigger.RECOVERY && active?.state !in RESUMABLE_STATES) {
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
                (trigger in setOf(SyncTrigger.RECOVERY, SyncTrigger.PERIODIC) && it.state in RESUMABLE_STATES) ||
                (trigger == SyncTrigger.MANUAL && it.state == SyncRunState.RUNNING)
        }
        val run = existing ?: runStore.start(connection.spaceId, connection.generation, trigger)
        val ownerSession = UUID.randomUUID().toString()
        val automatic = trigger in setOf(SyncTrigger.RECOVERY, SyncTrigger.PERIODIC)
        val exhausted = automatic && run.attempt >= MAX_AUTOMATIC_ATTEMPTS
        val attempt = if (exhausted) run.attempt else run.attempt + 1
        if (!runStore.claim(run.runId, ownerSession, attempt)) {
            return@withLock SyncRunResult(SyncRunStatus.SKIPPED)
        }
        if (exhausted) {
            runStore.finish(run.runId, SyncRunState.FAILED, "retry_exhausted", ownerSession)
            return@withLock SyncRunResult(SyncRunStatus.FAILED, problem = SyncRunProblem.NETWORK)
        }
        runStore.progress(
            run.runId,
            SyncRunPhase.CHECKING,
            run.processed,
            run.total,
            run.completed,
            run.skipped,
            run.failed,
            attempt = attempt,
            ownerSession = ownerSession,
        )
        val result = try {
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
                onboarding.transport(session.token, material),
                spaceMaterial = material,
                allowImport = { !preferences.importPaused.get() },
                progress = runStore.reporter(run.runId, ownerSession),
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
        if (result.status == SyncRunStatus.SUCCESS) {
            runStore.progress(
                run.runId,
                SyncRunPhase.COMPLETE,
                (result.uploaded + result.downloaded).toLong(),
                (result.uploaded + result.downloaded).toLong(),
                completed = (result.uploaded + result.downloaded).toLong(),
                ownerSession = ownerSession,
            )
        } else if (result.problem == SyncRunProblem.NETWORK && attempt < MAX_AUTOMATIC_ATTEMPTS) {
            val snapshot = runStore.get(run.runId)
            if (snapshot != null) {
                runStore.progress(
                    run.runId,
                    snapshot.phase,
                    snapshot.processed,
                    snapshot.total,
                    snapshot.completed,
                    snapshot.skipped,
                    snapshot.failed,
                    attempt = snapshot.attempt,
                    nextRetryAt = clock() + RETRY_DELAYS[(attempt - 1).toInt()],
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
                result.problem == SyncRunProblem.NETWORK && attempt < MAX_AUTOMATIC_ATTEMPTS ->
                    SyncRunState.WAITING_RETRY
                result.problem == SyncRunProblem.NETWORK -> SyncRunState.FAILED
                else -> SyncRunState.BLOCKED
            },
            if (result.problem == SyncRunProblem.NETWORK && attempt >= MAX_AUTOMATIC_ATTEMPTS) {
                "retry_exhausted"
            } else {
                result.problem?.name
            },
            ownerSession,
        )
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
        private const val MAX_AUTOMATIC_ATTEMPTS = 3L
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
