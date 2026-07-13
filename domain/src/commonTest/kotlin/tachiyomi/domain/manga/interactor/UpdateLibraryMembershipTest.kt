package tachiyomi.domain.manga.interactor

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class UpdateLibraryMembershipTest {
    @Test
    fun `adding favorite synchronizes selected categories`() = runTest {
        var update: tachiyomi.domain.manga.model.MangaUpdate? = null
        var categories: List<Long>? = null
        val useCase = UpdateLibraryMembership(
            update = {
                update = it
                true
            },
            setCategories = { _, ids -> categories = ids },
        )
        val result = useCase.await(
            Manga.create().copy(id = 1L, favorite = false),
            favorite = true,
            categoryIds = listOf(10L, 11L),
            nowMillis = 123L,
        )

        assertTrue(result is LibraryMembershipResult.Success)
        assertEquals(listOf(10L, 11L), categories)
        assertEquals(true, update?.favorite)
        assertEquals(123L, update?.dateAdded)
    }

    @Test
    fun `removing favorite clears category links`() = runTest {
        var update: tachiyomi.domain.manga.model.MangaUpdate? = null
        var categories: List<Long>? = null
        val useCase = UpdateLibraryMembership(
            update = {
                update = it
                true
            },
            setCategories = { _, ids -> categories = ids },
        )
        val manga = Manga.create().copy(id = 1L, favorite = true, dateAdded = 99L)

        useCase.await(manga, favorite = false)

        assertEquals(emptyList<Long>(), categories)
        assertEquals(false, update?.favorite)
        assertEquals(99L, update?.dateAdded)
    }
}
