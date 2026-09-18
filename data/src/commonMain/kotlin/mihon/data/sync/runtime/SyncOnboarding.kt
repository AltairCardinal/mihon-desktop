package mihon.data.sync.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mihon.data.sync.auth.DiscoveredSyncSpace
import mihon.data.sync.auth.GitHubPrivateRepositorySelector
import mihon.data.sync.auth.GitHubSyncSpaceClient
import mihon.data.sync.auth.SyncDiscoveryProblem
import mihon.data.sync.auth.SyncGitHubAccount
import mihon.data.sync.auth.SyncSpaceCreation
import mihon.data.sync.auth.SyncSpaceDiscovery
import mihon.data.sync.crypto.SyncSpaceCrypto
import mihon.data.sync.http.SyncHttpException
import mihon.data.sync.transport.GitHubSyncTransport
import mihon.domain.sync.crypto.SyncSpaceMaterial
import mihon.domain.sync.transport.SyncInitializationResult
import mihon.domain.sync.transport.SyncRepository
import okhttp3.OkHttpClient
import java.util.UUID

internal class SyncSetupException(val problem: SyncDiscoveryProblem) : IllegalStateException("sync setup failed")

internal sealed interface SyncSetupOutcome {
    data class Connected(val setup: StoredSyncSetup) : SyncSetupOutcome
    data class Existing(val space: DiscoveredSyncSpace) : SyncSetupOutcome
}

/** Durable setup intent is saved before any GitHub mutation, independently of panel lifetime. */
internal class SyncOnboarding(
    private val runtime: SyncRuntime,
    private val client: OkHttpClient,
    private val apiBaseUrl: String,
    val storage: SyncSetupStorage,
) {
    suspend fun discover(): SyncSpaceDiscovery = spaces(runtime.accessToken()).discover()

    suspend fun pending(): StoredSyncSetup? = storage.pending(session().account.id)

    suspend fun create(account: SyncGitHubAccount, password: String): StoredSyncSetup {
        SyncSpaceCrypto.validatePassword(password)
        session(account.id)
        storage.pending(account.id)?.let { return it }
        val material = SyncSpaceCrypto.create(UUID.randomUUID().toString(), 1, password)
        return StoredSyncSetup(
            accountId = account.id,
            accountLogin = account.login,
            attemptId = UUID.randomUUID().toString(),
            newSpace = true,
            material = StoredSyncMaterial.from(material),
            owner = account.login,
            repository = GitHubSyncSpaceClient.REPOSITORY_NAME,
            branch = GitHubSyncSpaceClient.BRANCH,
        ).also { storage.save(it, null) }
    }

    suspend fun join(space: DiscoveredSyncSpace, material: SyncSpaceMaterial): StoredSyncSetup {
        require(material.descriptor == space.descriptor)
        session(space.account.id)
        val pending = storage.pending(space.account.id)
        if (pending != null) {
            if (pending.material == StoredSyncMaterial.from(material) && pending.repositoryId == space.repositoryId) {
                return pending
            }
            storage.clear(pending)
        }
        return StoredSyncSetup(
            accountId = space.account.id,
            accountLogin = space.account.login,
            attemptId = UUID.randomUUID().toString(),
            newSpace = false,
            material = StoredSyncMaterial.from(material),
            repositoryId = space.repositoryId,
            owner = space.repository.owner,
            repository = space.repository.name,
            branch = space.repository.branch,
        ).also { storage.save(it, null) }
    }

    suspend fun resume(initial: StoredSyncSetup): SyncSetupOutcome {
        var setup = storage.pending(initial.accountId)
            ?.takeIf { it.attemptId == initial.attemptId } ?: throw IllegalStateException("sync setup changed")
        val session = session(setup.accountId)
        val material = setup.material.material()
        if (setup.newSpace && !setup.connected) {
            when (
                val created = spaces(session.token).createOrResume(setup.attempt()) { attempt ->
                    val next = setup.copy(submitted = attempt.submitted, repositoryId = attempt.repositoryId)
                    storage.save(next, setup)
                    setup = next
                }
            ) {
                is SyncSpaceCreation.Failed -> throw SyncSetupException(created.problem)
                is SyncSpaceCreation.Existing -> {
                    if (created.space.descriptor != material.descriptor ||
                        (setup.repositoryId != null && setup.repositoryId != created.space.repositoryId)
                    ) {
                        storage.clear(setup)
                        return SyncSetupOutcome.Existing(created.space)
                    }
                    val next = setup.copy(repositoryId = created.space.repositoryId)
                    storage.save(next, setup)
                    setup = next
                }
                is SyncSpaceCreation.Ready -> {
                    val next = setup.copy(repositoryId = created.repositoryId)
                    storage.save(next, setup)
                    setup = next
                    verifyRepository(session, setup.repository(), created.repositoryId)
                    when (
                        transport(session.token, material).initialize(
                            setup.repository(),
                            material.descriptor.spaceId,
                            material.descriptor.generation,
                        )
                    ) {
                        is SyncInitializationResult.Initialized, is SyncInitializationResult.Adopted -> Unit
                        else -> {
                            val found = spaces(session.token).discover(setup.accountId)
                            if (found is SyncSpaceDiscovery.Found && found.space.descriptor != material.descriptor) {
                                storage.clear(setup)
                                return SyncSetupOutcome.Existing(found.space)
                            }
                            throw SyncSetupException(SyncDiscoveryProblem.RETRYABLE)
                        }
                    }
                }
            }
        }
        verifyRepository(session, setup.repository(), requireNotNull(setup.repositoryId))
        transport(session.token, material).readSnapshot(
            setup.repository(),
            material.descriptor.spaceId,
            material.descriptor.generation,
        ).getOrThrow()
        runtime.bindSetup(setup)
        val connected = setup.copy(connected = true)
        storage.save(connected, setup)
        return SyncSetupOutcome.Connected(connected)
    }

    suspend fun complete(setup: StoredSyncSetup) = storage.clear(setup)

    suspend fun session(expectedAccountId: Long? = null): Session {
        val token = runtime.accessToken()
        val http = GitHubPrivateRepositorySelector(client, { token }, apiBaseUrl)
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
    }

    fun transport(token: String, material: SyncSpaceMaterial) = GitHubSyncTransport(
        client,
        { token },
        apiBaseUrl,
        spaceMaterial = material,
    )

    private fun spaces(token: String) = GitHubSyncSpaceClient(client, { token }, apiBaseUrl)

    class Session(val account: SyncGitHubAccount, val token: String, val http: GitHubPrivateRepositorySelector) {
        override fun toString(): String = "SyncSession(<redacted>)"
    }
}
