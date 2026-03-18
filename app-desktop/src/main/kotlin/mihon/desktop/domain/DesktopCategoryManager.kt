package mihon.desktop.domain

import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * Lightweight desktop category manager.
 *
 * Wraps [CategoryRepository] directly, avoiding the heavy
 * [LibraryPreferences] / [DownloadPreferences] chains that the
 * Android interactors depend on.
 */
class DesktopCategoryManager(
    private val categoryRepository: CategoryRepository,
) {

    /** Create a new category. Blank names are rejected. */
    suspend fun create(name: String): Result {
        if (name.isBlank()) return Result.Error("Category name must not be blank")
        val all = categoryRepository.getAll()
        val nextOrder = all.maxOfOrNull { it.order }?.plus(1) ?: 0L
        return try {
            categoryRepository.insert(
                Category(id = 0, name = name.trim(), order = nextOrder, flags = 0L),
            )
            Result.Success
        } catch (e: Exception) {
            Result.Error(e.message ?: "Unknown error")
        }
    }

    /** Rename an existing category. */
    suspend fun rename(categoryId: Long, name: String): Result {
        if (name.isBlank()) return Result.Error("Category name must not be blank")
        return try {
            categoryRepository.updatePartial(CategoryUpdate(id = categoryId, name = name.trim()))
            Result.Success
        } catch (e: Exception) {
            Result.Error(e.message ?: "Unknown error")
        }
    }

    /** Delete a category and reorder the remaining ones. */
    suspend fun delete(categoryId: Long): Result {
        return try {
            categoryRepository.delete(categoryId)
            // Re-index remaining categories
            val remaining = categoryRepository.getAll().sortedBy { it.order }
            val updates = remaining.mapIndexed { index, cat ->
                CategoryUpdate(id = cat.id, order = index.toLong())
            }
            if (updates.isNotEmpty()) categoryRepository.updatePartial(updates)
            Result.Success
        } catch (e: Exception) {
            Result.Error(e.message ?: "Unknown error")
        }
    }

    /** Move a category to [newIndex] and re-index all non-system categories. */
    suspend fun reorder(categoryId: Long, newIndex: Int): Result {
        val categories = categoryRepository.getAll()
            .filterNot { it.isSystemCategory }
            .sortedBy { it.order }
            .toMutableList()

        val currentIndex = categories.indexOfFirst { it.id == categoryId }
        if (currentIndex < 0) return Result.Unchanged

        return try {
            categories.add(newIndex, categories.removeAt(currentIndex))
            val updates = categories.mapIndexed { index, cat ->
                CategoryUpdate(id = cat.id, order = index.toLong())
            }
            categoryRepository.updatePartial(updates)
            Result.Success
        } catch (e: Exception) {
            Result.Error(e.message ?: "Unknown error")
        }
    }

    /** Get all categories ordered by [Category.order]. */
    suspend fun getAll(): List<Category> =
        categoryRepository.getAll().sortedBy { it.order }

    sealed interface Result {
        data object Success : Result
        data object Unchanged : Result
        data class Error(val message: String) : Result
    }
}
