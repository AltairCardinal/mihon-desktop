package mihon.data.sync.auth

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import mihon.data.sync.http.SyncHttpException
import mihon.data.sync.http.SyncHttpResponse
import mihon.domain.sync.auth.GitHubAuthException
import mihon.domain.sync.auth.GitHubAuthFailureReason
import mihon.domain.sync.crypto.SyncSpaceDescriptor
import mihon.domain.sync.crypto.SyncSpaceDescriptorCodec
import mihon.domain.sync.transport.SyncRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.decodeBase64

data class SyncGitHubAccount(val id: Long, val login: String)

data class DiscoveredSyncSpace(
    val account: SyncGitHubAccount,
    val repositoryId: Long,
    val repository: SyncRepository,
    val descriptor: SyncSpaceDescriptor,
    val head: String,
)

enum class SyncDiscoveryProblem {
    REPOSITORY_NOT_PRIVATE,
    AUTHORIZATION_REQUIRED,
    RATE_LIMITED,
    RETRYABLE,
    MALFORMED,
    INCOMPATIBLE,
    NAME_OCCUPIED,
    CREATION_UNCONFIRMED,
    ACCOUNT_CHANGED,
    MULTIPLE_SPACES,
}

sealed interface SyncSpaceDiscovery {
    data class Found(val space: DiscoveredSyncSpace) : SyncSpaceDiscovery
    data class Multiple(val spaces: List<DiscoveredSyncSpace>) : SyncSpaceDiscovery
    data class NoVisibleSpace(val account: SyncGitHubAccount) : SyncSpaceDiscovery
    data class Failed(val problem: SyncDiscoveryProblem) : SyncSpaceDiscovery
}

data class SyncCreationAttempt(
    val account: SyncGitHubAccount,
    val attemptId: String,
    val submitted: Boolean = false,
    val repositoryId: Long? = null,
)

sealed interface SyncSpaceCreation {
    data class Ready(val repository: SyncRepository, val repositoryId: Long) : SyncSpaceCreation
    data class Existing(val space: DiscoveredSyncSpace) : SyncSpaceCreation
    data class Failed(val problem: SyncDiscoveryProblem) : SyncSpaceCreation
}

