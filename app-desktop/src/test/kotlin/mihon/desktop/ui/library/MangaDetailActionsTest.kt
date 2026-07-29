package mihon.desktop.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.util.Locale

class MangaDetailActionsTest {

    @Test
    fun `primary actions show upstream detail affordances`() {
        val actions = mangaDetailPrimaryActionTypes(
            isFavorite = true,
            isHttpSource = true,
            hasUnreadChapters = true,
        )

        assertEquals(
            listOf(
                MangaDetailPrimaryActionType.TOGGLE_LIBRARY,
                MangaDetailPrimaryActionType.EDIT_CATEGORIES,
                MangaDetailPrimaryActionType.EDIT_FETCH_INTERVAL,
                MangaDetailPrimaryActionType.TRACKING,
                MangaDetailPrimaryActionType.OPEN_IN_BROWSER,
                MangaDetailPrimaryActionType.COPY_LINK,
                MangaDetailPrimaryActionType.SHARE,
                MangaDetailPrimaryActionType.CONTINUE_READING,
            ),
            actions,
        )
    }

    @Test
    fun `non favorite manga hides category and fetch interval actions`() {
        val actions = mangaDetailPrimaryActionTypes(
            isFavorite = false,
            isHttpSource = true,
            hasUnreadChapters = false,
        )

        assertTrue(MangaDetailPrimaryActionType.TOGGLE_LIBRARY in actions)
        assertFalse(MangaDetailPrimaryActionType.EDIT_CATEGORIES in actions)
        assertFalse(MangaDetailPrimaryActionType.EDIT_FETCH_INTERVAL in actions)
        assertFalse(MangaDetailPrimaryActionType.CONTINUE_READING in actions)
    }

    @Test
    fun `download menu matches upstream bulk download actions`() {
        assertEquals(
            listOf(
                MangaDetailDownloadAction.NEXT_1_CHAPTER,
                MangaDetailDownloadAction.NEXT_5_CHAPTERS,
                MangaDetailDownloadAction.NEXT_10_CHAPTERS,
                MangaDetailDownloadAction.NEXT_25_CHAPTERS,
                MangaDetailDownloadAction.UNREAD_CHAPTERS,
                MangaDetailDownloadAction.BOOKMARKED_CHAPTERS,
            ),
            mangaDetailDownloadActions(),
        )
    }

    @Test
    fun `bulk download action chooses expected chapters`() {
        val chapters = listOf(
            chapter(id = 1, sourceOrder = 1, read = false, bookmark = false),
            chapter(id = 2, sourceOrder = 2, read = false, bookmark = true),
            chapter(id = 3, sourceOrder = 3, read = true, bookmark = true),
        )

        assertEquals(listOf(1L), chaptersForDownloadAction(chapters, MangaDetailDownloadAction.NEXT_1_CHAPTER).map { it.id })
        assertEquals(listOf(1L, 2L), chaptersForDownloadAction(chapters, MangaDetailDownloadAction.UNREAD_CHAPTERS).map { it.id })
        assertEquals(listOf(2L), chaptersForDownloadAction(chapters, MangaDetailDownloadAction.BOOKMARKED_CHAPTERS).map { it.id })
    }

    @Test
    fun `next unread chapter follows displayed order`() {
        val chapters = listOf(
            chapter(id = 1, sourceOrder = 1, read = true),
            chapter(id = 2, sourceOrder = 2, read = false),
        )

        assertEquals(2L, nextUnreadChapter(chapters)?.id)
        assertNull(nextUnreadChapter(chapters.map { it.copy(read = true) }))
    }

    @Test
    fun `chapter display title can use chapter number`() {
        val chapter = chapter(id = 7, name = "Release title", chapterNumber = 12.5)

        assertEquals("Release title", chapterDisplayTitle(chapter, Manga.CHAPTER_DISPLAY_NAME))
        assertEquals("Ch. 12.5", chapterDisplayTitle(chapter, Manga.CHAPTER_DISPLAY_NUMBER, Locale.ENGLISH))
    }
}

private fun chapter(
    id: Long,
    name: String = "Chapter $id",
    sourceOrder: Long = id,
    read: Boolean = false,
    bookmark: Boolean = false,
    chapterNumber: Double = id.toDouble(),
): Chapter =
    Chapter.create().copy(
        id = id,
        mangaId = 1L,
        name = name,
        sourceOrder = sourceOrder,
        read = read,
        bookmark = bookmark,
        chapterNumber = chapterNumber,
    )
