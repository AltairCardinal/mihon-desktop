package mihon.desktop.ui.reader

import kotlinx.coroutines.test.runTest
import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.ReaderNavigator
import mihon.domain.reader.ReaderChapterState
import mihon.domain.reader.ReaderNavigationCommand
import mihon.domain.reader.ReaderTransitionDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopReaderChapterTransitionIntegrationTest {

    @Test
    fun `next chapter enters target immediately at zero pages before page count arrives`() = runTest {
        val chapters = listOf(
            ReaderChapterRef(id = 2L, url = "/2", name = "Chapter 2", chapterNumber = 2.0),
            ReaderChapterRef(id = 1L, url = "/1", name = "Chapter 1", chapterNumber = 1.0),
        )
        var legacyAdjacentLoadCalls = 0
        val model = ReaderScreenModel(
            adjacentChapterLoader = AdjacentChapterLoader {
                legacyAdjacentLoadCalls++
                ReaderChapterState.Loaded(emptyList())
            },
        )
        val screen = DesktopReaderScreen(
            chapterTitle = "Chapter 1",
            chapterId = 1L,
            chapterUrl = "/1",
            chapterNumber = 1.0,
            sourceId = 9L,
            chapters = chapters,
            currentChapterIndex = 1,
        )

        val result: Any? = screen.requestAdjacentChapterTransition(
            ReaderTransitionDirection.NEXT,
            model,
            ReaderNavigator(chapters, currentIndex = 1),
        )

        val destination = assertInstanceOf(DesktopReaderScreen::class.java, result)
        assertEquals(2L, destination.chapterId)
        assertEquals("/2", destination.chapterUrl)
        assertEquals(emptyList<String>(), destination.pageUrls)
        assertEquals(ReaderInitialPage.FIRST, destination.initialPage)
        assertEquals(0, legacyAdjacentLoadCalls)
        assertNull(model.state.value.chapterTransition)

        val targetModel = ReaderScreenModel(
            pageUrls = destination.pageUrls,
            initialPage = destination.initialPage,
            sourceId = destination.sourceId,
            chapterUrl = destination.chapterUrl,
        )
        assertEquals(ReaderChapterState.Loading, targetModel.state.value.chapterState)
        assertTrue(targetModel.state.value.resolvedUrls.isEmpty())

        targetModel.setLoadingPageSlots(totalPages = 3, initialPage = destination.initialPage)

        assertEquals(List(3) { "" }, targetModel.state.value.resolvedUrls)
        assertEquals(0, targetModel.state.value.currentPage)
    }

    @Test
    fun `previous chapter also enters immediately and resolves its last page after page count arrives`() = runTest {
        val chapters = listOf(
            ReaderChapterRef(id = 2L, url = "/2", name = "Chapter 2", chapterNumber = 2.0),
            ReaderChapterRef(id = 1L, url = "/1", name = "Chapter 1", chapterNumber = 1.0),
        )
        val model = ReaderScreenModel()
        val screen = DesktopReaderScreen(
            chapterTitle = "Chapter 2",
            chapterId = 2L,
            chapterUrl = "/2",
            chapterNumber = 2.0,
            sourceId = 9L,
            chapters = chapters,
            currentChapterIndex = 0,
        )

        val result: Any? = screen.requestAdjacentChapterTransition(
            ReaderTransitionDirection.PREVIOUS,
            model,
            ReaderNavigator(chapters, currentIndex = 0),
        )

        val destination = assertInstanceOf(DesktopReaderScreen::class.java, result)
        assertEquals(1L, destination.chapterId)
        assertEquals(ReaderInitialPage.LAST, destination.initialPage)

        val targetModel = ReaderScreenModel(
            initialPage = destination.initialPage,
            sourceId = destination.sourceId,
            chapterUrl = destination.chapterUrl,
        )
        assertTrue(targetModel.state.value.resolvedUrls.isEmpty())
        targetModel.setLoadingPageSlots(totalPages = 4, initialPage = destination.initialPage)
        assertEquals(3, targetModel.state.value.currentPage)
    }

    @Test
    fun `chapter boundaries return no destination and keep explicit feedback`() = runTest {
        val chapters = listOf(
            ReaderChapterRef(id = 1L, url = "/1", name = "Chapter 1", chapterNumber = 1.0),
        )
        var legacyAdjacentLoadCalls = 0
        val model = ReaderScreenModel(
            adjacentChapterLoader = AdjacentChapterLoader {
                legacyAdjacentLoadCalls++
                ReaderChapterState.Loaded(emptyList())
            },
        )
        val screen = DesktopReaderScreen(
            chapterTitle = "Chapter 1",
            chapterId = 1L,
            chapterUrl = "/1",
            chapterNumber = 1.0,
            chapters = chapters,
            currentChapterIndex = 0,
        )
        val navigator = ReaderNavigator(chapters, currentIndex = 0)

        val previous: Any? = screen.requestAdjacentChapterTransition(
            ReaderTransitionDirection.PREVIOUS,
            model,
            navigator,
        )
        assertNull(previous)
        assertNull(model.state.value.chapterTransition?.to)
        assertEquals(
            ReaderNavigationCommand.ChapterBoundary(ReaderTransitionDirection.PREVIOUS),
            model.chapterTransitionCommand(),
        )

        val next: Any? = screen.requestAdjacentChapterTransition(
            ReaderTransitionDirection.NEXT,
            model,
            navigator,
        )
        assertNull(next)
        assertNull(model.state.value.chapterTransition?.to)
        assertEquals(ReaderTransitionDirection.NEXT, model.state.value.chapterTransition?.direction)
        assertEquals(
            ReaderNavigationCommand.ChapterBoundary(ReaderTransitionDirection.NEXT),
            model.chapterTransitionCommand(),
        )
        assertEquals(0, legacyAdjacentLoadCalls)
    }
}