/** Uses the application's network configuration and GitHub user authorization. */
class GitHubSyncSpaceClient(
    private val productionClient: OkHttpClient,
    private val accessToken: suspend () -> String,
    private val apiBaseUrl: String = "https://api.github.com",
) {
    suspend fun discover(expectedAccountId: Long? = null): SyncSpaceDiscovery = try {
        val scan = session().scan(expectedAccountId)
        when {
            scan.spaces.size > 1 -> SyncSpaceDiscovery.Multiple(scan.spaces)
            scan.spaces.size == 1 -> SyncSpaceDiscovery.Found(scan.spaces.single())
            scan.target != null -> SyncSpaceDiscovery.Failed(scan.target.problem())
            else -> SyncSpaceDiscovery.NoVisibleSpace(scan.account)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        SyncSpaceDiscovery.Failed(error.problem())
    }

    suspend fun createOrResume(
        attempt: SyncCreationAttempt,
        persist: suspend (SyncCreationAttempt) -> Unit,
    ): SyncSpaceCreation = try {
        require(attempt.attemptId.matches(Regex("[A-Za-z0-9_-]{16,128}")))
        val session = session()
        val scan = session.scan(attempt.account.id)
        when {
            scan.spaces.size > 1 -> SyncSpaceCreation.Failed(SyncDiscoveryProblem.MULTIPLE_SPACES)
            scan.spaces.size == 1 -> SyncSpaceCreation.Existing(scan.spaces.single())
            scan.account != attempt.account -> SyncSpaceCreation.Failed(SyncDiscoveryProblem.ACCOUNT_CHANGED)
            scan.target != null -> session.resume(scan.target, attempt, persist)
            attempt.submitted -> SyncSpaceCreation.Failed(SyncDiscoveryProblem.CREATION_UNCONFIRMED)
            else -> {
                // Durable intent precedes the only POST. A process restart must first reconcile it.
                val submitted = attempt.copy(submitted = true)
                persist(submitted)
                val payload = buildJsonObject {
                    put("name", REPOSITORY_NAME)
                    put("private", true)
                    put("auto_init", false)
                    put("description", marker(attempt))
                }.toString().toRequestBody("application/json".toMediaType())
                val response = try {
                    session.api.requestPath("/user/repos", "POST", payload)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                when (response?.code) {
                    201 -> {
                        val repository = session.repository(response.objectBody(), scan.account)
                        require(
                            repository.repository.name == REPOSITORY_NAME && repository.description == marker(attempt),
                        )
                        require(repository.size == 0L)
                        persist(submitted.copy(repositoryId = repository.id))
                        SyncSpaceCreation.Ready(repository.repository, repository.id)
                    }
                    401, 403, 429 -> {
                        // A definite rejection is safe to retry after authorization/rate-limit recovery.
                        persist(attempt)
                        SyncSpaceCreation.Failed(response.failure())
                    }
                    else -> {
                        // Includes 422 (which is not necessarily a name conflict), 5xx and lost responses.
                        val after = session.scan(attempt.account.id)
                        when {
                            after.spaces.size > 1 -> SyncSpaceCreation.Failed(SyncDiscoveryProblem.MULTIPLE_SPACES)
                            after.spaces.size == 1 -> SyncSpaceCreation.Existing(after.spaces.single())
                            after.target != null -> session.resume(after.target, submitted, persist)
                            else -> SyncSpaceCreation.Failed(SyncDiscoveryProblem.CREATION_UNCONFIRMED)
                        }
                    }
                }
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        SyncSpaceCreation.Failed(error.problem())
    }

    private suspend fun session(): Session {
        val token = accessToken().takeIf { it.isNotBlank() } ?: fail(SyncDiscoveryProblem.AUTHORIZATION_REQUIRED)
        // Capture one authorization for the entire operation; never mix requests across logins.
        return Session(token)
    }

    private inner class Session(token: String) {
        val api = GitHubPrivateRepositorySelector(productionClient, { token }, apiBaseUrl)

        suspend fun scan(expectedAccountId: Long?): Scan {
            val user = get("/user").checked().objectBody()
            require(user.string("type") == "User")
            val account = SyncGitHubAccount(user.number("id"), user.string("login"))
            require(account.id > 0)
            SyncRepository(account.login, REPOSITORY_NAME, BRANCH)
            if (expectedAccountId != null && expectedAccountId != account.id) fail(SyncDiscoveryProblem.ACCOUNT_CHANGED)
            val installs = api.objects("/user/installations?per_page=100&page=1", "installations")
                .filter {
                    it.string("app_slug") == "mihon-desktop" && it.obj("account").number("id") == account.id &&
                        it.obj("account").string("type") == "User"
                }
            if (installs.size != 1) fail(SyncDiscoveryProblem.AUTHORIZATION_REQUIRED)
            val installation = installs.single()
            if (installation["suspended_at"] != JsonNull ||
                installation.obj("permissions").optionalString("administration") != "write" ||
                installation.obj("permissions").optionalString("contents") != "write"
            ) {
                fail(SyncDiscoveryProblem.AUTHORIZATION_REQUIRED)
            }
            require(installation.string("repository_selection") in setOf("all", "selected"))
            val id = installation.number("id")
            require(id > 0)
            val repositories = api.objects("/user/installations/$id/repositories?per_page=100&page=1", "repositories")
            val owned = repositories.filter { it.obj("owner").number("id") == account.id }
            val candidates = linkedMapOf<Long, Repository>()
            for (item in owned) {
                if (!item.boolean("private")) {
                    if (item.string("name") == REPOSITORY_NAME) fail(SyncDiscoveryProblem.NAME_OCCUPIED)
                    continue
                }
                val repo = repository(item, account)
                require(candidates.put(repo.id, repo) == null)
            }
            // Check the canonical name even if it was omitted from the installed repository list.
            val targetResponse = get("/repos/${account.login}/$REPOSITORY_NAME")
            val target = when (targetResponse.code) {
                404 -> candidates.values.firstOrNull { it.repository.name == REPOSITORY_NAME }
                200 -> repository(targetResponse.objectBody(), account).also {
                    require(it.repository.name == REPOSITORY_NAME)
                    candidates[it.id] = it
                }
                else -> fail(targetResponse.failure())
            }
            val found = candidates.values.mapNotNull { inspect(it, account) }
            return Scan(account, found, target)
        }

        fun repository(value: JsonObject, account: SyncGitHubAccount): Repository {
            val owner = value.obj("owner")
            require(owner.number("id") == account.id && owner.string("type") == "User")
            require(owner.string("login").equals(account.login, ignoreCase = true))
            if (!value.boolean("private")) fail(SyncDiscoveryProblem.NAME_OCCUPIED)
            val permissions = value["permissions"] as? JsonObject
            if (permissions?.get("push") != JsonPrimitive(true) && permissions?.get("admin") != JsonPrimitive(true)) {
                fail(SyncDiscoveryProblem.AUTHORIZATION_REQUIRED)
            }
            val repo = SyncRepository(account.login, value.string("name"), BRANCH)
            require(value.string("full_name").equals(repo.fullName, ignoreCase = true))
            val id = value.number("id")
            val size = value.number("size")
            require(id > 0 && size >= 0)
            val defaultBranch = value.string("default_branch")
            SyncRepository(account.login, repo.name, defaultBranch)
            return Repository(id, repo, value.optionalString("description"), size, defaultBranch)
        }

        suspend fun resume(
            repository: Repository,
            attempt: SyncCreationAttempt,
            persist: suspend (SyncCreationAttempt) -> Unit,
        ): SyncSpaceCreation {
            if (!attempt.submitted || repository.description != marker(attempt)) {
                return SyncSpaceCreation.Failed(repository.problem())
            }
            if (attempt.repositoryId != null &&
                attempt.repositoryId != repository.id
            ) {
                fail(SyncDiscoveryProblem.NAME_OCCUPIED)
            }
            // Both initial branches must be ours; a clean default branch does not establish ownership
            // of the synchronization branch. Transport checks again immediately before initialization.
            verifyBootstrapBranch(repository, repository.defaultBranch, allowMissing = repository.size == 0L)
            if (repository.defaultBranch != BRANCH) {
                verifyBootstrapBranch(repository, BRANCH, allowMissing = true)
            }
            persist(attempt.copy(repositoryId = repository.id))
            return SyncSpaceCreation.Ready(repository.repository, repository.id)
        }

        private suspend fun verifyBootstrapBranch(repository: Repository, name: String, allowMissing: Boolean) {
            val branch = get("/repos/${repository.repository.fullName}/git/ref/heads/$name")
            if (branch.code in setOf(404, 409)) {
                if (!allowMissing || (branch.code == 409 && repository.size != 0L)) {
                    fail(SyncDiscoveryProblem.CREATION_UNCONFIRMED)
                }
            } else {
                val base = "/repos/${repository.repository.fullName}"
                val head = branch.checked().objectBody().obj("object").string("sha").also(::checkSha)
                val commit = get("$base/git/commits/$head").checked().objectBody()
                val treeSha = commit.obj("tree").string("sha").also(::checkSha)
                val tree = get("$base/git/trees/$treeSha?recursive=1").checked().objectBody()
                val entries = tree["tree"]!!.jsonArray.map { it.jsonObject }
                if (tree.boolean("truncated") ||
                    !isBootstrapTree(entries)
                ) {
                    fail(SyncDiscoveryProblem.CREATION_UNCONFIRMED)
                }
                val sha = entries.single { it.string("path") == BOOTSTRAP_PATH }.string("sha").also(::checkSha)
                val blob = get("$base/git/blobs/$sha").checked().objectBody()
                require(blob.string("encoding") == "base64")
                val content = blob.string("content").replace("\n", "").decodeBase64()?.toByteArray()
                if (content == null || !content.contentEquals("mihon-sync bootstrap".encodeToByteArray())) {
                    fail(SyncDiscoveryProblem.CREATION_UNCONFIRMED)
                }
            }
        }

        private suspend fun inspect(repository: Repository, account: SyncGitHubAccount): DiscoveredSyncSpace? {
            val base = "/repos/${repository.repository.fullName}"
            val ref = get("$base/git/ref/heads/$BRANCH")
            if (ref.code == 404 || (ref.code == 409 && repository.size == 0L)) return null
            val head = ref.checked().objectBody().obj("object").string("sha").also(::checkSha)
            val commit = get("$base/git/commits/$head").checked().objectBody()
            val treeSha = commit.obj("tree").string("sha").also(::checkSha)
            val tree = get("$base/git/trees/$treeSha?recursive=1").checked().objectBody()
            require(!tree.boolean("truncated"))
            val entries = tree["tree"]!!.jsonArray.map { it.jsonObject }
            require(entries.size <= 20_000)
            val paths = entries.map { it.string("path") }
            require(paths.distinct().size == paths.size)
            val descriptor = entries.singleOrNull { it.string("path") == SyncSpaceDescriptorCodec.PATH }
            if (descriptor == null) {
                if (repository.description?.startsWith(MARKER_PREFIX) == true && isBootstrapTree(entries)) return null
                if (paths.any { it.startsWith(".mihon-sync/") }) fail(SyncDiscoveryProblem.INCOMPATIBLE)
                return null
            }
            require(descriptor.string("type") == "blob" && descriptor.string("mode") == "100644")
            val sha = descriptor.string("sha").also(::checkSha)
            val blob = get("$base/git/blobs/$sha").checked().objectBody()
            require(blob.string("encoding") == "base64")
            val bytes = blob.string("content").replace("\n", "").decodeBase64()?.toByteArray() ?: error("invalid blob")
            val decoded = SyncSpaceDescriptorCodec.decode(bytes).getOrElse { fail(SyncDiscoveryProblem.INCOMPATIBLE) }
            return DiscoveredSyncSpace(account, repository.id, repository.repository, decoded, head)
        }

        private suspend fun get(path: String) = api.requestPath(path)
    }

    private data class Repository(
        val id: Long,
        val repository: SyncRepository,
        val description: String?,
        val size: Long,
        val defaultBranch: String,
    ) {
        fun problem() = if (description?.startsWith(MARKER_PREFIX) == true) {
            SyncDiscoveryProblem.CREATION_UNCONFIRMED
        } else {
            SyncDiscoveryProblem.NAME_OCCUPIED
        }
    }

    private data class Scan(
        val account: SyncGitHubAccount,
        val spaces: List<DiscoveredSyncSpace>,
        val target: Repository?,
    )

    companion object {
        const val REPOSITORY_NAME = "mihon-sync"
        const val BRANCH = "mihon-sync-v1"
        private const val MARKER_PREFIX = "Mihon sync setup:"
        private const val BOOTSTRAP_PATH = ".mihon-sync/bootstrap"

        private fun marker(attempt: SyncCreationAttempt) = MARKER_PREFIX + attempt.attemptId

        private fun isBootstrapTree(entries: List<JsonObject>): Boolean =
            entries.count { it.string("path") == BOOTSTRAP_PATH } == 1 && entries.all {
                (it.string("path") == BOOTSTRAP_PATH && it.string("type") == "blob" && it.string("mode") == "100644") ||
                    (it.string("path") == ".mihon-sync" && it.string("type") == "tree" && it.string("mode") == "040000")
            }
    }
}

private class DiscoveryException(val problem: SyncDiscoveryProblem) : IllegalStateException("sync discovery failed")

private fun fail(problem: SyncDiscoveryProblem): Nothing = throw DiscoveryException(problem)

private fun Exception.problem(): SyncDiscoveryProblem = when (this) {
    is DiscoveryException -> problem
    is GitHubAuthException -> when (failure.reason) {
        GitHubAuthFailureReason.RATE_LIMITED -> SyncDiscoveryProblem.RATE_LIMITED
        GitHubAuthFailureReason.HTTP -> SyncDiscoveryProblem.RETRYABLE
        GitHubAuthFailureReason.MALFORMED_RESPONSE -> SyncDiscoveryProblem.MALFORMED
        else -> SyncDiscoveryProblem.AUTHORIZATION_REQUIRED
    }
    is SyncHttpException -> when {
        code == 429 || (code == 403 && retryable) -> SyncDiscoveryProblem.RATE_LIMITED
        code == 401 || code == 403 -> SyncDiscoveryProblem.AUTHORIZATION_REQUIRED
        retryable || code == null || code >= 500 -> SyncDiscoveryProblem.RETRYABLE
        else -> SyncDiscoveryProblem.MALFORMED
    }
    else -> SyncDiscoveryProblem.MALFORMED
}

private fun SyncHttpResponse.failure(): SyncDiscoveryProblem = when {
    code == 429 || (code == 403 && (headers["retry-after"] != null || headers["x-ratelimit-remaining"] == "0")) ->
        SyncDiscoveryProblem.RATE_LIMITED
    code == 401 || code == 403 -> SyncDiscoveryProblem.AUTHORIZATION_REQUIRED
    code >= 500 -> SyncDiscoveryProblem.RETRYABLE
    else -> SyncDiscoveryProblem.MALFORMED
}

private fun SyncHttpResponse.checked(): SyncHttpResponse = also { if (code != 200) fail(failure()) }

private fun SyncHttpResponse.objectBody(): JsonObject = Json.parseToJsonElement(
    body.decodeToString(throwOnInvalidSequence = true),
).jsonObject

private fun JsonObject.string(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }
        ?: error("missing string field")

private fun JsonObject.optionalString(name: String): String? = when (val value = this[name]) {
    null, JsonNull -> null
    else -> (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: error("invalid string field")
}

private fun JsonObject.obj(name: String): JsonObject = this[name] as? JsonObject ?: error("missing object field")

private fun JsonObject.number(name: String): Long =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: error("missing numeric field")

private fun JsonObject.boolean(name: String): Boolean =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: error("missing boolean field")

private fun checkSha(value: String) = require(value.matches(Regex("[a-f0-9]{40,64}")))
