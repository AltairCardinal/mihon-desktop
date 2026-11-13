package tachiyomi.domain.download.service

import mihon.domain.reader.ChapterSkipPolicy
import mihon.domain.reader.ReaderChapterEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ReadDownloadDeletionPolicyTest {
    @Test
    fun `slots use reading positions and never jump over an unread candidate`() {
        val entries = (0L..5L).map { ReaderChapterEntry(it, isRead = it != 2L) }
        assertEquals(null, readDownloadDeletionCandidate(entries, 5, -1) { it.isRead })
        (0..4).forEach { slots ->
            assertEquals(
                if (5 - slots == 2) null else (5 - slots).toLong(),
                readDownloadDeletionCandidate(entries, 5, slots) { it.isRead }?.id,
            )
        }
        assertEquals(null, readDownloadDeletionCandidate(entries, 0, 1) { true })
    }

    @Test
    fun `catalog shares reader filtering and current chapter remains included`() {
        val entries = listOf(ReaderChapterEntry(1, isFiltered = true), ReaderChapterEntry(2), ReaderChapterEntry(3))
        assertEquals(
            listOf(2L, 3L),
            readDownloadDeletionCatalog(entries, 3, ChapterSkipPolicy(filtered = true)).map {
                it.id
            },
        )
    }

    @Test
    fun `default duplicate and multiple categories and bookmarks share the protection rules`() {
        val read = Chapter.create().copy(id = 1, read = true)
        val bookmark = read.copy(id = 2, bookmark = true)
        val unread = read.copy(id = 3, read = false)
        val chapters = listOf(read, bookmark, unread)
        assertEquals(listOf(read, unread), filterReadDownloadDeletion(chapters, emptyList(), emptySet(), false))
        assertEquals(listOf(unread), filterReadDownloadDeletion(chapters, emptyList(), setOf("0"), true))
        assertEquals(listOf(unread), filterReadDownloadDeletion(chapters, listOf(3, 8, 3), setOf("8"), false))
        assertTrue(filterReadDownloadDeletion(listOf(bookmark), listOf(3), emptySet(), false).isEmpty())
        assertEquals(chapters, filterReadDownloadDeletion(chapters, listOf(3), emptySet(), true))
    }
}
