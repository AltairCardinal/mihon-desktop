package mihon.desktop.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.domain.manga.model.Manga

class LibrarySearchFilterTest {

    private fun libraryManga(
        id: Long = 1L,
        title: String = "Test Manga",
        totalChapters: Long = 10L,
        readCount: Long = 0L,
        lastRead: Long = 0L,
        dateAdded: Long = 0L,
        latestUpload: Long = 0L,
        status: Long = 0L,
        categories: List<Long> = listOf(0L),
    ) = LibraryManga(
        manga = Manga.create().copy(
            id = id,
            title = title,
            dateAdded = dateAdded,
            status = status,
        ),
        categories = categories,
        totalChapters = totalChapters,
        readCount = readCount,
        bookmarkCount = 0L,
        latestUpload = latestUpload,
        chapterFetchedAt = 0L,
        lastRead = lastRead,
    )

    // ── Search ────────────────────────────────────────────────────────────────

    @Test
    fun `search filters by title case-insensitively`() {
        val items = listOf(
            libraryManga(id = 1, title = "One Piece"),
            libraryManga(id = 2, title = "Naruto"),
            libraryManga(id = 3, title = "One Punch Man"),
        )
        val result = LibrarySearchFilter.applySearch(items, "one")
        assertEquals(2, result.size)
        assertTrue(result.all { "one" in it.manga.title.lowercase() })
    }

    @Test
    fun `empty search returns all items`() {
        val items = listOf(
            libraryManga(id = 1, title = "A"),
            libraryManga(id = 2, title = "B"),
        )
        assertEquals(items, LibrarySearchFilter.applySearch(items, ""))
        assertEquals(items, LibrarySearchFilter.applySearch(items, null))
    }

    // ── Filter ────────────────────────────────────────────────────────────────

    @Test
    fun `filter unread shows only manga with unread chapters`() {
        val items = listOf(
            libraryManga(id = 1, totalChapters = 10, readCount = 10), // fully read
            libraryManga(id = 2, totalChapters = 10, readCount = 3),  // has unread
            libraryManga(id = 3, totalChapters = 0, readCount = 0),   // no chapters
        )
        val result = LibrarySearchFilter.applyFilters(items, unread = true)
        assertEquals(1, result.size)
        assertEquals(2L, result[0].id)
    }

    @Test
    fun `filter started shows only manga that have been started`() {
        val items = listOf(
            libraryManga(id = 1, readCount = 0),  // not started
            libraryManga(id = 2, readCount = 5),  // started
        )
        val result = LibrarySearchFilter.applyFilters(items, started = true)
        assertEquals(1, result.size)
        assertEquals(2L, result[0].id)
    }

    @Test
    fun `filter completed shows only completed manga`() {
        val items = listOf(
            libraryManga(id = 1, status = SManga.ONGOING.toLong()),
            libraryManga(id = 2, status = SManga.COMPLETED.toLong()),
        )
        val result = LibrarySearchFilter.applyFilters(items, completed = true)
        assertEquals(1, result.size)
        assertEquals(2L, result[0].id)
    }

    @Test
    fun `no filters returns all items`() {
        val items = listOf(
            libraryManga(id = 1),
            libraryManga(id = 2),
        )
        assertEquals(items, LibrarySearchFilter.applyFilters(items))
    }

    // ── Sort ──────────────────────────────────────────────────────────────────

    @Test
    fun `sort by title ascending`() {
        val items = listOf(
            libraryManga(id = 1, title = "Zebra"),
            libraryManga(id = 2, title = "Apple"),
            libraryManga(id = 3, title = "Mango"),
        )
        val result = LibrarySearchFilter.applySort(items, SortMode.TITLE, ascending = true)
        assertEquals(listOf("Apple", "Mango", "Zebra"), result.map { it.manga.title })
    }

    @Test
    fun `sort by title descending`() {
        val items = listOf(
            libraryManga(id = 1, title = "Zebra"),
            libraryManga(id = 2, title = "Apple"),
        )
        val result = LibrarySearchFilter.applySort(items, SortMode.TITLE, ascending = false)
        assertEquals(listOf("Zebra", "Apple"), result.map { it.manga.title })
    }

