package mihon.desktop.domain

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import mihon.desktop.domain.fakes.FakeCategoryRepository
import mihon.desktop.domain.fakes.FakeMangaRepository
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category

class BatchSetCategoriesTest {

    private val categoryRepo = FakeCategoryRepository()
    private val mangaRepo = FakeMangaRepository()
    private val setMangaCategories = SetMangaCategories(mangaRepo)

    @Test
    fun `batch set categories assigns all selected manga to chosen categories`() = runTest {
        val categoryIds = listOf(1L, 3L)
        val mangaIds = listOf(10L, 20L, 30L)

        batchSetCategories(mangaIds, categoryIds, setMangaCategories)

        mangaIds.forEach { mangaId ->
            assertEquals(categoryIds, mangaRepo.getMangaCategoryIds(mangaId))
        }
    }

    @Test
    fun `batch set empty categories clears all category assignments`() = runTest {
        val mangaIds = listOf(10L, 20L)

        // First assign categories
        batchSetCategories(mangaIds, listOf(1L, 2L), setMangaCategories)
        // Then clear
        batchSetCategories(mangaIds, emptyList(), setMangaCategories)

        mangaIds.forEach { mangaId ->
            assertEquals(emptyList<Long>(), mangaRepo.getMangaCategoryIds(mangaId))
        }
    }

    @Test
    fun `batch set with empty manga list does nothing`() = runTest {
        batchSetCategories(emptyList(), listOf(1L), setMangaCategories)
        // No crash, no state change
    }
}
