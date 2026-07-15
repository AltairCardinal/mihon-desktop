package eu.kanade.tachiyomi.ui.reader.model

import mihon.domain.reader.ReaderChapterState
import mihon.domain.reader.ReaderNavigationCommand
import mihon.domain.reader.ReaderTransitionDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ReaderChapterTransitionIntegrationTest {

    @Test
    fun `current chapter error exposes the same shared retry command`() {
        val current = chapter(10)
        current.state = ReaderChapter.State.Error(IllegalStateException("current failed"))

        val error = current.sharedStateFlow.value as ReaderChapterState.Error

        assertEquals(ReaderNavigationCommand.RetryChapter(10), error.retryCommand())
    }

    @Test
    fun `previous and next errors retain their own retry target`() {
        val current = chapter(10)
        val previous = chapter(9).apply { state = ReaderChapter.State.Error(IllegalStateException("prev failed")) }
        val next = chapter(11).apply { state = ReaderChapter.State.Error(IllegalStateException("next failed")) }

        val previousCommand = ChapterTransition.Prev(current, previous)
            .toSharedTransitionModel(previous.sharedStateFlow.value)
            .retryCommand()
        val nextCommand = ChapterTransition.Next(current, next)
            .toSharedTransitionModel(next.sharedStateFlow.value)
            .retryCommand()

        assertEquals(ReaderNavigationCommand.RetryChapter(9), previousCommand)
        assertEquals(ReaderNavigationCommand.RetryChapter(11), nextCommand)
    }

    @Test
    fun `both chapter edges map to explicit shared boundaries without a target`() {
        val current = chapter(10)

        val previous = ChapterTransition.Prev(current, null).toSharedTransitionModel().retryCommand()
        val next = ChapterTransition.Next(current, null).toSharedTransitionModel().retryCommand()

        assertEquals(ReaderNavigationCommand.ChapterBoundary(ReaderTransitionDirection.PREVIOUS), previous)
        assertEquals(ReaderNavigationCommand.ChapterBoundary(ReaderTransitionDirection.NEXT), next)
    }

    private fun chapter(id: Long) = ReaderChapter(Chapter.create().copy(id = id, mangaId = 1))
}
