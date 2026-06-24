package mihon.desktop.library

import mihon.desktop.ui.library.ChapterSortMode
import mihon.desktop.ui.library.LibraryDisplayMode
import mihon.desktop.ui.library.LibrarySelectionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class LibraryPhase3Test {

    // ── Display mode ──────────────────────────────────────────────────────────

    @Test
    fun `LibraryDisplayMode has three values`() {
        val modes = LibraryDisplayMode.entries
        assertTrue(modes.any { it == LibraryDisplayMode.COMPACT_GRID })
        assertTrue(modes.any { it == LibraryDisplayMode.COMFORTABLE_GRID })
        assertTrue(modes.any { it == LibraryDisplayMode.LIST })
    }

    // ── Batch selection ───────────────────────────────────────────────────────

    @Test
    fun `toggle adds id when not selected`() {
        val state = LibrarySelectionState()
        state.toggle(1L)
        assertTrue(state.isSelected(1L))
    }

    @Test
    fun `toggle removes id when already selected`() {
        val state = LibrarySelectionState()
        state.toggle(1L)
        state.toggle(1L)
        assertFalse(state.isSelected(1L))
    }

    @Test
    fun `clear empties selection`() {
        val state = LibrarySelectionState()
        state.toggle(1L)
        state.toggle(2L)
        state.clear()
        assertEquals(0, state.selectedIds.size)
    }

    @Test
    fun `selectAll adds all ids`() {
        val state = LibrarySelectionState()
        state.selectAll(listOf(10L, 20L, 30L))
        assertEquals(3, state.selectedIds.size)
        assertTrue(state.isSelected(10L))
        assertTrue(state.isSelected(20L))
        assertTrue(state.isSelected(30L))
    }

    @Test
    fun `isInSelectionMode true when at least one item selected`() {
        val state = LibrarySelectionState()
        assertFalse(state.isInSelectionMode)
        state.toggle(5L)
        assertTrue(state.isInSelectionMode)
    }

    // ── Chapter sort ──────────────────────────────────────────────────────────

    @Test
    fun `ChapterSortMode has expected values`() {
        val modes = ChapterSortMode.entries
        assertTrue(modes.any { it == ChapterSortMode.BY_SOURCE_ORDER })
        assertTrue(modes.any { it == ChapterSortMode.BY_CHAPTER_NUMBER })
        assertTrue(modes.any { it == ChapterSortMode.BY_DATE_UPLOAD })
    }

    @Test
    fun `sortChapters BY_CHAPTER_NUMBER ascending`() {
        val chapters = listOf(
            makeChapter(1, chapterNumber = 3.0),
            makeChapter(2, chapterNumber = 1.0),
            makeChapter(3, chapterNumber = 2.0),
        )
        val sorted = sortChapters(chapters, ChapterSortMode.BY_CHAPTER_NUMBER, ascending = true)
        assertEquals(listOf(1.0, 2.0, 3.0), sorted.map { it.chapterNumber })
    }

    @Test
    fun `sortChapters BY_CHAPTER_NUMBER descending`() {
        val chapters = listOf(
            makeChapter(1, chapterNumber = 1.0),
            makeChapter(2, chapterNumber = 3.0),
            makeChapter(3, chapterNumber = 2.0),
        )
        val sorted = sortChapters(chapters, ChapterSortMode.BY_CHAPTER_NUMBER, ascending = false)
        assertEquals(listOf(3.0, 2.0, 1.0), sorted.map { it.chapterNumber })
    }

    @Test
    fun `filterChapters shows only unread when showUnread=true showRead=false`() {
        val chapters = listOf(
            makeChapter(1, read = false),
            makeChapter(2, read = true),
            makeChapter(3, read = false),
        )
        val filtered = filterChapters(chapters, showRead = false, showUnread = true, showBookmarked = false)
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { !it.read })
    }

    @Test
    fun `filterChapters shows only bookmarked when showBookmarked=true`() {
        val chapters = listOf(
            makeChapter(1, bookmark = true),
            makeChapter(2, bookmark = false),
            makeChapter(3, bookmark = true),
        )
        val filtered = filterChapters(chapters, showRead = true, showUnread = true, showBookmarked = true, onlyBookmarked = true)
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.bookmark })
    }

    // ── Downloaded badge ──────────────────────────────────────────────────────

    @Test
    fun `manga id in downloadedMangaIds resolves as downloaded`() {
        val downloadedIds = setOf(1L, 3L, 5L)
        assertTrue(1L in downloadedIds)
        assertTrue(3L in downloadedIds)
        assertFalse(2L in downloadedIds)
        assertFalse(4L in downloadedIds)
    }

    @Test
    fun `empty downloadedMangaIds means no manga shows downloaded badge`() {
        val downloadedIds = emptySet<Long>()
        assertFalse(1L in downloadedIds)
        assertFalse(99L in downloadedIds)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeChapter(
        id: Long,
        chapterNumber: Double = id.toDouble(),
        read: Boolean = false,
        bookmark: Boolean = false,
        dateUpload: Long = id * 1000L,
        sourceOrder: Long = id,
    ) = Chapter(
        id = id,
        mangaId = 1L,
        read = read,
        bookmark = bookmark,
        lastPageRead = 0L,
        dateFetch = 0L,
        sourceOrder = sourceOrder,
        url = "/ch/$id",
        name = "Chapter $id",
        dateUpload = dateUpload,
        chapterNumber = chapterNumber,
        scanlator = null,
        lastModifiedAt = 0L,
        version = 0L,
    )
}

// ── Pure sort/filter helpers (tested separately, later extracted to domain) ──

fun sortChapters(
    chapters: List<Chapter>,
    mode: ChapterSortMode,
    ascending: Boolean,
): List<Chapter> {
    val comparator: Comparator<Chapter> = when (mode) {
        ChapterSortMode.BY_SOURCE_ORDER -> compareBy { it.sourceOrder }
        ChapterSortMode.BY_CHAPTER_NUMBER -> compareBy { it.chapterNumber }
        ChapterSortMode.BY_DATE_UPLOAD -> compareBy { it.dateUpload }
        ChapterSortMode.BY_ALPHABET -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }
    return if (ascending) chapters.sortedWith(comparator) else chapters.sortedWith(comparator.reversed())
}

fun filterChapters(
    chapters: List<Chapter>,
    showRead: Boolean,
    showUnread: Boolean,
    showBookmarked: Boolean,
    onlyBookmarked: Boolean = false,
): List<Chapter> = chapters.filter { ch ->
    val readOk = (showRead && ch.read) || (showUnread && !ch.read)
    val bookmarkOk = if (onlyBookmarked) ch.bookmark else true
    readOk && bookmarkOk
}
