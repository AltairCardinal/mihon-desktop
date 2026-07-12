package mihon.domain.extensionrepo.interactor

import logcat.LogPriority
import mihon.domain.extensionrepo.exception.SaveExtensionRepoException
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.repository.ExtensionRepoRepository
import mihon.domain.extensionrepo.service.ExtensionRepoService
import mihon.domain.extensionrepo.service.ExtensionRepoService.FetchRepoDetailsResult
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.util.system.logcat

class CreateExtensionRepo(
    private val repository: ExtensionRepoRepository,
    private val service: ExtensionRepoService,
) {
    suspend fun await(url: String): Result {
        val baseUrl = normalizeRepoUrl(url)
            ?: return Result.InvalidUrl

        return when (val result = service.fetchRepoDetailsResult(baseUrl)) {
            is FetchRepoDetailsResult.Success -> insert(result.repo)
            FetchRepoDetailsResult.RepositoryUnavailable -> Result.RepositoryUnavailable
            FetchRepoDetailsResult.InvalidRepository -> Result.InvalidRepository
            FetchRepoDetailsResult.UnknownError -> Result.Error
        }
    }

    private fun normalizeRepoUrl(url: String): String? {
        val parsed = url.trim()
            .takeIf { it.isNotEmpty() }
            ?.toHttpUrlOrNull()
            ?: return null

        val isLocalHttp = parsed.scheme == "http" && parsed.host in setOf("localhost", "127.0.0.1", "::1")
        if (parsed.scheme != "https" && !isLocalHttp) {
            return null
        }

        return parsed.toString()
            .removeSuffix("/")
            .removeSuffix("/index.min.json")
    }

    private suspend fun insert(repo: ExtensionRepo): Result {
        return try {
            repository.insertRepo(
                repo.baseUrl,
                repo.name,
                repo.shortName,
                repo.website,
                repo.signingKeyFingerprint,
            )
            Result.Success
        } catch (e: SaveExtensionRepoException) {
            logcat(LogPriority.WARN, e) { "SQL Conflict attempting to add new repository ${repo.baseUrl}" }
            return handleInsertionError(repo)
        }
    }

    /**
     * Error Handler for insert when there are trying to create new repositories
     *
     * SaveExtensionRepoException doesn't provide constraint info in exceptions.
     * First check if the conflict was on primary key. if so return RepoAlreadyExists
     * Then check if the conflict was on fingerprint. if so Return DuplicateFingerprint
     * If neither are found, there was some other Error, and return Result.Error
     *
     * @param repo Extension Repo holder for passing to DB/Error Dialog
     */
    private suspend fun handleInsertionError(repo: ExtensionRepo): Result {
        val repoExists = repository.getRepo(repo.baseUrl)
        if (repoExists != null) {
            return Result.RepoAlreadyExists
        }
        val matchingFingerprintRepo = repository.getRepoBySigningKeyFingerprint(repo.signingKeyFingerprint)
        if (matchingFingerprintRepo != null) {
            return Result.DuplicateFingerprint(matchingFingerprintRepo, repo)
        }
        return Result.Error
    }

    sealed interface Result {
        data class DuplicateFingerprint(val oldRepo: ExtensionRepo, val newRepo: ExtensionRepo) : Result
        data object InvalidUrl : Result
        data object RepositoryUnavailable : Result
        data object InvalidRepository : Result
        data object RepoAlreadyExists : Result
        data object Success : Result
        data object Error : Result
    }
}
