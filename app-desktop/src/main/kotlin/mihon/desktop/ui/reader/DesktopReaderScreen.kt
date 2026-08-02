package mihon.desktop.ui.reader

import tachiyomi.i18n.MR

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.launch
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.image.LocalDesktopSourceImageId
import mihon.desktop.reader.DesktopReaderRuntimeFactory
import mihon.desktop.reader.DualPageState
import mihon.desktop.reader.EdgePixelMatcher
import mihon.desktop.reader.DesktopReaderPageLoader
import mihon.desktop.reader.PagePreloader
import mihon.desktop.reader.ReaderBackgroundTheme
import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.ReaderColorFilter
import mihon.desktop.reader.ReaderKeyboardAction
import mihon.desktop.reader.ReaderNavigator
import mihon.desktop.reader.ReaderPageAction
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.VirtualPage
import mihon.desktop.reader.WebtoonSidePadding
import mihon.desktop.reader.ZoomState
import mihon.desktop.reader.buildVirtualPageList
import mihon.desktop.reader.viewerFlagsWithDualPage
import mihon.desktop.reader.viewerFlagsWithReadingMode
import mihon.desktop.reader.firstVirtualIndex
import mihon.desktop.reader.realPageIndex
import mihon.domain.reader.ReaderChapterState
import mihon.domain.reader.ReaderChapterTransitionModel
import mihon.domain.reader.ReaderTransitionDirection

