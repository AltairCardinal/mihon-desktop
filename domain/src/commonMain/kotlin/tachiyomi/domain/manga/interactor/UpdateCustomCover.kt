package tachiyomi.domain.manga.interactor

import mihon.domain.error.AppError
import mihon.domain.task.TaskState

interface CustomCoverStore {
    suspend fun write(mangaId: Long, bytes: ByteArray)
    suspend fun delete(mangaId: Long)
}

class UpdateCustomCover(
    private val coverStore: CustomCoverStore,
    private val invalidateCover: suspend (Long) -> Unit,
) {
    suspend operator fun invoke(mangaId: Long, bytes: ByteArray): TaskState<Unit> =
        try {
            coverStore.write(mangaId, bytes)
            invalidateCover(mangaId)
            TaskState.Success(Unit)
        } catch (error: SecurityException) {
            TaskState.Failure(AppError.Permission(error))
        } catch (error: Throwable) {
            TaskState.Failure(AppError.Storage(error))
        }

    suspend fun delete(mangaId: Long): TaskState<Unit> =
        try {
            coverStore.delete(mangaId)
            invalidateCover(mangaId)
            TaskState.Success(Unit)
        } catch (error: SecurityException) {
            TaskState.Failure(AppError.Permission(error))
        } catch (error: Throwable) {
            TaskState.Failure(AppError.Storage(error))
        }
}
