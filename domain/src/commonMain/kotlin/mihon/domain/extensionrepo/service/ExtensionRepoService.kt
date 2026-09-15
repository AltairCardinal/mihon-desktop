package mihon.domain.extensionrepo.service

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import logcat.LogPriority
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.model.normalizedSigningKeyFingerprint
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.IOException

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
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
                val baseUrl = repo.trim().trimEnd('/').removeSuffix("/repo.json").removeSuffix("/index.min.json")
                ExtensionStoreCatalogDecoder.requireSupportedCatalogUrl(baseUrl)
                var explicitIndex = baseUrl.toHttpUrl().pathSegments.last().substringAfterLast('.') in
                    setOf("json", "pb", "gz", "protobuf")
                var metadataUrl = if (explicitIndex) baseUrl else "$baseUrl/repo.json"
                val catalogClient = client.withCatalogRedirectPolicy()
                suspend fun fetch(url: String) = catalogClient.newCall(GET(url)).awaitSuccess().use { it.body.bytes() }
                val responseBytes = try {
                    fetch(metadataUrl)
                } catch (error: HttpException) {
                    // Legacy roots retain their established first request. An absent legacy
                    // manifest permits one direct-index probe, without a filename requirement.
                    if (error.code != 404 || explicitIndex) throw error
                    metadataUrl = baseUrl
                    explicitIndex = true
                    fetch(metadataUrl)
                }
                val repoDetails = decodeRepository(responseBytes, metadataUrl, baseUrl, explicitIndex)
                FetchRepoDetailsResult.Success(repoDetails)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                logcat(LogPriority.ERROR, e) { "Repository metadata request failed with HTTP ${e.code}" }
                FetchRepoDetailsResult.RepositoryUnavailable
            } catch (e: InvalidCatalogRequestException) {
                FetchRepoDetailsResult.InvalidRepository
            } catch (e: IOException) {
                logcat(LogPriority.ERROR, e) { "Failed to reach repository metadata" }
                if (generateSequence<Throwable>(e) { it.cause }.any { it is InvalidCatalogRequestException }) {
                    FetchRepoDetailsResult.InvalidRepository
                } else {
                    FetchRepoDetailsResult.RepositoryUnavailable
                }
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

    private fun decodeRepository(
        responseBytes: ByteArray,
        metadataUrl: String,
        baseUrl: String,
        explicitIndex: Boolean,
    ): ExtensionRepo {
        val provisional = ExtensionRepo(
            baseUrl = baseUrl,
            name = baseUrl,
            shortName = null,
            website = baseUrl,
            signingKeyFingerprint = "",
        )
        val catalog = ExtensionStoreCatalogDecoder.decode(
            bytes = responseBytes,
            indexUrl = metadataUrl,
            repository = provisional,
            json = json,
            expectedFingerprint = null,
        )
        val store = catalog.store
        return ExtensionRepo(
            baseUrl = baseUrl,
            name = store.name,
            shortName = store.badgeLabel,
            website = store.contact.website,
            signingKeyFingerprint = store.signingKey.normalizedSigningKeyFingerprint(),
            indexUrl = if (store.isLegacy) {
                catalog.nextUrl?.takeUnless { it.endsWith("/index.min.json") }
            } else {
                metadataUrl.takeIf { explicitIndex }
            },
            extensionListUrl = store.extensionListUrl,
            contactDiscord = store.contact.discord,
        )
    }

    suspend fun create(
        repo: String,
        operation: suspend (String) -> ExtensionRepoCreateOutcome,
    ) = Actions.create(repo, operation)

    suspend fun execute(
        action: ExtensionRepoAction,
        publish: (ExtensionRepoActionResult) -> Unit,
        operation: suspend () -> ExtensionRepoActionResult,
    ) = Actions.execute(action, publish, operation)

    suspend fun replace(
        oldRepo: ExtensionRepo,
        newRepo: ExtensionRepo,
        operation: suspend (ExtensionRepo) -> Unit,
    ) = Actions.replace(oldRepo, newRepo, operation)

    suspend fun delete(repo: String, operation: suspend (String) -> Unit) =
        Actions.delete(repo, operation)

    companion object Actions {
        suspend fun create(
            repo: String,
            operation: suspend (String) -> ExtensionRepoCreateOutcome,
        ): ExtensionRepoActionResult {
            val result = try {
                operation(repo)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return failure()
            }
            return when (result) {
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

        suspend fun execute(
            action: ExtensionRepoAction,
            publish: (ExtensionRepoActionResult) -> Unit,
            operation: suspend () -> ExtensionRepoActionResult,
        ): ExtensionRepoActionResult {
            publish(ExtensionRepoActionResult.Pending(action))
            return operation().also(publish)
        }

        suspend fun replace(
            oldRepo: ExtensionRepo,
            newRepo: ExtensionRepo,
            operation: suspend (ExtensionRepo) -> Unit,
        ): ExtensionRepoActionResult {
            if (oldRepo.signingKeyFingerprint.normalizedSigningKeyFingerprint() !=
                newRepo.signingKeyFingerprint.normalizedSigningKeyFingerprint()
            ) {
                return validation(ExtensionRepoValidation.FINGERPRINT_CHANGED, ExtensionRepoAction.REPLACE)
            }
            return try {
                operation(newRepo.copy(signingKeyFingerprint = oldRepo.signingKeyFingerprint))
                ExtensionRepoActionResult.Success(ExtensionRepoAction.REPLACE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failure(action = ExtensionRepoAction.REPLACE)
            }
        }

        suspend fun delete(repo: String, operation: suspend (String) -> Unit) =
            try {
                operation(repo)
                ExtensionRepoActionResult.Success(ExtensionRepoAction.DELETE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failure(action = ExtensionRepoAction.DELETE)
            }

        private fun validation(
            reason: ExtensionRepoValidation,
            action: ExtensionRepoAction = ExtensionRepoAction.CREATE,
        ) = ExtensionRepoActionResult.Validation(action, reason)
        private fun failure(
            reason: ExtensionRepoFailure = ExtensionRepoFailure.UNKNOWN,
            action: ExtensionRepoAction = ExtensionRepoAction.CREATE,
        ) = ExtensionRepoActionResult.Failure(action, reason)
    }

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