@OptIn(ExperimentalMaterial3Api::class)
data class DesktopReaderScreen(
    val chapterTitle: String,
    val mangaTitle: String = "",
    val pageUrls: List<String> = emptyList(),
    val isWebtoon: Boolean = false,
    val sourceId: Long = 0L,
    val chapterUrl: String = "",
    val chapterId: Long = 0L,
    val mangaId: Long = 0L,
    val chapterNumber: Double = 0.0,
    /** All chapters for this manga (desc order = newest first), enables prev/next navigation. */
    val chapters: List<ReaderChapterRef> = emptyList(),
    /** Index of the current chapter within [chapters]. */
    val currentChapterIndex: Int = 0,
    /** Page to open first (resume from lastPageRead). */
    val initialPage: Int = 0,
    /** Per-manga viewer flags from Manga.viewerFlags (0 = use global default). */
    val mangaViewerFlags: Long = 0L,
    /** RTL (right-to-left) pager mode. */
    val isRtl: Boolean = false,
    /** Explicit dual-page override; null resolves per-manga flags, then the global preference. */
    val isDualPage: Boolean? = null,
    @Transient val progressTracker: ReaderProgressTracker? = null,
) : Screen {

    // Voyager 默认 key = 类名，两个不同章节的 DesktopReaderScreen 会被视为同一个 screen。
    // 用 chapterId + chapterUrl 区分，确保每次切换章节都创建全新 composition。
    override val key: String get() = "DesktopReaderScreen-$chapterId-$chapterUrl"

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val runtime = remember { DesktopReaderRuntimeFactory.createRuntime(progressTracker, sourceId) }
        val model = rememberScreenModel {
            createReaderScreenModel(runtime.prefs, runtime.pageLoader)
        }
        val state by model.state.collectAsState()
        val focusRequester = remember { FocusRequester() }
        val readerExitEventId = remember(chapterId, key) { java.util.UUID.randomUUID().toString() }
        ReaderLifecycleEffect(
            state,
            scope,
            runtime.tracker,
            chapterId,
            sourceId,
            mangaId,
            chapters.getOrNull(currentChapterIndex)?.chapterNumber ?: chapterNumber,
            readerExitEventId,
        )

        // Background page loading (network / local)
        ReaderPageLoaderEffect(
            model = model,
            pageLoader = runtime.pageLoader,
            pageUrls = pageUrls,
            sourceId = sourceId,
            chapterUrl = chapterUrl,
            mangaTitle = mangaTitle,
            chapterTitle = chapterTitle,
            initialPage = initialPage,
            loadGeneration = state.loadGeneration,
        )

        // Compute: zoom reset, preload, focus, edge-scan, virtual pages
        ReaderSideEffects(state, model, runtime.preloader, focusRequester)

        // Chapter navigation lambdas
        val skipRead = state.skipReadChapters
        val skipFiltered = state.skipFilteredChapters
        val skipDuplicate = state.skipDuplicateChapters
        val readerNav = remember(chapters, currentChapterIndex, skipRead, skipFiltered, skipDuplicate) {
            chapters.takeIf { it.isNotEmpty() }?.let {
                ReaderNavigator(
                    chapters = it,
                    currentIndex = currentChapterIndex,
                    skipReadChapters = skipRead,
                    skipFilteredChapters = skipFiltered,
                    skipDuplicateChapters = skipDuplicate,
                )
            }
        }
        fun currentViewerFlags(): Long = viewerFlagsWithReadingMode(
            viewerFlagsWithDualPage(mangaViewerFlags, state.dualPageMode),
            state.readingMode,
        )
        val onPrevChapter: () -> Unit = {
            requestAdjacentChapterTransition(
                ReaderTransitionDirection.PREVIOUS,
                model,
                readerNav,
                currentViewerFlags(),
            )?.let { destination ->
                model.clearChapterTransition()
                navigator.replace(destination)
            }
        }
        val onNextChapter: () -> Unit = {
            requestAdjacentChapterTransition(
                ReaderTransitionDirection.NEXT,
                model,
                readerNav,
                currentViewerFlags(),
            )?.let { destination ->
                model.clearChapterTransition()
                navigator.replace(destination)
            }
        }

        // Settings dialog
        if (state.showSettings) {
            ReaderSettingsPanel(
                currentMode = state.readingMode, isDualPage = state.dualPageMode,
                autoSplitPages = state.autoSplitPages, isAutoSpreadMatching = state.autoSpreadMatching,
                backgroundTheme = state.backgroundTheme, navigationMode = state.navigationMode,
                cropBordersPager = state.cropBordersPager, cropBordersWebtoon = state.cropBordersWebtoon,
                webtoonSidePadding = state.webtoonSidePadding, webtoonAutoScroll = state.webtoonAutoScroll,
                webtoonAutoScrollSpeed = state.webtoonAutoScrollSpeed, colorFilter = state.colorFilter,
                scaleType = state.scaleType, skipReadChapters = state.skipReadChapters,
                skipFilteredChapters = state.skipFilteredChapters,
                skipDuplicateChapters = state.skipDuplicateChapters, zoomState = state.zoomState,
                onModeChange = {
                    model.setReadingMode(it, runtime.prefs)
                    scope.launch {
                        model.persistViewerFlags(
                            mangaId = mangaId,
                            flags = viewerFlagsWithReadingMode(
                                viewerFlagsWithDualPage(mangaViewerFlags, state.dualPageMode),
                                it,
                            ),
                        )
                    }
                },
                onDualPageChange = {
                    model.setDualPageMode(it, runtime.prefs)
                    scope.launch {
                        model.persistViewerFlags(
                            mangaId = mangaId,
                            flags = viewerFlagsWithDualPage(
                                viewerFlagsWithReadingMode(mangaViewerFlags, state.readingMode),
                                it,
                            ),
                        )
                    }
                },
                onAutoSplitPagesChange = { model.setAutoSplitPages(it, runtime.prefs) },
                onAutoSpreadMatchingChange = { model.setAutoSpreadMatching(it, runtime.prefs) },
                onBackgroundThemeChange = { model.setBackgroundTheme(it, runtime.prefs) },
                onNavigationModeChange = { model.setNavigationMode(it, runtime.prefs) },
                onCropBordersPagerChange = { model.setCropBordersPager(it, runtime.prefs) },
                onCropBordersWebtoonChange = { model.setCropBordersWebtoon(it, runtime.prefs) },
                onWebtoonSidePaddingChange = { model.setWebtoonSidePadding(it, runtime.prefs) },
                onWebtoonAutoScrollChange = { model.setWebtoonAutoScroll(it, runtime.prefs) },
                onWebtoonAutoScrollSpeedChange = { model.setWebtoonAutoScrollSpeed(it, runtime.prefs) },
                onColorFilterChange = { model.setColorFilter(it, runtime.prefs) },
                onSkipReadChaptersChange = { model.setSkipReadChapters(it, runtime.prefs) },
                onSkipFilteredChaptersChange = { model.setSkipFilteredChapters(it, runtime.prefs) },
                onSkipDuplicateChaptersChange = { model.setSkipDuplicateChapters(it, runtime.prefs) },
                onScaleTypeChange = { model.setScaleType(it, runtime.prefs) },
                onZoomChange = { model.setZoomState(it) },
                onDismiss = { model.closeSettings() },
            )
        }

        // Main reader viewport
        ReaderViewport(
            state = state,
            model = model,
            chapterId = chapterId,
            sourceId = sourceId,
            navigator = navigator,
            focusRequester = focusRequester,
            contextMenuScope = scope,
            mangaTitle = mangaTitle,
            chapterTitle = chapterTitle,
            preloader = runtime.preloader,
            readerNav = readerNav,
            onPrevChapter = onPrevChapter,
            onNextChapter = onNextChapter,
        )
    }

    internal fun requestAdjacentChapterTransition(
        direction: ReaderTransitionDirection,
        model: ReaderScreenModel,
        readerNavigator: ReaderNavigator?,
        viewerFlags: Long = mangaViewerFlags,
    ): DesktopReaderScreen? {
        val target = when (direction) {
            ReaderTransitionDirection.PREVIOUS -> readerNavigator?.previousRead
            ReaderTransitionDirection.NEXT -> readerNavigator?.nextToRead
        }
        if (target == null) {
            model.showChapterBoundary(direction, chapterId, chapterUrl, chapterTitle, chapterNumber)
            return null
        }
        return copyForChapter(
            ref = target,
            newIndex = ReaderNavigator.indexForId(chapters, target.id),
            initialPage = initialPageForChapterNavigation(
                if (direction == ReaderTransitionDirection.PREVIOUS) {
                    ReaderChapterNavigationDirection.Previous
                } else {
                    ReaderChapterNavigationDirection.Next
                },
            ),
            viewerFlags = viewerFlags,
            loadedPageUrls = emptyList(),
        )
    }

    internal fun destinationForChapterTransition(
        transition: ReaderChapterTransitionModel,
        viewerFlags: Long,
    ): DesktopReaderScreen? {
        val loaded = transition.state as? ReaderChapterState.Loaded ?: return null
        val target = transition.to?.let { destination -> chapters.firstOrNull { it.id == destination.id } }
            ?: return null
        return copyForChapter(
            ref = target,
            newIndex = ReaderNavigator.indexForId(chapters, target.id),
            initialPage = initialPageForChapterNavigation(
                if (transition.direction == ReaderTransitionDirection.PREVIOUS) {
                    ReaderChapterNavigationDirection.Previous
                } else {
                    ReaderChapterNavigationDirection.Next
                },
            ),
            viewerFlags = viewerFlags,
            loadedPageUrls = loaded.pages.map { page -> page.imageUrl ?: page.url },
        )
    }

    /** Creates a replacement screen for a sibling chapter, preserving context. */
    private fun copyForChapter(
        ref: ReaderChapterRef,
        newIndex: Int,
        initialPage: Int,
        viewerFlags: Long,
        loadedPageUrls: List<String>,
    ) = DesktopReaderScreen(
        chapterTitle = ref.name, mangaTitle = mangaTitle, pageUrls = loadedPageUrls,
        isWebtoon = isWebtoon, sourceId = sourceId, chapterUrl = ref.url, chapterId = ref.id,
        mangaId = mangaId, chapterNumber = ref.chapterNumber, mangaViewerFlags = viewerFlags,
        chapters = chapters, currentChapterIndex = newIndex, initialPage = initialPage,
        isRtl = isRtl, isDualPage = isDualPage, progressTracker = progressTracker,
    )

    internal fun createReaderScreenModel(
        prefs: ReaderPreferences,
        pageLoader: DesktopReaderPageLoader,
    ): ReaderScreenModel = DesktopReaderRuntimeFactory.createModel(
        chapterTitle = chapterTitle,
        pageUrls = pageUrls,
        initialPage = initialPage,
        chapterId = chapterId,
        isWebtoon = isWebtoon,
        sourceId = sourceId,
        chapterUrl = chapterUrl,
        mangaTitle = mangaTitle,
        mangaViewerFlags = mangaViewerFlags,
        dualPageOverride = isDualPage,
        prefs = prefs,
        pageLoader = pageLoader,
    )
}

