package mihon.desktop.ui.reader

import mihon.desktop.LocalDesktopUiDependencies
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.reader.DesktopReaderChapterContext
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.DesktopReaderRuntimeFactory
import mihon.desktop.reader.EdgePixelMatcher
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
import mihon.desktop.reader.desktopReaderRuntimeFactory
import mihon.desktop.reader.runResourceActions
import mihon.desktop.ui.reader.presentation.DesktopReaderPresentationRegistry
import mihon.desktop.ui.reader.presentation.ReaderPresentationMode
import mihon.desktop.ui.reader.presentation.ReaderPresentationSnapshot
import mihon.desktop.ui.reader.presentation.desktopReaderPresentationRequest
import mihon.desktop.ui.reader.presentation.dualDisplayUnitIndexForSourcePage
import mihon.desktop.ui.reader.presentation.firstDualPageIndex
import mihon.desktop.ui.source.desktopSourceErrorMessage
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderTransitionDirection
import mihon.domain.reader.session.ReaderChapterLoadState

@OptIn(ExperimentalMaterial3Api::class)
data class DesktopReaderScreen(
    val chapterTitle: String,
    val mangaTitle: String = "",
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
    val localChapterPath: String? = null,
    @Transient val progressTracker: ReaderProgressTracker? = null,
    @Transient val resumeSnapshot: tachiyomi.domain.reader.model.ReadingSyncSnapshot? = null,
    @Transient val onProductionClosed: () -> Unit = {},
) : Screen {

    // 章节切换发生在同一 Screen/session 内；key 只标识最初打开的 reader entry。
    override val key: String get() = "DesktopReaderScreen-$chapterId-$chapterUrl"

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val runtimeFactory = remember { desktopReaderRuntimeFactory() }
        val model = rememberScreenModel {
            runtimeFactory.createScreenModel(
                initialContext = initialContext(),
                isWebtoon = isWebtoon,
                mangaViewerFlags = mangaViewerFlags,
                dualPageOverride = isDualPage,
                progressTrackerOverride = progressTracker,
                onProductionClosed = onProductionClosed,
            )
        }
        val runtime = checkNotNull(model.runtime)
        val state by model.state.collectAsState()
        val notifications = LocalDesktopUiDependencies.current.notificationService
        LaunchedEffect(state.context.chapterId, state.resumePageUnavailable) {
            if (state.resumePageUnavailable) notifications.post(
                mihon.desktop.domain.DesktopNotification(
                    MR.strings.action_resume.localized(), MR.strings.sync_resume_page_unavailable.localized(),
                ),
            )
        }
        val focusRequester = remember { FocusRequester() }
        ReaderLifecycleEffect(model)
        LaunchedEffect(runtime.session) {
            runtime.session.state.collect(model::acceptSessionState)
        }

        // Compute: zoom reset, focus, edge-scan, virtual pages
        ReaderSideEffects(state, model, runtime.presentationImageOwner, focusRequester)

        // Chapter navigation lambdas
        val skipRead = state.skipReadChapters
        val skipFiltered = state.skipFilteredChapters
        val skipDuplicate = state.skipDuplicateChapters
        val readerNav = remember(chapters, state.context.chapterIndex, skipRead, skipFiltered, skipDuplicate) {
            chapters.takeIf { it.isNotEmpty() }?.let {
                ReaderNavigator(
                    chapters = it,
                    currentIndex = state.context.chapterIndex.coerceIn(it.indices),
                    skipReadChapters = skipRead,
                    skipFilteredChapters = skipFiltered,
                    skipDuplicateChapters = skipDuplicate,
                )
            }
        }
        LaunchedEffect(
            runtime.session,
            state.context.chapterId,
            readerNav?.nextToRead?.id,
            state.readingMode,
            state.dualPageMode,
        ) {
            updateNextChapterPrefetch(
                model = model,
                readerNavigator = readerNav,
                readingMode = state.readingMode,
                dualPage = state.dualPageMode,
            )
        }
        val onPrevChapter: () -> Unit = {
            requestAdjacentChapterTransition(
                ReaderTransitionDirection.PREVIOUS,
                model,
                readerNav,
            )
        }
        val onNextChapter: () -> Unit = {
            requestAdjacentChapterTransition(
                ReaderTransitionDirection.NEXT,
                model,
                readerNav,
            )
        }

        // Settings dialog
        if (state.showSettings) {
            ReaderSettingsPanel(
                currentMode = if (state.automaticLayout) ReadingMode.DEFAULT else state.readingMode,
                followsGlobal = state.followsGlobalReadingMode,
                onFollowGlobal = {
                    model.followGlobalReadingMode(runtime.prefs)
                    scope.launch { model.persistViewerFlags(mangaId, model.currentViewerFlags()) }
                },
                isDualPage = state.dualPageMode,
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
                            flags = model.currentViewerFlags(),
                        )
                    }
                },
                onDualPageChange = {
                    model.setDualPageMode(it, runtime.prefs)
                    scope.launch {
                        model.persistViewerFlags(
                            mangaId = mangaId,
                            flags = model.currentViewerFlags(),
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
            navigator = navigator,
            focusRequester = focusRequester,
            contextMenuScope = scope,
            mangaTitle = state.context.mangaTitle,
            chapterTitle = state.context.chapterTitle,
            presentationImageOwner = runtime.presentationImageOwner,
            readerNav = readerNav,
            onPrevChapter = onPrevChapter,
            onNextChapter = onNextChapter,
        )
    }

    internal fun requestAdjacentChapterTransition(
        direction: ReaderTransitionDirection,
        model: ReaderScreenModel,
        readerNavigator: ReaderNavigator?,
    ): Boolean {
        val target = when (direction) {
            ReaderTransitionDirection.PREVIOUS -> readerNavigator?.previousRead
            ReaderTransitionDirection.NEXT -> readerNavigator?.nextToRead
        }
        if (target == null) {
            // The boundary is an item in the pager/webtoon reading stream. Keep this
            // callback side-effect free when the user attempts to move past it.
            model.clearChapterTransition()
            return false
        }
        model.clearChapterTransition()
        model.activateChapter(chapterContext(target, direction))
        return true
    }

    internal fun updateNextChapterPrefetch(
        model: ReaderScreenModel,
        readerNavigator: ReaderNavigator?,
        readingMode: ReadingMode,
        dualPage: Boolean,
    ) {
        val nextContext = readerNavigator?.nextToRead?.let { target ->
            chapterContext(target, ReaderTransitionDirection.NEXT)
        }
        val firstViewportPageCount = when {
            readingMode == ReadingMode.WEBTOON -> WEBTOON_FIRST_VIEWPORT_PAGES
            dualPage -> DUAL_FIRST_VIEWPORT_PAGES
            else -> SINGLE_FIRST_VIEWPORT_PAGES
        }
        model.updateNextChapterPrefetch(nextContext, firstViewportPageCount)
    }

    private fun chapterContext(
        target: ReaderChapterRef,
        direction: ReaderTransitionDirection,
    ) = DesktopReaderChapterContext(
        chapterId = target.id,
        sourceId = sourceId,
        chapterUrl = target.url,
        mangaTitle = mangaTitle,
        chapterTitle = target.name,
        chapterNumber = target.chapterNumber,
        chapterIndex = ReaderNavigator.indexForId(chapters, target.id),
        initialPage = initialPageForChapterNavigation(
                if (direction == ReaderTransitionDirection.PREVIOUS) {
                    ReaderChapterNavigationDirection.Previous
                } else {
                    ReaderChapterNavigationDirection.Next
                },
        ),
        wasRead = target.isRead,
        mangaId = mangaId,
        scanlator = target.scanlator,
        isDownloaded = target.isDownloaded,
    )

    internal fun initialContext() = DesktopReaderChapterContext(
        resumeSnapshot = resumeSnapshot,
        chapterId = chapterId,
        sourceId = sourceId,
        chapterUrl = chapterUrl,
        mangaTitle = mangaTitle,
        chapterTitle = chapterTitle,
        chapterNumber = chapters.getOrNull(currentChapterIndex)?.chapterNumber ?: chapterNumber,
        chapterIndex = currentChapterIndex,
        initialPage = initialPage,
        wasRead = chapters.getOrNull(currentChapterIndex)?.isRead ?: false,
        localChapterPath = localChapterPath,
        mangaId = mangaId,
        scanlator = chapters.getOrNull(currentChapterIndex)?.scanlator,
        isDownloaded = chapters.getOrNull(currentChapterIndex)?.isDownloaded ?: false,
    )

    private companion object {
        const val SINGLE_FIRST_VIEWPORT_PAGES = 1
        const val DUAL_FIRST_VIEWPORT_PAGES = 2
        const val WEBTOON_FIRST_VIEWPORT_PAGES = 3
    }
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

internal fun ReaderState.effectiveMatchedPairs(): Set<Pair<Int, Int>> =
    if (forcedSinglePages.isEmpty()) matchedPairs else emptySet()

internal fun ReaderState.dualPresentationSnapshot(
    hasPreviousChapter: Boolean = true,
    hasNextChapter: Boolean = true,
): ReaderPresentationSnapshot {
    val direction = if (readingMode == ReadingMode.RTL) ReaderDirection.RTL else ReaderDirection.LTR
    val request = desktopReaderPresentationRequest(
        chapter = session.activeChapter,
        direction = direction,
        hasPreviousChapter = hasPreviousChapter,
        hasNextChapter = hasNextChapter,
        spreadPageIndices = spreadPages,
        forcedSinglePageIndices = forcedSinglePages,
        matchedPagePairs = effectiveMatchedPairs(),
        splitWidePages = autoSplitPages,
    )
    return DesktopReaderPresentationRegistry.require(ReaderPresentationMode.DUAL_PAGED).present(request)
}

internal fun ReaderState.singlePresentationSnapshot(
    hasPreviousChapter: Boolean = true,
    hasNextChapter: Boolean = true,
): ReaderPresentationSnapshot {
    val direction = if (readingMode == ReadingMode.RTL) ReaderDirection.RTL else ReaderDirection.LTR
    val request = desktopReaderPresentationRequest(
        chapter = session.activeChapter,
        direction = direction,
        hasPreviousChapter = hasPreviousChapter,
        hasNextChapter = hasNextChapter,
        splitPageIndices = spreadPages,
        splitWidePages = autoSplitPages,
    )
    return DesktopReaderPresentationRegistry.require(ReaderPresentationMode.SINGLE_PAGED).present(request)
}

internal typealias ReaderSpreadAdjustment = mihon.domain.reader.ReaderPairingAdjustment

internal fun adjustedForcedSinglePages(state: ReaderState): Set<Int> = adjustedSpread(state).forcedSinglePages

internal fun adjustedSpread(state: ReaderState): ReaderSpreadAdjustment {
    val unchanged = ReaderSpreadAdjustment(state.forcedSinglePages, state.currentPage)
    if (!state.dualPageMode || state.session.activeChapter.pages.isEmpty()) return unchanged
    val presentation = state.dualPresentationSnapshot()
    val unitIndex = presentation.dualDisplayUnitIndexForSourcePage(state.currentPage)
    if (unitIndex < 0) return unchanged

    val pageIndices = presentation.displayUnits[unitIndex].slots
        .mapNotNull { it.page?.id?.sourcePageIndex }
        .distinct()
    return mihon.domain.reader.adjustReaderPairing(state.currentPage, pageIndices, state.forcedSinglePages)
}

@Composable
private fun ReaderSideEffects(
    state: ReaderState,
    model: ReaderScreenModel,
    presentationImageOwner: DesktopReaderPresentationImageOwner,
    focusRequester: FocusRequester,
) {
    LaunchedEffect(state.currentPage) { model.setZoomState(ZoomState()) }
    LaunchedEffect(state.showSettings) { if (!state.showSettings) focusRequester.requestFocus() }
    LaunchedEffect(state.session.activeChapter.pages.size, state.autoSpreadMatching, state.dualPageMode) {
        observeDesktopMatchedPairs(
            presentationImageOwner = presentationImageOwner,
            autoSpreadMatching = state.autoSpreadMatching,
            dualPageMode = state.dualPageMode,
            pageCount = state.session.activeChapter.pages.size,
            retainedMatchedPairs = state.matchedPairs,
            onMatchedPairsChanged = model::setMatchedPairs,
        )
    }
    LaunchedEffect(state.session.activeChapter.pages.size, state.spreadPages, state.autoSplitPages, state.dualPageMode, state.readingMode) {
        model.setVirtualPages(
            if (state.autoSplitPages && !state.dualPageMode && state.readingMode != ReadingMode.WEBTOON && state.spreadPages.isNotEmpty())
                buildVirtualPageList(totalPages = state.session.activeChapter.pages.size, spreadPages = state.spreadPages, isRtl = state.readingMode == ReadingMode.RTL)
            else null,
        )
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); focusRequester.requestFocus() }
}

