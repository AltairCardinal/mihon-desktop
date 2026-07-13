package tachiyomi.domain.library.interactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

class AggregateLibraryStatsTest {
    private val aggregate = AggregateLibraryStats()

    @Test
    fun `empty library has zero totals and no groups`() {
        assertEquals(LibraryStats(), aggregate(emptyList()))
    }

    @Test
    fun `distinct titles aggregate categories sources statuses and chapters`() {
        val first = item(1, source = 10, status = 1, categories = listOf(2, 3), total = 8, read = 3)
        val duplicateCategoryRow = first.copy(categories = listOf(4))
        val second = item(2, source = 11, status = 2, categories = listOf(3), total = 5, read = 5)

        val result = aggregate(listOf(first, duplicateCategoryRow, second))

        assertEquals(2, result.mangaCount)
        assertEquals(2, result.startedCount)
        assertEquals(13, result.totalChapters)
        assertEquals(8, result.readChapters)
        assertEquals(mapOf(2L to 1, 3L to 2, 4L to 1), result.byCategory)
        assertEquals(mapOf(10L to 1, 11L to 1), result.bySource)
        assertEquals(mapOf(1L to 1, 2L to 1), result.byStatus)
    }

    private fun item(
        id: Long,
        source: Long,
        status: Long,
        categories: List<Long>,
        total: Long,
        read: Long,
    ) = LibraryManga(
        manga = Manga.create().copy(id = id, source = source, status = status),
        categories = categories,
        totalChapters = total,
        readCount = read,
        bookmarkCount = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
        lastRead = 0,
    )
}
