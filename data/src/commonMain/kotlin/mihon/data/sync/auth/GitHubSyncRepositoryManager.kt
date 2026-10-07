package mihon.data.sync.auth

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import mihon.data.sync.http.SyncHttpException
import mihon.data.sync.http.SyncHttpFailureClass
import mihon.data.sync.http.SyncHttpRequestGate
import mihon.data.sync.http.SyncHttpResponse
import mihon.data.sync.http.requireSyncSuccess
import mihon.domain.sync.transport.SyncRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class SyncRepositoryCreationIntent(
    val account: SyncGitHubAccount,
    val repositoryName: String,
    val attemptId: String,
    val submitted: Boolean = false,
    val repositoryId: Long? = null,
    val creationAdminConfirmed: Boolean = false,
    val manuallyConfirmed: Boolean = false,
)

data class SyncRepositoryRepairTarget(
    val account: SyncGitHubAccount,
    val repository: SyncRepository,
    val repositoryId: Long,
)

/** All mutations require caller confirmation and use the production, account-gated HTTP path. */
class GitHubSyncRepositoryManager(
    private val productionClient: OkHttpClient,
    private val accessToken: suspend () -> String,
    private val apiBaseUrl: String = "https://api.github.com",
    private val requestGate: SyncHttpRequestGate? = null,
) {
    suspend fun createOrResume(
        intent: SyncRepositoryCreationIntent,
        persist: suspend (SyncRepositoryCreationIntent) -> Unit,
    ): SyncSpaceCreation = try {
        require(intent.attemptId.matches(Regex("[A-Za-z0-9_-]{16,128}")))
        val repository = SyncRepository(intent.account.login, intent.repositoryName, GitHubSyncSpaceClient.BRANCH)
        val api = session(intent.account)
        var current = intent
        val marker = "Mihon sync setup:${intent.attemptId}"
        var response = api.requestPath("/repos/${repository.fullName}")
        if (response.code == 404) {
            if (current.submitted) {
                if (current.repositoryId == null || !current.creationAdminConfirmed) {
                    failManagement(SyncDiscoveryProblem.CREATION_UNCONFIRMED)
                }
                ensureRepositoryAccess(
                    api,
                    SyncRepositoryRepairTarget(current.account, repository, requireNotNull(current.repositoryId)),
                    creationEvidence = current,
                )
                response = api.requestPath("/repos/${repository.fullName}")
                if (response.code == 404) failManagement(SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS)
            }
            if (!current.submitted) {
                current = current.copy(submitted = true)
                // The submit marker is durable before the first mutation, including cancellation windows.
                persistSafely(current, persist)
                val created = try {
                    api.requestPath(
                        "/user/repos",
                        "POST",
                        JsonObject(
                            mapOf(
                                "name" to JsonPrimitive(repository.name),
                                "private" to JsonPrimitive(true),
                                "auto_init" to JsonPrimitive(false),
                                "description" to JsonPrimitive(marker),
                            ),
                        ).toString().toRequestBody(JSON_MEDIA_TYPE),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (created != null && created.code !in 200..299 && created.code < 500) {
                    // These HTTP rejections confirm that this request did not create a repository.
                    current = current.copy(submitted = false)
                    persistSafely(current, persist)
                    failManagement(
                        if (created.code == 422) {
                            SyncDiscoveryProblem.NAME_OCCUPIED
                        } else {
                            created.managementProblem(SyncDiscoveryProblem.NEEDS_CREATION_PERMISSION)
                        },
                    )
                }
                if (created?.code in 200..299) {
                    val submitted = requireNotNull(created).managementObject()
                    verifyIdentity(submitted, intent.account, repository)
                    require(submitted.string("description") == marker)
                    if (!submitted.boolean("private")) failManagement(SyncDiscoveryProblem.REPOSITORY_NOT_PRIVATE)
                    current = current.copy(
                        repositoryId = submitted.number("id"),
                        creationAdminConfirmed =
                        (submitted["permissions"] as? JsonObject)?.get("admin") == JsonPrimitive(true),
                    )
                    persistSafely(current, persist)
                }
                if (current.repositoryId != null && current.creationAdminConfirmed) {
                    ensureRepositoryAccess(
                        api,
                        SyncRepositoryRepairTarget(current.account, repository, requireNotNull(current.repositoryId)),
                        creationEvidence = current,
                    )
                }
                // Do not trust a write response. Network/5xx uncertainty is resolved only by readback.
                response = api.requestPath("/repos/${repository.fullName}")
                if (response.code == 404) {
                    failManagement(
                        if (current.repositoryId != null) {
                            SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS
                        } else {
                            SyncDiscoveryProblem.CREATION_UNCONFIRMED
                        },
                    )
                }
            }
        }
        response.requireSyncSuccess()
        val named = response.managementObject()
        verifyIdentity(named, intent.account, repository, current.repositoryId)
        if (!current.manuallyConfirmed &&
            named.optionalString("description") != marker
        ) {
            failManagement(SyncDiscoveryProblem.NAME_OCCUPIED)
        }
        val id = named.number("id")
        if (!current.submitted) failManagement(SyncDiscoveryProblem.NAME_OCCUPIED)
        current = current.copy(repositoryId = id)
        persistSafely(current, persist)
        // Name reuse is never identity: after recording the id, read via the stable id endpoint.
        val fixed = api.requestPath("/repositories/$id").requireSyncSuccess().managementObject()
        verifyIdentity(fixed, intent.account, repository, id)
        if (!current.manuallyConfirmed &&
            fixed.optionalString("description") != marker
        ) {
            failManagement(SyncDiscoveryProblem.CREATION_UNCONFIRMED)
        }
        if (!fixed.boolean("private")) failManagement(SyncDiscoveryProblem.REPOSITORY_NOT_PRIVATE)
        if (fixed.boolean("disabled")) failManagement(SyncDiscoveryProblem.REPOSITORY_DISABLED)
        if (fixed.boolean("archived")) failManagement(SyncDiscoveryProblem.REPOSITORY_ARCHIVED)
        if (fixed.number("size") != 0L) failManagement(SyncDiscoveryProblem.NAME_OCCUPIED)
        val branch = fixed.string("default_branch")
        SyncRepository(repository.owner, repository.name, branch)
        if (api.matchingRefs("/repos/${repository.fullName}/git/matching-refs/").isNotEmpty()) {
            failManagement(SyncDiscoveryProblem.NAME_OCCUPIED)
        }
        val ref = api.requestPath("/repos/${repository.fullName}/git/ref/heads/$branch")
        if (ref.code == 200) failManagement(SyncDiscoveryProblem.NAME_OCCUPIED)
        if (ref.code != 404 && !(ref.code == 409 && ref.hasExplicitEmptyRepositoryMessage())) ref.requireSyncSuccess()
        val contents = api.requestPath("/repos/${repository.fullName}/contents/?ref=$branch")
        if (!(contents.code == 404 && contents.hasExplicitEmptyRepositoryMessage())) {
            contents.requireSyncSuccess()
            if (contents.body.decodeToString().trim() != "[]") failManagement(SyncDiscoveryProblem.NAME_OCCUPIED)
        }
        SyncSpaceCreation.Ready(repository, id, branch)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        SyncSpaceCreation.Failed(error.managementProblem())
    }

    suspend fun confirmManualSelection(
        intent: SyncRepositoryCreationIntent,
        persist: suspend (SyncRepositoryCreationIntent) -> Unit,
    ): SyncSpaceCreation = try {
        val api = session(intent.account)
        val repository = SyncRepository(intent.account.login, intent.repositoryName, GitHubSyncSpaceClient.BRANCH)
        val response = api.requestPath("/repos/${repository.fullName}")
        if (response.code == 404) failManagement(SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS)
        if (response.code ==
            403
        ) {
            failManagement(response.managementProblem(SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS))
        }
        val value = response.requireSyncSuccess().managementObject()
        verifyIdentity(value, intent.account, repository, intent.repositoryId)
        if (!value.boolean("private")) failManagement(SyncDiscoveryProblem.REPOSITORY_NOT_PRIVATE)
        val confirmed = intent.copy(
            submitted = true,
            repositoryId = value.number("id"),
            manuallyConfirmed = true,
            creationAdminConfirmed = (value["permissions"] as? JsonObject)?.get("admin") == JsonPrimitive(true),
        )
        persistSafely(confirmed, persist)
        createOrResume(confirmed, persist)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        SyncSpaceCreation.Failed(error.managementProblem())
    }

    suspend fun repairProperties(
        target: SyncRepositoryRepairTarget,
        makePrivate: Boolean,
        unarchive: Boolean,
    ): SyncDiscoveryProblem? = managed {
        require(makePrivate || unarchive)
        val api = session(target.account)
        val before = readTarget(api, target)
        if (before.boolean("disabled")) failManagement(SyncDiscoveryProblem.REPOSITORY_DISABLED)
        val changes = buildMap {
            if (makePrivate && !before.boolean("private")) put("private", JsonPrimitive(true))
            if (unarchive && before.boolean("archived")) put("archived", JsonPrimitive(false))
        }
        if (changes.isNotEmpty()) {
            if (before.obj("permissions")["admin"] != JsonPrimitive(true)) {
                failManagement(SyncDiscoveryProblem.NEEDS_ADMINISTRATION_PERMISSION)
            }
            val response = api.requestPath(
                "/repos/${target.repository.fullName}",
                "PATCH",
                JsonObject(changes).toString().toRequestBody(JSON_MEDIA_TYPE),
            )
            if (response.code !in 200..299) {
                failManagement(response.managementProblem(SyncDiscoveryProblem.NEEDS_ADMINISTRATION_PERMISSION))
            }
        }
        val after = readTarget(api, target)
        if (makePrivate && !after.boolean("private")) failManagement(SyncDiscoveryProblem.REPOSITORY_NOT_PRIVATE)
        if (unarchive && after.boolean("archived")) failManagement(SyncDiscoveryProblem.REPOSITORY_ARCHIVED)
        if (after.boolean("disabled")) failManagement(SyncDiscoveryProblem.REPOSITORY_DISABLED)
    }

    suspend fun authorizeRepository(
        target: SyncRepositoryRepairTarget,
        creationEvidence: SyncRepositoryCreationIntent? = null,
    ): SyncDiscoveryProblem? = managed {
        val api = session(target.account)
        ensureRepositoryAccess(api, target, creationEvidence)
        readTarget(api, target)
    }

    private suspend fun ensureRepositoryAccess(
        api: GitHubPrivateRepositorySelector,
        target: SyncRepositoryRepairTarget,
        creationEvidence: SyncRepositoryCreationIntent? = null,
    ) {
        val confirmedCreation = creationEvidence?.takeIf {
            it.submitted && it.creationAdminConfirmed && it.account == target.account &&
                it.repositoryName == target.repository.name && it.repositoryId == target.repositoryId
        }
        if (confirmedCreation == null) {
            val metadata = readTarget(api, target)
            if (metadata.obj("permissions")["admin"] != JsonPrimitive(true)) {
                failManagement(SyncDiscoveryProblem.NEEDS_ADMINISTRATION_PERMISSION)
            }
        }
        val installations = api.objects("/user/installations?per_page=100&page=1", "installations").filter {
            it.string("app_slug") == "mihon-desktop" && it.obj("account").number("id") == target.account.id
        }
        if (installations.isEmpty()) failManagement(SyncDiscoveryProblem.NEEDS_INSTALLATION)
        require(installations.size == 1)
        val installation = installations.single()
        require(installation.containsKey("suspended_at"))
        if (installation["suspended_at"] != JsonNull) failManagement(SyncDiscoveryProblem.INSTALLATION_SUSPENDED)
        if (installation.obj("permissions").optionalString("contents") != "write") {
            failManagement(SyncDiscoveryProblem.NEEDS_CONTENTS_PERMISSION)
        }
        val id = installation.number("id")
        val path = "/user/installations/$id/repositories"
        if (api.objects("$path?per_page=100&page=1", "repositories").none {
                it.number("id") == target.repositoryId
            }
        ) {
            val response = api.requestPath("$path/${target.repositoryId}", "PUT", ByteArray(0).toRequestBody(null))
            if (response.code !in setOf(204, 304)) {
                failManagement(response.managementProblem(SyncDiscoveryProblem.NEEDS_INSTALLATION_ACCESS_PERMISSION))
            }
        }
        if (api.objects("$path?per_page=100&page=1", "repositories").none { it.number("id") == target.repositoryId }) {
            failManagement(SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS)
        }
        readTarget(api, target)
    }

    private suspend fun session(expected: SyncGitHubAccount): GitHubPrivateRepositorySelector {
        val token =
            accessToken().takeIf(String::isNotBlank) ?: failManagement(SyncDiscoveryProblem.AUTHORIZATION_REQUIRED)
        val api = GitHubPrivateRepositorySelector(productionClient, { token }, apiBaseUrl, requestGate)
        val account = api.requestPath("/user").requireSyncSuccess().managementObject()
        if (account.number("id") != expected.id || !account.string("login").equals(expected.login, true) ||
            account.string("type") != "User"
        ) {
            failManagement(SyncDiscoveryProblem.ACCOUNT_CHANGED)
        }
        return api
    }

    private suspend fun readTarget(
        api: GitHubPrivateRepositorySelector,
        target: SyncRepositoryRepairTarget,
    ): JsonObject {
        require(target.repositoryId > 0)
        val response = api.requestPath("/repositories/${target.repositoryId}")
        if (response.code == 404) failManagement(SyncDiscoveryProblem.REPOSITORY_UNAVAILABLE)
        if (response.code ==
            403
        ) {
            failManagement(response.managementProblem(SyncDiscoveryProblem.NEEDS_REPOSITORY_ACCESS))
        }
        val value = response.requireSyncSuccess().managementObject()
        verifyIdentity(value, target.account, target.repository, target.repositoryId)
        return value
    }

    private fun verifyIdentity(
        value: JsonObject,
        account: SyncGitHubAccount,
        repository: SyncRepository,
        repositoryId: Long? = null,
    ) {
        val owner = value.obj("owner")
        if (owner.number("id") != account.id || owner.string("type") != "User" ||
            !owner.string("login").equals(account.login, true) ||
            !value.string("name").equals(repository.name, true) ||
            !value.string("full_name").equals(repository.fullName, true) ||
            (repositoryId != null && value.number("id") != repositoryId)
        ) {
            failManagement(SyncDiscoveryProblem.ACCOUNT_CHANGED)
        }
        require(value.number("id") > 0)
    }

    private suspend fun persistSafely(
        intent: SyncRepositoryCreationIntent,
        persist: suspend (SyncRepositoryCreationIntent) -> Unit,
    ) {
        try {
            persist(intent)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failManagement(SyncDiscoveryProblem.STORAGE_ERROR)
        }
    }

    private suspend fun managed(block: suspend () -> Unit): SyncDiscoveryProblem? = try {
        block()
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        error.managementProblem()
    }
}

private class SyncRepositoryManagementException(val problem: SyncDiscoveryProblem) : IllegalStateException()
private fun failManagement(problem: SyncDiscoveryProblem): Nothing = throw SyncRepositoryManagementException(problem)

private fun Exception.managementProblem(): SyncDiscoveryProblem = when (this) {
    is SyncRepositoryManagementException -> problem
    is SyncHttpException -> when (failureClass) {
        SyncHttpFailureClass.RATE_LIMITED -> SyncDiscoveryProblem.RATE_LIMITED
        SyncHttpFailureClass.AUTHORIZATION -> SyncDiscoveryProblem.AUTHORIZATION_REQUIRED
        else -> if (retryable || code == null) SyncDiscoveryProblem.RETRYABLE else SyncDiscoveryProblem.MALFORMED
    }
    else -> SyncDiscoveryProblem.MALFORMED
}

private fun SyncHttpResponse.managementProblem(permission: SyncDiscoveryProblem): SyncDiscoveryProblem = try {
    requireSyncSuccess()
    SyncDiscoveryProblem.MALFORMED
} catch (error: SyncHttpException) {
    if (error.code == 403 && error.failureClass != SyncHttpFailureClass.RATE_LIMITED) {
        permission
    } else {
        error.managementProblem()
    }
}

private fun SyncHttpResponse.managementObject(): JsonObject =
    Json.parseToJsonElement(body.decodeToString(throwOnInvalidSequence = true)).jsonObject
private fun JsonObject.string(name: String): String = (this[name] as? JsonPrimitive)
    ?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank) ?: error("missing field")
private fun JsonObject.optionalString(name: String): String? = when (val value = this[name]) {
    null, JsonNull -> null
    else -> (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: error("invalid field")
}
private fun JsonObject.number(name: String): Long = (this[name] as? JsonPrimitive)
    ?.takeIf { !it.isString }?.longOrNull ?: error("missing field")
private fun JsonObject.boolean(name: String): Boolean = (this[name] as? JsonPrimitive)
    ?.takeIf { !it.isString }?.booleanOrNull ?: error("missing field")
private fun JsonObject.obj(name: String): JsonObject = this[name] as? JsonObject ?: error("missing field")
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
