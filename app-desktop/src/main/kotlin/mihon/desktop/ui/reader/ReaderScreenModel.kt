package mihon.desktop.ui.reader

import cafe.adriel.voyager.core.model.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import mihon.desktop.reader.DesktopReaderChapterContext
import mihon.desktop.reader.DesktopReaderRuntime
import mihon.desktop.reader.DesktopReaderSessionState
import mihon.desktop.reader.ReaderBackgroundTheme
import mihon.desktop.reader.ReaderColorFilter
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.WebtoonSidePadding
import mihon.desktop.reader.ZoomState
import mihon.desktop.reader.dualPageFromViewerFlags
import mihon.desktop.reader.readingModeFromViewerFlags
import mihon.desktop.ui.reader.presentation.VisiblePageSet
import mihon.desktop.ui.reader.presentation.WebtoonViewportUpdate
import mihon.domain.reader.ReaderChapterModel
import mihon.domain.reader.ReaderChapterState
import mihon.domain.reader.ReaderChapterTransitionModel
import mihon.domain.reader.ReaderNavigationCommand
import mihon.domain.reader.ReaderTransitionDirection
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderSessionSnapshot

/** UI preferences and presentation state for one long-lived Desktop reader session. */
class ReaderScreenModel(
    chapterTitle: String = "",
    initialPage: Int = 0,
    chapterId: Long = 0L,
    private val isWebtoon: Boolean = false,
    sourceId: Long = 0L,
    chapterUrl: String = "",
    mangaTitle: String = "",
    chapterNumber: Double = 0.0,
    chapterIndex: Int = 0,
    wasRead: Boolean = false,
    localChapterPath: String? = null,
    val mangaViewerFlags: Long = 0L,
    private val dualPageOverride: Boolean? = null,
    prefs: ReaderPreferences = ReaderPreferences(),
    initialSessionState: DesktopReaderSessionState = DesktopReaderSessionState(
        context = DesktopReaderChapterContext(
            chapterId = chapterId,
            sourceId = sourceId,
            chapterUrl = chapterUrl,
            mangaTitle = mangaTitle,
            chapterTitle = chapterTitle,
            chapterNumber = chapterNumber,
            chapterIndex = chapterIndex,
            initialPage = initialPage,
            wasRead = wasRead,
            localChapterPath = localChapterPath,
        ),
        snapshot = ReaderSessionSnapshot.initial(ReaderChapterId(chapterId)),
    ),
    private val persistViewerFlags: suspend (mangaId: Long, flags: Long) -> Unit = { _, _ -> },
    private val onViewportSettled: (Set<ReaderPageId>, ReaderPageId) -> Unit = { _, _ -> },
    private val onPageRetry: (ReaderPageId) -> Unit = {},
    private val onChapterRetry: () -> Unit = {},
    private val onChapterActivated: (DesktopReaderChapterContext) -> DesktopReaderSessionState? = { null },
    internal val runtime: DesktopReaderRuntime? = null,
    private val ownedRuntimeScope: CoroutineScope? = null,
) : ScreenModel {
    private val _state = MutableStateFlow(buildInitialState(prefs, initialSessionState))
    val state: StateFlow<ReaderState> = _state.asStateFlow()
    private var lastSettledViewport: SettledViewportIdentity? = null

    private fun buildInitialState(
        prefs: ReaderPreferences,
        reader: DesktopReaderSessionState,
    ): ReaderState {
        val resolvedMode = when {
            isWebtoon -> ReadingMode.WEBTOON
            else -> readingModeFromViewerFlags(mangaViewerFlags) ?: prefs.readingMode
        }
        return ReaderState(
            context = reader.context,
            session = reader.snapshot,
            currentPage = resolveInitialPage(reader.context.initialPage, reader.snapshot.activeChapter.pages.size),
            readingMode = resolvedMode,
            dualPageMode = dualPageFromViewerFlags(mangaViewerFlags) ?: dualPageOverride ?: prefs.isDualPage,
            autoSplitPages = prefs.autoSplitPages,
            autoSpreadMatching = prefs.isAutoSpreadMatching,
            backgroundTheme = prefs.backgroundTheme,
            navigationMode = prefs.navigationMode,
            cropBordersPager = prefs.cropBordersPager,
            cropBordersWebtoon = prefs.cropBordersWebtoon,
            webtoonSidePadding = prefs.webtoonSidePadding,
            webtoonAutoScroll = prefs.webtoonAutoScroll,
            webtoonAutoScrollSpeed = prefs.webtoonAutoScrollSpeed,
            scaleType = prefs.scaleType,
            colorFilter = prefs.loadColorFilter(),
            skipReadChapters = prefs.skipReadChapters,
            skipFilteredChapters = prefs.skipFilteredChapters,
            skipDuplicateChapters = prefs.skipDuplicateChapters,
        )
    }

    fun acceptSessionState(reader: DesktopReaderSessionState) {
        _state.update { current ->
            val chapterChanged = current.context.chapterId != reader.context.chapterId
            val firstStablePageList = current.session.activeChapter.pages.isEmpty() &&
                reader.snapshot.activeChapter.pages.isNotEmpty()
            val pageCount = reader.snapshot.activeChapter.pages.size
            val currentPage = if (chapterChanged || firstStablePageList) {
                resolveInitialPage(reader.context.initialPage, pageCount)
            } else {
                current.currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            }
            current.copy(
                context = reader.context,
                session = reader.snapshot,
                currentPage = currentPage,
                currentDisplayUnitId = if (chapterChanged) null else current.currentDisplayUnitId,
                visiblePageIds = if (chapterChanged) emptySet() else current.visiblePageIds,
                webtoonScrollAnchor = if (chapterChanged) null else current.webtoonScrollAnchor,
                chapterTransition = if (chapterChanged) null else current.chapterTransition,
                forcedSinglePages = if (chapterChanged) emptySet() else current.forcedSinglePages,
                spreadPages = if (chapterChanged) emptySet() else current.spreadPages,
                matchedPairs = if (chapterChanged) emptySet() else current.matchedPairs,
                virtualPages = if (chapterChanged) null else current.virtualPages,
            )
        }
    }

    fun activateChapter(context: DesktopReaderChapterContext) {
        onChapterActivated(context)?.let(::acceptSessionState)
    }

    fun goToPage(page: Int) {
        _state.update { state ->
            val max = (state.session.activeChapter.pages.size - 1).coerceAtLeast(0)
            state.copy(
                currentPage = page.coerceIn(0, max),
                currentDisplayUnitId = null,
                visiblePageIds = emptySet(),
                webtoonScrollAnchor = null,
            )
        }
    }

    internal fun settleSinglePage(visiblePages: VisiblePageSet) {
        settleVisiblePages(visiblePages, webtoonScrollAnchor = null)
    }

    internal fun settleWebtoon(update: WebtoonViewportUpdate) {
        settleVisiblePages(update.visiblePages, update.anchor)
    }

    internal fun settleDualPage(visiblePages: VisiblePageSet) {
        settleVisiblePages(visiblePages, webtoonScrollAnchor = null)
    }

    private fun settleVisiblePages(
        visiblePages: VisiblePageSet,
        webtoonScrollAnchor: mihon.desktop.ui.reader.presentation.WebtoonScrollAnchor?,
    ) {
        val activePageId = visiblePages.activePageId ?: visiblePages.pageIds.maxByOrNull(ReaderPageId::sourcePageIndex)
            ?: return
        val current = _state.value
        val pages = current.session.activeChapter.pages
        if (activePageId.chapterId != current.session.activeChapter.id || pages.none { it.id == activePageId }) return
        _state.update { state ->
            state.copy(
                currentPage = activePageId.sourcePageIndex,
                currentDisplayUnitId = visiblePages.displayUnitId,
                visiblePageIds = visiblePages.pageIds,
                webtoonScrollAnchor = webtoonScrollAnchor,
            )
        }
        val identity = SettledViewportIdentity(
            generation = current.session.generation,
            pageIds = visiblePages.pageIds,
            activePageId = activePageId,
        )
        if (lastSettledViewport != identity) {
            lastSettledViewport = identity
            onViewportSettled(visiblePages.pageIds, activePageId)
        }
    }

    fun retryPage(pageId: ReaderPageId) {
        onPageRetry(pageId)
    }

    fun requestRetry() {
        val state = _state.value
        val chapter = state.session.activeChapter
        if (chapter.loadState is ReaderChapterLoadState.Error || chapter.pages.isEmpty()) {
            onChapterRetry()
            return
        }
        val page = chapter.pages.getOrNull(state.currentPage) ?: return
        onPageRetry(page.id)
    }

    fun showChapterBoundary(
        direction: ReaderTransitionDirection,
        chapterId: Long,
        chapterUrl: String,
        chapterName: String,
        chapterNumber: Double,
    ) {
        _state.update {
            it.copy(
                chapterTransition = ReaderChapterTransitionModel(
                    direction = direction,
                    from = ReaderChapterModel(chapterId, chapterUrl, chapterName, chapterNumber),
                    to = null,
                    state = ReaderChapterState.Wait,
                ),
            )
        }
    }

    fun clearChapterTransition() {
        _state.update { it.copy(chapterTransition = null) }
    }

    fun chapterTransitionCommand(): ReaderNavigationCommand? = state.value.chapterTransition?.retryCommand()

    // ── UI visibility ─────────────────────────────────────────────────────────

    fun toggleSettings() {
        _state.update { it.copy(showSettings = !it.showSettings) }
    }

    fun closeSettings() {
        _state.update { it.copy(showSettings = false) }
    }

    fun toggleUI() {
        _state.update { it.copy(showUI = !it.showUI) }
    }

    // ── Reading mode ──────────────────────────────────────────────────────────

    fun setReadingMode(mode: ReadingMode, prefs: ReaderPreferences? = null) {
        if (isWebtoon) return
        _state.update {
            it.copy(
                readingMode = mode,
                currentDisplayUnitId = null,
                visiblePageIds = emptySet(),
                webtoonScrollAnchor = null,
            )
        }
        if (mode != ReadingMode.WEBTOON) prefs?.readingMode = mode
    }

    // ── Dual-page & spread management ─────────────────────────────────────────

    fun setDualPageMode(on: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { state ->
            state.copy(
                dualPageMode = on,
                forcedSinglePages = if (!on) emptySet() else state.forcedSinglePages,
                currentDisplayUnitId = null,
                visiblePageIds = emptySet(),
                webtoonScrollAnchor = null,
            )
        }
        prefs?.isDualPage = on
    }

    fun setAutoSplitPages(on: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { state ->
            if (state.readingMode == ReadingMode.WEBTOON) {
                state.copy(autoSplitPages = on)
            } else {
                state.copy(autoSplitPages = on, currentDisplayUnitId = null, visiblePageIds = emptySet())
            }
        }
        prefs?.autoSplitPages = on
    }

    fun setAutoSpreadMatching(on: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(autoSpreadMatching = on) }
        prefs?.isAutoSpreadMatching = on
    }

    fun setSpreadPages(pages: Set<Int>) {
        _state.update { it.copy(spreadPages = pages) }
    }

    fun setForcedSinglePages(pages: Set<Int>) {
        _state.update { it.copy(forcedSinglePages = pages) }
    }

    fun setMatchedPairs(pairs: Set<Pair<Int, Int>>) {
        _state.update { it.copy(matchedPairs = pairs) }
    }

    fun setVirtualPages(pages: List<mihon.desktop.reader.VirtualPage>?) {
        _state.update { it.copy(virtualPages = pages) }
    }

    // ── Display settings ──────────────────────────────────────────────────────

    fun setBackgroundTheme(theme: ReaderBackgroundTheme, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(backgroundTheme = theme) }
        prefs?.backgroundTheme = theme
    }

    fun setNavigationMode(mode: NavigationMode, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(navigationMode = mode) }
        prefs?.navigationMode = mode
    }

    fun setCropBordersPager(on: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(cropBordersPager = on) }
        prefs?.cropBordersPager = on
    }

    fun setCropBordersWebtoon(on: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(cropBordersWebtoon = on) }
        prefs?.cropBordersWebtoon = on
    }

    fun setWebtoonSidePadding(padding: WebtoonSidePadding, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(webtoonSidePadding = padding) }
        prefs?.webtoonSidePadding = padding
    }

    fun setWebtoonAutoScroll(on: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(webtoonAutoScroll = on) }
        prefs?.webtoonAutoScroll = on
    }

    fun setWebtoonAutoScrollSpeed(speed: WebtoonAutoScrollSpeed, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(webtoonAutoScrollSpeed = speed) }
        prefs?.webtoonAutoScrollSpeed = speed
    }

    fun setScaleType(type: ScaleType, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(scaleType = type) }
        prefs?.scaleType = type
    }

    fun setColorFilter(filter: ReaderColorFilter, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(colorFilter = filter) }
        prefs?.saveColorFilter(filter)
    }

    fun setZoomState(zoom: ZoomState) {
        _state.update { it.copy(zoomState = zoom) }
    }

    fun setSkipReadChapters(skip: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(skipReadChapters = skip) }
        prefs?.skipReadChapters = skip
    }

    fun setSkipFilteredChapters(skip: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(skipFilteredChapters = skip) }
        prefs?.skipFilteredChapters = skip
    }

    fun setSkipDuplicateChapters(skip: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(skipDuplicateChapters = skip) }
        prefs?.skipDuplicateChapters = skip
    }

    suspend fun persistViewerFlags(mangaId: Long, flags: Long) {
        if (mangaId == 0L) return
        persistViewerFlags.invoke(mangaId, flags)
    }

    override fun onDispose() {
        runtime?.close()
        ownedRuntimeScope?.cancel()
    }
}

private data class SettledViewportIdentity(
    val generation: Long,
    val pageIds: Set<ReaderPageId>,
    val activePageId: ReaderPageId,
)

private fun resolveInitialPage(requestedPage: Int, pageCount: Int): Int = when {
    pageCount <= 0 -> 0
    requestedPage == ReaderInitialPage.LAST -> pageCount - 1
    else -> requestedPage.coerceIn(0, pageCount - 1)
}
