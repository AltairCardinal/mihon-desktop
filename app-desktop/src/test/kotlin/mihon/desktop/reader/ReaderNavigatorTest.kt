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
}
