package mihon.data.sync.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mihon.data.sync.auth.DiscoveredSyncSpace
import mihon.data.sync.auth.EmptySyncRepositoryCandidate
import mihon.data.sync.auth.GitHubPrivateRepositorySelector
import mihon.data.sync.auth.GitHubSyncSpaceClient
import mihon.data.sync.auth.SyncCreationAttempt
import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.data.sync.auth.SyncGitHubAccount
import mihon.data.sync.auth.SyncSpaceCreation
import mihon.data.sync.auth.SyncSpaceDiscovery
import mihon.data.sync.crypto.SyncSpaceCrypto
import mihon.data.sync.http.SyncHttpBodyObserver
import mihon.data.sync.http.SyncHttpException
import mihon.data.sync.http.SyncHttpRequestGate
import mihon.data.sync.transport.GitHubSyncTransport
import mihon.data.sync.transport.SyncSnapshotManifestBinding
import mihon.data.sync.transport.SyncSnapshotManifestStore
import mihon.domain.sync.crypto.SyncSpaceMaterial
import mihon.domain.sync.transport.SyncInitializationCheckpoint
import mihon.domain.sync.transport.SyncInitializationIntent
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncInitializationStage
import mihon.domain.sync.transport.SyncRepository
import okhttp3.OkHttpClient
import okio.Path
import java.util.UUID

internal class SyncSetupException(val problem: SyncDiscoveryProblem) : IllegalStateException("sync setup failed")

internal sealed interface SyncSetupOutcome {
    data class Connected(val setup: StoredSyncSetup) : SyncSetupOutcome
    data class Existing(val space: DiscoveredSyncSpace) : SyncSetupOutcome
}

internal sealed interface SyncPendingSetup {
    data class Current(val setup: StoredSyncSetup) : SyncPendingSetup
    data class Legacy(val setup: StoredLegacySyncSetup) : SyncPendingSetup
    data object None : SyncPendingSetup
}

internal data class LegacySyncSetupRecheck(
    val setup: StoredLegacySyncSetup,
    val discovery: SyncSpaceDiscovery,
    val matchingSpace: DiscoveredSyncSpace?,
)