internal object ReaderInitialPage {
    const val FIRST: Int = 0
    const val LAST: Int = Int.MAX_VALUE
}

internal enum class ReaderChapterNavigationDirection {
    Previous,
    Next,
}

internal fun initialPageForChapterNavigation(direction: ReaderChapterNavigationDirection): Int =
    when (direction) {
        ReaderChapterNavigationDirection.Previous -> ReaderInitialPage.LAST
        ReaderChapterNavigationDirection.Next -> ReaderInitialPage.FIRST
    }

// ── Private helper composables ───────────────────────────────────────────────

internal fun readerProgressPageForTracking(state: ReaderState): Int {
    val pageCount = state.resolvedUrls.size
    if (pageCount <= 0) return 0
    val safeCurrent = state.currentPage.coerceIn(0, pageCount - 1)
    if (!state.dualPageMode || pageCount == 1) return safeCurrent

    val dualState = DualPageState(
        totalPages = pageCount,
        spreadPages = state.spreadPages,
        forcedSinglePages = state.forcedSinglePages,
        matchedPairs = state.matchedPairs,
    )
    val group = dualState.getGroup(dualState.groupIndexForPage(safeCurrent))
    return group.maxOrNull()?.coerceIn(0, pageCount - 1) ?: safeCurrent
}