internal suspend fun observeDesktopMatchedPairs(
    presentationImageOwner: DesktopReaderPresentationImageOwner,
    autoSpreadMatching: Boolean,
    dualPageMode: Boolean,
    pageCount: Int,
    retainedMatchedPairs: Set<Pair<Int, Int>>,
    findMatchedPairs: suspend (Int, (Int) -> androidx.compose.ui.graphics.ImageBitmap?) -> Set<Pair<Int, Int>> =
        { count, provider -> EdgePixelMatcher().findMatchedPairs(count, provider) },
    onMatchedPairsChanged: (Set<Pair<Int, Int>>) -> Unit,
) {
    var retained = retainedMatchedPairs
    presentationImageOwner.cacheRevision.collectLatest { revision ->
        val cachedAssets = presentationImageOwner.tryRetainCachedFullPageAssets() ?: return@collectLatest
        val nextRetained =
            try {
                if (autoSpreadMatching && dualPageMode) {
                    retained + resolveDesktopMatchedPairs(
                        autoSpreadMatching = true,
                        dualPageMode = true,
                        pageCount = pageCount,
                        pageAt = { pageIndex -> cachedAssets[pageIndex]?.asset?.bitmap },
                        findMatchedPairs = findMatchedPairs,
                    )
                } else {
                    emptySet()
                }
            } finally {
                runResourceActions(cachedAssets.values.map { lease -> lease::close })
            }
        if (presentationImageOwner.isClosed() || presentationImageOwner.cacheRevision.value != revision) {
            return@collectLatest
        }
        retained = nextRetained
        onMatchedPairsChanged(retained)
    }
}

