package mihon.desktop.ui.reader

import mihon.desktop.reader.DesktopReaderChapterContext
import mihon.desktop.reader.DesktopReaderSessionState
import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.ReaderNavigator
import mihon.domain.reader.ReaderNavigationCommand
import mihon.domain.reader.ReaderTransitionDirection
import mihon.domain.reader.materialize.ReaderChapterMaterializeResult
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderPageDescriptor
import mihon.domain.reader.session.ReaderSessionCore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopReaderChapterTransitionIntegrationTest {

    @Test
    fun `next chapter activates inside the same core at zero pages without a replacement screen`() {
        val chapters = chapters()
        val core = ReaderSessionCore(ReaderChapterId(1L), sessionId = "transition-test")
        val initialContext = context(chapters[1], chapterIndex = 1, initialPage = 0)
        lateinit var model: ReaderScreenModel
        val activations = mutableListOf<DesktopReaderChapterContext>()
        model = ReaderScreenModel(
            initialSessionState = DesktopReaderSessionState(initialContext, core.snapshot),
            onChapterActivated = { target ->
                activations += target
                DesktopReaderSessionState(target, core.openChapter(ReaderChapterId(target.chapterId)).snapshot)
            },
        )
        val screen = DesktopReaderScreen(
            chapterTitle = "Chapter 1",
            mangaTitle = "Manga",
            chapterId = 1L,
            chapterUrl = "/1",
            chapterNumber = 1.0,
            sourceId = 9L,
            chapters = chapters,
            currentChapterIndex = 1,
        )

        val activated = screen.requestAdjacentChapterTransition(
            ReaderTransitionDirection.NEXT,
            model,
            ReaderNavigator(chapters, currentIndex = 1),
        )

        assertTrue(activated)
        assertEquals(2L, activations.single().chapterId)
        assertEquals(ReaderInitialPage.FIRST, activations.single().initialPage)
        assertSame(core, core)
        assertEquals(ReaderChapterId(2L), model.state.value.session.activeChapter.id)
        assertTrue(model.state.value.session.activeChapter.pages.isEmpty())
        assertInstanceOf(
            ReaderChapterLoadState.LoadingPageList::class.java,
            model.state.value.session.activeChapter.loadState,
        )
        assertNull(model.state.value.chapterTransition)
    }

    @Test
    fun `previous chapter resolves last page after its stable page list arrives`() {
        val chapters = chapters()
        val core = ReaderSessionCore(ReaderChapterId(2L), sessionId = "previous-transition-test")
        val initialContext = context(chapters[0], chapterIndex = 0, initialPage = 0)
        lateinit var model: ReaderScreenModel
        model = ReaderScreenModel(
            initialSessionState = DesktopReaderSessionState(initialContext, core.snapshot),
            onChapterActivated = { target ->
                DesktopReaderSessionState(target, core.openChapter(ReaderChapterId(target.chapterId)).snapshot)
            },
        )
        val screen = DesktopReaderScreen(
            chapterTitle = "Chapter 2",
            mangaTitle = "Manga",
            chapterId = 2L,
            chapterUrl = "/2",
            chapterNumber = 2.0,
            sourceId = 9L,
            chapters = chapters,
            currentChapterIndex = 0,
        )

        assertTrue(
            screen.requestAdjacentChapterTransition(
                ReaderTransitionDirection.PREVIOUS,
                model,
                ReaderNavigator(chapters, currentIndex = 0),
            ),
        )
        val opening = core.snapshot
        assertTrue(opening.activeChapter.pages.isEmpty())
        val loaded = core.acceptChapterMaterialization(
            chapterId = ReaderChapterId(1L),
            generation = opening.generation,
            result = ReaderChapterMaterializeResult.Loaded(List(4) { ReaderPageDescriptor(it, url = "/1/$it") }),
        ).snapshot
        model.acceptSessionState(DesktopReaderSessionState(model.state.value.context, loaded))

        assertEquals(3, model.state.value.currentPage)
        assertEquals(4, model.state.value.session.activeChapter.pages.size)
    }

    @Test
    fun `chapter boundaries do not activate and keep explicit feedback`() {
        val only = listOf(ReaderChapterRef(id = 1L, url = "/1", name = "Chapter 1", chapterNumber = 1.0))
        val context = context(only.single(), chapterIndex = 0, initialPage = 0)
        var activations = 0
        val model = ReaderScreenModel(
            initialSessionState = DesktopReaderSessionState(
                context,
                ReaderSessionCore(ReaderChapterId(1L), sessionId = "boundary-test").snapshot,
            ),
            onChapterActivated = { activations++; null },
        )
        val screen = DesktopReaderScreen(
            chapterTitle = "Chapter 1",
            chapterId = 1L,
            chapterUrl = "/1",
            chapterNumber = 1.0,
            chapters = only,
            currentChapterIndex = 0,
        )
        val navigator = ReaderNavigator(only, currentIndex = 0)

        val previous = screen.requestAdjacentChapterTransition(ReaderTransitionDirection.PREVIOUS, model, navigator)
        assertFalse(previous)
        assertEquals(
            ReaderNavigationCommand.ChapterBoundary(ReaderTransitionDirection.PREVIOUS),
            model.chapterTransitionCommand(),
        )

        val next = screen.requestAdjacentChapterTransition(ReaderTransitionDirection.NEXT, model, navigator)
        assertFalse(next)
        assertEquals(
            ReaderNavigationCommand.ChapterBoundary(ReaderTransitionDirection.NEXT),
            model.chapterTransitionCommand(),
        )
        assertEquals(0, activations)
    }

    private fun chapters() = listOf(
        ReaderChapterRef(id = 2L, url = "/2", name = "Chapter 2", chapterNumber = 2.0),
        ReaderChapterRef(id = 1L, url = "/1", name = "Chapter 1", chapterNumber = 1.0),
    )

    private fun context(ref: ReaderChapterRef, chapterIndex: Int, initialPage: Int) = DesktopReaderChapterContext(
        chapterId = ref.id,
        sourceId = 9L,
        chapterUrl = ref.url,
        mangaTitle = "Manga",
        chapterTitle = ref.name,
        chapterNumber = ref.chapterNumber,
        chapterIndex = chapterIndex,
        initialPage = initialPage,
        wasRead = ref.isRead,
    )
}
