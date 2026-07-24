package mihon.domain.extensionrepo.service

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.domain.extensionrepo.model.ExtensionRepo
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.IOException

class ExtensionRepoService private constructor(
    val client: OkHttpClient,
    private val json: Json,
) {
    constructor(networkHelper: NetworkHelper, json: Json) : this(networkHelper.client, json)
    internal constructor() : this(OkHttpClient(), Json)

    suspend fun fetchRepoDetails(
        repo: String,
    ): ExtensionRepo? {
        return when (val result = fetchRepoDetailsResult(repo)) {
            is FetchRepoDetailsResult.Success -> result.repo
            else -> null
        }
    }

    suspend fun fetchRepoDetailsResult(
        repo: String,
    ): FetchRepoDetailsResult {
        return withIOContext {
            try {
                val repoDetails = with(json) {
                    client.newCall(GET("$repo/repo.json"))
                        .awaitSuccess()
                        .parseAs<ExtensionRepoMetaDto>()
                        .toExtensionRepo(baseUrl = repo)
                }
                FetchRepoDetailsResult.Success(repoDetails)
            } catch (e: HttpException) {
                logcat(LogPriority.ERROR, e) { "Repository metadata request failed with HTTP ${e.code}" }
                FetchRepoDetailsResult.RepositoryUnavailable
            } catch (e: IOException) {
                logcat(LogPriority.ERROR, e) { "Failed to reach repository metadata" }
                FetchRepoDetailsResult.RepositoryUnavailable
            } catch (e: SerializationException) {
                logcat(LogPriority.ERROR, e) { "Repository metadata is invalid" }
                FetchRepoDetailsResult.InvalidRepository
            } catch (e: IllegalArgumentException) {
                logcat(LogPriority.ERROR, e) { "Repository metadata is invalid" }
                FetchRepoDetailsResult.InvalidRepository
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to fetch repo details" }
                FetchRepoDetailsResult.UnknownError
            }
        }
    }

    suspend fun create(
        repo: String,
        operation: suspend (String) -> ExtensionRepoCreateOutcome,
    ): ExtensionRepoActionResult {
        return when (val result = runCatching { operation(repo) }.getOrElse { return failure() }) {
            ExtensionRepoCreateOutcome.Success -> ExtensionRepoActionResult.Success(ExtensionRepoAction.CREATE)
            ExtensionRepoCreateOutcome.InvalidUrl -> validation(ExtensionRepoValidation.INVALID_URL)
            ExtensionRepoCreateOutcome.AlreadyExists -> validation(ExtensionRepoValidation.ALREADY_EXISTS)
            is ExtensionRepoCreateOutcome.Conflict ->
                ExtensionRepoActionResult.FingerprintConflict(result.oldRepo, result.newRepo)
            ExtensionRepoCreateOutcome.RepositoryUnavailable -> failure(ExtensionRepoFailure.REPOSITORY_UNAVAILABLE)
            ExtensionRepoCreateOutcome.InvalidRepository -> failure(ExtensionRepoFailure.INVALID_REPOSITORY)
            ExtensionRepoCreateOutcome.Failure -> failure()
        }
    }

    suspend fun replace(
        oldRepo: ExtensionRepo,
        newRepo: ExtensionRepo,
        operation: suspend (ExtensionRepo) -> Unit,
    ): ExtensionRepoActionResult {
        if (!oldRepo.signingKeyFingerprint.equals(newRepo.signingKeyFingerprint, ignoreCase = true)) {
            return validation(ExtensionRepoValidation.FINGERPRINT_CHANGED, ExtensionRepoAction.REPLACE)
        }
        return runCatching { operation(newRepo.copy(signingKeyFingerprint = oldRepo.signingKeyFingerprint)) }.fold(
            onSuccess = { ExtensionRepoActionResult.Success(ExtensionRepoAction.REPLACE) },
            onFailure = { failure(action = ExtensionRepoAction.REPLACE) },
        )
    }

    suspend fun delete(repo: String, operation: suspend (String) -> Unit) =
        runCatching { operation(repo) }.fold(
            onSuccess = { ExtensionRepoActionResult.Success(ExtensionRepoAction.DELETE) },
            onFailure = { failure(action = ExtensionRepoAction.DELETE) },
        )

    private fun validation(reason: ExtensionRepoValidation, action: ExtensionRepoAction = ExtensionRepoAction.CREATE) =
        ExtensionRepoActionResult.Validation(action, reason)
    private fun failure(
        reason: ExtensionRepoFailure = ExtensionRepoFailure.UNKNOWN,
        action: ExtensionRepoAction = ExtensionRepoAction.CREATE,
    ) =
        ExtensionRepoActionResult.Failure(action, reason)

    sealed interface FetchRepoDetailsResult {
        data class Success(val repo: ExtensionRepo) : FetchRepoDetailsResult
        data object RepositoryUnavailable : FetchRepoDetailsResult
        data object InvalidRepository : FetchRepoDetailsResult
        data object UnknownError : FetchRepoDetailsResult
    }
}

sealed interface ExtensionRepoCreateOutcome {
    data object Success : ExtensionRepoCreateOutcome
    data object InvalidUrl : ExtensionRepoCreateOutcome
    data object AlreadyExists : ExtensionRepoCreateOutcome
    data class Conflict(val oldRepo: ExtensionRepo, val newRepo: ExtensionRepo) : ExtensionRepoCreateOutcome
    data object RepositoryUnavailable : ExtensionRepoCreateOutcome
    data object InvalidRepository : ExtensionRepoCreateOutcome
    data object Failure : ExtensionRepoCreateOutcome
}

enum class ExtensionRepoAction { CREATE, REPLACE, DELETE }
enum class ExtensionRepoValidation { INVALID_URL, ALREADY_EXISTS, FINGERPRINT_CHANGED }
enum class ExtensionRepoFailure { REPOSITORY_UNAVAILABLE, INVALID_REPOSITORY, UNKNOWN }

sealed interface ExtensionRepoActionResult {
    val action: ExtensionRepoAction
    data class Pending(override val action: ExtensionRepoAction) : ExtensionRepoActionResult
    data class Success(override val action: ExtensionRepoAction) : ExtensionRepoActionResult
    data class Validation(override val action: ExtensionRepoAction, val reason: ExtensionRepoValidation) :
        ExtensionRepoActionResult
    data class FingerprintConflict(
        val oldRepo: ExtensionRepo,
        val newRepo: ExtensionRepo,
    ) : ExtensionRepoActionResult {
        override val action = ExtensionRepoAction.CREATE
    }
    data class Failure(override val action: ExtensionRepoAction, val reason: ExtensionRepoFailure) :
        ExtensionRepoActionResult
}
