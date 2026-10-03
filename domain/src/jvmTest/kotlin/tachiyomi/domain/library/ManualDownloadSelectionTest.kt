package tachiyomi.domain.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class ManualDownloadSelectionTest {
    private val chapters = listOf(1.0, 2.0, 2.0, 3.0, 4.0).mapIndexed { index, number ->
        Chapter.create().copy(id = index + 1L, chapterNumber = number, bookmark = true, read = index == 0)
    }

    @Test
    fun `manual eligibility precedes each limit and bookmark commands retain read chapters`() {
        val manga = Manga.create().copy(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_DESC)
        assertEquals(
            listOf(3L, 4L),
            selectManualDownloadChapters(
                chapters,
                manga,
                limit = 2,
                isQueued = { it.id == 2L },
                isDownloaded = { it.id == 5L },
            ).map { it.id },
        )
        assertEquals(
            listOf(1L, 2L),
            selectManualDownloadChapters(
                chapters,
                manga,
                bookmarkedOnly = true,
                limit = 2,
                isQueued = { false },
                isDownloaded = { false },
            ).map { it.id },
        )
        assertEquals(
            emptyList<Long>(),
            selectManualDownloadChapters(
                chapters,
                manga,
                limit = 0,
                isQueued = { false },
                isDownloaded = { false },
            ).map { it.id },
        )
    }

    @Test
    fun `manual stable original ties do not change with display direction`() {
        for (direction in listOf(Manga.CHAPTER_SORT_ASC, Manga.CHAPTER_SORT_DESC)) {
            val manga = Manga.create().copy(chapterFlags = Manga.CHAPTER_SORTING_NUMBER or direction)
            assertEquals(
                listOf(2L),
                selectManualDownloadChapters(
                    chapters,
                    manga,
                    limit = 1,
                    isQueued = { false },
                    isDownloaded = { false },
                ).map { it.id },
            )
            assertEquals(
                listOf(3L),
                selectManualDownloadChapters(
                    chapters,
                    manga,
                    limit = 1,
                    isQueued = { false },
                    isDownloaded = { false },
                    isDownloadable = { it.id != 2L },
                ).map { it.id },
            )
        }
    }
}
