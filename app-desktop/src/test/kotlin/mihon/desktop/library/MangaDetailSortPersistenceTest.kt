package mihon.desktop.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

/**
 * Tests for manga chapter sort mode persistence.
 * Verifies that sort mode changes are correctly mapped to Manga.chapterFlags.
 */
class MangaDetailSortPersistenceTest {

    /**
     * Verify that Manga.chapterFlags correctly stores sorting mode.
     * Sorting mode is stored in bits 8-9 of chapterFlags.
     */
    @Test
    fun `Manga sorting is stored in chapterFlags`() {
        // BY_SOURCE_ORDER = 0x00000000L (bits 8-9 = 00)
        val sourceOrderManga = createMangaWithSort(
            Manga.CHAPTER_SORTING_SOURCE,
            Manga.CHAPTER_SORT_DESC,
        )
        assertEquals(0L, sourceOrderManga.sorting)
        assertTrue(sourceOrderManga.sortDescending())

        // BY_CHAPTER_NUMBER = 0x00000100L (bits 8-9 = 01)
        val chapterNumberManga = createMangaWithSort(
            Manga.CHAPTER_SORTING_NUMBER,
            Manga.CHAPTER_SORT_ASC,
        )
        assertEquals(Manga.CHAPTER_SORTING_NUMBER, chapterNumberManga.sorting)
        assertFalse(chapterNumberManga.sortDescending())

        // BY_UPLOAD_DATE = 0x00000200L (bits 8-9 = 10)
        val uploadDateManga = createMangaWithSort(
            Manga.CHAPTER_SORTING_UPLOAD_DATE,
            Manga.CHAPTER_SORT_DESC,
        )
        assertEquals(Manga.CHAPTER_SORTING_UPLOAD_DATE, uploadDateManga.sorting)
        assertTrue(uploadDateManga.sortDescending())
    }

    /**
     * Verify that sort direction is stored correctly.
     * Sort direction is stored in bit 0 of chapterFlags.
     */
    @Test
    fun `Manga sort direction is stored correctly`() {
        val descManga = createMangaWithSort(Manga.CHAPTER_SORTING_NUMBER, Manga.CHAPTER_SORT_DESC)
        assertTrue(descManga.sortDescending())

        val ascManga = createMangaWithSort(Manga.CHAPTER_SORTING_NUMBER, Manga.CHAPTER_SORT_ASC)
        assertFalse(ascManga.sortDescending())
    }

    /**
     * Verify that sorting mode bits don't interfere with other chapterFlags bits.
     */
    @Test
    fun `sorting bits are independent of other chapterFlags`() {
        // Set some filter flags + sorting
        val unreadFilter = Manga.CHAPTER_SHOW_UNREAD
        val downloadedFilter = Manga.CHAPTER_SHOW_DOWNLOADED
        val sorting = Manga.CHAPTER_SORTING_UPLOAD_DATE
        val sortDir = Manga.CHAPTER_SORT_ASC

        val combinedFlags = 0L
            .setFlag(unreadFilter, Manga.CHAPTER_UNREAD_MASK)
            .setFlag(downloadedFilter, Manga.CHAPTER_DOWNLOADED_MASK)
            .setFlag(sorting, Manga.CHAPTER_SORTING_MASK)
            .setFlag(sortDir, Manga.CHAPTER_SORT_DIR_MASK)

        val manga = createManga(combinedFlags)

        // Verify sorting is still correct
        assertEquals(Manga.CHAPTER_SORTING_UPLOAD_DATE, manga.sorting)
        assertFalse(manga.sortDescending())
    }

    /**
     * Verify ChapterSortMode to Manga flag mapping.
     */
    @Test
    fun `ChapterSortMode maps to correct Manga flags`() {
        // Source order
        assertEquals(Manga.CHAPTER_SORTING_SOURCE, 0x00000000L)

        // Chapter number
        assertEquals(Manga.CHAPTER_SORTING_NUMBER, 0x00000100L)

        // Upload date
        assertEquals(Manga.CHAPTER_SORTING_UPLOAD_DATE, 0x00000200L)
    }

    /**
     * Verify that toggling the same sort mode flips direction.
     */
    @Test
    fun `toggling same sort mode flips direction`() {
        // Start with ascending
        var flags = createFlags(sorting = Manga.CHAPTER_SORTING_NUMBER, ascending = true)
        var manga = createManga(flags)
        assertFalse(manga.sortDescending())

        // Toggle: same mode, should flip to descending
        val newFlags = manga.chapterFlags.let {
            val orderFlag = if (manga.sortDescending()) Manga.CHAPTER_SORT_ASC else Manga.CHAPTER_SORT_DESC
            it.setFlag(orderFlag, Manga.CHAPTER_SORT_DIR_MASK)
        }
        manga = createManga(newFlags)
        assertTrue(manga.sortDescending())
    }

    /**
     * Verify that changing sort mode resets to ascending.
     */
    @Test
    fun `changing sort mode resets to ascending`() {
        // Start with chapter number descending
        var flags = createFlags(sorting = Manga.CHAPTER_SORTING_NUMBER, ascending = false)
        var manga = createManga(flags)
        assertEquals(Manga.CHAPTER_SORTING_NUMBER, manga.sorting)
        assertTrue(manga.sortDescending())

        // Switch to upload date: should reset to ascending
        val newFlags = manga.chapterFlags
            .setFlag(Manga.CHAPTER_SORTING_UPLOAD_DATE, Manga.CHAPTER_SORTING_MASK)
            .setFlag(Manga.CHAPTER_SORT_ASC, Manga.CHAPTER_SORT_DIR_MASK)
        manga = createManga(newFlags)
        assertEquals(Manga.CHAPTER_SORTING_UPLOAD_DATE, manga.sorting)
        assertFalse(manga.sortDescending())
    }

    private fun createMangaWithSort(sortingMode: Long, sortDirection: Long): Manga {
        val flags = 0L
            .setFlag(sortingMode, Manga.CHAPTER_SORTING_MASK)
            .setFlag(sortDirection, Manga.CHAPTER_SORT_DIR_MASK)
        return createManga(flags)
    }

    private fun createFlags(sorting: Long, ascending: Boolean): Long {
        return 0L
            .setFlag(sorting, Manga.CHAPTER_SORTING_MASK)
            .setFlag(if (ascending) Manga.CHAPTER_SORT_ASC else Manga.CHAPTER_SORT_DESC, Manga.CHAPTER_SORT_DIR_MASK)
    }

    private fun createManga(chapterFlags: Long): Manga {
        return Manga(
            id = 1L,
            source = 1L,
            favorite = true,
            lastUpdate = 0L,
            nextUpdate = 0L,
            fetchInterval = 0,
            dateAdded = 0L,
            viewerFlags = 0L,
            chapterFlags = chapterFlags,
            coverLastModified = 0L,
            url = "",
            title = "Test Manga",
            artist = null,
            author = null,
            description = null,
            genre = null,
            status = 0L,
            thumbnailUrl = null,
            updateStrategy = eu.kanade.tachiyomi.source.model.UpdateStrategy.ALWAYS_UPDATE,
            initialized = true,
            lastModifiedAt = 0L,
            favoriteModifiedAt = null,
            version = 0L,
            notes = "",
        )
    }

    private fun Long.setFlag(flag: Long, mask: Long): Long {
        return this and mask.inv() or (flag and mask)
    }
}
