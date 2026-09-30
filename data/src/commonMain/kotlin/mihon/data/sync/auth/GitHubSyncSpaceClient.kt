package mihon.data.sync.auth

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import mihon.data.sync.http.SyncHttpException
import mihon.data.sync.http.SyncHttpRequestGate
import mihon.data.sync.http.SyncHttpResponse
import mihon.domain.sync.auth.GitHubAuthException
import mihon.domain.sync.auth.GitHubAuthFailureReason
import mihon.domain.sync.crypto.SyncSpaceDescriptor
import mihon.domain.sync.crypto.SyncSpaceDescriptorCodec
import mihon.domain.sync.transport.SyncRepository
import mihon.domain.sync.transport.SyncRepositoryTarget
import okhttp3.OkHttpClient
import okio.ByteString.Companion.decodeBase64

data class SyncGitHubAccount(val id: Long, val login: String)

enum class SyncRepositorySelection { ALL, SELECTED }
enum class SyncInstallationAccountType { USER, ORGANIZATION }

data class SyncAppInstallation(
    val id: Long,
    val repositorySelection: SyncRepositorySelection,
    val authorizedRepositoryCount: Int? = null,
    val accountType: SyncInstallationAccountType = SyncInstallationAccountType.USER,
)

data class DiscoveredSyncSpace(
    val account: SyncGitHubAccount,
    val repositoryId: Long,
    val repository: SyncRepository,
    val descriptor: SyncSpaceDescriptor,
    val head: String,
    val installation: SyncAppInstallation? = null,
)

data class EmptySyncRepositoryCandidate(
    val account: SyncGitHubAccount,
    val repositoryId: Long,
    val repository: SyncRepository,
    val defaultBranch: String,
    val installation: SyncAppInstallation? = null,
)

enum class SyncDiscoveryProblem {
    NEEDS_INSTALLATION,
    NEEDS_REPOSITORY_ACCESS,
    NEEDS_CONTENTS_PERMISSION,
    INSTALLATION_SUSPENDED,
    REPOSITORY_NOT_WRITABLE,
    REPOSITORY_UNAVAILABLE,
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
    data class NeedsInstallation(val account: SyncGitHubAccount) : SyncSpaceDiscovery
    data class NeedsRepositoryAccess(
        val account: SyncGitHubAccount,
        val installation: SyncAppInstallation? = null,
    ) : SyncSpaceDiscovery
    data class NeedsContentsPermission(
        val account: SyncGitHubAccount,
        val installation: SyncAppInstallation? = null,
    ) : SyncSpaceDiscovery
    data class InstallationSuspended(
        val account: SyncGitHubAccount,
        val installation: SyncAppInstallation? = null,
    ) : SyncSpaceDiscovery
    data class EmptyRepository(val candidate: EmptySyncRepositoryCandidate) : SyncSpaceDiscovery
    data class NoVisibleSpace(
        val account: SyncGitHubAccount,
        val installation: SyncAppInstallation? = null,
    ) : SyncSpaceDiscovery
    data class Failed(
        val problem: SyncDiscoveryProblem,
        val account: SyncGitHubAccount? = null,
        val installation: SyncAppInstallation? = null,
    ) : SyncSpaceDiscovery
}

data class SyncCreationAttempt(
    val account: SyncGitHubAccount,
    val attemptId: String,
    val submitted: Boolean = false,
    val repositoryId: Long? = null,
)

sealed interface SyncSpaceCreation {
    data class Ready(
        val repository: SyncRepository,
        val repositoryId: Long,
        val defaultBranch: String,
    ) : SyncSpaceCreation
    data class Existing(val space: DiscoveredSyncSpace) : SyncSpaceCreation
    data class Failed(val problem: SyncDiscoveryProblem) : SyncSpaceCreation
}

