package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.reader.ReaderChapterState
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class ReaderSessionProductionWiringTest {

    @Test
    fun `Android chapter publishes zero pages then the complete stable page list`() = runTest {
        val chapter = chapter(7)
        val snapshots = mutableListOf<Int>()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            chapter.sharedSessionStateFlow.collect { snapshots += it.activeChapter.pages.size }
        }
        runCurrent()

        chapter.state = ReaderChapter.State.Loading
        val first = page(chapter, index = 0, url = "")
        val second = page(chapter, index = 1, url = "/page/1").apply { status = Page.State.Ready }
        chapter.state = ReaderChapter.State.Loaded(listOf(first, second))
        runCurrent()

        val shared = chapter.sharedSessionStateFlow.value
        assertEquals(listOf(0, 0, 2), snapshots)
        assertInstanceOf(ReaderChapterLoadState.Loaded::class.java, shared.activeChapter.loadState)
        assertEquals(
            listOf(
                ReaderPageId(ReaderChapterId(7), 0),
                ReaderPageId(ReaderChapterId(7), 1),
            ),
            shared.activeChapter.pages.map { it.id },
        )
        assertEquals(listOf("", "/page/1"), shared.activeChapter.pages.map { it.url })
        assertInstanceOf(ReaderChapterState.Loaded::class.java, chapter.sharedStateFlow.value)
        collectJob.cancel()
    }

    @Test
    fun `Android page state changes retain the same shared page identity`() {
        val chapter = chapter(7)
        val page = page(chapter, index = 0, url = "")
        chapter.state = ReaderChapter.State.Loading
        chapter.state = ReaderChapter.State.Loaded(listOf(page))
        val pageId = chapter.sharedSessionStateFlow.value.activeChapter.pages.single().id

        page.status = Page.State.LoadPage
        assertEquals(ReaderPageLoadState.ResolvingImage, chapter.sharedSessionStateFlow.value.pageState(pageId))
        page.status = Page.State.DownloadImage
        page.progress = 42
        assertEquals(
            ReaderPageLoadState.Downloading(progressPercent = 42),
            chapter.sharedSessionStateFlow.value.pageState(pageId),
        )
        page.status = Page.State.Ready
        assertEquals(ReaderPageLoadState.Ready, chapter.sharedSessionStateFlow.value.pageState(pageId))
        page.status = Page.State.Error(IllegalStateException("broken"))
        assertInstanceOf(
            ReaderPageLoadState.Error::class.java,
            chapter.sharedSessionStateFlow.value.pageState(pageId),
        )
        assertEquals(pageId, chapter.sharedSessionStateFlow.value.activeChapter.pages.single().id)
    }

    @Test
    fun `Android replacement generation ignores state from an unbound old page`() {
        val chapter = chapter(7)
        val oldPage = page(chapter, index = 0, url = "/old")
        chapter.state = ReaderChapter.State.Loading
        chapter.state = ReaderChapter.State.Loaded(listOf(oldPage))
        val firstGeneration = chapter.sharedSessionStateFlow.value.generation

        chapter.state = ReaderChapter.State.Loading
        val replacementPage = page(chapter, index = 0, url = "/replacement")
        chapter.state = ReaderChapter.State.Loaded(listOf(replacementPage))
        val replacement = chapter.sharedSessionStateFlow.value

        oldPage.status = Page.State.Error(IllegalStateException("late old failure"))

        assertEquals(firstGeneration + 1, replacement.generation)
        assertSame(replacement, chapter.sharedSessionStateFlow.value)
        assertEquals("/replacement", chapter.sharedSessionStateFlow.value.activeChapter.pages.single().url)
        assertEquals(
            ReaderPageLoadState.Queued,
            chapter.sharedSessionStateFlow.value.activeChapter.pages.single().loadState,
        )
    }

    @Test
    fun `Android chapter error projects from canonical session into the existing transition state`() {
        val chapter = chapter(7)
        chapter.state = ReaderChapter.State.Loading
        chapter.state = ReaderChapter.State.Error(IllegalStateException("page list failed"))

        assertInstanceOf(
            ReaderChapterLoadState.Error::class.java,
            chapter.sharedSessionStateFlow.value.activeChapter.loadState,
        )
        assertInstanceOf(ReaderChapterState.Error::class.java, chapter.sharedStateFlow.value)
        assertEquals(emptyList<Any>(), chapter.sharedSessionStateFlow.value.activeChapter.pages)
    }

    private fun page(
        chapter: ReaderChapter,
        index: Int,
        url: String,
    ) = ReaderPage(index = index, url = url).apply { this.chapter = chapter }

    private fun chapter(id: Long) = ReaderChapter(Chapter.create().copy(id = id, mangaId = 1))
}

private fun mihon.domain.reader.session.ReaderSessionSnapshot.pageState(pageId: ReaderPageId): ReaderPageLoadState =
    activeChapter.pages.single { it.id == pageId }.loadState
