package mihon.desktop.ui.reader

import io.mockk.mockk
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import mihon.desktop.reader.DesktopReaderPageLoader
import mihon.desktop.reader.ReaderBackgroundTheme
import mihon.desktop.reader.ReaderColorFilter
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.WebtoonSidePadding
import mihon.desktop.reader.ZoomState
import mihon.desktop.reader.viewerFlagsWithDualPage
import mihon.desktop.reader.viewerFlagsWithReadingMode
import mihon.desktop.ui.reader.presentation.DisplaySlotId
import mihon.desktop.ui.reader.presentation.DisplayUnitId
import mihon.desktop.ui.reader.presentation.ReaderPresentationMode
import mihon.desktop.ui.reader.presentation.VisiblePageSet
import mihon.desktop.ui.reader.presentation.WebtoonScrollAnchor
import mihon.desktop.ui.reader.presentation.WebtoonViewportUpdate
import mihon.domain.reader.ReaderChapterState
import mihon.domain.reader.ReaderNavigationCommand
import mihon.domain.reader.ReaderTransitionDirection
import mihon.domain.error.AppError
import mihon.domain.reader.PageSplitHalf
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.i18n.MR
import java.util.Locale
import java.util.prefs.Preferences

/**
 * Stage 25.0 — ReaderScreenModel tests.
 *
 * Verifies that all reader state lives in a Voyager ScreenModel with a
 * StateFlow<ReaderState> and mutation methods, enabling JVM unit tests
 * for all state transitions without Compose or DI.
 */
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

    // ── Construction ────────────────────────────────────────────────────────

    @Test
    fun `state flow exists and is accessible`() {
        val model = ReaderScreenModel()
        val flow: StateFlow<ReaderState> = model.state
        assertNotNull(flow)
        assertNotNull(flow.value)
    }

    @Test
    fun `initial state reflects pageUrls param`() {
        val model = ReaderScreenModel(
            pageUrls = listOf("url1", "url2", "url3"),
            initialPage = 1,
        )
        val state = model.state.value
        assertEquals(listOf("url1", "url2", "url3"), state.resolvedUrls)
        assertEquals(1, state.currentPage)
        assertFalse(state.isLoadingPages)
    }

    @Test
    fun `initial state marks loading when no urls and sourceId provided`() {
        val model = ReaderScreenModel(
            pageUrls = emptyList(),
            sourceId = 42L,
            chapterUrl = "/chapter/1",
        )
        assertTrue(model.state.value.isLoadingPages)
    }

    @Test
    fun `reader defaults to fixed main single page mode when enhancement is unset`() {
        val prefs = testPreferences()
        val model = ReaderScreenModel(prefs = prefs)

        assertFalse(model.state.value.dualPageMode)
    }

    @Test
    fun `desktop dual page enhancement follows explicit global preference`() {
        val prefs = testPreferences { isDualPage = false }
        assertFalse(ReaderScreenModel(prefs = prefs).state.value.dualPageMode)

        prefs.isDualPage = true
        assertTrue(ReaderScreenModel(prefs = prefs).state.value.dualPageMode)
    }

    @Test
    fun `dual page resolution is manga flags then explicit screen override then global preference`() {
        val prefs = testPreferences { isDualPage = true }
        val model = ReaderScreenModel(
            mangaViewerFlags = viewerFlagsWithDualPage(0L, enabled = false),
            dualPageOverride = true,
            prefs = prefs,
        )

        assertFalse(model.state.value.dualPageMode)
        assertFalse(ReaderScreenModel(dualPageOverride = false, prefs = prefs).state.value.dualPageMode)

        prefs.isDualPage = false
        assertTrue(ReaderScreenModel(dualPageOverride = true, prefs = prefs).state.value.dualPageMode)
    }

    @Test
    fun `DesktopReaderScreen explicit dual-page override reaches production runtime factory`() {
        val prefs = testPreferences { isDualPage = false }
        val screen = DesktopReaderScreen(chapterTitle = "Chapter 1", isDualPage = true)

        val model = screen.createReaderScreenModel(
            prefs = prefs,
            pageLoader = mockk<DesktopReaderPageLoader>(relaxed = true),
        )

        assertTrue(model.state.value.dualPageMode)
    }

    @Test
    fun `webtoon flag forces webtoon reading mode regardless of prefs`() {
        val model = ReaderScreenModel(isWebtoon = true)
        assertEquals(ReadingMode.WEBTOON, model.state.value.readingMode)
    }

    @Test
    fun `fresh reader defaults RTL while explicit current and per-manga LTR remain supported`() {
        val prefs = testPreferences()
        assertEquals(ReadingMode.RTL, ReaderScreenModel(prefs = prefs).state.value.readingMode)

        prefs.readingMode = ReadingMode.LTR
        assertEquals(ReadingMode.LTR, ReaderScreenModel(prefs = prefs).state.value.readingMode)

        prefs.readingMode = ReadingMode.RTL
        val flags = viewerFlagsWithReadingMode(0L, ReadingMode.LTR)
        assertEquals(
            ReadingMode.LTR,
            ReaderScreenModel(mangaViewerFlags = flags, prefs = prefs).state.value.readingMode,
        )
    }

    @Test
    fun `non-webtoon manga viewer flags keep RTL dual page reader mode`() {
        val flags = viewerFlagsWithDualPage(
            viewerFlagsWithReadingMode(0L, ReadingMode.RTL),
            enabled = true,
        )

        val model = ReaderScreenModel(isWebtoon = false, mangaViewerFlags = flags)

        assertEquals(ReadingMode.RTL, model.state.value.readingMode)
        assertTrue(model.state.value.dualPageMode)
    }

    @Test
    fun `showSettings and showUI start as false`() {
        val model = ReaderScreenModel()
        assertFalse(model.state.value.showSettings)
        assertFalse(model.state.value.showUI)
    }

    @Test
    fun `errorMessage starts null`() {
        val model = ReaderScreenModel()
        assertNull(model.state.value.errorMessage)
    }

    // ── Page navigation ──────────────────────────────────────────────────────

    @Test
    fun `goToPage updates currentPage`() {
        val model = ReaderScreenModel(
            pageUrls = listOf("a", "b", "c"),
            initialPage = 0,
        )
        model.goToPage(2)
        assertEquals(2, model.state.value.currentPage)
    }

    @Test
    fun `goToPage clamps to zero on negative input`() {
        val model = ReaderScreenModel(pageUrls = listOf("a", "b", "c"))
        model.goToPage(-5)
        assertEquals(0, model.state.value.currentPage)
    }

    @Test
    fun `goToPage clamps to last page when exceeding url count`() {
        val model = ReaderScreenModel(pageUrls = listOf("a", "b", "c"))
        model.goToPage(100)
        assertEquals(2, model.state.value.currentPage)
    }

    @Test
    fun `goToPage on empty url list stays at 0`() {
        val model = ReaderScreenModel(pageUrls = emptyList())
        model.goToPage(3)
        assertEquals(0, model.state.value.currentPage)
    }

    @Test
    fun `settled single-page position preserves the exact display unit until explicit navigation`() {
        val model = ReaderScreenModel(pageUrls = listOf("a", "b"), chapterId = 7L)
        val pageId = ReaderPageId(ReaderChapterId(7L), 0)
        val secondSlice = DisplayUnitId(
            mode = ReaderPresentationMode.SINGLE_PAGED,
            slots = listOf(DisplaySlotId(pageId, PageSplitHalf.RIGHT)),
        )

        model.settleSinglePage(VisiblePageSet(secondSlice, setOf(pageId)))

        assertEquals(0, model.state.value.currentPage)
        assertEquals(secondSlice, model.state.value.currentDisplayUnitId)

        model.goToPage(1)
        assertEquals(1, model.state.value.currentPage)
        assertNull(model.state.value.currentDisplayUnitId)
    }

    @Test
    fun `settled webtoon viewport stores every visible page active page and scroll anchor`() {
        val model = ReaderScreenModel(pageUrls = listOf("a", "b", "c"), chapterId = 7L)
        val firstPage = ReaderPageId(ReaderChapterId(7L), 0)
        val activePage = ReaderPageId(ReaderChapterId(7L), 1)
        val anchorUnit = DisplayUnitId(
            ReaderPresentationMode.WEBTOON,
            listOf(DisplaySlotId(firstPage)),
        )
        val activeUnit = DisplayUnitId(
            ReaderPresentationMode.WEBTOON,
            listOf(DisplaySlotId(activePage)),
        )
        val anchor = WebtoonScrollAnchor(anchorUnit, scrollOffset = 43)

        model.settleWebtoon(
            WebtoonViewportUpdate(
                visiblePages = VisiblePageSet(activeUnit, setOf(firstPage, activePage), activePageId = activePage),
                anchor = anchor,
            ),
        )

        assertEquals(1, model.state.value.currentPage)
        assertEquals(activeUnit, model.state.value.currentDisplayUnitId)
        assertEquals(anchor, model.state.value.webtoonScrollAnchor)
        assertEquals(setOf(firstPage, activePage), model.state.value.visiblePageIds)

        model.setLoadingPageSlots(totalPages = 3, initialPage = 0)
        assertEquals(1, model.state.value.currentPage)
        assertEquals(anchor, model.state.value.webtoonScrollAnchor)

        model.setLoadedPages(listOf("a", "b", "c"), initialPage = 0)
        assertEquals(1, model.state.value.currentPage)
        assertEquals(anchor, model.state.value.webtoonScrollAnchor)

        model.goToPage(2)
        assertEquals(2, model.state.value.currentPage)
        assertNull(model.state.value.currentDisplayUnitId)
        assertNull(model.state.value.webtoonScrollAnchor)
        assertTrue(model.state.value.visiblePageIds.isEmpty())
    }

    @Test
    fun `settled dual-page viewport stores both pages and advances to maximum visible progress`() {
        val model = ReaderScreenModel(pageUrls = listOf("a", "b", "c", "d"), chapterId = 7L)
        val first = ReaderPageId(ReaderChapterId(7L), 1)
        val second = ReaderPageId(ReaderChapterId(7L), 2)
        val pair = DisplayUnitId(
            ReaderPresentationMode.DUAL_PAGED,
            listOf(DisplaySlotId(first), DisplaySlotId(second)),
        )
        model.setZoomState(ZoomState(scale = 2.0f))

        model.settleDualPage(VisiblePageSet(pair, setOf(first, second), activePageId = second))

        assertEquals(2, model.state.value.currentPage)
        assertEquals(pair, model.state.value.currentDisplayUnitId)
        assertEquals(setOf(first, second), model.state.value.visiblePageIds)
        assertEquals(2.0f, model.state.value.zoomState.scale)
        assertNull(model.state.value.webtoonScrollAnchor)
    }

    @Test
    fun `single-page viewport keeps stable slots mounted through chapter loading and error`() {
        val error = ReaderChapterState.Error(AppError.Network(), retryTargetChapterId = 7L)
        val singleSlots = ReaderState(
            resolvedUrls = listOf("", "ready"),
            readingMode = ReadingMode.LTR,
            chapterState = error,
            errorMessage = "offline",
        )

        assertEquals(ReaderViewportBody.CONTENT, readerViewportBody(singleSlots))
        assertEquals(
            ReaderViewportBody.CONTENT,
            readerViewportBody(singleSlots.copy(errorMessage = null, isLoadingPages = true, chapterState = ReaderChapterState.Loading)),
        )
        assertEquals(ReaderViewportBody.ERROR, readerViewportBody(singleSlots.copy(resolvedUrls = emptyList())))
        assertEquals(ReaderViewportBody.CONTENT, readerViewportBody(singleSlots.copy(dualPageMode = true)))
        assertEquals(ReaderViewportBody.CONTENT, readerViewportBody(singleSlots.copy(readingMode = ReadingMode.WEBTOON)))
    }

    // ── UI visibility ────────────────────────────────────────────────────────

    @Test
    fun `toggleSettings flips showSettings`() {
        val model = ReaderScreenModel()
        assertFalse(model.state.value.showSettings)
        model.toggleSettings()
        assertTrue(model.state.value.showSettings)
        model.toggleSettings()
        assertFalse(model.state.value.showSettings)
    }

    @Test
    fun `toggleUI flips showUI`() {
        val model = ReaderScreenModel()
        assertFalse(model.state.value.showUI)
        model.toggleUI()
        assertTrue(model.state.value.showUI)
        model.toggleUI()
        assertFalse(model.state.value.showUI)
    }

    // ── Reading mode ─────────────────────────────────────────────────────────

    @Test
    fun `setReadingMode changes readingMode for non-webtoon`() {
        val model = ReaderScreenModel(isWebtoon = false)
        model.setReadingMode(ReadingMode.RTL)
        assertEquals(ReadingMode.RTL, model.state.value.readingMode)
    }

    @Test
    fun `setReadingMode has no effect for webtoon chapters`() {
        val model = ReaderScreenModel(isWebtoon = true)
        assertEquals(ReadingMode.WEBTOON, model.state.value.readingMode)
        model.setReadingMode(ReadingMode.LTR)
        // Webtoon chapters are pinned to WEBTOON mode
        assertEquals(ReadingMode.WEBTOON, model.state.value.readingMode)
    }

    // ── Dual-page mode ────────────────────────────────────────────────────────

    @Test
    fun `setDualPageMode updates dualPageMode`() {
        val model = ReaderScreenModel(mangaViewerFlags = viewerFlagsWithDualPage(0L, enabled = false))
        assertFalse(model.state.value.dualPageMode)
        model.setDualPageMode(true)
        assertTrue(model.state.value.dualPageMode)
    }

    @Test
    fun `disabling dualPageMode clears forcedSinglePages`() {
        val model = ReaderScreenModel()
        model.setForcedSinglePages(setOf(0, 2))
        model.setDualPageMode(false)
        assertTrue(model.state.value.forcedSinglePages.isEmpty())
    }

    // ── Spread pages ──────────────────────────────────────────────────────────

    @Test
    fun `setSpreadPages updates spreadPages`() {
        val model = ReaderScreenModel(pageUrls = listOf("a", "b", "c", "d"))
        model.setSpreadPages(setOf(1, 3))
        assertEquals(setOf(1, 3), model.state.value.spreadPages)
    }

    @Test
    fun `setForcedSinglePages updates forcedSinglePages`() {
        val model = ReaderScreenModel()
        model.setForcedSinglePages(setOf(2, 4))
        assertEquals(setOf(2, 4), model.state.value.forcedSinglePages)
    }

    // ── Settings state ────────────────────────────────────────────────────────

    @Test
    fun `setBackgroundTheme updates backgroundTheme`() {
        val model = ReaderScreenModel()
        model.setBackgroundTheme(ReaderBackgroundTheme.BLACK)
        assertEquals(ReaderBackgroundTheme.BLACK, model.state.value.backgroundTheme)
    }

    @Test
    fun `setScaleType updates scaleType`() {
        val model = ReaderScreenModel()
        model.setScaleType(ScaleType.FIT_WIDTH)
        assertEquals(ScaleType.FIT_WIDTH, model.state.value.scaleType)
    }

    @Test
    fun `setColorFilter updates colorFilter`() {
        val model = ReaderScreenModel()
        val filter = ReaderColorFilter(brightnessEnabled = true, brightness = 0.5f)
        model.setColorFilter(filter)
        assertEquals(filter, model.state.value.colorFilter)
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
    fun `setZoomState updates zoomState`() {
        val model = ReaderScreenModel()
        val zoom = ZoomState(scale = 2.0f)
        model.setZoomState(zoom)
        assertEquals(2.0f, model.state.value.zoomState.scale)
    }

    // ── Loaded pages ──────────────────────────────────────────────────────────

    @Test
    fun `setLoadedPages updates resolvedUrls and clears loading flag`() {
        val model = ReaderScreenModel(
            pageUrls = emptyList(),
            sourceId = 1L,
            chapterUrl = "/ch/1",
        )
        assertTrue(model.state.value.isLoadingPages)
        model.setLoadedPages(listOf("img1.jpg", "img2.jpg"), initialPage = 0)
        assertFalse(model.state.value.isLoadingPages)
        assertEquals(listOf("img1.jpg", "img2.jpg"), model.state.value.resolvedUrls)
        assertEquals(0, model.state.value.currentPage)
    }

    @Test
    fun `setLoadingPageSlots fixes total page count before out of order page downloads`() {
        val model = ReaderScreenModel(
            pageUrls = emptyList(),
            sourceId = 1L,
            chapterUrl = "/ch/1",
        )

        model.setLoadingPageSlots(totalPages = 44, initialPage = 0)
        model.appendLoadedPage(20, "img21.jpg")

        assertFalse(model.state.value.isLoadingPages)
        assertEquals(44, model.state.value.resolvedUrls.size)
        assertEquals("img21.jpg", model.state.value.resolvedUrls[20])
        assertEquals(0, model.state.value.currentPage)
        assertTrue(model.hasLoadedPage())
    }

    @Test
    fun `setLoadError sets errorMessage and clears loading flag`() {
        val model = ReaderScreenModel(
            pageUrls = emptyList(),
            chapterId = 7L,
            sourceId = 1L,
            chapterUrl = "/ch/1",
        )
        model.setLoadError("Network timeout")
        assertFalse(model.state.value.isLoadingPages)
        assertEquals("Network timeout", model.state.value.errorMessage)
        val error = model.state.value.chapterState as ReaderChapterState.Error
        assertEquals(ReaderNavigationCommand.RetryChapter(7L), error.retryCommand())
    }

    @Test
    fun `retry clears error marks shared chapter state loading and advances request generation`() {
        val model = ReaderScreenModel(pageUrls = emptyList(), sourceId = 1L, chapterUrl = "/ch/1")
        model.setLoadError("Network timeout")
        val previousGeneration = model.state.value.loadGeneration

        model.requestRetry()

        assertNull(model.state.value.errorMessage)
        assertTrue(model.state.value.isLoadingPages)
        assertEquals(ReaderChapterState.Loading, model.state.value.chapterState)
        assertEquals(previousGeneration + 1, model.state.value.loadGeneration)
    }

    @Test
    fun `chapter boundary feedback uses shared transition state`() {
        val model = ReaderScreenModel()

        model.showChapterBoundary(
            direction = ReaderTransitionDirection.NEXT,
            chapterId = 1L,
            chapterUrl = "/1",
            chapterName = "Chapter 1",
            chapterNumber = 1.0,
        )

        assertEquals(ReaderTransitionDirection.NEXT, model.state.value.chapterTransition?.direction)
        assertNull(model.state.value.chapterTransition?.to)
        assertEquals(
            ReaderNavigationCommand.ChapterBoundary(ReaderTransitionDirection.NEXT),
            model.chapterTransitionCommand(),
        )
    }

    @Test
    fun `chapter transition error exposes target retry command`() {
        val model = ReaderScreenModel()
        val from = mihon.domain.reader.ReaderChapterModel(1L, "/1", "Chapter 1", 1.0)
        val to = mihon.domain.reader.ReaderChapterModel(2L, "/2", "Chapter 2", 2.0)
        model.showChapterTransition(ReaderTransitionDirection.NEXT, from, to, missingChapterCount = 0)
        model.setChapterTransitionState(
            ReaderChapterState.Error(AppError.Network(), retryTargetChapterId = to.id),
        )

        assertEquals(ReaderNavigationCommand.RetryChapter(to.id), model.chapterTransitionCommand())
    }

    @Test
    fun `chapter transition presentation never offers cancel while loading or continue after loading`() {
        val from = mihon.domain.reader.ReaderChapterModel(1L, "/1", "Chapter 1", 1.0)
        val to = mihon.domain.reader.ReaderChapterModel(3L, "/3", "Chapter 3", 3.0)
        val loading = mihon.domain.reader.ReaderChapterTransitionModel(
            ReaderTransitionDirection.NEXT,
            from,
            to,
            missingChapterCount = 1,
            state = ReaderChapterState.Loading,
        )
        val error = loading.copy(
            state = ReaderChapterState.Error(AppError.Network(), retryTargetChapterId = to.id),
        )
        val ready = loading.copy(state = ReaderChapterState.Wait)
        val boundary = loading.copy(to = null, missingChapterCount = 0, state = ReaderChapterState.Wait)

        assertTrue(chapterTransitionPresentation(loading).showLoading)
        assertFalse(chapterTransitionPresentation(loading).showDismiss)
        assertTrue(chapterTransitionPresentation(error).showRetry)
        assertTrue(chapterTransitionPresentation(error).showDismiss)
        assertFalse(chapterTransitionPresentation(ready).showContinue)
        assertFalse(chapterTransitionPresentation(ready).showDismiss)
        assertEquals(1, chapterTransitionPresentation(ready).missingChapterCount)
        assertTrue(chapterTransitionPresentation(boundary).isBoundary)
        assertTrue(chapterTransitionPresentation(boundary).showDismiss)
    }

    @Test
    fun `chapter transition dismiss copy is localized as close in Chinese`() {
        assertEquals("关闭", MR.strings.desktop_ui_dismiss.localized(Locale.SIMPLIFIED_CHINESE))
        assertEquals("關閉", MR.strings.desktop_ui_dismiss.localized(Locale.TRADITIONAL_CHINESE))
    }

    @Test
    fun `persistViewerFlags ignores missing manga id`() = runTest {
        val calls = mutableListOf<Pair<Long, Long>>()
        val model = ReaderScreenModel(persistViewerFlags = { mangaId, flags -> calls += mangaId to flags })

        model.persistViewerFlags(mangaId = 0L, flags = 7L)

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `persistViewerFlags delegates nonzero manga id`() = runTest {
        val calls = mutableListOf<Pair<Long, Long>>()
        val model = ReaderScreenModel(persistViewerFlags = { mangaId, flags -> calls += mangaId to flags })

        model.persistViewerFlags(mangaId = 42L, flags = 7L)

        assertEquals(listOf(42L to 7L), calls)
    }

    // ── ReaderState data class sanity ─────────────────────────────────────────

    @Test
    fun `ReaderState has expected fields`() {
        val state = ReaderState(
            currentPage = 3,
            resolvedUrls = listOf("a", "b"),
            isLoadingPages = true,
            errorMessage = "err",
            readingMode = ReadingMode.RTL,
            dualPageMode = true,
            showSettings = true,
            showUI = true,
        )
        assertEquals(3, state.currentPage)
        assertEquals(listOf("a", "b"), state.resolvedUrls)
        assertTrue(state.isLoadingPages)
        assertEquals("err", state.errorMessage)
        assertEquals(ReadingMode.RTL, state.readingMode)
        assertTrue(state.dualPageMode)
        assertTrue(state.showSettings)
        assertTrue(state.showUI)
    }
}