/** Uses the application's network configuration and GitHub user authorization. */
class GitHubSyncSpaceClient(
    private val productionClient: OkHttpClient,
    private val accessToken: suspend () -> String,
    private val apiBaseUrl: String = "https://api.github.com",
    private val requestGate: SyncHttpRequestGate? = null,
    private val repositoryScope: SyncRepositoryScope = SyncRepositoryScope.Default,
) {
    suspend fun discover(expectedAccountId: Long? = null): SyncSpaceDiscovery = try {
        val scan = session().scan(expectedAccountId)
        when {
            scan.spaces.size > 1 -> SyncSpaceDiscovery.Multiple(scan.spaces)
            scan.emptyCandidate != null -> SyncSpaceDiscovery.EmptyRepository(scan.emptyCandidate)
            scan.spaces.size == 1 -> SyncSpaceDiscovery.Found(scan.spaces.single())
            scan.target != null -> SyncSpaceDiscovery.Failed(
                scan.target.problem(),
                scan.account,
                scan.installation,
            )
            scan.targetProblem != null -> discoveryResult(scan.account, scan.targetProblem, scan.installation)
            else -> SyncSpaceDiscovery.NoVisibleSpace(scan.account, scan.installation)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: DiscoveryException) {
        discoveryResult(error.account, error.problem, error.installation)
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
            scan.account != attempt.account -> SyncSpaceCreation.Failed(SyncDiscoveryProblem.ACCOUNT_CHANGED)
            attempt.submitted -> SyncSpaceCreation.Failed(SyncDiscoveryProblem.CREATION_UNCONFIRMED)
            scan.emptyCandidate != null && attempt.repositoryId != null &&
                attempt.repositoryId != scan.emptyCandidate.repositoryId ->
                SyncSpaceCreation.Failed(SyncDiscoveryProblem.ACCOUNT_CHANGED)
            scan.emptyCandidate != null -> {
                val confirmed = attempt.copy(repositoryId = scan.emptyCandidate.repositoryId)
                persist(confirmed)
                SyncSpaceCreation.Ready(
                    scan.emptyCandidate.repository,
                    scan.emptyCandidate.repositoryId,
                    scan.emptyCandidate.defaultBranch,
                )
            }
            scan.spaces.size == 1 -> SyncSpaceCreation.Existing(scan.spaces.single())
            scan.target == null -> SyncSpaceCreation.Failed(SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS)
            else -> SyncSpaceCreation.Failed(scan.target.problem())
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
        val api = GitHubPrivateRepositorySelector(productionClient, { token }, apiBaseUrl, requestGate)

        suspend fun scan(expectedAccountId: Long?): Scan {
            val user = get("/user").checked().objectBody()
            require(user.string("type") == "User")
            val account = SyncGitHubAccount(user.number("id"), user.string("login"))
            require(account.id > 0)
            SyncRepository(account.login, repositoryScope.repositoryName, BRANCH)
            if (expectedAccountId != null && expectedAccountId != account.id) fail(SyncDiscoveryProblem.ACCOUNT_CHANGED)
            val installs = api.objects("/user/installations?per_page=100&page=1", "installations")
                .filter {
                    it.string("app_slug") == "mihon-desktop" && it.obj("account").number("id") == account.id &&
                        it.obj("account").string("type") == "User"
                }
            if (installs.isEmpty()) fail(SyncDiscoveryProblem.NEEDS_INSTALLATION, account)
            if (installs.size != 1) fail(SyncDiscoveryProblem.MALFORMED, account)
            val installation = installs.single()
            val installationId = installation.number("id")
            require(installationId > 0)
            val installationAccount = installation.obj("account")
            val accountType = when (installationAccount.string("type")) {
                "User" -> SyncInstallationAccountType.USER
                "Organization" -> SyncInstallationAccountType.ORGANIZATION
                else -> error("invalid installation account type")
            }
            require(installationAccount.number("id") == account.id)
            val repositorySelection = when (installation.string("repository_selection")) {
                "all" -> SyncRepositorySelection.ALL
                "selected" -> SyncRepositorySelection.SELECTED
                else -> error("invalid repository selection")
            }
            val initialInstallation =
                SyncAppInstallation(installationId, repositorySelection, accountType = accountType)
            when (val suspendedAt = installation["suspended_at"]) {
                null -> fail(SyncDiscoveryProblem.MALFORMED, account, initialInstallation)
                JsonNull -> Unit
                is JsonPrimitive -> {
                    if (!suspendedAt.isString || suspendedAt.contentOrNull.isNullOrBlank()) {
                        fail(SyncDiscoveryProblem.MALFORMED, account, initialInstallation)
                    }
                    fail(SyncDiscoveryProblem.INSTALLATION_SUSPENDED, account, initialInstallation)
                }
                else -> fail(SyncDiscoveryProblem.MALFORMED, account, initialInstallation)
            }
            if (installation.obj("permissions").optionalString("contents") != "write") {
                fail(SyncDiscoveryProblem.NEEDS_CONTENTS_PERMISSION, account, initialInstallation)
            }
            val repositories = api.objects(
                "/user/installations/$installationId/repositories?per_page=100&page=1",
                "repositories",
            )
            val appInstallation = initialInstallation.copy(authorizedRepositoryCount = repositories.size)
            val visible = if (repositoryScope.isolated) {
                repositories.filter { it.string("name") == repositoryScope.repositoryName }
            } else {
                repositories
            }
            val owned = visible.filter {
                it.obj("owner").number("id") == account.id && it.obj("owner").string("type") == "User"
            }
            val candidates = linkedMapOf<Long, Repository>()
            for (item in owned) {
                val name = item.string("name")
                if (!repositoryScope.accepts(name)) continue
                if (!item.boolean("private")) {
                    if (name == repositoryScope.repositoryName) {
                        fail(SyncDiscoveryProblem.REPOSITORY_NOT_PRIVATE, account, appInstallation)
                    }
                    continue
                }
                if (!writable(item)) {
                    if (name == repositoryScope.repositoryName) {
                        fail(SyncDiscoveryProblem.REPOSITORY_NOT_WRITABLE, account, appInstallation)
                    }
                    continue
                }
                val repo = repository(item, account, appInstallation)
                if (repo.archived || repo.disabled) continue
                require(candidates.put(repo.id, repo) == null)
            }
            val listedTarget = candidates.values.any { it.repository.name == repositoryScope.repositoryName }
            // A direct 404 is ambiguous only when installation listing omitted the fixed target.
            val targetResponse = get("/repos/${account.login}/${repositoryScope.repositoryName}")
            val target = when (targetResponse.code) {
                404 -> if (listedTarget) fail(SyncDiscoveryProblem.RETRYABLE, account, appInstallation) else null
                200 -> repository(targetResponse.objectBody(), account, appInstallation).also {
                    if (it.repository.name != repositoryScope.repositoryName) {
                        fail(SyncDiscoveryProblem.MALFORMED, account, appInstallation)
                    }
                    if (candidates[it.id] == null) {
                        fail(SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS, account, appInstallation)
                    }
                    val listed = candidates.getValue(it.id)
                    if (listed.repository != it.repository || listed.defaultBranch != it.defaultBranch) {
                        fail(SyncDiscoveryProblem.RETRYABLE, account, appInstallation)
                    }
                    it
                }
                else -> fail(targetResponse.failure(), account, appInstallation)
            }
            val found = candidates.values.mapNotNull { inspect(it, account, appInstallation) }
            val emptyCandidate = target?.let { verifyEmptyRepository(it, account, appInstallation) }
            return Scan(
                account,
                found,
                target,
                emptyCandidate,
                appInstallation,
                if (target == null) SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS else null,
            )
        }

        fun repository(
            value: JsonObject,
            account: SyncGitHubAccount,
            installation: SyncAppInstallation? = null,
        ): Repository {
            val owner = value.obj("owner")
            require(owner.number("id") == account.id && owner.string("type") == "User")
            require(owner.string("login").equals(account.login, ignoreCase = true))
            if (!value.boolean("private")) {
                fail(SyncDiscoveryProblem.REPOSITORY_NOT_PRIVATE, account, installation)
            }
            if (!writable(value)) fail(SyncDiscoveryProblem.REPOSITORY_NOT_WRITABLE, account, installation)
            val repo = SyncRepository(account.login, value.string("name"), BRANCH)
            val archived = value.boolean("archived")
            val disabled = value.boolean("disabled")
            if (repo.name == repositoryScope.repositoryName && (archived || disabled)) {
                fail(SyncDiscoveryProblem.REPOSITORY_UNAVAILABLE, account, installation)
            }
            require(value.string("full_name").equals(repo.fullName, ignoreCase = true))
            val id = value.number("id")
            val size = value.number("size")
            require(id > 0 && size >= 0)
            val defaultBranch = value.string("default_branch")
            SyncRepository(account.login, repo.name, defaultBranch)
            return Repository(id, repo, size, defaultBranch, archived, disabled)
        }

        private suspend fun verifyEmptyRepository(
            repository: Repository,
            account: SyncGitHubAccount,
            installation: SyncAppInstallation,
        ): EmptySyncRepositoryCandidate? {
            if (repository.repository.name != repositoryScope.repositoryName || repository.size != 0L) return null
            val base = "/repos/${repository.repository.fullName}"

            // GitHub's matching-refs endpoint without a prefix includes every namespace, including notes and stashes.
            // Preserve the verified account and installation when the response is malformed or unavailable so
            // the UI can offer a targeted recheck instead of losing the management context.
            val matchingRefs = try {
                api.matchingRefs("$base/git/matching-refs/")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail(error.problem(), account, installation)
            }
            if (matchingRefs.isNotEmpty()) return null

            val defaultRef = get("$base/git/ref/heads/${repository.defaultBranch}")
            when (defaultRef.code) {
                200 -> return null
                // A missing default branch is not proof of an empty repository. The complete ref
                // listings and root Contents response below must independently confirm it.
                404 -> Unit
                409 -> if (!defaultRef.hasExplicitEmptyRepositoryMessage()) {
                    fail(SyncDiscoveryProblem.RETRYABLE, account, installation)
                }
                else -> fail(defaultRef.failure(), account, installation)
            }

            // GitHub reports an empty repository as 404 from Contents in current fixtures. Accept an empty array
            // too, but only after the complete branch/tag checks above; other statuses remain retryable failures.
            val contents = get("$base/contents/?ref=${repository.defaultBranch}")
            when (contents.code) {
                200 -> {
                    val root = try {
                        Json.parseToJsonElement(contents.body.decodeToString(throwOnInvalidSequence = true)).jsonArray
                    } catch (_: Exception) {
                        fail(SyncDiscoveryProblem.MALFORMED, account, installation)
                    }
                    if (root.isNotEmpty()) return null
                }
                404 -> if (!contents.hasExplicitEmptyRepositoryMessage()) {
                    fail(SyncDiscoveryProblem.RETRYABLE, account, installation)
                }
                else -> fail(contents.failure(), account, installation)
            }
            return EmptySyncRepositoryCandidate(
                account,
                repository.id,
                repository.repository,
                repository.defaultBranch,
                installation,
            )
        }

        private fun writable(value: JsonObject): Boolean {
            val permissions = value["permissions"] as? JsonObject ?: return false
            return permissions["push"] == JsonPrimitive(true) || permissions["admin"] == JsonPrimitive(true)
        }

        private suspend fun inspect(
            repository: Repository,
            account: SyncGitHubAccount,
            installation: SyncAppInstallation,
        ): DiscoveredSyncSpace? {
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
                if (paths.any { it.startsWith(".mihon-sync/") }) {
                    fail(SyncDiscoveryProblem.INCOMPATIBLE, account, installation)
                }
                return null
            }
            require(descriptor.string("type") == "blob" && descriptor.string("mode") == "100644")
            val sha = descriptor.string("sha").also(::checkSha)
            val blob = get("$base/git/blobs/$sha").checked().objectBody()
            require(blob.string("encoding") == "base64")
            val bytes = blob.string("content").replace("\n", "").decodeBase64()?.toByteArray() ?: error("invalid blob")
            val decoded = SyncSpaceDescriptorCodec.decode(bytes).getOrElse {
                fail(SyncDiscoveryProblem.INCOMPATIBLE, account, installation)
            }
            return DiscoveredSyncSpace(account, repository.id, repository.repository, decoded, head, installation)
        }

        private suspend fun get(path: String) = api.requestPath(path)
    }

    private data class Repository(
        val id: Long,
        val repository: SyncRepository,
        val size: Long,
        val defaultBranch: String,
        val archived: Boolean,
        val disabled: Boolean,
    ) {
        fun problem() = SyncDiscoveryProblem.NAME_OCCUPIED
    }

    private data class Scan(
        val account: SyncGitHubAccount,
        val spaces: List<DiscoveredSyncSpace>,
        val target: Repository?,
        val emptyCandidate: EmptySyncRepositoryCandidate?,
        val installation: SyncAppInstallation,
        val targetProblem: SyncDiscoveryProblem?,
    )

    companion object {
        const val REPOSITORY_NAME = SyncRepositoryTarget.NAME
        const val BRANCH = SyncRepositoryTarget.BRANCH
    }
}

