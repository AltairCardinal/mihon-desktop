package tachiyomi.domain.category.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.repository.MangaRepository

class SetMangaCategories(
    private val mangaRepository: MangaRepository,
) {

    suspend fun await(mangaId: Long, categoryIds: List<Long>) {
        awaitResult(mangaId, categoryIds)
    }

    suspend fun awaitResult(mangaId: Long, categoryIds: List<Long>): Result {
        try {
            mangaRepository.setMangaCategories(mangaId, categoryIds)
            return Result.Success
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return Result.InternalError(e)
        }
    }

    suspend fun awaitBatch(mangaIds: List<Long>, categoryIds: List<Long>): BatchResult {
        if (mangaIds.isEmpty()) return BatchResult.Empty
        val succeeded = mutableListOf<Long>()
        val failures = mutableListOf<BatchFailure>()
        mangaIds.forEach { mangaId ->
            when (val result = awaitResult(mangaId, categoryIds)) {
                Result.Success -> succeeded += mangaId
                is Result.InternalError -> failures += BatchFailure(mangaId, result.error)
            }
        }
        return BatchResult(succeeded, failures)
    }

    sealed interface Result {
        data object Success : Result
        data class InternalError(val error: Throwable) : Result
    }

    data class BatchResult(val succeededIds: List<Long>, val failures: List<BatchFailure>) {
        companion object {
            val Empty = BatchResult(emptyList(), emptyList())
        }
    }

    data class BatchFailure(val id: Long, val error: Throwable)
}
