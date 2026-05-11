package mihon.desktop.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryUpdateCategoryFilterTest {

    @Test
    fun `no filter includes all manga`() {
        val mangaCategories = mapOf(
            1L to listOf(10L),
            2L to listOf(20L),
            3L to emptyList(),
        )
        val result = filterMangaForUpdate(
            mangaIds = listOf(1L, 2L, 3L),
            mangaCategoryLookup = mangaCategories::getValue,
            includeCategories = emptySet(),
            excludeCategories = emptySet(),
        )
        assertEquals(listOf(1L, 2L, 3L), result)
    }

    @Test
    fun `include filter only keeps manga in included categories`() {
        val mangaCategories = mapOf(
            1L to listOf(10L),
            2L to listOf(20L),
            3L to listOf(10L, 20L),
        )
        val result = filterMangaForUpdate(
            mangaIds = listOf(1L, 2L, 3L),
            mangaCategoryLookup = mangaCategories::getValue,
            includeCategories = setOf(10L),
            excludeCategories = emptySet(),
        )
        assertEquals(listOf(1L, 3L), result)
    }

    @Test
    fun `exclude filter removes manga in excluded categories`() {
        val mangaCategories = mapOf(
            1L to listOf(10L),
            2L to listOf(20L),
            3L to listOf(10L, 20L),
        )
        val result = filterMangaForUpdate(
            mangaIds = listOf(1L, 2L, 3L),
            mangaCategoryLookup = mangaCategories::getValue,
            includeCategories = emptySet(),
            excludeCategories = setOf(20L),
        )
        assertEquals(listOf(1L), result)
    }

    @Test
    fun `include takes precedence over exclude`() {
        val mangaCategories = mapOf(
            1L to listOf(10L, 20L),
            2L to listOf(20L),
        )
        // Include 10, exclude 20 — manga 1 is in both, include wins
        val result = filterMangaForUpdate(
            mangaIds = listOf(1L, 2L),
            mangaCategoryLookup = mangaCategories::getValue,
            includeCategories = setOf(10L),
            excludeCategories = setOf(20L),
        )
        assertEquals(listOf(1L), result)
    }

    @Test
    fun `uncategorized manga included when no include filter`() {
        val mangaCategories = mapOf(
            1L to emptyList<Long>(),
            2L to listOf(20L),
        )
        val result = filterMangaForUpdate(
            mangaIds = listOf(1L, 2L),
            mangaCategoryLookup = mangaCategories::getValue,
            includeCategories = emptySet(),
            excludeCategories = setOf(20L),
        )
        assertEquals(listOf(1L), result)
    }

    @Test
    fun `empty manga list returns empty`() {
        val result = filterMangaForUpdate(
            mangaIds = emptyList(),
            mangaCategoryLookup = { emptyList() },
            includeCategories = setOf(10L),
            excludeCategories = emptySet(),
        )
        assertTrue(result.isEmpty())
    }
}
