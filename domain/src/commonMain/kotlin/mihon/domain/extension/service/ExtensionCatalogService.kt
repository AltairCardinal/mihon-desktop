package mihon.domain.extension.service

import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import mihon.domain.error.AppError
import mihon.domain.extension.model.ExtensionCatalogEntry
import mihon.domain.extension.model.ExtensionCatalogResult
import mihon.domain.extension.model.RepositoryCatalogFailure
import mihon.domain.extension.model.RepositoryIdentity
import mihon.domain.extension.model.toIdentity
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.model.normalizedSigningKeyFingerprint
import mihon.domain.extensionrepo.service.InvalidCatalogRequestException
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
    private val refreshMutex = Mutex()
    private val snapshots = mutableMapOf<Pair<String, String>, List<ExtensionCatalogEntry>>()

    suspend fun refresh(
        repositories: List<ExtensionRepo>,
        fetch: suspend (ExtensionRepo) -> RepositoryFetchResult,
    ): ExtensionCatalogResult = refreshMutex.withLock {
        coroutineScope {
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

            // Publish only after all repositories settle; cancellation must not replace a good snapshot.
            snapshots.keys.retainAll(repositories.map { it.toIdentity().snapshotKey() }.toSet())
            results.filterIsInstance<RepositoryFetchResult.Success>().forEach {
                snapshots[it.repository.snapshotKey()] = it.entries
            }

            ExtensionCatalogResult(
                entries = results.flatMap { snapshots[it.repository.snapshotKey()].orEmpty() },
                failures = results.filterIsInstance<RepositoryFetchResult.Failure>().map {
                    RepositoryCatalogFailure(it.repository, it.error)
                },
                repositories = repositories.map { it.toIdentity() },
            )
        }
    }

    fun failure(repository: ExtensionRepo, error: Throwable): RepositoryFetchResult.Failure {
        return RepositoryFetchResult.Failure(repository.toIdentity(), error.toCatalogAppError())
    }

    private fun RepositoryIdentity.snapshotKey() = baseUrl to signingKeyFingerprint.normalizedSigningKeyFingerprint()
}

private fun Throwable.toCatalogAppError(): AppError = when (this) {
    is AppErrorException -> error
    is InvalidCatalogRequestException -> AppError.MalformedData(this)
    is HttpException -> when (code) {
        401, 403 -> AppError.Authentication(this)
        429 -> AppError.RateLimited(cause = this)
        in 500..599 -> AppError.Server(code, this)
        else -> AppError.Unknown(this)
    }
    is IOException -> if (generateSequence<Throwable>(this) { it.cause }
            .any { it is InvalidCatalogRequestException }
    ) {
        AppError.MalformedData(this)
    } else {
        AppError.Network(this)
    }
    is SerializationException,
    is IllegalArgumentException,
    -> AppError.MalformedData(this)
    else -> AppError.Unknown(this)
}