internal suspend fun resolveDesktopMatchedPairs(
    autoSpreadMatching: Boolean,
    dualPageMode: Boolean,
    pageCount: Int,
    pageAt: (Int) -> androidx.compose.ui.graphics.ImageBitmap?,
    findMatchedPairs: suspend (Int, (Int) -> androidx.compose.ui.graphics.ImageBitmap?) -> Set<Pair<Int, Int>> =
        { count, provider -> EdgePixelMatcher().findMatchedPairs(count, provider) },
): Set<Pair<Int, Int>> =
    if (autoSpreadMatching && dualPageMode && pageCount > 1) {
        findMatchedPairs(pageCount, pageAt)
    } else {
        emptySet()
    }

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderViewport(
    state: ReaderState,
    model: ReaderScreenModel,
    navigator: Navigator,
    focusRequester: FocusRequester,
    contextMenuScope: kotlinx.coroutines.CoroutineScope,
    mangaTitle: String,
    chapterTitle: String,
    presentationImageOwner: DesktopReaderPresentationImageOwner,
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
                .readerKeyboardFocus(focusRequester) { event ->
                    handleReaderKeyEvent(event, state, model, navigator, readerNav, onPrevChapter, onNextChapter)
                }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val native = event.nativeEvent as? java.awt.event.MouseWheelEvent ?: return@onPointerEvent
                    handleReaderWheelEvent(
                        native,
                        state,
                        model,
                        onPrevChapter,
                        onNextChapter,
                        hasPreviousChapter = readerNav?.previousRead != null,
                        hasNextChapter = readerNav?.nextToRead != null,
                    )
                },
        ) {
            when (readerViewportBody(state)) {
                ReaderViewportBody.LOADING -> LoadingState()
                ReaderViewportBody.ERROR -> ErrorState(
                    desktopSourceErrorMessage(
                        (state.session.activeChapter.loadState as ReaderChapterLoadState.Error).error,
                    ),
                    onRetry = model::requestRetry,
                    onBack = { navigator.pop() },
                )
                ReaderViewportBody.EMPTY -> EmptyState(onBack = { navigator.pop() })
                ReaderViewportBody.CONTENT -> {
                    ReaderViewportColorLayer(state.colorFilter) {
                        ReaderContent(
                            state,
                            model,
                            contextMenuScope,
                            mangaTitle,
                            chapterTitle,
                            presentationImageOwner,
                            readerNav,
                            onPrevChapter,
                            onNextChapter,
                        )
                    }
                    ColorFilterOverlay(state.colorFilter)
                    if (state.showUI) {
                        ReaderBottomBar(
                            currentPage = state.currentPage, totalPages = state.session.activeChapter.pages.size,
                            onPageChange = { model.goToPage(it) }, isRtl = state.readingMode == ReadingMode.RTL,
                            isDualPage = state.dualPageMode, hasPrevChapter = readerNav?.previousRead != null,
                            hasNextChapter = readerNav?.nextToRead != null, onPrevChapter = onPrevChapter,
                            onNextChapter = onNextChapter,
                            onAdjustSpread = model::adjustSpread,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
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

@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.readerKeyboardFocus(
    focusRequester: FocusRequester,
    onKeyEvent: (androidx.compose.ui.input.key.KeyEvent) -> Boolean,
): Modifier =
    focusRequester(focusRequester)
        .focusable()
        .onPointerEvent(PointerEventType.Press) { focusRequester.requestFocus() }
        .onPointerEvent(PointerEventType.Scroll) { focusRequester.requestFocus() }
        .onKeyEvent(onKeyEvent)

internal enum class ReaderWheelIntent {
    NONE,
    PREVIOUS_PAGE,
    NEXT_PAGE,
    ZOOM_IN,
    ZOOM_OUT,
}

internal fun readerWheelIntent(
    rotation: Double,
    isControlDown: Boolean,
    readingMode: ReadingMode,
): ReaderWheelIntent = when {
    rotation == 0.0 -> ReaderWheelIntent.NONE
    isControlDown && rotation > 0.0 -> ReaderWheelIntent.ZOOM_OUT
    isControlDown -> ReaderWheelIntent.ZOOM_IN
    readingMode == ReadingMode.WEBTOON -> ReaderWheelIntent.NONE
    rotation > 0.0 -> ReaderWheelIntent.NEXT_PAGE
    else -> ReaderWheelIntent.PREVIOUS_PAGE
}

internal enum class ReaderViewportBody {
    CONTENT,
    LOADING,
    ERROR,
    EMPTY,
}

internal fun readerViewportBody(state: ReaderState): ReaderViewportBody {
    val chapter = state.session.activeChapter
    return when {
        chapter.pages.isNotEmpty() -> ReaderViewportBody.CONTENT
        chapter.loadState is ReaderChapterLoadState.Error -> ReaderViewportBody.ERROR
        chapter.loadState is ReaderChapterLoadState.LoadingPageList ||
            chapter.loadState is ReaderChapterLoadState.Wait -> ReaderViewportBody.LOADING
        else -> ReaderViewportBody.EMPTY
    }
}

@Composable
internal fun ReaderViewportColorLayer(
    colorFilter: ReaderColorFilter,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().readerColorTransform(colorFilter), content = { content() })
}

internal fun handleReaderWheelEvent(
    event: java.awt.event.MouseWheelEvent,
    state: ReaderState,
    model: ReaderScreenModel,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    hasPreviousChapter: Boolean = true,
    hasNextChapter: Boolean = true,
): Boolean {
    val intent = readerWheelIntent(event.preciseWheelRotation, event.isControlDown, state.readingMode)
    return when (intent) {
        ReaderWheelIntent.NONE -> false
        ReaderWheelIntent.ZOOM_IN -> {
            model.setZoomState(state.zoomState.zoomIn())
            true
        }
        ReaderWheelIntent.ZOOM_OUT -> {
            model.setZoomState(state.zoomState.zoomOut())
            true
        }
        ReaderWheelIntent.PREVIOUS_PAGE,
        ReaderWheelIntent.NEXT_PAGE,
        -> {
            val navPosition = readerKeyboardNavigationPosition(
                state = state,
                hasPreviousChapter = hasPreviousChapter,
                hasNextChapter = hasNextChapter,
            )
            val action = when (intent) {
                ReaderWheelIntent.NEXT_PAGE -> ReaderKeyboardAction.forNext(navPosition.current, navPosition.total)
                ReaderWheelIntent.PREVIOUS_PAGE -> ReaderKeyboardAction.forPrevious(navPosition.current)
                else -> error("Unexpected non-page wheel intent: $intent")
            }
            applyReaderPageAction(
                action,
                state,
                model,
                onPrevChapter,
                onNextChapter,
                hasPreviousChapter,
                hasNextChapter,
            )
        }
    }
}

internal fun handleReaderKeyEvent(
    event: androidx.compose.ui.input.key.KeyEvent,
    state: ReaderState,
    model: ReaderScreenModel,
    navigator: Navigator,
    readerNav: ReaderNavigator?,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val navPosition = readerKeyboardNavigationPosition(
        state = state,
        hasPreviousChapter = readerNav?.previousRead != null,
        hasNextChapter = readerNav?.nextToRead != null,
    )
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
    return applyReaderPageAction(
        action,
        state,
        model,
        onPrevChapter,
        onNextChapter,
        readerNav?.previousRead != null,
        readerNav?.nextToRead != null,
    )
}

private fun applyReaderPageAction(
    action: ReaderPageAction,
    state: ReaderState,
    model: ReaderScreenModel,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
): Boolean = when (action) {
    is ReaderPageAction.GoToPage -> {
        val presentation = if (state.dualPageMode) {
            state.dualPresentationSnapshot(hasPreviousChapter, hasNextChapter)
        } else {
            state.singlePresentationSnapshot(hasPreviousChapter, hasNextChapter)
        }
        if (presentation.displayUnits.isEmpty()) return false
        val targetUnit = presentation.displayUnits[action.page.coerceIn(presentation.displayUnits.indices)]
        if (targetUnit.transitionDirection != null) {
            model.selectDisplayUnit(targetUnit.id)
        } else {
            val target = if (state.dualPageMode) {
                presentation.firstDualPageIndex(action.page.coerceIn(presentation.displayUnits.indices))
            } else {
                targetUnit.slots.firstNotNullOfOrNull { it.page?.id?.sourcePageIndex } ?: return false
            }
            model.goToPage(target)
        }
        true
    }
    is ReaderPageAction.NoPrevPage -> { onPrevChapter(); true }
    is ReaderPageAction.NoNextPage -> { onNextChapter(); true }
}

internal data class ReaderKeyboardNavigationPosition(
    val current: Int,
    val total: Int,
)

internal fun readerKeyboardNavigationPosition(
    state: ReaderState,
    hasPreviousChapter: Boolean = true,
    hasNextChapter: Boolean = true,
): ReaderKeyboardNavigationPosition {
    val pageCount = state.session.activeChapter.pages.size
    if (state.dualPageMode && pageCount > 1) {
        val presentation = state.dualPresentationSnapshot(hasPreviousChapter, hasNextChapter)
        val safePage = state.currentPage.coerceIn(0, pageCount - 1)
        val currentUnitIndex = state.currentDisplayUnitId
            ?.let { displayUnitId -> presentation.displayUnits.indexOfFirst { it.id == displayUnitId } }
            ?.takeIf { it >= 0 }
            ?: presentation.dualDisplayUnitIndexForSourcePage(safePage)
        return ReaderKeyboardNavigationPosition(
            current = currentUnitIndex.coerceAtLeast(0),
            total = presentation.displayUnits.size,
        )
    }

    val safeCurrent = state.currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    if (pageCount == 0) return ReaderKeyboardNavigationPosition(current = 0, total = 0)
    val presentation = state.singlePresentationSnapshot(hasPreviousChapter, hasNextChapter)
    val navCurrent = state.currentDisplayUnitId
        ?.let { displayUnitId -> presentation.displayUnits.indexOfFirst { it.id == displayUnitId } }
        ?.takeIf { it >= 0 }
        ?: presentation.firstDisplayUnitIndex(state.session.activeChapter.pages[safeCurrent].id)
    return ReaderKeyboardNavigationPosition(current = navCurrent, total = presentation.displayUnits.size)
}

@Composable
internal fun ReaderContent(
    state: ReaderState,
    model: ReaderScreenModel,
    contextMenuScope: kotlinx.coroutines.CoroutineScope,
    mangaTitle: String,
    chapterTitle: String,
    presentationImageOwner: DesktopReaderPresentationImageOwner,
    readerNav: ReaderNavigator?,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    Box(Modifier.fillMaxSize().adaptiveReaderViewport(model, contextMenuScope)) {
        CompositionLocalProvider(
            LocalReaderChapterTransitionContext provides state.context,
            LocalReaderChapterTransitionContentColor provides readerChapterTransitionContentColor(state.backgroundTheme),
        ) {
            when (state.readingMode) {
                ReadingMode.WEBTOON -> WebtoonPresentationViewer(
                    chapter = state.session.activeChapter, currentPage = state.currentPage,
                    currentDisplayUnitId = state.currentDisplayUnitId,
                    initialAnchor = state.webtoonScrollAnchor,
                    autoSplitPages = state.autoSplitPages, splitPageIndices = state.spreadPages,
                    cropBorders = state.cropBordersWebtoon, sidePadding = state.webtoonSidePadding,
                    autoScroll = state.webtoonAutoScroll, autoScrollSpeed = state.webtoonAutoScrollSpeed,
                    contextMenuScope = contextMenuScope, mangaTitle = mangaTitle, chapterTitle = chapterTitle,
                    presentationImageOwner = presentationImageOwner,
                    onViewportChanged = model::settleWebtoon,
                    onRetryPage = model::retryPage,
                    onSpreadDetected = { realIdx -> if (realIdx !in state.spreadPages) model.setSpreadPages(state.spreadPages + realIdx) },
                    hasPreviousChapter = readerNav?.previousRead != null,
                    hasNextChapter = readerNav?.nextToRead != null,
                    onNextChapter = if (readerNav?.nextToRead != null) onNextChapter else null,
                )
                ReadingMode.DEFAULT, ReadingMode.LTR, ReadingMode.RTL -> {
                    val rtl = state.readingMode == ReadingMode.RTL
                    val animationPreference = LocalDesktopUiDependencies.current.appPreferences.pageTurnAnimation
                    val pageTurnAnimation by animationPreference.changes().collectAsState(initial = animationPreference.get())
                    val firstPresentedGeneration by presentationImageOwner.firstPresentedGeneration.collectAsState()
                    ZoomablePagerViewer(
                        pageTurnAnimation = pageTurnAnimation,
                        allowAdjacentViewport = firstPresentedGeneration == state.session.generation,
                        chapter = state.session.activeChapter, currentPage = state.currentPage,
                        currentDisplayUnitId = state.currentDisplayUnitId, isRtl = rtl,
                        isDualPage = state.dualPageMode, autoSplitPages = state.autoSplitPages,
                        cropBorders = state.cropBordersPager, contextMenuScope = contextMenuScope,
                        mangaTitle = mangaTitle, chapterTitle = chapterTitle, zoomState = state.zoomState,
                        forcedSinglePages = state.forcedSinglePages, matchedPairs = state.effectiveMatchedPairs(),
                        splitPageIndices = state.spreadPages,
                        presentationImageOwner = presentationImageOwner,
                        scaleType = state.scaleType,
                        navigationMode = state.navigationMode,
                        onPageChange = model::goToPage,
                        onZoomChange = { model.setZoomState(it) },
                        onRetryPage = model::retryPage,
                        onSingleVisiblePagesChanged = model::settleSinglePage,
                        onDualVisiblePagesChanged = model::settleDualPage,
                        onSpreadDetected = { realIdx -> if (realIdx !in state.spreadPages) model.setSpreadPages(state.spreadPages + realIdx) },
                        onTapCenter = { model.toggleUI() },
                        onPrevChapter = onPrevChapter,
                        onNextChapter = onNextChapter,
                        hasPreviousChapter = readerNav?.previousRead != null,
                        hasNextChapter = readerNav?.nextToRead != null,
                    )
                }
            }
        }
    }
}

internal fun readerChapterTransitionContentColor(backgroundTheme: ReaderBackgroundTheme): Color = when (backgroundTheme) {
    ReaderBackgroundTheme.WHITE -> Color.Black
    ReaderBackgroundTheme.BLACK,
    ReaderBackgroundTheme.GRAY,
    ReaderBackgroundTheme.AUTOMATIC,
    -> Color.White
}
