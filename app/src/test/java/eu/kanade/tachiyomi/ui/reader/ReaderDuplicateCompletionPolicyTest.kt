package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ReaderDuplicateCompletionPolicyTest {

    @Test
    fun `duplicate completion remains disabled independently of current chapter completion`() {
        val completed = chapter(id = 2, number = 4.0, read = true)

        val updates = duplicateChapterReadUpdates(
            chapters = listOf(completed, chapter(id = 20, number = 4.0, read = false)),
            completedChapter = completed,
            enabled = false,
        )

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `enabled duplicate completion marks only other unread chapters with the same number`() {
        val completed = chapter(id = 2, number = 4.0, read = true)

        val updates = duplicateChapterReadUpdates(
            chapters = listOf(
                completed,
                chapter(id = 20, number = 4.0, read = false),
                chapter(id = 21, number = 4.0, read = true),
                chapter(id = 30, number = 3.0, read = false),
            ),
            completedChapter = completed,
            enabled = true,
        )

        assertEquals(listOf(20L), updates.map { it.id })
        assertTrue(updates.all { it.read == true })
    }

    private fun chapter(id: Long, number: Double, read: Boolean) = Chapter.create().copy(
        id = id,
        mangaId = 1,
        chapterNumber = number,
        read = read,
    )
}
