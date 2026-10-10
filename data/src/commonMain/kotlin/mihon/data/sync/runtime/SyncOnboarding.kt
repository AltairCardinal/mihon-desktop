package mihon.data.sync.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mihon.data.sync.auth.DiscoveredSyncSpace
import mihon.data.sync.auth.EmptySyncRepositoryCandidate
import mihon.data.sync.auth.GitHubPrivateRepositorySelector
import mihon.data.sync.auth.GitHubSyncRepositoryManager
import mihon.data.sync.auth.GitHubSyncSpaceClient
import mihon.data.sync.auth.SyncCreationAttempt
import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.data.sync.auth.SyncGitHubAccount
import mihon.data.sync.auth.SyncRepositoryCreationIntent
import mihon.data.sync.auth.SyncSpaceCreation
import mihon.data.sync.auth.SyncSpaceDiscovery
import mihon.data.sync.crypto.SyncSpaceCrypto
import mihon.data.sync.http.SyncFailureDiagnostics
import mihon.data.sync.http.SyncFailurePhase
import mihon.data.sync.http.SyncHttpBodyObserver
import mihon.data.sync.http.SyncHttpException
import mihon.data.sync.http.SyncHttpRequestGate
import mihon.data.sync.http.SyncRequiredResource
import mihon.data.sync.http.SyncRequiredResourceUnavailable
import mihon.data.sync.http.requireSyncSuccess
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

