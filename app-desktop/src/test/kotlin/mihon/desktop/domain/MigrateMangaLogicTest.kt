package mihon.desktop.domain

import mihon.domain.migration.MigrationChapter
import mihon.domain.migration.MigrationOrchestrator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MigrateMangaLogicTest {
    private val orchestrator = MigrationOrchestrator()

    @Test
    fun `buildReadChapterNumbers returns empty set when no chapters are read`() {
        val chapters = listOf(
            makeChapter("Ch 1", 1.0, read = false),
            makeChapter("Ch 2", 2.0, read = false),
        )
        assertTrue(orchestrator.chapterUpdates(chapters, chapters).none { it.read == true })
    }

    @Test
    fun `buildReadChapterNumbers includes chapter_number for read chapters`() {
        val chapters = listOf(
            makeChapter("Ch 1", 1.0, read = true),
            makeChapter("Ch 2", 2.0, read = false),
            makeChapter("Ch 3", 3.0, read = true),
        )
        assertEquals(listOf(null, true, null), orchestrator.chapterUpdates(chapters, chapters).map { it.read })
    }

    @Test
    fun `shouldMarkRead returns true when chapter number is in readSet`() {
        assertEquals(
            true,
            orchestrator.chapterUpdates(
                listOf(makeChapter("5", 5.0, true)),
                listOf(makeChapter("5", 5.0, false)),
            ).single().read,
        )
    }

    @Test
    fun `shouldMarkRead returns false when chapter number is not in readSet`() {
        assertEquals(
            null,
            orchestrator.chapterUpdates(
                listOf(makeChapter("5", 5.0, true)),
                listOf(makeChapter("7", 7.0, false)),
            ).single().read,
        )
    }

    @Test
    fun `shouldMarkRead returns false for negative chapter numbers`() {
        assertEquals(
            null,
            orchestrator.chapterUpdates(
                listOf(makeChapter("special", -1.0, true)),
                listOf(makeChapter("special", -1.0, false)),
            ).single().read,
        )
    }

    @Test
    fun `Desktop adapter preserves all target read states when source read progress contains NaN`() {
        val updates = orchestrator.chapterUpdates(
            listOf(
                makeChapter("2", 2.0, true),
                makeChapter("unknown", Double.NaN, true),
            ),
            listOf(
                makeChapter("1", 1.0, false),
                makeChapter("2", 2.0, false),
                makeChapter("3", 3.0, true),
            ),
        )

        assertEquals(listOf(null, null, null), updates.map { it.read })
    }

    // Helper to create a ChapterForMigration test object
    private fun makeChapter(name: String, number: Double, read: Boolean) =
        MigrationChapter(
            id = name.hashCode().toLong(),
            chapterNumber = number,
            read = read,
        )
}