@Composable
private fun ReaderPageLoaderEffect(
    model: ReaderScreenModel,
    pageLoader: DesktopReaderPageLoader,
    pageUrls: List<String>,
    sourceId: Long,
    chapterUrl: String,
    mangaTitle: String,
    chapterTitle: String,
    initialPage: Int,
    loadGeneration: Long,
) {
    LaunchedEffect(sourceId, chapterUrl, loadGeneration) {
        if (pageUrls.isNotEmpty() || sourceId == 0L || chapterUrl.isBlank()) return@LaunchedEffect
        try {
            pageLoader.load(
                model = model,
                sourceId = sourceId,
                chapterUrl = chapterUrl,
                mangaTitle = mangaTitle,
                chapterTitle = chapterTitle,
                initialPage = initialPage,
            )
        } catch (e: Exception) {
                model.setLoadError(e.message ?: MR.strings.desktop_ui_unknown_page_load_error.localized())
        }
    }

}

@Composable
private fun ReaderSideEffects(
    state: ReaderState,
    model: ReaderScreenModel,
    preloader: PagePreloader,
    focusRequester: FocusRequester,
) {
    LaunchedEffect(state.currentPage) { model.setZoomState(ZoomState()) }
    LaunchedEffect(state.currentPage, state.resolvedUrls) {
        if (state.resolvedUrls.isNotEmpty()) preloader.preload(state.currentPage, state.resolvedUrls)
    }
    LaunchedEffect(state.showSettings) { if (!state.showSettings) focusRequester.requestFocus() }
    LaunchedEffect(state.resolvedUrls, state.autoSpreadMatching, state.dualPageMode) {
        model.setMatchedPairs(
            resolveDesktopMatchedPairs(
                autoSpreadMatching = state.autoSpreadMatching,
                dualPageMode = state.dualPageMode,
                pageUrls = state.resolvedUrls,
            ),
        )
    }
    LaunchedEffect(state.resolvedUrls.size, state.spreadPages, state.autoSplitPages, state.dualPageMode, state.readingMode) {
        model.setVirtualPages(
            if (state.autoSplitPages && !state.dualPageMode && state.readingMode != ReadingMode.WEBTOON && state.spreadPages.isNotEmpty())
                buildVirtualPageList(totalPages = state.resolvedUrls.size, spreadPages = state.spreadPages, isRtl = state.readingMode == ReadingMode.RTL)
            else null,
        )
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); focusRequester.requestFocus() }
}

