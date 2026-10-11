package tachiyomi.data.category

import kotlinx.coroutines.flow.Flow
import mihon.data.sync.journal.appendCategoryOperation
import mihon.domain.sync.SyncMutationContext
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository

class CategoryRepositoryImpl(
    private val handler: DatabaseHandler,
) : CategoryRepository {

    override suspend fun get(id: Long): Category? {
        return handler.awaitOneOrNull { categoriesQueries.getCategory(id, ::mapCategory) }
    }

    override suspend fun getAll(): List<Category> {
        return handler.awaitList { categoriesQueries.getCategories(::mapCategory) }
    }

    override fun getAllAsFlow(): Flow<List<Category>> {
        return handler.subscribeToList { categoriesQueries.getCategories(::mapCategory) }
    }

    override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> {
        return handler.awaitList {
            categoriesQueries.getCategoriesByMangaId(mangaId, ::mapCategory)
        }
    }

    override fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>> {
        return handler.subscribeToList {
            categoriesQueries.getCategoriesByMangaId(mangaId, ::mapCategory)
        }
    }

    override suspend fun insert(category: Category) {
        handler.await {
            categoriesQueries.insert(
                name = category.name,
                order = category.order,
                flags = category.flags,
            )
        }
    }

    override suspend fun updatePartial(update: CategoryUpdate) {
        handler.await(inTransaction = true) {
            updatePartialBlocking(update)
        }
    }

    override suspend fun updatePartial(updates: List<CategoryUpdate>) {
        handler.await(inTransaction = true) {
            for (update in updates) {
                updatePartialBlocking(update)
            }
        }
    }

    private fun Database.updatePartialBlocking(update: CategoryUpdate) {
        val oldName = categoriesQueries.getCategory(update.id).executeAsOneOrNull()?.name
        categoriesQueries.update(
            name = update.name,
            order = update.order,
            flags = update.flags,
            categoryId = update.id,
        )
        if (update.name != null && update.name != oldName) {
            forEachManga(update.id) { appendCategoryOperation(it, SyncMutationContext.User) }
        }
    }

    override suspend fun updateAllFlags(flags: Long?) {
        handler.await {
            categoriesQueries.updateAllFlags(flags)
        }
    }

    override suspend fun delete(categoryId: Long) {
        handler.await(inTransaction = true) {
            forEachManga(categoryId) { mangaId ->
                mangas_categoriesQueries.deleteAssociation(mangaId, categoryId)
                appendCategoryOperation(mangaId, SyncMutationContext.User)
            }
            categoriesQueries.delete(
                categoryId = categoryId,
            )
        }
    }

    private fun Database.forEachManga(categoryId: Long, action: Database.(Long) -> Unit) {
        var afterId = 0L
        while (true) {
            val page = mangas_categoriesQueries.getMangaIdsByCategory(categoryId, afterId, 256).executeAsList()
            if (page.isEmpty()) return
            page.forEach { action(it) }
            afterId = page.last()
        }
    }

    private fun mapCategory(
        id: Long,
        name: String,
        order: Long,
        flags: Long,
    ): Category {
        return Category(
            id = id,
            name = name,
            order = order,
            flags = flags,
        )
    }
}
