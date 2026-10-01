package tachiyomi.domain.category.interactor

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences

class DeleteCategoryRecoveryContractTest {
    @Test
    fun `confirmed IDs resume SQL and five references without repeating an already committed deletion`() = runTest {
        for (failure in listOf("before-sql", "after-sql", "reference")) {
            val store = Store()
            val library = LibraryPreferences(store)
            val downloads = DownloadPreferences(store)
            val repository = Repository()
            repository.failure = failure
            val references =
                listOf(
                    library.updateCategories(),
                    library.updateCategoriesExclude(),
                    downloads.removeExcludeCategories(),
                    downloads.downloadNewChapterCategories(),
                    downloads.downloadNewChapterCategoriesExclude(),
                )
            references.forEach { it.set(setOf("1", "2")) }
            library.defaultCategory().set(1)
            if (failure == "reference") store.reject = library.updateCategoriesExclude().key()
            assertTrue(DeleteCategory(repository, library, downloads).await(1) is DeleteCategory.Result.InternalError)
            assertEquals(setOf("1"), library.categoryDeletionPending().get())
            repository.failure = null
            store.reject = null
            val recovery = DeleteCategory(repository, library, downloads)
            assertEquals(DeleteCategory.Result.Success, recovery.recoverPending())
            assertTrue(recovery.recoveryReady.value)
            assertEquals(-1, library.defaultCategory().get())
            references.forEach { assertEquals(setOf("2"), it.get()) }
            assertEquals(listOf(Category(2, "Retained", 0, 0)), repository.categories)
            assertEquals(if (failure == "before-sql") 2 else 1, repository.deletes)
            assertEquals(DeleteCategory.Result.Success, recovery.recoverPending())
            assertTrue(library.categoryDeletionPending().get().isEmpty())
        }
    }

    @Test
    fun `unacknowledged pending writes block both confirmation retry and recovery before SQL`() = runTest {
        val store = Store()
        val library = LibraryPreferences(store)
        val downloads = DownloadPreferences(store)
        val repository = Repository()
        val deletion = DeleteCategory(repository, library, downloads)
        store.reject = library.categoryDeletionPending().key()
        store.afterWrite = true
        assertTrue(deletion.await(1) is DeleteCategory.Result.InternalError)
        assertTrue(deletion.await(1) is DeleteCategory.Result.InternalError)
        assertTrue(deletion.recoverPending() is DeleteCategory.Result.InternalError)
        assertEquals(0, repository.deletes)
        assertFalse(deletion.recoveryReady.value)
        store.reject = null
        assertEquals(DeleteCategory.Result.Success, deletion.recoverPending())
        assertEquals(1, repository.deletes)
        assertTrue(deletion.recoveryReady.value)
    }

    private class Store : PreferenceStore by InMemoryPreferenceStore() {
        private val sets = mutableMapOf<String, Preference<Set<String>>>()
        private val integers = mutableMapOf<String, Preference<Int>>()
        var reject: String? = null
        var afterWrite = false
        override fun getInt(key: String, defaultValue: Int): Preference<Int> = integers.getOrPut(key) {
            InMemoryPreferenceStore.InMemoryPreference(key, null, defaultValue)
        }
        override fun getStringSet(
            key: String,
            defaultValue: Set<String>,
        ): Preference<Set<String>> = sets.getOrPut(key) {
            val actual = InMemoryPreferenceStore.InMemoryPreference(key, null, defaultValue)
            object : Preference<Set<String>> by actual {
                override fun set(value: Set<String>) {
                    if (reject == key && !afterWrite) error("Write rejected")
                    actual.set(value)
                    if (reject == key && afterWrite) error("Acknowledgement rejected")
                }
            }
        }
    }

    private class Repository : CategoryRepository {
        val categories = mutableListOf(Category(1, "Confirmed", 0, 0), Category(2, "Retained", 1, 0))
        var failure: String? = null
        var deletes = 0
        override suspend fun get(id: Long) = categories.find { it.id == id }
        override suspend fun getAll() = categories.toList()
        override fun getAllAsFlow() = flowOf(categories.toList())
        override suspend fun getCategoriesByMangaId(mangaId: Long) = emptyList<Category>()
        override fun getCategoriesByMangaIdAsFlow(mangaId: Long) = flowOf(emptyList<Category>())
        override suspend fun insert(category: Category) {
            categories += category
        }
        override suspend fun updatePartial(update: CategoryUpdate) = updatePartial(listOf(update))
        override suspend fun updatePartial(updates: List<CategoryUpdate>) {
            updates.forEach { update ->
                val index = categories.indexOfFirst { it.id == update.id }
                if (index >=
                    0
                ) {
                    categories[index] = categories[index].copy(order = update.order ?: categories[index].order)
                }
            }
        }
        override suspend fun updateAllFlags(flags: Long?) = Unit
        override suspend fun delete(categoryId: Long) {
            deletes++
            if (failure == "before-sql") error("SQL rejected")
            categories.removeAll { it.id == categoryId }
            if (failure == "after-sql") error("SQL commit acknowledgement rejected")
        }
    }
}
