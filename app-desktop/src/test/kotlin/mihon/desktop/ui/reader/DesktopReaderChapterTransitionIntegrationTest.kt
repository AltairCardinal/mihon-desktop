package mihon.desktop.ui.reader

import kotlinx.coroutines.test.runTest
import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.ReaderNavigator
import mihon.domain.error.AppError
import mihon.domain.reader.ReaderChapterState
import mihon.domain.reader.ReaderNavigationCommand
import mihon.domain.reader.ReaderPageModel
import mihon.domain.reader.ReaderTransitionDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopReaderChapterTransitionIntegrationTest {

    @Test
    fun `production adjacent chain publishes loading error retry loaded and navigates with loaded pages`() = runTest {
        val repository = FakeChapterRepository(
            listOf(
                ReaderChapterRef(id = 2L, url = "/2", name = "Chapter 2", chapterNumber = 2.0),
                ReaderChapterRef(id = 1L, url = "/1", name = "Chapter 1", chapterNumber = 1.0),
            ),
        )
        val responses = ArrayDeque<ReaderChapterState>().apply {
            add(ReaderChapterState.Error(AppError.Network(), retryTargetChapterId = 2L))
            add(ReaderChapterState.Loaded(listOf(ReaderPageModel(0, imageUrl = "file:///chapter-2-page.jpg"))))
        }
        val loadingStatesSeenByLoader = mutableListOf<ReaderChapterState>()
        lateinit var model: ReaderScreenModel
        val loader = AdjacentChapterLoader { chapter ->
            assertEquals(2L, chapter.id)
            loadingStatesSeenByLoader += requireNotNull(model.state.value.chapterTransition).state
            responses.removeFirst()
        }
        model = ReaderScreenModel(adjacentChapterLoader = loader)
        val screen = DesktopReaderScreen(
            chapterTitle = "Chapter 1",
            chapterId = 1L,
            chapterUrl = "/1",
            chapterNumber = 1.0,
            sourceId = 9L,
            chapters = repository.chapters,
            currentChapterIndex = 1,
        )
        val navigator = ReaderNavigator(repository.chapters, currentIndex = 1)

        screen.requestAdjacentChapterTransition(ReaderTransitionDirection.NEXT, model, navigator)

        assertEquals(listOf(ReaderChapterState.Loading), loadingStatesSeenByLoader)
        assertTrue(model.state.value.chapterTransition?.state is ReaderChapterState.Error)
        assertEquals(ReaderNavigationCommand.RetryChapter(2L), model.chapterTransitionCommand())

        val retry = model.retryChapterTransition()

        assertEquals(ReaderNavigationCommand.RetryChapter(2L), retry)
        assertEquals(
            listOf(ReaderChapterState.Loading, ReaderChapterState.Loading),
            loadingStatesSeenByLoader,
        )
        assertTrue(model.state.value.chapterTransition?.state is ReaderChapterState.Loaded)

        val destination = screen.destinationForChapterTransition(
            transition = requireNotNull(model.state.value.chapterTransition),
            viewerFlags = 0L,
        )

        assertEquals(2L, destination?.chapterId)
        assertEquals(listOf("file:///chapter-2-page.jpg"), destination?.pageUrls)
    }

    @Test
    fun `both adjacent boundaries never invoke the production loader`() = runTest {
        val repository = FakeChapterRepository(
            listOf(ReaderChapterRef(id = 1L, url = "/1", name = "Chapter 1", chapterNumber = 1.0)),
        )
        var loadCalls = 0
        val model = ReaderScreenModel(
            adjacentChapterLoader = AdjacentChapterLoader {
                loadCalls++
                ReaderChapterState.Loaded(emptyList())
            },
        )
        val screen = DesktopReaderScreen(
            chapterTitle = "Chapter 1",
            chapterId = 1L,
            chapterUrl = "/1",
            chapterNumber = 1.0,
            chapters = repository.chapters,
            currentChapterIndex = 0,
        )
        val navigator = ReaderNavigator(repository.chapters, currentIndex = 0)

        screen.requestAdjacentChapterTransition(ReaderTransitionDirection.PREVIOUS, model, navigator)
        assertNull(model.state.value.chapterTransition?.to)
        assertEquals(ReaderNavigationCommand.ChapterBoundary(ReaderTransitionDirection.PREVIOUS), model.chapterTransitionCommand())

        screen.requestAdjacentChapterTransition(ReaderTransitionDirection.NEXT, model, navigator)
        assertNull(model.state.value.chapterTransition?.to)
        assertEquals(ReaderNavigationCommand.ChapterBoundary(ReaderTransitionDirection.NEXT), model.chapterTransitionCommand())
        assertEquals(0, loadCalls)
    }

    private class FakeChapterRepository(val chapters: List<ReaderChapterRef>)
}
