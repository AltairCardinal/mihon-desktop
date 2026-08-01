package mihon.desktop.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ChapterReadPresentationTest {

    @Test
    fun `partial chapter shows progress ring and one based page`() {
        val chapter = Chapter.create().copy(read = false, lastPageRead = 4)

        val presentation = chapterReadPresentation(chapter)

        assertEquals(ChapterReadIndicator.PROGRESS_RING, presentation.indicator)
        assertEquals(5L, presentation.pageNumber)
    }

    @Test
    fun `finished chapter shows read check without partial progress`() {
        val chapter = Chapter.create().copy(read = true, lastPageRead = 9)

        val presentation = chapterReadPresentation(chapter)

        assertEquals(ChapterReadIndicator.READ_CHECK, presentation.indicator)
        assertNull(presentation.pageNumber)
    }

    @Test
    fun `untouched chapter shows unread dot without progress text`() {
        val presentation = chapterReadPresentation(Chapter.create())

        assertEquals(ChapterReadIndicator.UNREAD_DOT, presentation.indicator)
        assertNull(presentation.pageNumber)
    }
}
