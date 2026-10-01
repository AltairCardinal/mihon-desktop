package tachiyomi.domain.chapter.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class ChapterPreviousSelectionTest {
    private val chapters = listOf(3.0, -1.0, 2.5, 2.5, 7.0, 1.0).mapIndexed { index, number ->
        Chapter.create().copy(id = index + 1L, mangaId = 1L, chapterNumber = number)
    }

    @Test
    fun `previous uses stable identity for equal comparisons independent of display direction`() {
        for (direction in listOf(Manga.CHAPTER_SORT_ASC, Manga.CHAPTER_SORT_DESC)) {
            val manga = Manga.create().copy(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or direction)
            val displayed = chapters.sortedWith(getChapterSort(manga))
            assertEquals(listOf(2L, 6L), chaptersBeforePointer(displayed, manga, 3L).map { it.id })
            assertEquals(listOf(2L, 6L, 3L), chaptersBeforePointer(displayed, manga, 4L).map { it.id })
            assertEquals(emptyList<Long>(), chaptersBeforePointer(displayed, manga, 99L).map { it.id })
            assertEquals(emptyList<Long>(), chaptersBeforePointer(emptyList(), manga, 3L).map { it.id })
        }
    }

    @Test
    fun `previous reuses each actual shared comparator with literal eligible prefixes`() {
        val names = listOf("Z", "B", "A", "C", "D", "E")
        val actual = chapters.mapIndexed { index, chapter ->
            chapter.copy(
                name = names[index],
                sourceOrder = index + 1L,
                dateUpload = 6 - index.toLong(),
            )
        }
        val cases = listOf(
            Manga.CHAPTER_SORTING_SOURCE to listOf(6L, 5L, 4L),
            Manga.CHAPTER_SORTING_NUMBER to listOf(2L, 6L),
            Manga.CHAPTER_SORTING_UPLOAD_DATE to listOf(6L, 5L, 4L),
            Manga.CHAPTER_SORTING_ALPHABET to emptyList(),
        )
        for ((sort, expected) in cases) {
            for (direction in listOf(Manga.CHAPTER_SORT_ASC, Manga.CHAPTER_SORT_DESC)) {
                val manga = Manga.create().copy(chapterFlags = sort or direction)
                val displayed = actual.sortedWith(getChapterSort(manga))
                assertEquals(expected, chaptersBeforePointer(displayed, manga, 3L).map { it.id })
            }
        }
    }
}
