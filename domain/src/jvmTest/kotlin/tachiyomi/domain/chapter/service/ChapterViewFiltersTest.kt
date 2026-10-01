package tachiyomi.domain.chapter.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class ChapterViewFiltersTest {
    private val chapters = listOf(
        Chapter.create().copy(id = 1L, read = false, bookmark = false, sourceOrder = 2),
        Chapter.create().copy(id = 2L, read = true, bookmark = true, sourceOrder = 1),
        Chapter.create().copy(id = 3L, read = false, bookmark = true, sourceOrder = 0),
    )

    @Test
    fun `shared chapter flags preserve all include and exclude states`() {
        listOf(
            Manga.SHOW_ALL to listOf(3L, 2L, 1L),
            Manga.CHAPTER_SHOW_READ to listOf(2L),
            Manga.CHAPTER_SHOW_UNREAD to listOf(3L, 1L),
            Manga.CHAPTER_SHOW_BOOKMARKED to listOf(3L, 2L),
            Manga.CHAPTER_SHOW_NOT_BOOKMARKED to listOf(1L),
            Manga.CHAPTER_SHOW_DOWNLOADED to listOf(2L),
            Manga.CHAPTER_SHOW_NOT_DOWNLOADED to listOf(3L, 1L),
        ).forEach { (flags, expected) ->
            val manga = Manga.create().copy(chapterFlags = flags)
            assertEquals(flags != Manga.SHOW_ALL, manga.hasActiveChapterFilters(downloadedOnly = false))
            assertEquals(expected, chapters.filterAndSortChapters(manga) { it.id == 2L }.map { it.id })
        }
    }

    @Test
    fun `global download constraint overrides the local filter and local works are downloaded`() {
        val manga = Manga.create().copy(chapterFlags = Manga.CHAPTER_SHOW_NOT_DOWNLOADED)
        assertEquals(
            listOf(2L),
            chapters.filterAndSortChapters(manga, downloadedOnly = true) {
                it.id == 2L
            }.map { it.id },
        )
        assertEquals(emptyList<Long>(), chapters.filterAndSortChapters(manga, isLocal = true) { false }.map { it.id })
        assertEquals(
            listOf(3L, 2L, 1L),
            chapters.filterAndSortChapters(manga, downloadedOnly = true, isLocal = true) { false }.map { it.id },
        )
        assertEquals(Manga.CHAPTER_SHOW_NOT_DOWNLOADED, manga.downloadedFilterRaw)
        assertEquals(true, Manga.create().hasActiveChapterFilters(downloadedOnly = true))
        assertEquals(false, Manga.create().hasActiveChapterFilters(downloadedOnly = false))
    }
}
