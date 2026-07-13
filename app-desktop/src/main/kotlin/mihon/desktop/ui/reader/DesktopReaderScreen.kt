package mihon.desktop.ui.reader

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.reader.DesktopReaderRuntimeFactory
import mihon.desktop.reader.DualPageState
import mihon.desktop.reader.EdgePixelMatcher
import mihon.desktop.reader.DesktopReaderPageLoader
import mihon.desktop.reader.PagePreloader
import mihon.desktop.reader.ReaderBackgroundTheme
import mihon.desktop.reader.ReaderChapterRef
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
    /** Show two pages side-by-side (dual-page spread). */
    val isDualPage: Boolean = true,
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
        val runtime = remember { DesktopReaderRuntimeFactory.createRuntime(progressTracker) }
        val model = rememberScreenModel {
            DesktopReaderRuntimeFactory.createModel(
                chapterTitle = chapterTitle,
                pageUrls = pageUrls,
                initialPage = initialPage,
                isWebtoon = isWebtoon,
                sourceId = sourceId,
                chapterUrl = chapterUrl,
                mangaViewerFlags = mangaViewerFlags,
                prefs = runtime.prefs,
            )
        }
        val state by model.state.collectAsState()
        val focusRequester = remember { FocusRequester() }
        val readerExitEventId = remember(chapterId, key) { java.util.UUID.randomUUID().toString() }
        ReaderLifecycleEffect(state, model, scope, runtime.tracker, chapterId, readerExitEventId)

        // Background page loading (network / local)
        ReaderPageLoaderEffect(
            model = model,
            scope = scope,
            pageLoader = runtime.pageLoader,
            pageUrls = pageUrls,
            sourceId = sourceId,
            chapterUrl = chapterUrl,
            mangaTitle = mangaTitle,
            chapterTitle = chapterTitle,
            initialPage = initialPage,
        )

        // Compute: zoom reset, preload, focus, edge-scan, virtual pages
        ReaderSideEffects(state, model, runtime.preloader, focusRequester)

        // Chapter navigation lambdas
        val skipRead = state.skipReadChapters
        val readerNav = remember(chapters, currentChapterIndex, skipRead) {
            chapters.takeIf { it.isNotEmpty() }?.let { ReaderNavigator(it, currentChapterIndex, skipRead) }
        }
        fun currentViewerFlags(): Long = viewerFlagsWithReadingMode(
            viewerFlagsWithDualPage(mangaViewerFlags, state.dualPageMode),
            state.readingMode,
        )
        val onPrevChapter: () -> Unit = {
            readerNav?.previousRead?.let {
                navigator.replace(
                    copyForChapter(
                        ref = it,
                        newIndex = ReaderNavigator.indexForId(chapters, it.id),
                        initialPage = initialPageForChapterNavigation(ReaderChapterNavigationDirection.Previous),
                        viewerFlags = currentViewerFlags(),
                    ),
                )
            }
        }
        val onNextChapter: () -> Unit = {
            readerNav?.nextToRead?.let {
                navigator.replace(
                    copyForChapter(
                        ref = it,
                        newIndex = ReaderNavigator.indexForId(chapters, it.id),
                        initialPage = initialPageForChapterNavigation(ReaderChapterNavigationDirection.Next),
                        viewerFlags = currentViewerFlags(),
                    ),
                )
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
                scaleType = state.scaleType, skipReadChapters = state.skipReadChapters, zoomState = state.zoomState,
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
                onScaleTypeChange = { model.setScaleType(it, runtime.prefs) },
                onZoomChange = { model.setZoomState(it) },
                onDismiss = { model.closeSettings() },
            )
        }

        // Main reader viewport
        ReaderViewport(
            state = state,
            model = model,
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

    /** Creates a replacement screen for a sibling chapter, preserving context. */
    private fun copyForChapter(
        ref: ReaderChapterRef,
        newIndex: Int,
        initialPage: Int,
        viewerFlags: Long,
    ) = DesktopReaderScreen(
        chapterTitle = ref.name, mangaTitle = mangaTitle, pageUrls = emptyList(),
        isWebtoon = isWebtoon, sourceId = sourceId, chapterUrl = ref.url, chapterId = ref.id,
        mangaId = mangaId, mangaViewerFlags = viewerFlags,
        chapters = chapters, currentChapterIndex = newIndex, initialPage = initialPage,
        isRtl = isRtl, isDualPage = isDualPage, progressTracker = progressTracker,
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

@Composable
private fun ReaderLifecycleEffect(
    state: ReaderState,
    model: ReaderScreenModel,
    scope: kotlinx.coroutines.CoroutineScope,
    tracker: ReaderProgressTracker,
    chapterId: Long,
    exitEventId: String,
) {
    val latestPage by rememberUpdatedState(readerProgressPageForTracking(state))
    val latestUrls by rememberUpdatedState(state.resolvedUrls)
    DisposableEffect(Unit) {
        ReaderModeState.isInReaderMode = true
        onDispose {
            ReaderModeState.isInReaderMode = false
            if (chapterId != 0L && latestUrls.isNotEmpty()) {
                scope.launch(NonCancellable) {
                    tracker.track(exitEventId, chapterId, latestPage, latestUrls.size)
                }
            }
        }
    }
}

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
    scope: kotlinx.coroutines.CoroutineScope,
    pageLoader: DesktopReaderPageLoader,
    pageUrls: List<String>,
    sourceId: Long,
    chapterUrl: String,
    mangaTitle: String,
    chapterTitle: String,
    initialPage: Int,
) {
    LaunchedEffect(sourceId, chapterUrl) {
        if (pageUrls.isNotEmpty() || sourceId == 0L || chapterUrl.isBlank()) return@LaunchedEffect
        try {
            pageLoader.load(
                model = model,
                scope = scope,
                sourceId = sourceId,
                chapterUrl = chapterUrl,
                mangaTitle = mangaTitle,
                chapterTitle = chapterTitle,
                initialPage = initialPage,
            )
        } catch (e: Exception) {
            model.setLoadError(e.message ?: "Unknown error loading pages")
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
            if (state.autoSpreadMatching && state.dualPageMode && state.resolvedUrls.size > 1)
                EdgePixelMatcher().findMatchedPairs(state.resolvedUrls) else emptySet(),
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

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReaderViewport(
    state: ReaderState,
    model: ReaderScreenModel,
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
            when {
                state.isLoadingPages -> LoadingState()
                state.errorMessage != null -> ErrorState(state.errorMessage, onBack = { navigator.pop() })
                state.resolvedUrls.isEmpty() -> EmptyState(onBack = { navigator.pop() })
                else -> {
                    ReaderContent(state, model, navigator, contextMenuScope, mangaTitle, chapterTitle, preloader, readerNav, onPrevChapter, onNextChapter)
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
            if (state.showUI) {
                TopAppBar(
                    title = { Text(chapterTitle, maxLines = 1, style = MaterialTheme.typography.bodyMedium, color = Color.White) },
                    navigationIcon = { IconButton(onClick = { navigator.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) } },
                    actions = { IconButton(onClick = { model.toggleSettings() }) { Icon(Icons.Default.Settings, "Reader Settings", tint = Color.White) } },
                    modifier = Modifier.align(Alignment.TopCenter),
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                )
            }
        }
    }
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
            onNextChapter = if (readerNav?.nextToRead != null) onNextChapter else null,
        )
        ReadingMode.LTR, ReadingMode.RTL -> {
            val rtl = state.readingMode == ReadingMode.RTL
            val vpCurrent = state.virtualPages?.firstVirtualIndex(state.currentPage.coerceIn(0, (state.resolvedUrls.size - 1).coerceAtLeast(0))) ?: state.currentPage
            ZoomablePagerViewer(
                pageUrls = state.resolvedUrls, currentPage = vpCurrent, isRtl = rtl,
                isDualPage = state.dualPageMode, autoSplitPages = state.autoSplitPages,
                cropBorders = state.cropBordersPager, contextMenuScope = contextMenuScope,
                mangaTitle = mangaTitle, chapterTitle = chapterTitle, zoomState = state.zoomState,
                forcedSinglePages = state.forcedSinglePages, matchedPairs = state.matchedPairs,
                virtualPages = state.virtualPages, preloader = preloader, scaleType = state.scaleType,
                navigationMode = state.navigationMode,
                onPageChange = { idx -> model.goToPage(state.virtualPages?.realPageIndex(idx) ?: idx) },
                onZoomChange = { model.setZoomState(it) },
                onSpreadPagesChanged = { model.setSpreadPages(it) },
                onSpreadDetected = { realIdx -> if (realIdx !in state.spreadPages) model.setSpreadPages(state.spreadPages + realIdx) },
                onTapCenter = { model.toggleUI() },
                onPrevChapter = if (readerNav?.previousRead != null) onPrevChapter else null,
                onNextChapter = if (readerNav?.nextToRead != null) onNextChapter else null,
            )
        }
    }
}

@Composable
private fun ColorFilterOverlay(colorFilter: mihon.desktop.reader.ReaderColorFilter) {
    if (!colorFilter.isEffective) return
    val overlayColor = if (colorFilter.enabled && colorFilter.alpha > 0) {
        Color(red = colorFilter.r / 255f, green = colorFilter.g / 255f, blue = colorFilter.b / 255f, alpha = colorFilter.alpha / 255f)
    } else Color.Transparent
    val brightnessColor = when {
        colorFilter.brightness > 0f -> Color.White.copy(alpha = colorFilter.brightness)
        colorFilter.brightness < 0f -> Color.Black.copy(alpha = -colorFilter.brightness)
        else -> Color.Transparent
    }
    if (overlayColor != Color.Transparent) Box(Modifier.fillMaxSize().background(overlayColor))
    if (brightnessColor != Color.Transparent) Box(Modifier.fillMaxSize().background(brightnessColor))
}

// ── Dispatcher: dual-page vs single-page ─────────────────────────────────────

@Composable
private fun ZoomablePagerViewer(
    pageUrls: List<String>,
    currentPage: Int,
    isRtl: Boolean,
    isDualPage: Boolean,
    autoSplitPages: Boolean = false,
    cropBorders: Boolean = false,
    contextMenuScope: kotlinx.coroutines.CoroutineScope? = null,
    mangaTitle: String = "",
    chapterTitle: String = "",
    zoomState: ZoomState,
    forcedSinglePages: Set<Int> = emptySet(),
    matchedPairs: Set<Pair<Int, Int>> = emptySet(),
    virtualPages: List<VirtualPage>? = null,
    preloader: PagePreloader? = null,
    scaleType: ScaleType = ScaleType.FIT_SCREEN,
    navigationMode: NavigationMode = NavigationMode.RightAndLeft,
    onPageChange: (Int) -> Unit,
    onZoomChange: (ZoomState) -> Unit,
    onSpreadPagesChanged: ((Set<Int>) -> Unit)? = null,
    onSpreadDetected: ((Int) -> Unit)? = null,
    onTapCenter: (() -> Unit)? = null,
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
) {
    if (isDualPage && pageUrls.size > 1) {
        DualPagePagerViewer(
            pageUrls = pageUrls, currentPage = currentPage, isRtl = isRtl,
            autoSplitPages = autoSplitPages, cropBorders = cropBorders, contextMenuScope = contextMenuScope,
            mangaTitle = mangaTitle, chapterTitle = chapterTitle, zoomState = zoomState,
            forcedSinglePages = forcedSinglePages, matchedPairs = matchedPairs,
            scaleType = scaleType, navigationMode = navigationMode, onPageChange = onPageChange,
            onZoomChange = onZoomChange, onSpreadPagesChanged = onSpreadPagesChanged,
            onTapCenter = onTapCenter, onPrevChapter = onPrevChapter, onNextChapter = onNextChapter,
        )
    } else {
        SinglePagePagerViewer(
            pageUrls = pageUrls, currentPage = currentPage, isRtl = isRtl,
            cropBorders = cropBorders, contextMenuScope = contextMenuScope,
            mangaTitle = mangaTitle, chapterTitle = chapterTitle, zoomState = zoomState,
            virtualPages = virtualPages, preloader = preloader, scaleType = scaleType,
            navigationMode = navigationMode, onPageChange = onPageChange, onZoomChange = onZoomChange,
            onSpreadDetected = onSpreadDetected, onTapCenter = onTapCenter,
            onPrevChapter = onPrevChapter, onNextChapter = onNextChapter,
        )
    }
}

// ── State overlays ───────────────────────────────────────────────────────────

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Text("Loading pages…", color = Color.White, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ErrorState(message: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("Failed to load pages", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(message, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Go Back") }
        }
    }
}

@Composable
private fun EmptyState(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No pages available", color = Color.White)
            Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Go Back") }
        }
    }
}
