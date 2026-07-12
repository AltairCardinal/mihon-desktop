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
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.IOException

class ExtensionRepoService(
    networkHelper: NetworkHelper,
    private val json: Json,
) {
    val client = networkHelper.client

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

    sealed interface FetchRepoDetailsResult {
        data class Success(val repo: ExtensionRepo) : FetchRepoDetailsResult
        data object RepositoryUnavailable : FetchRepoDetailsResult
        data object InvalidRepository : FetchRepoDetailsResult
        data object UnknownError : FetchRepoDetailsResult
    }
}
