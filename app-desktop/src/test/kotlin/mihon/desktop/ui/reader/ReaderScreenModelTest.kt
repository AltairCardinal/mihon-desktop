package mihon.desktop.ui.reader

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import mihon.desktop.reader.DesktopReaderChapterContext
import mihon.desktop.reader.DesktopReaderSessionState
import mihon.desktop.reader.ReaderBackgroundTheme
import mihon.desktop.reader.ReaderColorFilter
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.desktopReaderSessionState
import mihon.desktop.reader.viewerFlagsWithDualPage
import mihon.desktop.reader.viewerFlagsWithReadingMode
import mihon.desktop.ui.reader.presentation.DisplaySlotId
import mihon.desktop.ui.reader.presentation.DisplayUnitId
import mihon.desktop.ui.reader.presentation.ReaderPresentationMode
import mihon.desktop.ui.reader.presentation.VisiblePageSet
import mihon.desktop.ui.reader.presentation.WebtoonScrollAnchor
import mihon.desktop.ui.reader.presentation.WebtoonViewportUpdate
import mihon.domain.error.AppError
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.ReaderChapterModel
import mihon.domain.reader.ReaderChapterState
import mihon.domain.reader.ReaderChapterTransitionModel
import mihon.domain.reader.ReaderNavigationCommand
import mihon.domain.reader.ReaderTransitionDirection
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderChapterSession
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.session.ReaderSessionSnapshot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import java.util.prefs.Preferences

class ReaderScreenModelTest {

    private val preferenceRoots = mutableListOf<Preferences>()

    @AfterEach
    fun removePreferenceRoots() {
        preferenceRoots.asReversed().forEach { root -> runCatching { root.removeNode() } }
        preferenceRoots.clear()
    }

    private fun testPreferences(configure: ReaderPreferences.() -> Unit = {}): ReaderPreferences {
        val root = Preferences.userRoot().node("/mihon/reader-screen-model/${System.nanoTime()}-${preferenceRoots.size}")
        preferenceRoots += root
        return ReaderPreferences(DesktopPreferenceStore(root.node("current")), root.node("legacy")).apply(configure)
    }

    @Test
    fun `state flow exposes the canonical session snapshot`() {
        val reader = desktopReaderSessionState(chapterId = 7L, pageCount = 3, initialPage = 1)
        val model = ReaderScreenModel(initialSessionState = reader)
        val flow: StateFlow<ReaderState> = model.state

        assertNotNull(flow)
        assertSame(reader.snapshot, flow.value.session)
        assertEquals(reader.context, flow.value.context)
        assertEquals(1, flow.value.currentPage)
        assertEquals(listOf(0, 1, 2), flow.value.session.activeChapter.pages.map { it.id.sourcePageIndex })
        assertTrue(flow.value.session.activeChapter.pages.all { it.encodedPageRef != null })
    }

    @Test
    fun `zero-page loading error and loaded chapters select one canonical viewport body`() {
        assertEquals(
            ReaderViewportBody.LOADING,
            readerViewportBody(ReaderScreenModel(initialSessionState = nonLoadedState(1L, ReaderChapterLoadState.LoadingPageList)).state.value),
        )
        assertEquals(
            ReaderViewportBody.ERROR,
            readerViewportBody(
                ReaderScreenModel(
                    initialSessionState = nonLoadedState(
                        1L,
                        ReaderChapterLoadState.Error(AppError.Network()),
                    ),
                ).state.value,
            ),
        )
        assertEquals(
            ReaderViewportBody.CONTENT,
            readerViewportBody(ReaderScreenModel(initialSessionState = desktopReaderSessionState(pageCount = 2)).state.value),
        )
        assertEquals(
            ReaderViewportBody.EMPTY,
            readerViewportBody(ReaderScreenModel(initialSessionState = desktopReaderSessionState(pageCount = 0)).state.value),
        )
    }