    @Test
    fun `sort by unread count descending`() {
        val items = listOf(
            libraryManga(id = 1, totalChapters = 10, readCount = 8),  // 2 unread
            libraryManga(id = 2, totalChapters = 20, readCount = 5),  // 15 unread
            libraryManga(id = 3, totalChapters = 5, readCount = 5),   // 0 unread
        )
        val result = LibrarySearchFilter.applySort(items, SortMode.UNREAD_COUNT, ascending = false)
        assertEquals(listOf(2L, 1L, 3L), result.map { it.id })
    }

    @Test
    fun `sort by date added descending`() {
        val items = listOf(
            libraryManga(id = 1, dateAdded = 100L),
            libraryManga(id = 2, dateAdded = 300L),
            libraryManga(id = 3, dateAdded = 200L),
        )
        val result = LibrarySearchFilter.applySort(items, SortMode.DATE_ADDED, ascending = false)
        assertEquals(listOf(2L, 3L, 1L), result.map { it.id })
    }

    @Test
    fun `sort by last read descending`() {
        val items = listOf(
            libraryManga(id = 1, lastRead = 50L),
            libraryManga(id = 2, lastRead = 200L),
            libraryManga(id = 3, lastRead = 100L),
        )
        val result = LibrarySearchFilter.applySort(items, SortMode.LAST_READ, ascending = false)
        assertEquals(listOf(2L, 3L, 1L), result.map { it.id })
    }

    // ── Category filter ──────────────────────────────────────────────────────

    @Test
    fun `filter by category shows only matching manga`() {
        val items = listOf(
            libraryManga(id = 1, categories = listOf(1L, 2L)),
            libraryManga(id = 2, categories = listOf(2L)),
            libraryManga(id = 3, categories = listOf(3L)),
        )
        val result = LibrarySearchFilter.applyCategory(items, categoryId = 2L)
        assertEquals(2, result.size)
        assertEquals(listOf(1L, 2L), result.map { it.id })
    }

    @Test
    fun `filter by uncategorized (0) returns items with category 0`() {
        val items = listOf(
            libraryManga(id = 1, categories = listOf(0L)),
            libraryManga(id = 2, categories = listOf(1L)),
        )
        val result = LibrarySearchFilter.applyCategory(items, categoryId = 0L)
        assertEquals(1, result.size)
        assertEquals(1L, result[0].id)
    }

    // ── Downloaded filter ────────────────────────────────────────────────────

    @Test
    fun `filter downloaded keeps only manga whose id is in the downloaded set`() {
        val items = listOf(
            libraryManga(id = 1),
            libraryManga(id = 2),
            libraryManga(id = 3),
        )
        val result = LibrarySearchFilter.applyFilters(items, downloaded = true, downloadedMangaIds = setOf(2L))
        assertEquals(1, result.size)
        assertEquals(2L, result[0].id)
    }

    @Test
    fun `filter downloaded false returns all items regardless of downloaded set`() {
        val items = listOf(libraryManga(id = 1), libraryManga(id = 2))
        val result = LibrarySearchFilter.applyFilters(items, downloaded = false, downloadedMangaIds = setOf(1L))
        assertEquals(2, result.size)
    }

    @Test
    fun `apply passes downloadedMangaIds to downloaded filter`() {
        val items = listOf(libraryManga(id = 10), libraryManga(id = 20))
        val result = LibrarySearchFilter.apply(items, downloaded = true, downloadedMangaIds = setOf(10L))
        assertEquals(1, result.size)
        assertEquals(10L, result[0].id)
    }

    // ── Combined pipeline ────────────────────────────────────────────────────

    @Test
    fun `apply combines search + filter + sort`() {
        val items = listOf(
            libraryManga(id = 1, title = "One Piece", totalChapters = 10, readCount = 3, dateAdded = 100),
            libraryManga(id = 2, title = "One Punch Man", totalChapters = 10, readCount = 10, dateAdded = 200),
            libraryManga(id = 3, title = "Naruto", totalChapters = 10, readCount = 5, dateAdded = 50),
        )
        val result = LibrarySearchFilter.apply(
            items = items,
            searchQuery = "one",
            unread = true,
            sortMode = SortMode.DATE_ADDED,
            ascending = false,
        )
        // "One Piece" matches search + has unread; "One Punch Man" matches search but fully read
        assertEquals(1, result.size)
        assertEquals("One Piece", result[0].manga.title)
    }
}
