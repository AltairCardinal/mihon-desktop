package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import mihon.domain.reader.ReaderTransitionDirection
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterWindowIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ReaderChapterWindowOwnerTest {

    @Test
    fun `Android owner keeps overlapping chapter sessions and releases only chapters outside the new window`() {
        val previous = retainedChapter(1)
        val current = retainedChapter(2)
        val next = retainedChapter(3)
        val replacement = retainedChapter(4)
        val owner = ReaderChapterWindowOwner()

        owner.replace(ViewerChapters(current, previous, next))
        owner.dispatch(
            ReaderChapterWindowIntent.OpenAdjacent(
                direction = ReaderTransitionDirection.NEXT,
                expectedCurrentChapterId = ReaderChapterId(2),
                expectedTargetChapterId = ReaderChapterId(3),
                replacementChapterId = ReaderChapterId(4),
            ),
            availableChapters = listOf(previous, current, next, replacement).map(RetainedChapter::chapter),
        )

        assertTrue(previous.loader.isRecycled)
        assertFalse(current.loader.isRecycled)
        assertFalse(next.loader.isRecycled)
        assertFalse(replacement.loader.isRecycled)
        assertEquals(
            ViewerChapters(currChapter = next, prevChapter = current, nextChapter = replacement),
            owner.viewerChapters(),
        )

        owner.close()

        assertTrue(current.loader.isRecycled)
        assertTrue(next.loader.isRecycled)
        assertTrue(replacement.loader.isRecycled)
    }

    private fun retainedChapter(id: Long): RetainedChapter {
        val chapter = ReaderChapter(Chapter.create().copy(id = id, mangaId = 1))
        val loader = TestPageLoader()
        chapter.pageLoader = loader
        return RetainedChapter(chapter, loader)
    }

    private data class RetainedChapter(
        val chapter: ReaderChapter,
        val loader: TestPageLoader,
    )

    private class TestPageLoader : PageLoader() {
        override var isLocal = false

        override suspend fun getPages() = emptyList<ReaderPage>()
    }

    private fun ViewerChapters(
        currChapter: RetainedChapter,
        prevChapter: RetainedChapter?,
        nextChapter: RetainedChapter?,
    ) = ViewerChapters(currChapter.chapter, prevChapter?.chapter, nextChapter?.chapter)
}