    @Test
    fun `reader mode resolves manga flags then explicit override then global preference`() {
        val prefs = testPreferences {
            readingMode = ReadingMode.RTL
            isDualPage = true
        }
        val mangaFlags = viewerFlagsWithDualPage(
            viewerFlagsWithReadingMode(0L, ReadingMode.LTR),
            enabled = false,
        )

        val mangaModel = ReaderScreenModel(mangaViewerFlags = mangaFlags, dualPageOverride = true, prefs = prefs)
        assertEquals(ReadingMode.LTR, mangaModel.state.value.readingMode)
        assertFalse(mangaModel.state.value.dualPageMode)

        assertFalse(ReaderScreenModel(dualPageOverride = false, prefs = prefs).state.value.dualPageMode)
        assertEquals(ReadingMode.WEBTOON, ReaderScreenModel(isWebtoon = true, prefs = prefs).state.value.readingMode)
    }

    @Test
    fun `goToPage clamps against the stable canonical page list`() {
        val model = ReaderScreenModel(initialSessionState = desktopReaderSessionState(pageCount = 3))

        model.goToPage(2)
        assertEquals(2, model.state.value.currentPage)
        model.goToPage(-5)
        assertEquals(0, model.state.value.currentPage)
        model.goToPage(100)
        assertEquals(2, model.state.value.currentPage)

        val empty = ReaderScreenModel(initialSessionState = desktopReaderSessionState(pageCount = 0))
        empty.goToPage(3)
        assertEquals(0, empty.state.value.currentPage)
    }

    @Test
    fun `first stable page list resolves LAST without replacing the model`() {
        val loading = nonLoadedState(7L, ReaderChapterLoadState.LoadingPageList, initialPage = ReaderInitialPage.LAST)
        val model = ReaderScreenModel(initialSessionState = loading)
        val loaded = desktopReaderSessionState(
            chapterId = 7L,
            pageCount = 4,
            initialPage = ReaderInitialPage.LAST,
        )

        model.acceptSessionState(loaded)

        assertEquals(3, model.state.value.currentPage)
        assertSame(loaded.snapshot, model.state.value.session)
    }

    @Test
    fun `same chapter readiness updates preserve mounted display identity`() {
        val queued = desktopReaderSessionState(
            chapterId = 7L,
            pageCount = 2,
            pageLoadState = { ReaderPageLoadState.Queued },
        )
        val settled = mutableListOf<Pair<Set<ReaderPageId>, ReaderPageId>>()
        val model = ReaderScreenModel(
            initialSessionState = queued,
            onViewportSettled = { pages, active -> settled += pages to active },
        )
        val pageId = queued.snapshot.activeChapter.pages.first().id
        val displayUnit = DisplayUnitId(
            mode = ReaderPresentationMode.SINGLE_PAGED,
            slots = listOf(DisplaySlotId(pageId, PageSplitHalf.RIGHT)),
        )
        model.settleSinglePage(VisiblePageSet(displayUnit, setOf(pageId), pageId))

        model.acceptSessionState(desktopReaderSessionState(chapterId = 7L, pageCount = 2))

        assertEquals(displayUnit, model.state.value.currentDisplayUnitId)
        assertEquals(setOf(pageId), model.state.value.visiblePageIds)
        assertEquals(listOf(setOf(pageId) to pageId), settled)
    }

