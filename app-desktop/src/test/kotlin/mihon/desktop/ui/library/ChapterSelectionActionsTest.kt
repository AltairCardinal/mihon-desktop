package mihon.desktop.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ChapterSelectionActionsTest {

    @Test
    fun `selection menu actions match upstream Mihon for not downloaded chapters`() {
        val actions = chapterSelectionActionTypes(ChapterSelectionDownloadAction.DOWNLOAD)

        assertEquals(
            listOf(
                ChapterSelectionActionType.BOOKMARK,
                ChapterSelectionActionType.MARK_READ,
                ChapterSelectionActionType.MARK_UNREAD,
                ChapterSelectionActionType.MARK_BELOW_READ,
                ChapterSelectionActionType.DOWNLOAD,
            ),
            actions,
        )
    }

    @Test
    fun `selection menu download action becomes delete when chapters are downloaded`() {
        val actions = chapterSelectionActionTypes(ChapterSelectionDownloadAction.DELETE_DOWNLOAD)

        assertEquals(
            listOf(
                ChapterSelectionActionType.BOOKMARK,
                ChapterSelectionActionType.MARK_READ,
                ChapterSelectionActionType.MARK_UNREAD,
                ChapterSelectionActionType.MARK_BELOW_READ,
                ChapterSelectionActionType.DELETE_DOWNLOAD,
            ),
            actions,
        )
    }

    @Test
    fun `download action is delete when every selected chapter is downloaded`() {
        val chapters = listOf(chapter(1), chapter(2))

        val action = chapterSelectionDownloadAction(chapters) { true }

        assertEquals(ChapterSelectionDownloadAction.DELETE_DOWNLOAD, action)
    }

    @Test
    fun `download action is download when any selected chapter is not downloaded`() {
        val chapters = listOf(chapter(1), chapter(2))

        val action = chapterSelectionDownloadAction(chapters) { it.id == 1L }

        assertEquals(ChapterSelectionDownloadAction.DOWNLOAD, action)
    }
}

private fun chapter(id: Long): Chapter =
    Chapter.create().copy(id = id, mangaId = 1L, name = "Chapter $id")
