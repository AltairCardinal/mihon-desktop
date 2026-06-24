package mihon.desktop.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class MangaChapterSortTest {

    @Test
    fun `source order ascending sorts newest to oldest`() {
        val chapters = listOf(
            chapter(id = 1L, sourceOrder = 1L),
            chapter(id = 3L, sourceOrder = 3L),
            chapter(id = 2L, sourceOrder = 2L),
        )

        val sorted = sortMangaDetailChapters(
            chapters = chapters,
            mode = ChapterSortMode.BY_SOURCE_ORDER,
            ascending = true,
        )

        assertEquals(listOf(3L, 2L, 1L), sorted.map { it.sourceOrder })
    }

    @Test
    fun `source order descending sorts oldest to newest`() {
        val chapters = listOf(
            chapter(id = 1L, sourceOrder = 1L),
            chapter(id = 3L, sourceOrder = 3L),
            chapter(id = 2L, sourceOrder = 2L),
        )

        val sorted = sortMangaDetailChapters(
            chapters = chapters,
            mode = ChapterSortMode.BY_SOURCE_ORDER,
            ascending = false,
        )

        assertEquals(listOf(1L, 2L, 3L), sorted.map { it.sourceOrder })
    }

    private fun chapter(id: Long, sourceOrder: Long): Chapter =
        Chapter.create().copy(
            id = id,
            sourceOrder = sourceOrder,
            name = "Chapter $id",
        )
}