    @Test
    fun `load-state updates do not resettle identical viewports in any presentation mode`() {
        val queued = desktopReaderSessionState(
            chapterId = 7L,
            generation = 1L,
            pageCount = 2,
            pageLoadState = { ReaderPageLoadState.Queued },
        )
        val ready = desktopReaderSessionState(chapterId = 7L, generation = 1L, pageCount = 2)
        val pages = queued.snapshot.activeChapter.pages.map { it.id }

        var singleSettlements = 0
        val singleModel = ReaderScreenModel(
            initialSessionState = queued,
            onViewportSettled = { _, _ -> singleSettlements++ },
        )
        val singleViewport = VisiblePageSet(
            DisplayUnitId(ReaderPresentationMode.SINGLE_PAGED, listOf(DisplaySlotId(pages[0]))),
            setOf(pages[0]),
            pages[0],
        )
        singleModel.settleSinglePage(singleViewport)
        singleModel.acceptSessionState(ready)
        singleModel.settleSinglePage(singleViewport)
        assertEquals(1, singleSettlements)

        var webtoonSettlements = 0
        val webtoonModel = ReaderScreenModel(
            initialSessionState = queued,
            onViewportSettled = { _, _ -> webtoonSettlements++ },
        )
        val webtoonUnit = DisplayUnitId(ReaderPresentationMode.WEBTOON, listOf(DisplaySlotId(pages[1])))
        val webtoonViewport = WebtoonViewportUpdate(
            visiblePages = VisiblePageSet(webtoonUnit, pages.toSet(), pages[1]),
            anchor = WebtoonScrollAnchor(webtoonUnit, scrollOffset = 24),
        )
        webtoonModel.settleWebtoon(webtoonViewport)
        webtoonModel.acceptSessionState(ready)
        webtoonModel.settleWebtoon(webtoonViewport)
        assertEquals(1, webtoonSettlements)

        var dualSettlements = 0
        val dualModel = ReaderScreenModel(
            initialSessionState = queued,
            onViewportSettled = { _, _ -> dualSettlements++ },
        )
        val dualViewport = VisiblePageSet(
            DisplayUnitId(
                ReaderPresentationMode.DUAL_PAGED,
                listOf(DisplaySlotId(pages[0]), DisplaySlotId(pages[1])),
            ),
            pages.toSet(),
            pages[1],
        )
        dualModel.settleDualPage(dualViewport)
        dualModel.acceptSessionState(ready)
        dualModel.settleDualPage(dualViewport)
        assertEquals(1, dualSettlements)

        singleModel.acceptSessionState(desktopReaderSessionState(chapterId = 7L, generation = 2L, pageCount = 2))
        singleModel.settleSinglePage(singleViewport)
        assertEquals(2, singleSettlements, "A new session generation must establish a fresh viewport")
    }

    @Test
    fun `chapter switch resets presentation state while retaining one screen model`() {
        val first = desktopReaderSessionState(chapterId = 7L, pageCount = 2)
        val model = ReaderScreenModel(initialSessionState = first)
        val pageId = first.snapshot.activeChapter.pages.first().id
        val displayUnit = DisplayUnitId(ReaderPresentationMode.SINGLE_PAGED, listOf(DisplaySlotId(pageId)))
        model.settleSinglePage(VisiblePageSet(displayUnit, setOf(pageId), pageId))
        model.setForcedSinglePages(setOf(0))

        model.acceptSessionState(desktopReaderSessionState(chapterId = 8L, pageCount = 1))

        assertEquals(8L, model.state.value.context.chapterId)
        assertNull(model.state.value.currentDisplayUnitId)
        assertTrue(model.state.value.visiblePageIds.isEmpty())
        assertTrue(model.state.value.forcedSinglePages.isEmpty())
    }

