package mihon.desktop.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.missingChaptersCount

class MangaDetailChapterRowsTest {
    @Test
    fun `missing rows use unique recognized numbers without changing actual display order`() {
        val chapters = listOf(
            chapter(5L, 5.0),
            chapter(8L, 8.0),
            chapter(80L, 8.5),
            chapter(90L, -1.0).copy(name = "Chapter 90"),
            chapter(3L, 3.0),
            chapter(50L, 5.0),
        )
        val expected = chapters.map {
            if (it.isRecognizedNumber) {
                it.chapterNumber
            } else {
                tachiyomi.domain.chapter.service.ChapterRecognition.parseChapterNumber("", it.name, null)
            }
        }.missingChaptersCount()
        assertEquals(86, expected)
        for (ascending in listOf(true, false)) {
            val rows = mangaDetailChapterRows(chapters, ascending, hideMissingChapters = false)
            assertEquals(chapters.map { it.id }, realChapterIds(rows))
            assertEquals(
                expected,
                rows.filterIsInstance<MangaDetailChapterListRow.MissingCountRow>().sumOf {
                    it.count
                },
            )
        }
    }

    @Test
    fun `unrecognized names never manufacture chapter gaps`() {
        val chapters = listOf(
            chapter(8L, -1.0).copy(name = "Special"),
            chapter(3L, -1.0).copy(name = "Bonus"),
        )
        assertEquals(
            0,
            mangaDetailChapterRows(chapters, ascending = false, hideMissingChapters = false)
                .filterIsInstance<MangaDetailChapterListRow.MissingCountRow>().sumOf { it.count },
        )
        assertEquals(0, chapters.map { it.chapterNumber }.missingChaptersCount())
    }

    @Test
    fun `descending final recognized chapter includes gap down to chapter one`() {
        val rows = mangaDetailChapterRows(
            chapters = listOf(chapter(8L, 8.0), chapter(8_1L, 8.5), chapter(3L, 3.0)),
            ascending = false,
            hideMissingChapters = false,
        )

        assertEquals(
            listOf("chapter:8", "chapter:81", "missing:4", "chapter:3", "missing:2"),
            rows.labels(),
        )
        assertEquals(6, rows.filterIsInstance<MangaDetailChapterListRow.MissingCountRow>().sumOf { it.count })
    }

    @Test
    fun `inserts missing row between visible chapter gaps`() {
        val rows = mangaDetailChapterRows(
            chapters = listOf(chapter(1L, 1.0), chapter(2L, 2.0), chapter(5L, 5.0)),
            ascending = true,
            hideMissingChapters = false,
        )

        assertEquals(
            listOf("chapter:1", "chapter:2", "missing:2", "chapter:5"),
            rows.labels(),
        )
    }

    @Test
    fun `inserts leading missing row before first recognized chapter in ascending order`() {
        val rows = mangaDetailChapterRows(
            chapters = listOf(chapter(3L, 3.0), chapter(4L, 4.0)),
            ascending = true,
            hideMissingChapters = false,
        )

        assertEquals(listOf("missing:2", "chapter:3", "chapter:4"), rows.labels())
    }

    @Test
    fun `does not create indicators for unknown or negative chapters`() {
        val rows = mangaDetailChapterRows(
            chapters = listOf(
                chapter(10L, -1.0).copy(name = "Special"),
                chapter(11L, -2.0).copy(name = "Bonus"),
            ),
            ascending = true,
            hideMissingChapters = false,
        )

        assertEquals(listOf("chapter:10", "chapter:11"), rows.labels())
    }

    @Test
    fun `descending order places missing row between adjacent displayed chapters`() {
        val rows = mangaDetailChapterRows(
            chapters = listOf(chapter(5L, 5.0), chapter(2L, 2.0), chapter(1L, 1.0)),
            ascending = false,
            hideMissingChapters = false,
        )

        assertEquals(
            listOf("chapter:5", "missing:2", "chapter:2", "chapter:1"),
            rows.labels(),
        )
    }

    @Test
    fun `descending volume gap from twenty two to sixteen inserts missing row`() {
        val rows = mangaDetailChapterRows(
            chapters = listOf(chapter(22L, 22.0), chapter(16L, 16.0)),
            ascending = false,
            hideMissingChapters = false,
        )

        assertEquals(listOf("chapter:22", "missing:5", "chapter:16", "missing:15"), rows.labels())
    }

    @Test
    fun `falls back to chapter name recognition for cached unrecognized volume numbers`() {
        val rows = mangaDetailChapterRows(
            chapters = listOf(
                chapter(22L, -1.0).copy(name = "第22卷"),
                chapter(16L, -1.0).copy(name = "第16卷"),
            ),
            ascending = false,
            hideMissingChapters = false,
        )

        assertEquals(listOf("chapter:22", "missing:5", "chapter:16", "missing:15"), rows.labels())
        assertEquals(20, listOf(22.0, 16.0).missingChaptersCount())
    }

    @Test
    fun `hide setting returns only chapter rows`() {
        val rows = mangaDetailChapterRows(
            chapters = listOf(chapter(1L, 1.0), chapter(5L, 5.0)),
            ascending = true,
            hideMissingChapters = true,
        )

        assertEquals(listOf("chapter:1", "chapter:5"), rows.labels())
    }

    @Test
    fun `realChapterIds excludes missing rows`() {
        val rows = mangaDetailChapterRows(
            chapters = listOf(chapter(1L, 1.0), chapter(5L, 5.0)),
            ascending = true,
            hideMissingChapters = false,
        )

        assertEquals(listOf(1L, 5L), realChapterIds(rows))
        assertTrue(rows.any { it is MangaDetailChapterListRow.MissingCountRow })
    }

    @Test
    fun `real chapter ids preserve displayed chapter order`() {
        val rows = mangaDetailChapterRows(
            chapters = listOf(chapter(5L, 5.0), chapter(2L, 2.0), chapter(1L, 1.0)),
            ascending = false,
            hideMissingChapters = false,
        )

        assertEquals(listOf(5L, 2L, 1L), realChapterIds(rows))
    }

    private fun chapter(id: Long, chapterNumber: Double): Chapter =
        Chapter.create().copy(
            id = id,
            name = "Chapter $chapterNumber",
            chapterNumber = chapterNumber,
        )

    private fun List<MangaDetailChapterListRow>.labels(): List<String> =
        map { row ->
            when (row) {
                is MangaDetailChapterListRow.ChapterRow -> "chapter:${row.chapter.id}"
                is MangaDetailChapterListRow.MissingCountRow -> "missing:${row.count}"
            }
        }
}
