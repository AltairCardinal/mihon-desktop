package tachiyomi.domain.chapter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChapterItemClickPolicyTest {

    @Test
    fun `an unselected chapter opens when no chapter is selected`() {
        assertEquals(
            ChapterItemClickAction.READ,
            chapterItemClickAction(chapterSelected = false, anyChapterSelected = false),
        )
    }

    @Test
    fun `an unselected chapter is selected when selection mode is active`() {
        assertEquals(
            ChapterItemClickAction.SELECT,
            chapterItemClickAction(chapterSelected = false, anyChapterSelected = true),
        )
    }

    @Test
    fun `a selected chapter is deselected before the active selection mode branch`() {
        assertEquals(
            ChapterItemClickAction.DESELECT,
            chapterItemClickAction(chapterSelected = true, anyChapterSelected = true),
        )
    }
}