    @Test
    fun `settled webtoon and dual viewports report canonical page identities`() {
        val reader = desktopReaderSessionState(chapterId = 7L, pageCount = 4)
        val settled = mutableListOf<Pair<Set<ReaderPageId>, ReaderPageId>>()
        val model = ReaderScreenModel(
            initialSessionState = reader,
            onViewportSettled = { pages, active -> settled += pages to active },
        )
        val pages = reader.snapshot.activeChapter.pages.map { it.id }
        val webtoonUnit = DisplayUnitId(ReaderPresentationMode.WEBTOON, listOf(DisplaySlotId(pages[1])))
        val anchor = WebtoonScrollAnchor(webtoonUnit, scrollOffset = 43)
        model.settleWebtoon(
            WebtoonViewportUpdate(
                VisiblePageSet(webtoonUnit, setOf(pages[0], pages[1]), pages[1]),
                anchor,
            ),
        )
        assertEquals(1, model.state.value.currentPage)
        assertEquals(anchor, model.state.value.webtoonScrollAnchor)

        val dualUnit = DisplayUnitId(
            ReaderPresentationMode.DUAL_PAGED,
            listOf(DisplaySlotId(pages[2]), DisplaySlotId(pages[3])),
        )
        model.settleDualPage(VisiblePageSet(dualUnit, setOf(pages[2], pages[3]), pages[3]))
        assertEquals(3, model.state.value.currentPage)
        assertNull(model.state.value.webtoonScrollAnchor)
        assertEquals(setOf(pages[2], pages[3]) to pages[3], settled.last())
    }

    @Test
    fun `retry routes chapter and page failures to their production callbacks`() {
        var chapterRetries = 0
        val pageRetries = mutableListOf<ReaderPageId>()
        val loadingModel = ReaderScreenModel(
            initialSessionState = nonLoadedState(7L, ReaderChapterLoadState.Error(AppError.Network())),
            onChapterRetry = { chapterRetries++ },
        )
        loadingModel.requestRetry()

        val failedPages = desktopReaderSessionState(
            chapterId = 7L,
            pageCount = 2,
            initialPage = 1,
            pageLoadState = { ReaderPageLoadState.Error(AppError.Network()) },
        )
        val pageModel = ReaderScreenModel(
            initialSessionState = failedPages,
            onPageRetry = pageRetries::add,
        )
        pageModel.requestRetry()

        assertEquals(1, chapterRetries)
        assertEquals(listOf(failedPages.snapshot.activeChapter.pages[1].id), pageRetries)
    }

    @Test
    fun `activateChapter delegates the target context without creating a Screen`() {
        val activations = mutableListOf<DesktopReaderChapterContext>()
        val model = ReaderScreenModel(onChapterActivated = { activations += it; null })
        val target = desktopReaderSessionState(chapterId = 9L, pageCount = 0).context

        model.activateChapter(target)

        assertEquals(listOf(target), activations)
    }

    @Test
    fun `chapter boundary and transition feedback expose retry close and no continue`() {
        val model = ReaderScreenModel()
        model.showChapterBoundary(
            direction = ReaderTransitionDirection.NEXT,
            chapterId = 1L,
            chapterUrl = "/1",
            chapterName = "Chapter 1",
            chapterNumber = 1.0,
        )
        assertEquals(
            ReaderNavigationCommand.ChapterBoundary(ReaderTransitionDirection.NEXT),
            model.chapterTransitionCommand(),
        )

        val from = ReaderChapterModel(1L, "/1", "Chapter 1", 1.0)
        val to = ReaderChapterModel(3L, "/3", "Chapter 3", 3.0)
        val loading = ReaderChapterTransitionModel(
            ReaderTransitionDirection.NEXT,
            from,
            to,
            missingChapterCount = 1,
            state = ReaderChapterState.Loading,
        )
        val error = loading.copy(state = ReaderChapterState.Error(AppError.Network(), retryTargetChapterId = to.id))
        val boundary = loading.copy(to = null, missingChapterCount = 0, state = ReaderChapterState.Wait)

        assertTrue(chapterTransitionPresentation(loading).showLoading)
        assertFalse(chapterTransitionPresentation(loading).showDismiss)
        assertTrue(chapterTransitionPresentation(error).showRetry)
        assertTrue(chapterTransitionPresentation(error).showDismiss)
        assertFalse(chapterTransitionPresentation(loading.copy(state = ReaderChapterState.Wait)).showContinue)
        assertTrue(chapterTransitionPresentation(boundary).isBoundary)
    }

