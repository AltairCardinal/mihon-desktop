package mihon.domain.extension.service

import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionCatalogEntry
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.RepositoryCatalogFailure
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.model.toIdentity
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.network.AppErrorException
import okio.IOException

sealed interface RepositoryFetchResult {
    val repository: RepositoryIdentity

    data class Success(
        override val repository: RepositoryIdentity,
        val entries: List<ExtensionCatalogEntry>,
    ) : RepositoryFetchResult

    data class Failure(
        override val repository: RepositoryIdentity,
        val error: AppError,
    ) : RepositoryFetchResult
}

class ExtensionCatalogService {

    suspend fun refresh(
        repositories: List<ExtensionRepo>,
        fetch: suspend (ExtensionRepo) -> RepositoryFetchResult,
    ): ExtensionCatalogResult = coroutineScope {
        val results = repositories.map { repository ->
            async {
                try {
                    fetch(repository)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failure(repository, error)
                }
            }
        }.awaitAll()

        ExtensionCatalogResult(
            entries = results.filterIsInstance<RepositoryFetchResult.Success>().flatMap { it.entries },
            failures = results.filterIsInstance<RepositoryFetchResult.Failure>().map {
                RepositoryCatalogFailure(it.repository, it.error)
            },
        )
    }

    fun failure(repository: ExtensionRepo, error: Throwable): RepositoryFetchResult.Failure {
        return RepositoryFetchResult.Failure(repository.toIdentity(), error.toCatalogAppError())
    }
}

private fun Throwable.toCatalogAppError(): AppError = when (this) {
    is AppErrorException -> error
    is HttpException -> when (code) {
        401, 403 -> AppError.Authentication(this)
        429 -> AppError.RateLimited(cause = this)
        in 500..599 -> AppError.Server(code, this)
        else -> AppError.Unknown(this)
    }
    is IOException -> AppError.Network(this)
    is SerializationException,
    is IllegalArgumentException,
    -> AppError.MalformedData(this)
    else -> AppError.Unknown(this)
}
