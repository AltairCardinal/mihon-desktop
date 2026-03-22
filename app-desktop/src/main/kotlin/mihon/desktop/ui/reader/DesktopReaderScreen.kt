package mihon.desktop.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusable
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
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.reader.DualPageState
import mihon.desktop.reader.EdgePixelMatcher
import mihon.desktop.reader.ReaderBackgroundTheme
import mihon.desktop.reader.ReaderColorFilter
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.ReaderKeyboardAction
import mihon.desktop.reader.ReaderNavigator
import mihon.desktop.reader.ReaderPageAction
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.WebtoonSidePadding
import mihon.desktop.reader.ZoomState
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@OptIn(ExperimentalMaterial3Api::class)
data class DesktopReaderScreen(
    val chapterTitle: String,
    val mangaTitle: String = "",
    val pageUrls: List<String> = emptyList(),
    val isWebtoon: Boolean = false,
    val sourceId: Long = 0L,
    val chapterUrl: String = "",
    val chapterId: Long = 0L,
    /** All chapters for this manga (desc order = newest first), enables prev/next navigation. */
    val chapters: List<ReaderChapterRef> = emptyList(),
    /** Index of the current chapter within [chapters]. */
    val currentChapterIndex: Int = 0,
    /** Page to open first (resume from lastPageRead). */
    val initialPage: Int = 0,
    /** RTL (right-to-left) pager mode. */
    val isRtl: Boolean = false,
    /** Show two pages side-by-side (dual-page spread). */
    val isDualPage: Boolean = false,
    @Transient val progressTracker: ReaderProgressTracker? = null,
) : Screen {

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val tracker = remember { progressTracker ?: Injekt.get<ReaderProgressTracker>() }
        val readerPrefs = remember { Injekt.get<ReaderPreferences>() }
        val focusRequester = remember { FocusRequester() }

        var currentPage by remember { mutableStateOf(initialPage.coerceAtLeast(0)) }
        var resolvedUrls by remember { mutableStateOf(pageUrls) }
        var isLoadingPages by remember { mutableStateOf(pageUrls.isEmpty() && sourceId != 0L) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var zoomState by remember { mutableStateOf(ZoomState()) }

        // Reading mode state — loaded from persisted preference.
        // Chapter-specific flags (isWebtoon) always take priority.
        var readingMode by remember {
            mutableStateOf(
                when {
                    isWebtoon -> ReadingMode.WEBTOON
                    else -> readerPrefs.readingMode
                },
            )
        }
        var dualPageMode by remember { mutableStateOf(readerPrefs.isDualPage) }
        var autoSpreadMatching by remember { mutableStateOf(readerPrefs.isAutoSpreadMatching) }
        var backgroundTheme by remember { mutableStateOf(readerPrefs.backgroundTheme) }
        var cropBordersPager by remember { mutableStateOf(readerPrefs.cropBordersPager) }
        var cropBordersWebtoon by remember { mutableStateOf(readerPrefs.cropBordersWebtoon) }
        var webtoonSidePadding by remember { mutableStateOf(readerPrefs.webtoonSidePadding) }
        var colorFilter by remember { mutableStateOf(readerPrefs.loadColorFilter()) }
        var showSettings by remember { mutableStateOf(false) }

        // ── Dual-page state lifted here so keyboard handler and viewer stay in sync ──
        // Pages forced to display alone by the user (via "Adjust Spread" button).
        // Reset when the chapter changes.
        var forcedSinglePages by remember(resolvedUrls) { mutableStateOf(emptySet<Int>()) }
        // Pages detected as spread images (width > height) by ZoomablePageBox after decode.
        // Lifted from DualPagePagerViewer so keyboard handler can use the same grouping.
        var spreadPages by remember(resolvedUrls) { mutableStateOf(emptySet<Int>()) }
        // Page pairs detected by edge-pixel scanning (auto spread matching).
        var matchedPairs by remember(resolvedUrls) { mutableStateOf(emptySet<Pair<Int, Int>>()) }

        val latestPage by rememberUpdatedState(currentPage)
        val latestUrls by rememberUpdatedState(resolvedUrls)

        // Re-request keyboard focus when settings dialog closes
        LaunchedEffect(showSettings) {
            if (!showSettings) focusRequester.requestFocus()
        }

        // Async edge-pixel scan — runs when auto spread matching is toggled or the chapter changes.
        LaunchedEffect(resolvedUrls, autoSpreadMatching, dualPageMode) {
            matchedPairs = if (autoSpreadMatching && dualPageMode && resolvedUrls.size > 1) {
                EdgePixelMatcher().findMatchedPairs(resolvedUrls)
            } else {
                emptySet()
            }
        }

        // Reset zoom/pan whenever the user navigates to a different page
        // (matches Android behaviour: each page starts at default zoom)
        LaunchedEffect(currentPage) {
            zoomState = ZoomState()
        }

        val readerNav = remember(chapters, currentChapterIndex) {
            if (chapters.isEmpty()) null
            else ReaderNavigator(chapters, currentChapterIndex)
        }

        DisposableEffect(Unit) {
            onDispose {
                if (chapterId != 0L && latestUrls.isNotEmpty()) {
                    scope.launch(NonCancellable) {
                        tracker.track(
                            chapterId = chapterId,
                            lastPageRead = latestPage,
                            totalPages = latestUrls.size,
                        )
                    }
                }
            }
        }

        LaunchedEffect(sourceId, chapterUrl) {
            if (pageUrls.isEmpty() && sourceId != 0L && chapterUrl.isNotBlank()) {
                isLoadingPages = true
                errorMessage = null
                try {
                    // Check for local downloads first
                    val downloadProvider = Injekt.get<DesktopDownloadProvider>()
                    val localPages = if (mangaTitle.isNotBlank()) {
                        downloadProvider.getDownloadedPages(
                            sourceId = sourceId,
                            mangaTitle = mangaTitle,
                            chapterName = chapterTitle,
                        )
                    } else emptyList()
                    if (localPages.isNotEmpty()) {
                        resolvedUrls = localPages.map { it.toURI().toString() }
                        currentPage = initialPage.coerceIn(0, resolvedUrls.size - 1)
                    } else {
                        // Fetch from network source
                        val sourceManager = Injekt.get<SourceManager>()
                        val source = sourceManager.getCatalogueSources().find { it.id == sourceId }
                        if (source != null) {
                            val chapter = SChapter.create().apply {
                                url = chapterUrl
                                name = chapterTitle
                            }
                            val pages = source.getPageList(chapter)
                            resolvedUrls = pages.mapNotNull { it.imageUrl }
                            if (resolvedUrls.isEmpty()) {
                                errorMessage = "Source returned 0 pages"
                            } else {
                                currentPage = initialPage.coerceIn(0, resolvedUrls.size - 1)
                            }
                        } else {
                            errorMessage = "Source not found (id=$sourceId)"
                        }
                    }
                } catch (e: Exception) {
                    errorMessage = e.message ?: "Unknown error loading pages"
                } finally {
                    isLoadingPages = false
                }
            }
        }

        // Settings panel dialog
        if (showSettings) {
            ReaderSettingsPanel(
                currentMode = readingMode,
                isDualPage = dualPageMode,
                isAutoSpreadMatching = autoSpreadMatching,
                backgroundTheme = backgroundTheme,
                cropBordersPager = cropBordersPager,
                cropBordersWebtoon = cropBordersWebtoon,
                webtoonSidePadding = webtoonSidePadding,
                colorFilter = colorFilter,
                zoomState = zoomState,
                onModeChange = {
                    readingMode = it
                    // Persist: don't save WEBTOON (chapter-specific flag, not a user preference)
                    if (it != ReadingMode.WEBTOON) readerPrefs.readingMode = it
                },
                onDualPageChange = {
                    dualPageMode = it
                    readerPrefs.isDualPage = it
                    // Clear forced singles when toggling dual page mode off
                    if (!it) forcedSinglePages = emptySet()
                },
                onAutoSpreadMatchingChange = {
                    autoSpreadMatching = it
                    readerPrefs.isAutoSpreadMatching = it
                },
                onBackgroundThemeChange = {
                    backgroundTheme = it
                    readerPrefs.backgroundTheme = it
                },
                onCropBordersPagerChange = {
                    cropBordersPager = it
                    readerPrefs.cropBordersPager = it
                },
                onCropBordersWebtoonChange = {
                    cropBordersWebtoon = it
                    readerPrefs.cropBordersWebtoon = it
                },
                onWebtoonSidePaddingChange = {
                    webtoonSidePadding = it
                    readerPrefs.webtoonSidePadding = it
                },
                onColorFilterChange = {
                    colorFilter = it
                    readerPrefs.saveColorFilter(it)
                },
                onZoomChange = { zoomState = it },
                onDismiss = { showSettings = false },
            )
        }

        // Resolve background colour from theme setting (AUTOMATIC follows Material surface)
        val bgColor = when (backgroundTheme) {
            ReaderBackgroundTheme.BLACK -> Color.Black
            ReaderBackgroundTheme.GRAY -> Color(0xFF444444)
            ReaderBackgroundTheme.WHITE -> Color.White
            ReaderBackgroundTheme.AUTOMATIC -> Color.Black // desktop defaults to black
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = chapterTitle,
                            maxLines = 1,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    actions = {
                        // Previous chapter (older)
                        readerNav?.previousRead?.let { prev ->
                            IconButton(onClick = {
                                navigator.replace(
                                    copyForChapter(prev, ReaderNavigator.indexForId(chapters, prev.id)),
                                )
                            }) {
                                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Chapter", tint = Color.White)
                            }
                        }
                        // Next chapter (newer)
                        readerNav?.nextToRead?.let { next ->
                            IconButton(onClick = {
                                navigator.replace(
                                    copyForChapter(next, ReaderNavigator.indexForId(chapters, next.id)),
                                )
                            }) {
                                Icon(Icons.Default.SkipNext, contentDescription = "Next Chapter", tint = Color.White)
                            }
                        }
                        // Settings gear — opens reading mode / dual-page / zoom panel
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Reader Settings", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.8f)),
                )
            },
            containerColor = bgColor,
        ) { scaffoldPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding)
                    .focusRequester(focusRequester)
                    .focusable()
                    // Ctrl+scroll wheel zoom — use AWT native event to reliably detect Ctrl
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        val native = event.nativeEvent as? java.awt.event.MouseWheelEvent
                        if (native != null && native.isControlDown) {
                            val delta = native.preciseWheelRotation.toFloat()
                            zoomState = if (delta > 0f) zoomState.zoomOut() else zoomState.zoomIn()
                        }
                    }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val totalPages = resolvedUrls.size
                        // Ctrl+= zoom in, Ctrl+- zoom out, Ctrl+0 reset
                        if (event.isCtrlPressed) {
                            when (event.key) {
                                Key.Equals -> { zoomState = zoomState.zoomIn(); true }
                                Key.Minus -> { zoomState = zoomState.zoomOut(); true }
                                Key.Zero -> { zoomState = zoomState.reset(); true }
                                else -> false
                            }
                        } else {
                            val action = when (event.key) {
                                Key.DirectionLeft, Key.A ->
                                    ReaderKeyboardAction.forLeft(readingMode == ReadingMode.RTL, currentPage, totalPages)
                                Key.DirectionRight, Key.D, Key.Spacebar ->
                                    ReaderKeyboardAction.forRight(readingMode == ReadingMode.RTL, currentPage, totalPages)
                                Key.MoveHome -> ReaderKeyboardAction.forHome()
                                Key.MoveEnd -> ReaderKeyboardAction.forEnd(totalPages)
                                Key.PageUp -> ReaderKeyboardAction.forPageUp(currentPage, totalPages)
                                Key.PageDown -> ReaderKeyboardAction.forPageDown(currentPage, totalPages)
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
                                Key.Escape -> { navigator.pop(); return@onKeyEvent true }
                                else -> null
                            }
                            when (action) {
                                is ReaderPageAction.GoToPage -> {
                                    val target = if (dualPageMode && resolvedUrls.isNotEmpty()) {
                                        val ds = DualPageState(resolvedUrls.size, spreadPages, forcedSinglePages, matchedPairs)
                                        val curGroup = ds.groupIndexForPage(
                                            currentPage.coerceIn(0, resolvedUrls.size - 1),
                                        )
                                        when (action.page) {
                                            // Single step forward → jump to next group
                                            currentPage + 1 -> {
                                                val g = (curGroup + 1).coerceIn(0, ds.groupCount - 1)
                                                ds.firstPageInGroup(g)
                                            }
                                            // Single step backward → jump to prev group
                                            currentPage - 1 -> {
                                                val g = (curGroup - 1).coerceIn(0, ds.groupCount - 1)
                                                ds.firstPageInGroup(g)
                                            }
                                            // Big jump (digit / Home / End) → land on first page of target group
                                            else -> {
                                                val targetGroup = ds.groupIndexForPage(
                                                    action.page.coerceIn(0, resolvedUrls.size - 1),
                                                )
                                                ds.firstPageInGroup(targetGroup)
                                            }
                                        }
                                    } else {
                                        action.page
                                    }
                                    currentPage = target
                                    true
                                }
                                is ReaderPageAction.NoPrevPage -> false
                                is ReaderPageAction.NoNextPage -> false
                                null -> false
                            }
                        }
                    },
            ) {
                when {
                    isLoadingPages -> LoadingState()
                    errorMessage != null -> ErrorState(errorMessage!!, onBack = { navigator.pop() })
                    resolvedUrls.isEmpty() -> EmptyState(onBack = { navigator.pop() })
                    else -> {
                        when (readingMode) {
                            ReadingMode.WEBTOON -> WebtoonViewer(
                                pageUrls = resolvedUrls,
                                cropBorders = cropBordersWebtoon,
                                sidePadding = webtoonSidePadding,
                            )
                            ReadingMode.LTR -> ZoomablePagerViewer(
                                pageUrls = resolvedUrls,
                                currentPage = currentPage,
                                isRtl = false,
                                isDualPage = dualPageMode,
                                cropBorders = cropBordersPager,
                                contextMenuScope = scope,
                                mangaTitle = mangaTitle,
                                chapterTitle = chapterTitle,
                                zoomState = zoomState,
                                forcedSinglePages = forcedSinglePages,
                                matchedPairs = matchedPairs,
                                onPageChange = { currentPage = it },
                                onZoomChange = { zoomState = it },
                                onSpreadPagesChanged = { spreadPages = it },
                            )
                            ReadingMode.RTL -> ZoomablePagerViewer(
                                pageUrls = resolvedUrls,
                                currentPage = currentPage,
                                isRtl = true,
                                isDualPage = dualPageMode,
                                cropBorders = cropBordersPager,
                                contextMenuScope = scope,
                                mangaTitle = mangaTitle,
                                chapterTitle = chapterTitle,
                                zoomState = zoomState,
                                forcedSinglePages = forcedSinglePages,
                                matchedPairs = matchedPairs,
                                onPageChange = { currentPage = it },
                                onZoomChange = { zoomState = it },
                                onSpreadPagesChanged = { spreadPages = it },
                            )
                        }

                        // Colour filter / brightness overlay — rendered above pages, below bottom bar
                        if (colorFilter.isEffective) {
                            val overlayColor = if (colorFilter.enabled && colorFilter.alpha > 0) {
                                Color(
                                    red = colorFilter.r / 255f,
                                    green = colorFilter.g / 255f,
                                    blue = colorFilter.b / 255f,
                                    alpha = colorFilter.alpha / 255f,
                                )
                            } else Color.Transparent
                            val brightnessColor = when {
                                colorFilter.brightness > 0f ->
                                    Color.White.copy(alpha = colorFilter.brightness)
                                colorFilter.brightness < 0f ->
                                    Color.Black.copy(alpha = -colorFilter.brightness)
                                else -> Color.Transparent
                            }
                            if (overlayColor != Color.Transparent) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(overlayColor),
                                )
                            }
                            if (brightnessColor != Color.Transparent) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(brightnessColor),
                                )
                            }
                        }

                        // Bottom bar: page counter + adjust spread button + progress slider
                        ReaderBottomBar(
                            currentPage = currentPage,
                            totalPages = resolvedUrls.size,
                            onPageChange = { currentPage = it },
                            isRtl = readingMode == ReadingMode.RTL,
                            isDualPage = dualPageMode,
                            onAdjustSpread = {
                                // Force the first page of the current group to display alone,
                                // shifting all subsequent dual-page pairings by one.
                                if (currentPage !in forcedSinglePages) {
                                    forcedSinglePages = forcedSinglePages + currentPage
                                } else {
                                    // Second press on the same page undoes the forced-single.
                                    forcedSinglePages = forcedSinglePages - currentPage
                                }
                            },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }

        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

    /** Creates a replacement screen for a sibling chapter, preserving context. */
    private fun copyForChapter(ref: ReaderChapterRef, newIndex: Int) = DesktopReaderScreen(
        chapterTitle = ref.name,
        mangaTitle = mangaTitle,
        pageUrls = emptyList(),
        isWebtoon = isWebtoon,
        sourceId = sourceId,
        chapterUrl = ref.url,
        chapterId = ref.id,
        chapters = chapters,
        currentChapterIndex = newIndex,
        initialPage = 0,
        isRtl = isRtl,
        isDualPage = isDualPage,
        progressTracker = progressTracker,
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// Local helper composables (screen-internal, not part of the viewer API)
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Dispatcher that routes to [DualPagePagerViewer] or [SinglePagePagerViewer]
 * based on [isDualPage].  Kept here (not extracted) because it owns no logic —
 * it is just a two-branch if.
 */
@Composable
private fun ZoomablePagerViewer(
    pageUrls: List<String>,
    currentPage: Int,
    isRtl: Boolean,
    isDualPage: Boolean,
    cropBorders: Boolean = false,
    contextMenuScope: kotlinx.coroutines.CoroutineScope? = null,
    mangaTitle: String = "",
    chapterTitle: String = "",
    zoomState: ZoomState,
    forcedSinglePages: Set<Int> = emptySet(),
    matchedPairs: Set<Pair<Int, Int>> = emptySet(),
    onPageChange: (Int) -> Unit,
    onZoomChange: (ZoomState) -> Unit,
    onSpreadPagesChanged: ((Set<Int>) -> Unit)? = null,
) {
    if (isDualPage && pageUrls.size > 1) {
        DualPagePagerViewer(
            pageUrls = pageUrls,
            currentPage = currentPage,
            isRtl = isRtl,
            cropBorders = cropBorders,
            contextMenuScope = contextMenuScope,
            mangaTitle = mangaTitle,
            chapterTitle = chapterTitle,
            zoomState = zoomState,
            forcedSinglePages = forcedSinglePages,
            matchedPairs = matchedPairs,
            onPageChange = onPageChange,
            onZoomChange = onZoomChange,
            onSpreadPagesChanged = onSpreadPagesChanged,
        )
    } else {
        SinglePagePagerViewer(
            pageUrls = pageUrls,
            currentPage = currentPage,
            isRtl = isRtl,
            cropBorders = cropBorders,
            contextMenuScope = contextMenuScope,
            mangaTitle = mangaTitle,
            chapterTitle = chapterTitle,
            zoomState = zoomState,
            onPageChange = onPageChange,
            onZoomChange = onZoomChange,
        )
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Text(
                "Loading pages…",
                color = Color.White,
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ErrorState(message: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("Failed to load pages", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(
                message,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
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
