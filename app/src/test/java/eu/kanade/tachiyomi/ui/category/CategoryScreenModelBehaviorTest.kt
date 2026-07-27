package eu.kanade.tachiyomi.ui.category

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class CategoryScreenModelBehaviorTest {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var libraryPreferences: LibraryPreferences
    private lateinit var downloadPreferences: DownloadPreferences

    @Before
    fun setUp() {
        val application = RuntimeEnvironment.getApplication()
        sharedPreferences = application.getSharedPreferences(
            "category-screen-model-${System.nanoTime()}",
            Context.MODE_PRIVATE,
        )
        val preferenceStore = AndroidPreferenceStore(application, sharedPreferences)
        libraryPreferences = LibraryPreferences(preferenceStore)
        downloadPreferences = DownloadPreferences(preferenceStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        sharedPreferences.edit().clear().commit()
    }

    @Test
    fun `production interactors drive category CRUD ordering and preference cleanup`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repository = RecordingCategoryRepository(
            listOf(
                category(id = Category.UNCATEGORIZED_ID, name = "Uncategorized", order = 0),
                category(id = 1, name = "Alpha", order = 1),
                category(id = 2, name = "Beta", order = 2),
            ),
        )
        val model = screenModel(repository)
        try {
            runCurrent()
            assertEquals(listOf("Alpha", "Beta"), model.categories().map(Category::name))

            model.createCategory("Created")
            advanceUntilIdle()
            assertEquals(listOf("Alpha", "Beta", "Created"), model.categories().map(Category::name))

            val beta = model.categories().single { it.name == "Beta" }
            model.renameCategory(beta, "Renamed")
            advanceUntilIdle()
            assertEquals(listOf("Alpha", "Renamed", "Created"), model.categories().map(Category::name))

            val created = model.categories().single { it.name == "Created" }
            model.changeOrder(created, newIndex = 0)
            advanceUntilIdle()
            assertEquals(listOf("Created", "Alpha", "Renamed"), model.categories().map(Category::name))
            assertEquals(listOf(0L, 1L, 2L), model.categories().map(Category::order))

            libraryPreferences.defaultCategory().set(beta.id.toInt())
            categoryPreferences().forEach { it.set(setOf(beta.id.toString(), "99")) }

            model.deleteCategory(beta.id)
            advanceUntilIdle()

            assertEquals(listOf("Created", "Alpha"), model.categories().map(Category::name))
            assertEquals(listOf(1L, 2L), model.categories().map(Category::order))
            assertEquals(-1, libraryPreferences.defaultCategory().get())
            categoryPreferences().forEach { assertEquals(setOf("99"), it.get()) }
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `each failed production mutation emits the explicit internal error boundary`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val alpha = category(id = 1, name = "Alpha", order = 0)
        val beta = category(id = 2, name = "Beta", order = 1)
        val repository = RecordingCategoryRepository(listOf(alpha, beta))
        val model = screenModel(repository)
        try {
            runCurrent()

            repository.failNext = RepositoryOperation.Insert
            assertInternalError(model) { model.createCategory("Failed create") }

            repository.failNext = RepositoryOperation.SingleUpdate
            assertInternalError(model) { model.renameCategory(alpha, "Failed rename") }

            repository.failNext = RepositoryOperation.BulkUpdate
            assertInternalError(model) { model.changeOrder(beta, newIndex = 0) }

            repository.failNext = RepositoryOperation.Delete
            assertInternalError(model) { model.deleteCategory(alpha.id) }

            assertEquals(listOf("Alpha", "Beta"), model.categories().map(Category::name))
        } finally {
            model.onDispose()
        }
    }

    private fun screenModel(repository: CategoryRepository): CategoryScreenModel {
        return CategoryScreenModel(
            getCategories = GetCategories(repository),
            createCategoryWithName = CreateCategoryWithName(repository, libraryPreferences),
            deleteCategory = DeleteCategory(repository, libraryPreferences, downloadPreferences),
            reorderCategory = ReorderCategory(repository),
            renameCategory = RenameCategory(repository),
        )
    }

    private suspend fun kotlinx.coroutines.test.TestScope.assertInternalError(
        model: CategoryScreenModel,
        action: () -> Unit,
    ) {
        val event = async(start = CoroutineStart.UNDISPATCHED) { model.events.first() }
        action()
        runCurrent()
        assertSame(CategoryEvent.InternalError, withTimeout(1_000) { event.await() })
    }

    private fun CategoryScreenModel.categories(): List<Category> {
        return (state.value as CategoryScreenState.Success).categories
    }

    private fun categoryPreferences() = listOf(
        libraryPreferences.updateCategories(),
        libraryPreferences.updateCategoriesExclude(),
        downloadPreferences.removeExcludeCategories(),
        downloadPreferences.downloadNewChapterCategories(),
        downloadPreferences.downloadNewChapterCategoriesExclude(),
    )

    private fun category(id: Long, name: String, order: Long): Category {
        return Category(id = id, name = name, order = order, flags = 0)
    }

    private enum class RepositoryOperation {
        Insert,
        SingleUpdate,
        BulkUpdate,
        Delete,
    }

    private class RecordingCategoryRepository(
        categories: List<Category>,
    ) : CategoryRepository {
        private val categories = MutableStateFlow(categories.sortedBy(Category::order))
        var failNext: RepositoryOperation? = null

        override suspend fun get(id: Long): Category? = categories.value.find { it.id == id }

        override suspend fun getAll(): List<Category> = categories.value

        override fun getAllAsFlow(): Flow<List<Category>> = categories

        override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> = emptyList()

        override fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>> {
            return MutableStateFlow(emptyList())
        }

        override suspend fun insert(category: Category) {
            failIfRequested(RepositoryOperation.Insert)
            val nextId = categories.value.maxOfOrNull(Category::id)?.plus(1) ?: 1
            publish(categories.value + category.copy(id = nextId))
        }

        override suspend fun updatePartial(update: CategoryUpdate) {
            failIfRequested(RepositoryOperation.SingleUpdate)
            publish(
                categories.value.map { category ->
                    if (category.id == update.id) category.withUpdate(update) else category
                },
            )
        }

        override suspend fun updatePartial(updates: List<CategoryUpdate>) {
            failIfRequested(RepositoryOperation.BulkUpdate)
            val updatesById = updates.associateBy(CategoryUpdate::id)
            publish(categories.value.map { category -> category.withUpdate(updatesById[category.id]) })
        }

        override suspend fun updateAllFlags(flags: Long?) {
            publish(categories.value.map { it.copy(flags = flags ?: it.flags) })
        }

        override suspend fun delete(categoryId: Long) {
            failIfRequested(RepositoryOperation.Delete)
            publish(categories.value.filterNot { it.id == categoryId })
        }

        private fun failIfRequested(operation: RepositoryOperation) {
            if (failNext != operation) return
            failNext = null
            throw IllegalStateException("$operation failure")
        }

        private fun publish(updated: List<Category>) {
            categories.value = updated.sortedWith(compareBy(Category::order, Category::id))
        }

        private fun Category.withUpdate(update: CategoryUpdate?): Category {
            if (update == null) return this
            return copy(
                name = update.name ?: name,
                order = update.order ?: order,
                flags = update.flags ?: flags,
            )
        }
    }
}