internal suspend fun resolveDesktopMatchedPairs(
    autoSpreadMatching: Boolean,
    dualPageMode: Boolean,
    pageUrls: List<String>,
    findMatchedPairs: suspend (List<String>) -> Set<Pair<Int, Int>> = { EdgePixelMatcher().findMatchedPairs(it) },
): Set<Pair<Int, Int>> =
    if (autoSpreadMatching && dualPageMode && pageUrls.size > 1) {
        findMatchedPairs(pageUrls)
    } else {
        emptySet()
    }

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReaderViewport(
    state: ReaderState,
    model: ReaderScreenModel,
    chapterId: Long,
    sourceId: Long,
    navigator: Navigator,
    focusRequester: FocusRequester,
    contextMenuScope: kotlinx.coroutines.CoroutineScope,
    mangaTitle: String,
    chapterTitle: String,
    preloader: PagePreloader,
    readerNav: ReaderNavigator?,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    val bgColor = when (state.backgroundTheme) {
        ReaderBackgroundTheme.BLACK -> Color.Black
        ReaderBackgroundTheme.GRAY -> Color(0xFF444444)
        ReaderBackgroundTheme.WHITE -> Color.White
        ReaderBackgroundTheme.AUTOMATIC -> Color.Black
    }
    Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val native = event.nativeEvent as? java.awt.event.MouseWheelEvent
                    if (native?.isControlDown == true) {
                        val delta = native.preciseWheelRotation.toFloat()
                        model.setZoomState(if (delta > 0f) state.zoomState.zoomOut() else state.zoomState.zoomIn())
                    }
                }
                .onKeyEvent { event ->
                    handleReaderKeyEvent(event, state, model, navigator, readerNav, onPrevChapter, onNextChapter)
                },
        ) {
            when (readerViewportBody(state)) {
                ReaderViewportBody.LOADING -> LoadingState()
                ReaderViewportBody.ERROR -> ErrorState(
                    requireNotNull(state.errorMessage),
                    onRetry = model::requestRetry,
                    onBack = { navigator.pop() },
                )
                ReaderViewportBody.EMPTY -> EmptyState(onBack = { navigator.pop() })
                ReaderViewportBody.CONTENT -> {
                    CompositionLocalProvider(LocalDesktopSourceImageId provides sourceId) {
                        ReaderViewportColorLayer(state.colorFilter) {
                            ReaderContent(state, model, navigator, chapterId, contextMenuScope, mangaTitle, chapterTitle, preloader, readerNav, onPrevChapter, onNextChapter)
                        }
                    }
                    ColorFilterOverlay(state.colorFilter)
                    if (state.showUI) {
                        ReaderBottomBar(
                            currentPage = state.currentPage, totalPages = state.resolvedUrls.size,
                            onPageChange = { model.goToPage(it) }, isRtl = state.readingMode == ReadingMode.RTL,
                            isDualPage = state.dualPageMode, hasPrevChapter = readerNav?.previousRead != null,
                            hasNextChapter = readerNav?.nextToRead != null, onPrevChapter = onPrevChapter,
                            onNextChapter = onNextChapter,
                            onAdjustSpread = {
                                val p = state.currentPage
                                model.setForcedSinglePages(if (p !in state.forcedSinglePages) state.forcedSinglePages + p else state.forcedSinglePages - p)
                            },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
            state.chapterTransition?.let { transition ->
                ChapterTransitionFeedback(
                    transition = transition,
                    onContinue = null,
                    onRetry = transition.to?.let {
                        {
                            contextMenuScope.launch { model.retryChapterTransition() }
                        }
                    },
                    onDismiss = model::clearChapterTransition,
                )
            }
            if (state.showUI) {
                TopAppBar(
                    title = { Text(chapterTitle, maxLines = 1, style = MaterialTheme.typography.bodyMedium, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            MR.strings.desktop_ui_back.localized(),
                            tint = Color.White,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { model.toggleSettings() }) {
                        Icon(
                            Icons.Default.Settings,
                            MR.strings.desktop_ui_reader_settings.localized(),
                            tint = Color.White,
                        )
                    }
                },
                    modifier = Modifier.align(Alignment.TopCenter),
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                )
            }
        }
    }
}

internal enum class ReaderViewportBody {
    CONTENT,
    LOADING,
    ERROR,
    EMPTY,
}

internal fun readerViewportBody(state: ReaderState): ReaderViewportBody {
    val keepsMountedSinglePage =
        state.readingMode != ReadingMode.WEBTOON && !state.dualPageMode && state.resolvedUrls.isNotEmpty()
    return when {
        keepsMountedSinglePage -> ReaderViewportBody.CONTENT
        state.isLoadingPages -> ReaderViewportBody.LOADING
        state.errorMessage != null -> ReaderViewportBody.ERROR
        state.resolvedUrls.isEmpty() -> ReaderViewportBody.EMPTY
        else -> ReaderViewportBody.CONTENT
    }
}

@Composable
internal fun ReaderViewportColorLayer(
    colorFilter: ReaderColorFilter,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().readerColorTransform(colorFilter), content = { content() })
}

private fun handleReaderKeyEvent(
    event: androidx.compose.ui.input.key.KeyEvent,
    state: ReaderState,
    model: ReaderScreenModel,
    navigator: Navigator,
    readerNav: ReaderNavigator?,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val navPosition = readerKeyboardNavigationPosition(state)
    val totalPages = navPosition.total
    val navCurrent = navPosition.current
    if (event.isCtrlPressed) {
        return when (event.key) {
            Key.Equals -> { model.setZoomState(state.zoomState.zoomIn()); true }
            Key.Minus -> { model.setZoomState(state.zoomState.zoomOut()); true }
            Key.Zero -> { model.setZoomState(state.zoomState.reset()); true }
            else -> false
        }
    }
    val isRtl = state.readingMode == ReadingMode.RTL
    val action = when (event.key) {
        Key.DirectionLeft, Key.A -> ReaderKeyboardAction.forLeft(isRtl, navCurrent, totalPages)
        Key.DirectionRight, Key.D, Key.Spacebar -> ReaderKeyboardAction.forRight(isRtl, navCurrent, totalPages)
        Key.MoveHome -> ReaderKeyboardAction.forHome()
        Key.MoveEnd -> ReaderKeyboardAction.forEnd(totalPages)
        Key.PageUp -> ReaderKeyboardAction.forPageUp(navCurrent, totalPages)
        Key.PageDown -> ReaderKeyboardAction.forPageDown(navCurrent, totalPages)
        Key.Zero -> ReaderKeyboardAction.forDigit(0, totalPages)
        Key.One -> ReaderKeyboardAction.forDigit(1, totalPages)
        Key.Two -> ReaderKeyboardAction.forDigit(2, totalPages)
        Key.Three -> ReaderKeyboardAction.forDigit(3, totalPages)
        Key.Four -> ReaderKeyboardAction.forDigit(4, totalPages)
        Key.Five -> ReaderKeyboardAction.forDigit(5, totalPages)
        Key.Six -> ReaderKeyboardAction.forDigit(6, totalPages)
        Key.Seven -> ReaderKeyboardAction.forDigit(7, totalPages)
        Key.Eight -> ReaderKeyboardAction.forDigit(8, totalPages)
        Key.Nine -> ReaderKeyboardAction.forDigit(9, totalPages)
        Key.Escape -> { navigator.pop(); return true }
        else -> null
    } ?: return false
    return when (action) {
        is ReaderPageAction.GoToPage -> {
            val target = if (state.dualPageMode && state.resolvedUrls.isNotEmpty()) {
                val ds = DualPageState(state.resolvedUrls.size, state.spreadPages, state.forcedSinglePages, state.matchedPairs)
                val cg = ds.groupIndexForPage(state.currentPage.coerceIn(0, state.resolvedUrls.size - 1))
                when (action.page) {
                    navCurrent + 1 -> ds.firstPageInGroup((cg + 1).coerceIn(0, ds.groupCount - 1))
                    navCurrent - 1 -> ds.firstPageInGroup((cg - 1).coerceIn(0, ds.groupCount - 1))
                    else -> ds.firstPageInGroup(action.page.coerceIn(0, ds.groupCount - 1))
                }
            } else if (state.virtualPages != null) {
                state.virtualPages.realPageIndex(action.page.coerceIn(0, state.virtualPages.size - 1))
            } else action.page
            model.goToPage(target); true
        }
        is ReaderPageAction.NoPrevPage -> { onPrevChapter(); true }
        is ReaderPageAction.NoNextPage -> { onNextChapter(); true }
    }
}

internal data class ReaderKeyboardNavigationPosition(
    val current: Int,
    val total: Int,
)

internal fun readerKeyboardNavigationPosition(state: ReaderState): ReaderKeyboardNavigationPosition {
    if (state.dualPageMode && state.resolvedUrls.size > 1) {
        val dualState = DualPageState(
            totalPages = state.resolvedUrls.size,
            spreadPages = state.spreadPages,
            forcedSinglePages = state.forcedSinglePages,
            matchedPairs = state.matchedPairs,
        )
        val safePage = state.currentPage.coerceIn(0, state.resolvedUrls.size - 1)
        return ReaderKeyboardNavigationPosition(
            current = dualState.groupIndexForPage(safePage),
            total = dualState.groupCount,
        )
    }

    val vPages = state.virtualPages
    val totalPages = vPages?.size ?: state.resolvedUrls.size
    val safeCurrent = state.currentPage.coerceIn(0, (state.resolvedUrls.size - 1).coerceAtLeast(0))
    val navCurrent = vPages?.firstVirtualIndex(safeCurrent) ?: state.currentPage
    return ReaderKeyboardNavigationPosition(current = navCurrent, total = totalPages)
}

@Composable
private fun ReaderContent(
    state: ReaderState,
    model: ReaderScreenModel,
    navigator: Navigator,
    chapterId: Long,
    contextMenuScope: kotlinx.coroutines.CoroutineScope,
    mangaTitle: String,
    chapterTitle: String,
    preloader: PagePreloader,
    readerNav: ReaderNavigator?,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    when (state.readingMode) {
        ReadingMode.WEBTOON -> WebtoonViewer(
            pageUrls = state.resolvedUrls, cropBorders = state.cropBordersWebtoon,
            sidePadding = state.webtoonSidePadding, autoScroll = state.webtoonAutoScroll,
            autoScrollSpeed = state.webtoonAutoScrollSpeed, contextMenuScope = contextMenuScope, mangaTitle = mangaTitle, chapterTitle = chapterTitle,
            preloader = preloader,
            onNextChapter = if (readerNav?.nextToRead != null) onNextChapter else null,
        )
        ReadingMode.LTR, ReadingMode.RTL -> {
            val rtl = state.readingMode == ReadingMode.RTL
            ZoomablePagerViewer(
                chapterId = chapterId, loadGeneration = state.loadGeneration,
                pageUrls = state.resolvedUrls, currentPage = state.currentPage,
                currentDisplayUnitId = state.currentDisplayUnitId, isRtl = rtl,
                isDualPage = state.dualPageMode, autoSplitPages = state.autoSplitPages,
                cropBorders = state.cropBordersPager, contextMenuScope = contextMenuScope,
                mangaTitle = mangaTitle, chapterTitle = chapterTitle, zoomState = state.zoomState,
                forcedSinglePages = state.forcedSinglePages, matchedPairs = state.matchedPairs,
                splitPageIndices = state.spreadPages, preloader = preloader, scaleType = state.scaleType,
                navigationMode = state.navigationMode,
                onPageChange = model::goToPage,
                onZoomChange = { model.setZoomState(it) },
                pageError = (state.chapterState as? ReaderChapterState.Error)?.error,
                onRetryPage = model::requestRetry,
                onSingleVisiblePagesChanged = model::settleSinglePage,
                onSpreadPagesChanged = { model.setSpreadPages(it) },
                onSpreadDetected = { realIdx -> if (realIdx !in state.spreadPages) model.setSpreadPages(state.spreadPages + realIdx) },
                onTapCenter = { model.toggleUI() },
                onPrevChapter = onPrevChapter,
                onNextChapter = onNextChapter,
            )
        }
    }
}
