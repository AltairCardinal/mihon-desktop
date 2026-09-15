package mihon.data.sync.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.data.sync.auth.GitHubAuthClient
import mihon.data.sync.auth.GitHubPrivateRepositorySelector
import mihon.data.sync.auth.GitHubTokenRefresher
import mihon.data.sync.auth.PersistentGitHubCredentialStore
import mihon.data.sync.inbox.SyncInboxProjector
import mihon.data.sync.journal.SyncBaselineStore
import mihon.data.sync.journal.SyncLocalIdentity
import mihon.data.sync.journal.SyncLocalJournal
import mihon.data.sync.projection.SyncRemoteProjectionWriter
import mihon.data.sync.transport.GitHubSyncTransport
import mihon.domain.sync.auth.GitHubAuthEndpoints
import mihon.domain.sync.auth.GitHubAuthException
import mihon.domain.sync.auth.GitHubAuthFailure
import mihon.domain.sync.auth.GitHubAuthFailureReason
import mihon.domain.sync.crypto.SyncRecoveryCodec
import mihon.domain.sync.crypto.SyncRecoveryData
import mihon.domain.sync.crypto.SyncSecret
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
import java.security.MessageDigest
import java.util.UUID

data class SyncConnection(
    val spaceId: String,
    val generation: Long,
    val repository: SyncRepository,
    val enabled: Boolean,
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
    val repositories = GitHubPrivateRepositorySelector(productionClient, { accessToken() }, endpoints.apiBaseUrl)
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

    suspend fun connection(): SyncConnection? = handler.await {
        sync_journalQueries.getActiveSpace().executeAsOneOrNull()?.let {
            SyncConnection(
                it.space_id,
                it.generation,
                SyncRepository(it.repository_owner, it.repository_name, it.repository_branch),
                it.exchange_enabled,
            )
        }
    }

    /** The UI must obtain recovery material and explicit connection consent before calling this. */
    suspend fun connect(repository: SyncRepository, recovery: SyncRecoveryData) {
        coordinator.cancelAndJoin()
        connectionMutex.withLock {
            val secret = SyncRecoveryCodec.importSecret(recovery, recovery.spaceId, recovery.generation).getOrThrow()
            val transport = transport(secret)
            transport.readSnapshot(repository, recovery.spaceId, recovery.generation).getOrThrow()
            val key = secretKey(recovery.spaceId, recovery.generation)
            val previous = secureStore.read(key)
            val stored = previous?.let(::decodeSpaceKey)
            require(stored == null || stored.recovery.rawKeyset == recovery.rawKeyset) { "sync key cannot be replaced" }
            val current = handler.await {
                sync_journalQueries.getCurrentActor(recovery.spaceId, recovery.generation).executeAsOneOrNull()
            }
            val identity = when {
                current == null -> SyncLocalIdentity(UUID.randomUUID().toString(), 1)
                stored?.actorId == current.actor_id && stored.epoch == current.epoch -> SyncLocalIdentity(
                    current.actor_id,
                    current.epoch,
                )
                else -> SyncLocalJournal(handler).renewIdentity(recovery.spaceId, recovery.generation)
            }
            val encoded = Json.encodeToString(SpaceKey(recovery, identity.actorId, identity.epoch))
            if (!secureStore.compareAndSet(key, previous, encoded)) throw SyncSecureStoreException()
            baseline.connectAndImport(
                recovery.spaceId,
                recovery.generation,
                repository,
                identity.actorId,
                identity.epoch,
            )
        }
    }

    suspend fun disconnect() {
        coordinator.cancelAndJoin()
        connectionMutex.withLock {
            connection()?.let { SyncLocalJournal(handler).disconnect(it.spaceId, it.generation) }
            credentials.clear()
        }
    }

    override suspend fun exchange(trigger: SyncTrigger): SyncRunResult = connectionMutex.withLock {
        val connection = connection()?.takeIf { it.enabled } ?: return@withLock SyncRunResult(SyncRunStatus.SKIPPED)
        val result = try {
            preferences.lastAttempt.set(clock())
            // Check authorization separately from key access so the UI can offer the appropriate recovery action.
            accessToken()
            val stored = secureStore.read(secretKey(connection.spaceId, connection.generation))?.let(::decodeSpaceKey)
                ?: throw SyncSecureStoreException()
            val actor = handler.await {
                sync_journalQueries.getCurrentActor(connection.spaceId, connection.generation).executeAsOne()
            }
            if (stored.actorId != actor.actor_id || stored.epoch != actor.epoch) throw SyncSecureStoreException()
            val secret = SyncRecoveryCodec.importSecret(
                stored.recovery,
                connection.spaceId,
                connection.generation,
            ).getOrThrow()
            SyncDatabaseExchange(handler, baseline, projector, transport(secret), secret)
                .exchange(connection.spaceId, connection.generation, connection.repository)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            SyncRunResult(SyncRunStatus.FAILED, problem = failure.syncProblem())
        }
        if (result.status == SyncRunStatus.SUCCESS) preferences.lastSuccess.set(clock())
        preferences.history.set(Json.encodeToString((records() + SyncRunRecord(clock(), trigger, result)).takeLast(20)))
        result
    }

    fun records(): List<SyncRunRecord> = runCatching {
        Json.decodeFromString<List<SyncRunRecord>>(preferences.history.get())
    }.getOrDefault(emptyList())

    fun transport(secret: SyncSecret) = GitHubSyncTransport(productionClient, {
        accessToken()
    }, endpoints.apiBaseUrl, indexSecret = secret)

    private fun secretKey(spaceId: String, generation: Long): String = "space-" + MessageDigest.getInstance("SHA-256")
        .digest("$generation:$spaceId".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun decodeSpaceKey(value: String): SpaceKey = try {
        Json.decodeFromString<SpaceKey>(value).also {
            require(it.actorId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && it.epoch > 0)
            SyncRecoveryCodec.importSecret(it.recovery, it.recovery.spaceId, it.recovery.generation).getOrThrow()
        }
    } catch (_: Exception) {
        throw SyncSecureStoreException()
    }

    @Serializable
    private data class SpaceKey(val recovery: SyncRecoveryData, val actorId: String, val epoch: Long) {
        override fun toString(): String = "SpaceKey(<redacted>)"
    }

    companion object {
        const val CLIENT_ID = "Iv23liNtj6rhGAXJEwCS"
        const val APP_SLUG = "mihon-desktop"
    }
}
