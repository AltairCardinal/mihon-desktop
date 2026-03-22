package mihon.desktop.ui.more

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

class StatsCalculatorTest {

    private fun manga(id: Long = 1L, source: Long = 1L) = Manga.create().copy(id = id, source = source)

    private fun libraryManga(
        id: Long = 1L,
        source: Long = 1L,
        totalChapters: Long = 10L,
        readCount: Long = 0L,
    ) = LibraryManga(
        manga = manga(id, source),
        categories = listOf(0L),
        totalChapters = totalChapters,
        readCount = readCount,
        bookmarkCount = 0L,
        latestUpload = 0L,
        chapterFetchedAt = 0L,
        lastRead = 0L,
    )

    @Test
    fun `manga count equals list size`() {
        val items = listOf(libraryManga(1), libraryManga(2), libraryManga(3))
        assertEquals(3, calculateStats(items).mangaCount)
    }

    @Test
    fun `started count only includes manga with readCount above zero`() {
        val items = listOf(
            libraryManga(1, readCount = 0L),
            libraryManga(2, readCount = 5L),
            libraryManga(3, readCount = 1L),
        )
        assertEquals(2, calculateStats(items).startedCount)
    }

    @Test
    fun `local count only includes manga from local source`() {
        val localSourceId = 0L
        val items = listOf(
            libraryManga(1, source = localSourceId),
            libraryManga(2, source = 99L),
            libraryManga(3, source = localSourceId),
        )
        assertEquals(2, calculateStats(items).localCount)
    }

    @Test
    fun `total chapters sums all manga`() {
        val items = listOf(
            libraryManga(1, totalChapters = 10L),
            libraryManga(2, totalChapters = 25L),
            libraryManga(3, totalChapters = 5L),
        )
        assertEquals(40L, calculateStats(items).totalChapters)
    }

    @Test
    fun `read chapters sums readCount for all manga`() {
        val items = listOf(
            libraryManga(1, totalChapters = 10L, readCount = 3L),
            libraryManga(2, totalChapters = 25L, readCount = 25L),
            libraryManga(3, totalChapters = 5L, readCount = 0L),
        )
        assertEquals(28L, calculateStats(items).readChapters)
    }

    @Test
    fun `empty library returns all zeros`() {
        val stats = calculateStats(emptyList())
        assertEquals(0, stats.mangaCount)
        assertEquals(0, stats.startedCount)
        assertEquals(0, stats.localCount)
        assertEquals(0L, stats.totalChapters)
        assertEquals(0L, stats.readChapters)
    }
}