    @Test
    fun `UI and display setting mutations remain local and persist when a preference is supplied`() {
        val prefs = testPreferences()
        val model = ReaderScreenModel(prefs = prefs)
        val filter = ReaderColorFilter(brightnessEnabled = true, brightness = 0.5f)

        model.toggleSettings()
        model.toggleUI()
        model.setReadingMode(ReadingMode.LTR, prefs)
        model.setDualPageMode(true, prefs)
        model.setSpreadPages(setOf(1, 3))
        model.setForcedSinglePages(setOf(2))
        model.setBackgroundTheme(ReaderBackgroundTheme.BLACK, prefs)
        model.setScaleType(ScaleType.FIT_WIDTH, prefs)
        model.setColorFilter(filter, prefs)
        model.setZoomState(mihon.desktop.reader.ZoomState(scale = 2f))

        val state = model.state.value
        assertTrue(state.showSettings)
        assertTrue(state.showUI)
        assertEquals(ReadingMode.LTR, state.readingMode)
        assertTrue(state.dualPageMode)
        assertEquals(setOf(1, 3), state.spreadPages)
        assertEquals(setOf(2), state.forcedSinglePages)
        assertEquals(ReaderBackgroundTheme.BLACK, prefs.backgroundTheme)
        assertEquals(ScaleType.FIT_WIDTH, prefs.scaleType)
        assertEquals(filter, state.colorFilter)
        assertEquals(2f, state.zoomState.scale)
    }

    @Test
    fun `webtoon mode is pinned and disabling dual page clears forced singles`() {
        val webtoon = ReaderScreenModel(isWebtoon = true)
        webtoon.setReadingMode(ReadingMode.LTR)
        assertEquals(ReadingMode.WEBTOON, webtoon.state.value.readingMode)

        val model = ReaderScreenModel()
        model.setForcedSinglePages(setOf(0, 2))
        model.setDualPageMode(false)
        assertTrue(model.state.value.forcedSinglePages.isEmpty())
    }

    @Test
    fun `reader model loads and persists all chapter skip preferences`() {
        val prefs = testPreferences {
            skipReadChapters = false
            skipFilteredChapters = true
            skipDuplicateChapters = true
        }
        val model = ReaderScreenModel(prefs = prefs)

        assertTrue(model.state.value.skipFilteredChapters)
        assertTrue(model.state.value.skipDuplicateChapters)
        model.setSkipFilteredChapters(false, prefs)
        model.setSkipDuplicateChapters(false, prefs)
        assertFalse(model.state.value.skipFilteredChapters)
        assertFalse(model.state.value.skipDuplicateChapters)
        assertFalse(prefs.skipFilteredChapters)
        assertFalse(prefs.skipDuplicateChapters)
    }

    @Test
    fun `persistViewerFlags ignores missing manga and delegates a real manga`() = runTest {
        val calls = mutableListOf<Pair<Long, Long>>()
        val model = ReaderScreenModel(persistViewerFlags = { mangaId, flags -> calls += mangaId to flags })

        model.persistViewerFlags(mangaId = 0L, flags = 6L)
        model.persistViewerFlags(mangaId = 42L, flags = 7L)

        assertEquals(listOf(42L to 7L), calls)
    }

    private fun nonLoadedState(
        chapterId: Long,
        loadState: ReaderChapterLoadState,
        initialPage: Int = 0,
        generation: Long = 1L,
    ): DesktopReaderSessionState {
        val id = ReaderChapterId(chapterId)
        return DesktopReaderSessionState(
            context = DesktopReaderChapterContext(
                chapterId = chapterId,
                sourceId = 42L,
                chapterUrl = "/chapter/$chapterId",
                mangaTitle = "Manga",
                chapterTitle = "Chapter $chapterId",
                chapterNumber = chapterId.toDouble(),
                chapterIndex = 0,
                initialPage = initialPage,
                wasRead = false,
            ),
            snapshot = ReaderSessionSnapshot(
                generation = generation,
                activeChapter = ReaderChapterSession(id, generation, loadState, emptyList()),
            ),
        )
    }
}
