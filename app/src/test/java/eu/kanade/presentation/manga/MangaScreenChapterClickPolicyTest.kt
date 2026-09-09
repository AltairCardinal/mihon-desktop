package eu.kanade.presentation.manga

import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.ChapterList
import org.junit.Assert.assertEquals
import org.junit.Test
import tachiyomi.domain.chapter.model.Chapter

class MangaScreenChapterClickPolicyTest {

    @Test
    fun `Android chapter row click consumes the shared three branch policy`() {
        val opened = mutableListOf<Long>()
        val selectionChanges = mutableListOf<Boolean>()

        fun invoke(selected: Boolean, anySelected: Boolean) {
            onChapterItemClick(
                chapterItem = ChapterList.Item(
                    chapter = Chapter.create().copy(id = 42L),
                    downloadState = Download.State.NOT_DOWNLOADED,
                    downloadProgress = 0,
                    selected = selected,
                ),
                isAnyChapterSelected = anySelected,
                onToggleSelection = selectionChanges::add,
                onChapterClicked = { opened += it.id },
            )
        }

        invoke(selected = false, anySelected = false)
        invoke(selected = false, anySelected = true)
        invoke(selected = true, anySelected = true)

        assertEquals(listOf(42L), opened)
        assertEquals(listOf(true, false), selectionChanges)
    }
}