/** Durable setup intent is saved before any GitHub mutation, independently of panel lifetime. */
internal class SyncOnboarding(
    private val runtime: SyncRuntime,
    private val client: OkHttpClient,
    private val apiBaseUrl: String,
    val storage: SyncSetupStorage,
    private val snapshotManifestStore: SyncSnapshotManifestStore,
) {
    private fun requireRepository(name: String) {
        if (!runtime.repositoryScope.accepts(
                name,
            )
        ) {
            throw SyncSetupException(SyncDiscoveryProblem.REPOSITORY_UNAVAILABLE)
        }
    }

    private suspend fun requireLocalScope() {
        if (!runtime.repositoryScope.isolated) return
        runtime.connection()?.let { requireRepository(it.repository.name) }
    }

    suspend fun discover(): SyncSpaceDiscovery {
        requireLocalScope()
        return spaces(runtime.accessToken()).discover()
    }

    suspend fun pending(): StoredSyncSetup? {
        requireLocalScope()
        return storage.pending(session().account.id)?.also { requireRepository(it.repository) }
    }

    suspend fun pendingForCurrentAccount(): SyncPendingSetup {
        requireLocalScope()
        val current = session().account.id
        storage.legacyPending(current)?.let {
            requireRepository(it.repository)
            return SyncPendingSetup.Legacy(it)
        }
        storage.pending(current)?.let {
            requireRepository(it.repository)
            return SyncPendingSetup.Current(it)
        }
        return SyncPendingSetup.None
    }

    /** Reads the fixed repository and valid v2 descriptors without changing the legacy local record. */
    suspend fun recheckLegacyPending(setup: StoredLegacySyncSetup): LegacySyncSetupRecheck {
        requireRepository(setup.repository)
        val current = session(setup.accountId)
        require(storage.legacyPending(setup.accountId) == setup) { "legacy sync setup changed" }
        val discovery = spaces(current.token).discover(setup.accountId)
        val spaces = when (discovery) {
            is SyncSpaceDiscovery.Found -> listOf(discovery.space)
            is SyncSpaceDiscovery.Multiple -> discovery.spaces
            else -> emptyList()
        }
        val material = setup.material.material()
        val match = spaces.firstOrNull {
            it.repositoryId == setup.repositoryId && it.repository == setup.repository() &&
                it.descriptor == material.descriptor
        }
        return LegacySyncSetupRecheck(setup, discovery, match)
    }

    /** Migrates an old join intent only after read-only discovery identifies its exact existing v2 space. */
    suspend fun migrateLegacyJoin(recheck: LegacySyncSetupRecheck): StoredSyncSetup {
        val legacy = recheck.setup
        requireRepository(legacy.repository)
        if (legacy.newSpace) throw SyncSetupException(SyncDiscoveryProblem.CREATION_UNCONFIRMED)
        require(storage.legacyPending(legacy.accountId) == legacy) { "legacy sync setup changed" }
        val space = recheck.matchingSpace ?: throw SyncSetupException(SyncDiscoveryProblem.RETRYABLE)
        if (space.account.id != legacy.accountId ||
            space.repositoryId != legacy.repositoryId ||
            space.repository != legacy.repository() ||
            space.descriptor != legacy.material.material().descriptor
        ) {
            throw SyncSetupException(SyncDiscoveryProblem.RETRYABLE)
        }
        val existing = storage.pending(legacy.accountId)
        if (existing != null) {
            if (!existing.newSpace && existing.repositoryId == space.repositoryId &&
                existing.repository() == space.repository && existing.material == legacy.material
            ) {
                return existing
            }
            throw SyncSetupException(SyncDiscoveryProblem.RETRYABLE)
        }
        val upgraded = StoredSyncSetup(
            accountId = space.account.id,
            accountLogin = space.account.login,
            attemptId = UUID.randomUUID().toString(),
            attemptNonce = UUID.randomUUID().toString(),
            newSpace = false,
            material = legacy.material,
            stage = SyncInitializationStage.SPACE_CONFIRMED,
            repositoryId = space.repositoryId,
            owner = space.repository.owner,
            repository = space.repository.name,
            branch = space.repository.branch,
        )
        storage.save(upgraded, null)
        return upgraded
    }

    suspend fun abandonLegacyPending(setup: StoredLegacySyncSetup) {
        requireRepository(setup.repository)
        storage.abandonLegacy(setup)
    }

    suspend fun create(candidate: EmptySyncRepositoryCandidate, password: String): StoredSyncSetup {
        requireLocalScope()
        requireRepository(candidate.repository.name)
        SyncSpaceCrypto.validatePassword(password)
        val account = candidate.account
        session(account.id)
        if (storage.legacyPending(account.id) !=
            null
        ) {
            throw SyncSetupException(SyncDiscoveryProblem.CREATION_UNCONFIRMED)
        }
        storage.pending(account.id)?.let {
            requireRepository(it.repository)
            return it
        }
        require(candidate.repository.name == runtime.repositoryScope.repositoryName)
        require(candidate.repository.owner.equals(account.login, ignoreCase = true))
        require(candidate.repositoryId > 0 && candidate.defaultBranch.isNotBlank())
        val material = SyncSpaceCrypto.create(UUID.randomUUID().toString(), 1, password)
        return StoredSyncSetup(
            accountId = account.id,
            accountLogin = account.login,
            attemptId = UUID.randomUUID().toString(),
            attemptNonce = UUID.randomUUID().toString(),
            newSpace = true,
            material = StoredSyncMaterial.from(material),
            stage = SyncInitializationStage.VERIFIED_EMPTY,
            repositoryId = candidate.repositoryId,
            owner = account.login,
            repository = candidate.repository.name,
            branch = GitHubSyncSpaceClient.BRANCH,
            defaultBranch = candidate.defaultBranch,
        ).also { storage.save(it, null) }
    }

    suspend fun join(space: DiscoveredSyncSpace, material: SyncSpaceMaterial): StoredSyncSetup {
        requireLocalScope()
        requireRepository(space.repository.name)
        require(material.descriptor == space.descriptor)
        session(space.account.id)
        if (storage.legacyPending(space.account.id) != null) {
            throw SyncSetupException(SyncDiscoveryProblem.CREATION_UNCONFIRMED)
        }
        val pending = storage.pending(space.account.id)
        if (pending != null) {
            requireRepository(pending.repository)
            if (pending.material == StoredSyncMaterial.from(material) && pending.repositoryId == space.repositoryId) {
                return pending
            }
            if (pending.stage != SyncInitializationStage.VERIFIED_EMPTY) {
                throw SyncSetupException(SyncDiscoveryProblem.RETRYABLE)
            }
            storage.clear(pending)
        }
        return StoredSyncSetup(
            accountId = space.account.id,
            accountLogin = space.account.login,
            attemptId = UUID.randomUUID().toString(),
            attemptNonce = UUID.randomUUID().toString(),
            newSpace = false,
            material = StoredSyncMaterial.from(material),
            stage = SyncInitializationStage.SPACE_CONFIRMED,
            repositoryId = space.repositoryId,
            owner = space.repository.owner,
            repository = space.repository.name,
            branch = space.repository.branch,
        ).also { storage.save(it, null) }
    }

    suspend fun resume(initial: StoredSyncSetup): SyncSetupOutcome {
        requireLocalScope()
        requireRepository(initial.repository)
        var setup = storage.pending(initial.accountId)
            ?.takeIf { it.attemptId == initial.attemptId } ?: throw IllegalStateException("sync setup changed")
        requireRepository(setup.repository)
        val session = session(setup.accountId)
        val material = setup.material.material()
        val requestGate = runtime.accountHttpRequestGate(session.account.id)
        if (setup.newSpace) {
            if (setup.stage == SyncInitializationStage.VERIFIED_EMPTY) {
                val rechecked = spaces(session.token, requestGate).createOrResume(
                    SyncCreationAttempt(session.account, setup.attemptId, repositoryId = setup.repositoryId),
                ) {}
                when (rechecked) {
                    is SyncSpaceCreation.Failed -> throw SyncSetupException(rechecked.problem)
                    is SyncSpaceCreation.Existing -> {
                        if (rechecked.space.repositoryId != setup.repositoryId ||
                            rechecked.space.descriptor != material.descriptor
                        ) {
                            if (!setup.matchesFixedRepository(rechecked.space)) {
                                throw SyncSetupException(SyncDiscoveryProblem.ACCOUNT_CHANGED)
                            }
                            storage.clear(setup)
                            return SyncSetupOutcome.Existing(rechecked.space)
                        }
                    }
                    is SyncSpaceCreation.Ready -> {
                        if (rechecked.repositoryId != setup.repositoryId ||
                            rechecked.defaultBranch != setup.defaultBranch
                        ) {
                            throw SyncSetupException(SyncDiscoveryProblem.ACCOUNT_CHANGED)
                        }
                    }
                }
            }
            verifyRepository(session, setup.repository(), setup.repositoryId)
            val result = transport(
                session.token,
                material,
                repositoryId = setup.repositoryId,
                persistentObjectCacheDirectory = runtime.persistentObjectCacheDirectory,
                requestGate = requestGate,
            ).initialize(
                setup.repository(),
                material.descriptor.spaceId,
                material.descriptor.generation,
                setup.initializationIntent(),
            ) { checkpoint ->
                require(checkpoint.stage.ordinal >= setup.stage.ordinal) { "sync setup stage moved backwards" }
                val next = setup.copy(
                    stage = checkpoint.stage,
                    confirmedBootstrapCommitSha = checkpoint.bootstrapCommitSha,
                    confirmedBootstrapTreeSha = checkpoint.bootstrapTreeSha,
                )
                storage.save(next, setup)
                setup = next
            }
            when (result) {
                is SyncInitializationResult.Initialized, is SyncInitializationResult.Adopted -> {
                    if (setup.stage != SyncInitializationStage.SPACE_CONFIRMED &&
                        setup.stage != SyncInitializationStage.CONNECTED
                    ) {
                        throw SyncSetupException(SyncDiscoveryProblem.RETRYABLE)
                    }
                }
                else -> {
                    val found = spaces(session.token, requestGate).discover(setup.accountId)
                    if (found is SyncSpaceDiscovery.Found && found.space.descriptor != material.descriptor) {
                        if (!setup.matchesFixedRepository(found.space)) {
                            throw SyncSetupException(SyncDiscoveryProblem.ACCOUNT_CHANGED)
                        }
                        storage.clear(setup)
                        return SyncSetupOutcome.Existing(found.space)
                    }
                    throw SyncSetupException(SyncDiscoveryProblem.RETRYABLE)
                }
            }
        }
        verifyRepository(session, setup.repository(), requireNotNull(setup.repositoryId))
        transport(
            session.token,
            material,
            repositoryId = requireNotNull(setup.repositoryId),
            manifestStore = snapshotManifestStore,
            manifestBinding = setup.snapshotManifestBinding(),
            persistentObjectCacheDirectory = runtime.persistentObjectCacheDirectory,
            requestGate = requestGate,
        ).readSnapshot(
            setup.repository(),
            material.descriptor.spaceId,
            material.descriptor.generation,
        ).getOrThrow()
        runtime.bindSetup(setup)
        val connected = setup.copy(stage = SyncInitializationStage.CONNECTED)
        storage.save(connected, setup)
        return SyncSetupOutcome.Connected(connected)
    }

    suspend fun complete(setup: StoredSyncSetup) {
        requireRepository(setup.repository)
        storage.clear(setup)
        storage.legacyPending(setup.accountId)?.let { legacy ->
            if (!legacy.newSpace) storage.clearMigratedLegacyJoin(legacy, setup)
        }
    }

    suspend fun session(expectedAccountId: Long? = null): Session {
        val token = runtime.accessToken()
        val http = GitHubPrivateRepositorySelector(
            client,
            { token },
            apiBaseUrl,
            expectedAccountId?.let(runtime::accountHttpRequestGate),
        )
        val response = http.requestPath("/user")
        if (response.code !in 200..299) throw SyncHttpException(response.code, "GitHub account lookup failed", false)
        val user = Json.parseToJsonElement(response.body.decodeToString()).jsonObject
        val id = user["id"]?.jsonPrimitive?.longOrNull ?: throw SyncSetupException(SyncDiscoveryProblem.MALFORMED)
        val login = user["login"]?.jsonPrimitive?.content ?: throw SyncSetupException(SyncDiscoveryProblem.MALFORMED)
        require(id > 0 && login.matches(Regex("[A-Za-z0-9-]{1,39}")))
        if (expectedAccountId != null &&
            id != expectedAccountId
        ) {
            throw SyncSetupException(SyncDiscoveryProblem.ACCOUNT_CHANGED)
        }
        return Session(SyncGitHubAccount(id, login), token, http)
    }

    suspend fun verifyRepository(session: Session, repository: SyncRepository, repositoryId: Long) {
        requireRepository(repository.name)
        val response = session.http.requestPath("/repos/${repository.fullName}")
        if (response.code !in 200..299) throw SyncHttpException(response.code, "GitHub repository lookup failed", false)
        val json = Json.parseToJsonElement(response.body.decodeToString()).jsonObject
        if (json["id"]?.jsonPrimitive?.longOrNull != repositoryId ||
            json["owner"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull != session.account.id
        ) {
            throw SyncSetupException(SyncDiscoveryProblem.ACCOUNT_CHANGED)
        }
        val private = json["private"]?.jsonPrimitive?.booleanOrNull
            ?: throw SyncSetupException(SyncDiscoveryProblem.MALFORMED)
        if (!private) throw SyncSetupException(SyncDiscoveryProblem.REPOSITORY_NOT_PRIVATE)
        val archived = json["archived"]?.jsonPrimitive?.booleanOrNull
            ?: throw SyncSetupException(SyncDiscoveryProblem.MALFORMED)
        val disabled = json["disabled"]?.jsonPrimitive?.booleanOrNull
            ?: throw SyncSetupException(SyncDiscoveryProblem.MALFORMED)
        if (archived || disabled) throw SyncSetupException(SyncDiscoveryProblem.REPOSITORY_UNAVAILABLE)
        val permissions = json["permissions"]?.jsonObject
            ?: throw SyncSetupException(SyncDiscoveryProblem.MALFORMED)
        if (permissions["push"]?.jsonPrimitive?.booleanOrNull != true &&
            permissions["admin"]?.jsonPrimitive?.booleanOrNull != true
        ) {
            throw SyncSetupException(SyncDiscoveryProblem.REPOSITORY_NOT_WRITABLE)
        }
    }

    private fun StoredSyncSetup.initializationIntent() = SyncInitializationIntent(
        accountId,
        repositoryId,
        requireNotNull(defaultBranch),
        attemptNonce,
        stage,
        confirmedBootstrapCommitSha,
        confirmedBootstrapTreeSha,
    )

    private fun StoredSyncSetup.matchesFixedRepository(space: DiscoveredSyncSpace): Boolean =
        newSpace && space.account.id == accountId && space.repositoryId == repositoryId &&
            space.repository.owner.equals(owner, ignoreCase = true) &&
            space.repository.name.equals(repository, ignoreCase = true) && space.repository.branch == branch

    fun transport(
        token: String,
        material: SyncSpaceMaterial,
        repositoryId: Long? = null,
        manifestStore: SyncSnapshotManifestStore? = null,
        manifestBinding: SyncSnapshotManifestBinding? = null,
        persistentObjectCacheDirectory: Path? = null,
        requestGate: SyncHttpRequestGate? = null,
        bodyObserver: SyncHttpBodyObserver? = null,
    ): GitHubSyncTransport = GitHubSyncTransport(
        client,
        { token },
        apiBaseUrl,
        spaceMaterial = material,
        repositoryId = repositoryId,
        persistentObjectCacheDirectory = persistentObjectCacheDirectory,
        requestGate = requestGate,
        bodyObserver = bodyObserver,
    ).also { transport ->
        if (manifestStore != null && manifestBinding != null) {
            transport.installSnapshotManifestStore(manifestStore, manifestBinding)
        }
    }

    private fun spaces(token: String, requestGate: SyncHttpRequestGate? = null) =
        GitHubSyncSpaceClient(client, { token }, apiBaseUrl, requestGate, runtime.repositoryScope)

    class Session(val account: SyncGitHubAccount, val token: String, val http: GitHubPrivateRepositorySelector) {
        override fun toString(): String = "SyncSession(<redacted>)"
    }
}
