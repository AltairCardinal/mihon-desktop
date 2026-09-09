package tachiyomi.domain.library.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

class LibraryUpdateScopeTest {
    @Test
    fun `whole library applies include and exclude with exclude precedence`() {
        val library = listOf(item(1, 10), item(2, 20), item(3, 10, 20), item(4, 0))

        assertEquals(
            listOf(1L),
            selectLibraryMangaForUpdate(library, null, setOf(10), setOf(20)).map { it.manga.id },
        )
        assertEquals(
            listOf(1L, 4L),
            selectLibraryMangaForUpdate(library, null, emptySet(), setOf(20)).map { it.manga.id },
        )
    }

    @Test
    fun `explicit category ignores whole library filters and supports empty categories`() {
        val library = listOf(item(1, 10), item(2, 20), item(3, 0))

        assertEquals(
            listOf(1L),
            selectLibraryMangaForUpdate(library, 10, setOf(20), setOf(10)).map { it.manga.id },
        )
        assertEquals(
            listOf(3L),
            selectLibraryMangaForUpdate(library, 0, setOf(20), setOf(0)).map { it.manga.id },
        )
        assertEquals(emptyList<LibraryManga>(), selectLibraryMangaForUpdate(library, 30, emptySet(), emptySet()))
    }

    private fun item(id: Long, vararg categories: Long) = LibraryManga(
        manga = Manga.create().copy(id = id),
        categories = categories.toList(),
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )
}
