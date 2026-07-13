package tachiyomi.domain.manga.interactor

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.LibraryMembershipUpdate

class UpdateLibraryMembershipTest {
    @Test
    fun `membership delegates favorite date and categories as one atomic request`() = runTest {
        var request: LibraryMembershipUpdate? = null
        val useCase = UpdateLibraryMembership { request = it }

        useCase.await(
            Manga.create().copy(id = 1L, favorite = false),
            favorite = true,
            categoryIds = listOf(10L, 10L, 11L),
            nowMillis = 123L,
        )

        assertEquals(LibraryMembershipUpdate(1L, true, 123L, listOf(10L, 11L)), request)
    }

    @Test
    fun `adding favorite synchronizes selected categories`() = runTest {
        var update: LibraryMembershipUpdate? = null
        val useCase = UpdateLibraryMembership { update = it }
        val result = useCase.await(
            Manga.create().copy(id = 1L, favorite = false),
            favorite = true,
            categoryIds = listOf(10L, 11L),
            nowMillis = 123L,
        )

        assertTrue(result is LibraryMembershipResult.Success)
        assertEquals(listOf(10L, 11L), update?.categoryIds)
        assertEquals(true, update?.favorite)
        assertEquals(123L, update?.dateAdded)
    }

    @Test
    fun `removing favorite clears category links`() = runTest {
        var update: LibraryMembershipUpdate? = null
        val useCase = UpdateLibraryMembership { update = it }
        val manga = Manga.create().copy(id = 1L, favorite = true, dateAdded = 99L)

        useCase.await(manga, favorite = false)

        assertEquals(emptyList<Long>(), update?.categoryIds)
        assertEquals(false, update?.favorite)
        assertEquals(0L, update?.dateAdded)
    }
}
