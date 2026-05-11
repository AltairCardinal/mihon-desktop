package mihon.desktop.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MigrateMangaLogicTest {

    @Test
    fun `buildReadChapterNumbers returns empty set when no chapters are read`() {
        val chapters = listOf(
            makeChapter("Ch 1", 1.0, read = false),
            makeChapter("Ch 2", 2.0, read = false),
        )
        assertTrue(buildReadChapterNumbers(chapters).isEmpty())
    }

    @Test
    fun `buildReadChapterNumbers includes chapter_number for read chapters`() {
        val chapters = listOf(
            makeChapter("Ch 1", 1.0, read = true),
            makeChapter("Ch 2", 2.0, read = false),
            makeChapter("Ch 3", 3.0, read = true),
        )
        assertEquals(setOf(1.0, 3.0), buildReadChapterNumbers(chapters))
    }

    @Test
    fun `shouldMarkRead returns true when chapter number is in readSet`() {
        assertTrue(shouldMarkRead(chapterNumber = 5.0, readNumbers = setOf(5.0, 10.0)))
    }

    @Test
    fun `shouldMarkRead returns false when chapter number is not in readSet`() {
        assertFalse(shouldMarkRead(chapterNumber = 7.0, readNumbers = setOf(5.0, 10.0)))
    }

    @Test
    fun `shouldMarkRead returns false for negative chapter numbers`() {
        assertFalse(shouldMarkRead(chapterNumber = -1.0, readNumbers = setOf(-1.0)))
    }

    // Helper to create a ChapterForMigration test object
    private fun makeChapter(name: String, number: Double, read: Boolean) =
        ChapterForMigration(
            id = name.hashCode().toLong(),
            name = name,
            chapterNumber = number,
            read = read,
        )
}
