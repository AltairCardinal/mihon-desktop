package mihon.desktop.domain.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository

class FakeCategoryRepository : CategoryRepository {

    private val categories = mutableListOf<Category>()
    private val flow = MutableStateFlow<List<Category>>(emptyList())

    /** mangaId → set of categoryIds */
    private val mangaCategories = mutableMapOf<Long, MutableSet<Long>>()

    override suspend fun get(id: Long): Category? = categories.find { it.id == id }

    override suspend fun getAll(): List<Category> = categories.toList()

    override fun getAllAsFlow(): Flow<List<Category>> = flow

    override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> {
        val ids = mangaCategories[mangaId] ?: emptySet()
        return categories.filter { it.id in ids }
    }

    override fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>> {
        return flow.map { all ->
            val ids = mangaCategories[mangaId] ?: emptySet()
            all.filter { it.id in ids }
        }
    }

    override suspend fun insert(category: Category) {
        val id = if (category.id == 0L) (categories.maxOfOrNull { it.id } ?: 0L) + 1 else category.id
        categories.add(category.copy(id = id))
        emitUpdate()
    }

    override suspend fun updatePartial(update: CategoryUpdate) {
        val idx = categories.indexOfFirst { it.id == update.id }
        if (idx >= 0) {
            val old = categories[idx]
            categories[idx] = old.copy(
                name = update.name ?: old.name,
                order = update.order ?: old.order,
                flags = update.flags ?: old.flags,
            )
            emitUpdate()
        }
    }

    override suspend fun updatePartial(updates: List<CategoryUpdate>) {
        updates.forEach { updatePartial(it) }
    }

    override suspend fun updateAllFlags(flags: Long?) {
        categories.forEachIndexed { i, c ->
            categories[i] = c.copy(flags = flags ?: 0L)
        }
        emitUpdate()
    }

    override suspend fun delete(categoryId: Long) {
        categories.removeAll { it.id == categoryId }
        emitUpdate()
    }

    fun setMangaCategories(mangaId: Long, categoryIds: Set<Long>) {
        mangaCategories[mangaId] = categoryIds.toMutableSet()
    }

    private fun emitUpdate() {
        flow.value = categories.toList()
    }
}
