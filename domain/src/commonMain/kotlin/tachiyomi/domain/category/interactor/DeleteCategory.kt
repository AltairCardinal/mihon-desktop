package tachiyomi.domain.category.interactor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences

class DeleteCategory(
    private val categoryRepository: CategoryRepository,
    private val libraryPreferences: LibraryPreferences,
    private val downloadPreferences: DownloadPreferences,
    private val operations: Mutex = Mutex(),
) {
    private val recoveryReadyState = MutableStateFlow(false)
    val recoveryReady = recoveryReadyState.asStateFlow()

    suspend fun await(categoryId: Long): Result = withNonCancellableContext {
        operations.withLock {
            recoveryReadyState.value = false
            try {
                val pending = libraryPreferences.categoryDeletionPending()
                val id = categoryId.toString()
                pending.set(pending.get() + id)
                finish(categoryId).also {
                    recoveryReadyState.value = pending.get().isEmpty()
                }
            } catch (error: Exception) {
                logcat(LogPriority.ERROR, error)
                Result.InternalError(error)
            }
        }
    }

    /** Resumes only confirmed deletion IDs; callers invoke this before reading category preferences. */
    suspend fun recoverPending(): Result = withNonCancellableContext {
        operations.withLock {
            recoveryReadyState.value = false
            try {
                val pending = libraryPreferences.categoryDeletionPending()
                val confirmed = pending.get()
                if (confirmed.isNotEmpty()) pending.set(confirmed)
                confirmed.forEach { raw ->
                    finish(requireNotNull(raw.toLongOrNull()) { "Invalid pending category deletion" })
                }
                recoveryReadyState.value = libraryPreferences.categoryDeletionPending().get().isEmpty()
                Result.Success
            } catch (error: Exception) {
                logcat(LogPriority.ERROR, error)
                Result.InternalError(error)
            }
        }
    }

    private suspend fun finish(categoryId: Long): Result {
        // A post-commit failure or restart must not delete the missing object again.
        if (categoryRepository.get(categoryId) != null) categoryRepository.delete(categoryId)
        val categories = categoryRepository.getAll()
        val updates = categories.mapIndexed { index, category ->
            CategoryUpdate(id = category.id, order = index.toLong())
        }
        val defaultCategory = libraryPreferences.defaultCategory()
        if (defaultCategory.get() == categoryId.toInt()) defaultCategory.delete()
        val references = listOf(
            libraryPreferences.updateCategories(),
            libraryPreferences.updateCategoriesExclude(),
            downloadPreferences.removeExcludeCategories(),
            downloadPreferences.downloadNewChapterCategories(),
            downloadPreferences.downloadNewChapterCategoriesExclude(),
        )
        val id = categoryId.toString()
        references.forEach { preference ->
            val previous = preference.get()
            if (id in previous) preference.set(previous - id)
        }
        categoryRepository.updatePartial(updates)
        val pending = libraryPreferences.categoryDeletionPending()
        pending.set(pending.get() - id)
        return Result.Success
    }

    sealed interface Result {
        data object Success : Result
        data class InternalError(val error: Throwable) : Result
    }
}
