package mihon.desktop.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** RED — ReaderNavigator and ReaderChapterRef do not exist yet. */
class ReaderNavigatorTest {

    // Chapters are stored in the same order as MangaDetailScreen:
    // sortedByDescending { sourceOrder }, so index 0 = newest chapter.
    // "Next to read" = lower index (newer), "Previous" = higher index (older).

    private val chapters = listOf(
        ReaderChapterRef(id = 3L, url = "/ch/3", name = "Chapter 3"),
        ReaderChapterRef(id = 2L, url = "/ch/2", name = "Chapter 2"),
        ReaderChapterRef(id = 1L, url = "/ch/1", name = "Chapter 1"),
    )

    @Test
    fun `current returns correct chapter`() {
        val nav = ReaderNavigator(chapters, currentIndex = 1)
        assertEquals(chapters[1], nav.current)
    }

    @Test
    fun `nextToRead returns lower index (newer) when not at start`() {
        val nav = ReaderNavigator(chapters, currentIndex = 1)
        assertEquals(chapters[0], nav.nextToRead)
    }

    @Test
    fun `nextToRead is null when at index 0 (newest chapter)`() {
        val nav = ReaderNavigator(chapters, currentIndex = 0)
        assertNull(nav.nextToRead)
    }

    @Test
    fun `previousRead returns higher index (older) when not at end`() {
        val nav = ReaderNavigator(chapters, currentIndex = 1)
        assertEquals(chapters[2], nav.previousRead)
    }

    @Test
    fun `previousRead is null when at last index (oldest chapter)`() {
        val nav = ReaderNavigator(chapters, currentIndex = 2)
        assertNull(nav.previousRead)
    }

    @Test
    fun `single chapter list has no next or previous`() {
        val single = listOf(ReaderChapterRef(id = 1L, url = "/ch/1", name = "Chapter 1"))
        val nav = ReaderNavigator(single, currentIndex = 0)
        assertNull(nav.nextToRead)
        assertNull(nav.previousRead)
    }

    @Test
    fun `indexForId returns correct index`() {
        val idx = ReaderNavigator.indexForId(chapters, targetId = 2L)
        assertEquals(1, idx)
    }

    @Test
    fun `indexForId returns 0 when id not found (safe fallback)`() {
        val idx = ReaderNavigator.indexForId(chapters, targetId = 99L)
        assertEquals(0, idx)
    }

    // ── Skip read chapters ───────────────────────────────────────────────────

    private val chaptersWithRead = listOf(
        ReaderChapterRef(id = 5L, url = "/ch/5", name = "Chapter 5", isRead = false),
        ReaderChapterRef(id = 4L, url = "/ch/4", name = "Chapter 4", isRead = true),
        ReaderChapterRef(id = 3L, url = "/ch/3", name = "Chapter 3", isRead = true),
        ReaderChapterRef(id = 2L, url = "/ch/2", name = "Chapter 2", isRead = false),
        ReaderChapterRef(id = 1L, url = "/ch/1", name = "Chapter 1", isRead = false),
    )

    @Test
    fun `nextToRead skips read chapters when skipRead is true`() {
        // Current = ch4 (index 1, read). Next would be ch5 (index 0, unread).
        val nav = ReaderNavigator(chaptersWithRead, currentIndex = 1, skipReadChapters = true)
        assertEquals(chaptersWithRead[0], nav.nextToRead) // ch5 unread
    }

    @Test
    fun `previousRead skips read chapters when skipRead is true`() {
        // Current = ch4 (index 1). Previous in order: ch3 (idx 2, read), ch2 (idx 3, unread).
        val nav = ReaderNavigator(chaptersWithRead, currentIndex = 1, skipReadChapters = true)
        assertEquals(chaptersWithRead[3], nav.previousRead) // ch2 unread
    }

    @Test
    fun `nextToRead returns null when all remaining are read and skipRead is true`() {
        // Current = ch2 (index 3). Next toward index 0: ch3 (read), ch4 (read), ch5 (unread) — ch5 should be found
        val nav = ReaderNavigator(chaptersWithRead, currentIndex = 3, skipReadChapters = true)
        assertEquals(chaptersWithRead[0], nav.nextToRead)
    }

    @Test
    fun `previousRead returns null when all remaining are read and skipRead is true`() {
        val allRead = listOf(
            ReaderChapterRef(id = 3L, url = "/ch/3", name = "Ch3", isRead = false),
            ReaderChapterRef(id = 2L, url = "/ch/2", name = "Ch2", isRead = true),
            ReaderChapterRef(id = 1L, url = "/ch/1", name = "Ch1", isRead = true),
        )
        // Current = ch3 (index 0). Previous: ch2 (read), ch1 (read) — all read.
        val nav = ReaderNavigator(allRead, currentIndex = 0, skipReadChapters = true)
        assertNull(nav.previousRead)
    }

    @Test
    fun `skip does not affect navigation when skipRead is false`() {
        val nav = ReaderNavigator(chaptersWithRead, currentIndex = 1, skipReadChapters = false)
        assertEquals(chaptersWithRead[0], nav.nextToRead)
        assertEquals(chaptersWithRead[2], nav.previousRead)
    }
}