internal class SyncSetupException(
    val problem: SyncDiscoveryProblem,
    val initialization: SyncInitializationFailure? = null,
) : IllegalStateException("sync setup failed")

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
    suspend fun discover(): SyncSpaceDiscovery = spaces(runtime.accessToken()).discover()

    suspend fun checkRepositoryCreationPermission(expectedAccountId: Long? = null) =
        spaces(runtime.accessToken()).checkRepositoryCreationPermission(expectedAccountId)

    suspend fun pending(): StoredSyncSetup? = storage.pending(session().account.id)

    suspend fun pendingForCurrentAccount(): SyncPendingSetup {
        var phase = SyncFailurePhase.PENDING_ACCOUNT
        try {
            val current = session().account.id
            phase = SyncFailurePhase.PENDING_STORAGE
            storage.legacyPending(current)?.let { return SyncPendingSetup.Legacy(it) }
            val connection = runtime.connection()
            val binding = connection?.takeIf { it.enabled && !it.unsupportedFormat }
                ?.let { storage.connection(it.spaceId, it.generation) }
            val pending = binding?.takeIf { it.accountId == current }?.let { storage.pendingForConnection(it) }
                ?: storage.pending(current)?.takeIf { binding == null || it.material == binding.material }
            pending?.let { return SyncPendingSetup.Current(it) }
            return SyncPendingSetup.None
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SyncFailureDiagnostics.record(phase, error)
            throw error
        }
    }

    /** Reads the fixed repository and valid v2 descriptors without changing the legacy local record. */
    suspend fun recheckLegacyPending(setup: StoredLegacySyncSetup): LegacySyncSetupRecheck {
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

    suspend fun abandonLegacyPending(setup: StoredLegacySyncSetup) = storage.abandonLegacy(setup)

    suspend fun create(
        candidate: EmptySyncRepositoryCandidate,
        password: String,
        intent: StoredSyncSpaceSwitch? = null,
    ): StoredSyncSetup {
        SyncSpaceCrypto.validatePassword(password)
        val account = candidate.account
        session(account.id)
        if (intent == null && storage.legacyPending(account.id) !=
            null
        ) {
            throw SyncSetupException(SyncDiscoveryProblem.CREATION_UNCONFIRMED)
        }
        if (intent == null) {
            storage.pending(account.id)?.let {
                if (it.repositoryId != candidate.repositoryId || it.repository() != candidate.repository) {
                    throw SyncSetupException(
                        SyncDiscoveryProblem.INITIALIZATION_REQUIRES_ACTION,
                        SyncInitializationFailure(
                            it.stage,
                            SyncInitializationFailureReason.REPOSITORY_IDENTITY_CHANGED,
                            true,
                        ),
                    )
                }
                return it
            }
        } else {
            runtime.verifySwitch(intent)
            require(intent.accountId == account.id && intent.purpose == SyncSpaceSwitchPurpose.CREATE)
            require(intent.stage == SyncSpaceSwitchStage.PREPARING && intent.target == null)
        }
        if (candidate.repository.name != GitHubSyncSpaceClient.REPOSITORY_NAME) {
            val recorded = candidate.creationAttemptId?.let { storage.repositoryCreation(account.id) }
                ?: throw SyncSetupException(SyncDiscoveryProblem.CREATION_UNCONFIRMED)
            require(
                recorded.attemptId == candidate.creationAttemptId &&
                    recorded.repositoryId == candidate.repositoryId &&
                    recorded.repositoryName == candidate.repository.name,
            )
        }
        require(candidate.repository.owner.equals(account.login, ignoreCase = true))
        require(candidate.repositoryId > 0 && candidate.defaultBranch.isNotBlank())
        val material = SyncSpaceCrypto.create(UUID.randomUUID().toString(), 1, password)
        return StoredSyncSetup(
            accountId = account.id,
            accountLogin = account.login,
            attemptId = candidate.creationAttemptId ?: UUID.randomUUID().toString(),
            attemptNonce = UUID.randomUUID().toString(),
            newSpace = true,
            switchIntentId = intent?.intentId,
            material = StoredSyncMaterial.from(material),
            stage = SyncInitializationStage.VERIFIED_EMPTY,
            repositoryId = candidate.repositoryId,
            owner = account.login,
            repository = candidate.repository.name,
            branch = GitHubSyncSpaceClient.BRANCH,
            defaultBranch = candidate.defaultBranch,
        ).also {
            storage.save(it, null)
            if (intent != null) storage.saveSwitch(intent.copy(target = it), intent)
            candidate.creationAttemptId?.let { attempt ->
                storage.repositoryCreation(account.id)?.takeIf { value -> value.attemptId == attempt }
                    ?.let { value -> storage.archiveRepositoryCreation(value) }
            }
        }
    }

    suspend fun join(
        space: DiscoveredSyncSpace,
        material: SyncSpaceMaterial,
        intent: StoredSyncSpaceSwitch? = null,
    ): StoredSyncSetup {
        require(material.descriptor == space.descriptor)
        session(space.account.id)
        if (intent == null && storage.legacyPending(space.account.id) != null) {
            throw SyncSetupException(SyncDiscoveryProblem.CREATION_UNCONFIRMED)
        }
        if (intent != null) {
            runtime.verifySwitch(intent)
            require(intent.accountId == space.account.id && intent.stage == SyncSpaceSwitchStage.PREPARING)
            require(intent.target == null)
            verifyRepository(session(space.account.id), space.repository, space.repositoryId)
            transport(
                runtime.accessToken(),
                material,
                repositoryId = space.repositoryId,
            ).readSnapshot(space.repository, material.descriptor.spaceId, material.descriptor.generation).getOrThrow()
        }
        val pending = if (intent == null) storage.pending(space.account.id) else null
        if (pending != null) {
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
            switchIntentId = intent?.intentId,
            material = StoredSyncMaterial.from(material),
            stage = SyncInitializationStage.SPACE_CONFIRMED,
            repositoryId = space.repositoryId,
            owner = space.repository.owner,
            repository = space.repository.name,
            branch = space.repository.branch,
        ).also {
            storage.save(it, null)
            if (intent != null) storage.saveSwitch(intent.copy(target = it), intent)
        }
    }

    suspend fun resume(initial: StoredSyncSetup): SyncSetupOutcome {
        var phase = SyncFailurePhase.RESUME_LOAD
        var diagnosticNewSpace = initial.newSpace
        var diagnosticStage = initial.stage
        try {
            var setup = storage.setupFor(initial)
                ?.takeIf { it.attemptId == initial.attemptId } ?: throw IllegalStateException("sync setup changed")
            diagnosticNewSpace = setup.newSpace
            diagnosticStage = setup.stage
            phase = SyncFailurePhase.RESUME_ACCOUNT
            val session = session(setup.accountId)
            phase = SyncFailurePhase.RESUME_LOAD
            val material = setup.material.material()
            val requestGate = runtime.accountHttpRequestGate(session.account.id)
            if (setup.newSpace && setup.stage != SyncInitializationStage.CONNECTED) {
                phase = SyncFailurePhase.RESUME_INITIALIZE
                if (setup.stage == SyncInitializationStage.VERIFIED_EMPTY) {
                    val creation = storage.repositoryCreationArchive(setup.attemptId)
                        ?: storage.repositoryCreation(setup.accountId)?.takeIf { it.attemptId == setup.attemptId }
                    val rechecked = if (creation != null) {
                        require(
                            creation.repositoryId == setup.repositoryId &&
                                creation.repositoryName == setup.repository &&
                                creation.account.id == setup.accountId,
                        )
                        GitHubSyncRepositoryManager(client, { session.token }, apiBaseUrl, requestGate)
                            .createOrResume(creation) {}
                    } else {
                        spaces(session.token, requestGate).createOrResume(
                            SyncCreationAttempt(session.account, setup.attemptId, repositoryId = setup.repositoryId),
                        ) {}
                    }
                    when (rechecked) {
                        is SyncSpaceCreation.Failed -> throw SyncSetupException(rechecked.problem)
                        is SyncSpaceCreation.Existing -> {
                            if (rechecked.space.repositoryId != setup.repositoryId ||
                                rechecked.space.descriptor != material.descriptor
                            ) {
                                if (!setup.matchesFixedRepository(rechecked.space)) {
                                    throw SyncSetupException(SyncDiscoveryProblem.ACCOUNT_CHANGED)
                                }
                                require(setup.switchIntentId == null) { "sync target descriptor changed" }
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
                phase = SyncFailurePhase.RESUME_VERIFY_REPOSITORY
                verifyRepository(session, setup.repository(), setup.repositoryId)
                phase = SyncFailurePhase.RESUME_INITIALIZE
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
                    phase = SyncFailurePhase.RESUME_SAVE
                    storage.save(next, setup)
                    setup = next
                    diagnosticStage = next.stage
                    phase = SyncFailurePhase.RESUME_INITIALIZE
                }
                SyncFailureDiagnostics.record(
                    SyncFailurePhase.RESUME_INITIALIZE_RESULT,
                    newSpace = setup.newSpace,
                    stage = setup.stage,
                    result = result,
                )
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
                            require(setup.switchIntentId == null) { "sync target descriptor changed" }
                            storage.clear(setup)
                            return SyncSetupOutcome.Existing(found.space)
                        }
                        val explicit = result is SyncInitializationResult.NeedsExplicitAction
                        val reason = when (result) {
                            is SyncInitializationResult.NeedsExplicitAction -> result.reason
                            is SyncInitializationResult.Failed -> result.reason
                            else -> ""
                        }
                        throw SyncSetupException(
                            if (explicit) {
                                SyncDiscoveryProblem.INITIALIZATION_REQUIRES_ACTION
                            } else {
                                SyncDiscoveryProblem.INITIALIZATION_UNCONFIRMED
                            },
                            SyncInitializationFailure(setup.stage, initializationReason(reason), explicit),
                        )
                    }
                }
            }
            phase = SyncFailurePhase.RESUME_VERIFY_REPOSITORY
            verifyRepository(session, setup.repository(), requireNotNull(setup.repositoryId))
            phase = SyncFailurePhase.RESUME_READ_SNAPSHOT
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
            phase = SyncFailurePhase.RESUME_BIND
            runtime.bindSetup(setup)
            val connected = setup.copy(stage = SyncInitializationStage.CONNECTED)
            phase = SyncFailurePhase.RESUME_SAVE
            val current = requireNotNull(storage.setupFor(setup))
            if (current != connected) storage.save(connected, current)
            return SyncSetupOutcome.Connected(connected)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SyncFailureDiagnostics.record(
                phase,
                error,
                newSpace = diagnosticNewSpace,
                stage = diagnosticStage,
            )
            throw error
        }
    }

    suspend fun complete(setup: StoredSyncSetup) {
        storage.clear(setup)
        storage.legacyPending(setup.accountId)?.let { legacy ->
            if (!legacy.newSpace) storage.clearMigratedLegacyJoin(legacy, setup)
        }
    }

    suspend fun session(expectedAccountId: Long? = null, accessToken: String? = null): Session {
        val token = accessToken ?: runtime.accessToken()
        val http = GitHubPrivateRepositorySelector(
            client,
            { token },
            apiBaseUrl,
            expectedAccountId?.let(runtime::accountHttpRequestGate),
        )
        val response = http.requestPath("/user")
        response.requireSyncSuccess()
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
        val response = session.http.requestPath("/repos/${repository.fullName}")
        if (response.code == 404) throw SyncRequiredResourceUnavailable(SyncRequiredResource.REPOSITORY)
        response.requireSyncSuccess()
        val json = Json.parseToJsonElement(response.body.decodeToString()).jsonObject
        val actualId = json["id"]?.jsonPrimitive?.longOrNull
            ?: throw SyncSetupException(SyncDiscoveryProblem.MALFORMED)
        val ownerId = json["owner"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
            ?: throw SyncSetupException(SyncDiscoveryProblem.MALFORMED)
        if (actualId != repositoryId || ownerId != session.account.id) {
            throw SyncSetupException(SyncDiscoveryProblem.REPOSITORY_IDENTITY_MISMATCH)
        }
        val private = json["private"]?.jsonPrimitive?.booleanOrNull
            ?: throw SyncSetupException(SyncDiscoveryProblem.MALFORMED)
        if (!private) throw SyncSetupException(SyncDiscoveryProblem.REPOSITORY_NOT_PRIVATE)
        val archived = json["archived"]?.jsonPrimitive?.booleanOrNull
            ?: throw SyncSetupException(SyncDiscoveryProblem.MALFORMED)
        val disabled = json["disabled"]?.jsonPrimitive?.booleanOrNull
            ?: throw SyncSetupException(SyncDiscoveryProblem.MALFORMED)
        if (disabled) throw SyncSetupException(SyncDiscoveryProblem.REPOSITORY_DISABLED)
        if (archived) throw SyncSetupException(SyncDiscoveryProblem.REPOSITORY_ARCHIVED)
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
        GitHubSyncSpaceClient(client, { token }, apiBaseUrl, requestGate)

    fun repositoryManager(token: String, accountId: Long) =
        GitHubSyncRepositoryManager(client, { token }, apiBaseUrl, runtime.accountHttpRequestGate(accountId))

    class Session(val account: SyncGitHubAccount, val token: String, val http: GitHubPrivateRepositorySelector) {
        override fun toString(): String = "SyncSession(<redacted>)"
    }

    private fun initializationReason(reason: String): SyncInitializationFailureReason = when (reason) {
        "initialization space identity does not match" -> SyncInitializationFailureReason.SPACE_IDENTITY_CHANGED
        "initialization attempt identity is invalid" -> SyncInitializationFailureReason.ATTEMPT_INVALID
        "repository identity changed" -> SyncInitializationFailureReason.REPOSITORY_IDENTITY_CHANGED
        "repository default branch changed" -> SyncInitializationFailureReason.DEFAULT_BRANCH_CHANGED
        "repository is not reported empty", "repository is no longer verified empty" ->
            SyncInitializationFailureReason.NOT_EMPTY
        "confirmed bootstrap commit is missing", "confirmed bootstrap tree is missing" ->
            SyncInitializationFailureReason.BOOTSTRAP_MISSING
        "confirmed bootstrap changed", "confirmed bootstrap identity is malformed" ->
            SyncInitializationFailureReason.BOOTSTRAP_CHANGED
        "bootstrap ownership could not be confirmed for this attempt" ->
            SyncInitializationFailureReason.BOOTSTRAP_UNCONFIRMED
        "initial sync branch contains unrecognized data" -> SyncInitializationFailureReason.UNRECOGNIZED_DATA
        "existing sync space requires explicit import" -> SyncInitializationFailureReason.EXISTING_SPACE_REQUIRES_JOIN
        "sync branch creation could not be confirmed", "initialization ref update failed" ->
            SyncInitializationFailureReason.REQUEST_UNCONFIRMED
        else -> SyncInitializationFailureReason.UNKNOWN
    }
}