private class DiscoveryException(
    val problem: SyncDiscoveryProblem,
    val account: SyncGitHubAccount? = null,
    val installation: SyncAppInstallation? = null,
) : IllegalStateException("sync discovery failed")

private fun fail(
    problem: SyncDiscoveryProblem,
    account: SyncGitHubAccount? = null,
    installation: SyncAppInstallation? = null,
): Nothing = throw DiscoveryException(problem, account, installation)

private fun discoveryResult(
    account: SyncGitHubAccount?,
    problem: SyncDiscoveryProblem,
    installation: SyncAppInstallation? = null,
): SyncSpaceDiscovery =
    when (problem) {
        SyncDiscoveryProblem.NEEDS_INSTALLATION -> account?.let(SyncSpaceDiscovery::NeedsInstallation)
        SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS -> account?.let {
            SyncSpaceDiscovery.NeedsRepositoryAccess(it, installation)
        }
        SyncDiscoveryProblem.NEEDS_CONTENTS_PERMISSION -> account?.let {
            SyncSpaceDiscovery.NeedsContentsPermission(it, installation)
        }
        SyncDiscoveryProblem.INSTALLATION_SUSPENDED -> account?.let {
            SyncSpaceDiscovery.InstallationSuspended(it, installation)
        }
        else -> null
    } ?: SyncSpaceDiscovery.Failed(problem, account, installation)

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
