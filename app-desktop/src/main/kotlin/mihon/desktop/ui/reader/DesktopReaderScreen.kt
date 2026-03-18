package mihon.desktop.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import coil3.compose.AsyncImagePainter
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.rememberAsyncImagePainter
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.reader.DualPageState
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReaderKeyboardAction
import mihon.desktop.reader.ReaderNavigator
import mihon.desktop.reader.ReaderPageAction
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
        var showSettings by remember { mutableStateOf(false) }

        val latestPage by rememberUpdatedState(currentPage)
        val latestUrls by rememberUpdatedState(resolvedUrls)

        // Re-request keyboard focus when settings dialog closes
        LaunchedEffect(showSettings) {
            if (!showSettings) focusRequester.requestFocus()
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
                zoomState = zoomState,
                onModeChange = {
                    readingMode = it
                    // Persist: don't save WEBTOON (chapter-specific flag, not a user preference)
                    if (it != ReadingMode.WEBTOON) readerPrefs.readingMode = it
                },
                onDualPageChange = {
                    dualPageMode = it
                    readerPrefs.isDualPage = it
                },
                onZoomChange = { zoomState = it },
                onDismiss = { showSettings = false },
            )
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
            containerColor = Color.Black,
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
                                        val ds = DualPageState(resolvedUrls.size)
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
                                is ReaderPageAction.NoPrevPage -> {
                                    readerNav?.previousRead?.let { prev ->
                                        navigator.replace(
                                            copyForChapter(prev, ReaderNavigator.indexForId(chapters, prev.id)),
                                        )
                                    }
                                    true
                                }
                                is ReaderPageAction.NoNextPage -> {
                                    readerNav?.nextToRead?.let { next ->
                                        navigator.replace(
                                            copyForChapter(next, ReaderNavigator.indexForId(chapters, next.id)),
                                        )
                                    }
                                    true
                                }
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
                            ReadingMode.WEBTOON -> WebtoonViewer(resolvedUrls)
                            ReadingMode.LTR -> ZoomablePagerViewer(
                                pageUrls = resolvedUrls,
                                currentPage = currentPage,
                                isRtl = false,
                                isDualPage = dualPageMode,
                                zoomState = zoomState,
                                onPageChange = { currentPage = it },
                                onZoomChange = { zoomState = it },
                            )
                            ReadingMode.RTL -> ZoomablePagerViewer(
                                pageUrls = resolvedUrls,
                                currentPage = currentPage,
                                isRtl = true,
                                isDualPage = dualPageMode,
                                zoomState = zoomState,
                                onPageChange = { currentPage = it },
                                onZoomChange = { zoomState = it },
                            )
                        }

                        // Chapter-end navigation hint at the bottom
                        ReaderBottomBar(
                            currentPage = currentPage,
                            totalPages = resolvedUrls.size,
                            onPageChange = { currentPage = it },
                            isRtl = readingMode == ReadingMode.RTL,
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
// Private viewer composables
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun ZoomablePagerViewer(
    pageUrls: List<String>,
    currentPage: Int,
    isRtl: Boolean,
    isDualPage: Boolean,
    zoomState: ZoomState,
    onPageChange: (Int) -> Unit,
    onZoomChange: (ZoomState) -> Unit,
) {
    if (isDualPage && pageUrls.size > 1) {
        DualPagePagerViewer(
            pageUrls = pageUrls,
            currentPage = currentPage,
            isRtl = isRtl,
            zoomState = zoomState,
            onPageChange = onPageChange,
            onZoomChange = onZoomChange,
        )
    } else {
        SinglePagePagerViewer(
            pageUrls = pageUrls,
            currentPage = currentPage,
            isRtl = isRtl,
            zoomState = zoomState,
            onPageChange = onPageChange,
            onZoomChange = onZoomChange,
        )
    }
}

/**
 * Standard single-page pager. Each swipe shows one manga page.
 *
 * RTL is handled entirely by [LocalLayoutDirection] — no URL reversal needed.
 * This avoids index-inversion math that would create feedback loops between
 * the two LaunchedEffects when the reading direction changes mid-session.
 */
@Composable
private fun SinglePagePagerViewer(
    pageUrls: List<String>,
    currentPage: Int,
    isRtl: Boolean,
    zoomState: ZoomState,
    onPageChange: (Int) -> Unit,
    onZoomChange: (ZoomState) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = currentPage.coerceIn(0, pageUrls.size - 1),
        pageCount = { pageUrls.size },
    )

    // External navigation (slider / keyboard) → jump pager
    LaunchedEffect(currentPage) {
        if (pagerState.currentPage != currentPage) {
            pagerState.scrollToPage(currentPage.coerceIn(0, pageUrls.size - 1))
        }
    }

    // Pager swipe → update logical page counter
    LaunchedEffect(pagerState.currentPage) {
        onPageChange(pagerState.currentPage)
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            ZoomablePageBox(
                url = pageUrls[page],
                pageLabel = "Page ${page + 1}",
                zoomState = zoomState,
                onZoomChange = onZoomChange,
            )
        }
    }
}

/**
 * Dual-page pager. The first page (cover) is shown alone; subsequent pages are
 * shown in pairs side-by-side, matching Android's double-page spread mode.
 *
 * Layout:
 *   Pager slot 0 → [page 0]          (cover)
 *   Pager slot 1 → [page 1 | page 2]
 *   Pager slot 2 → [page 3 | page 4]
 *   …
 *
 * RTL is handled by [LocalLayoutDirection] — no URL reversal, no inverted
 * index math. This eliminates the feedback loop that caused flickering when
 * switching between LTR and RTL mid-session.
 */
@Composable
private fun DualPagePagerViewer(
    pageUrls: List<String>,
    currentPage: Int,
    isRtl: Boolean,
    zoomState: ZoomState,
    onPageChange: (Int) -> Unit,
    onZoomChange: (ZoomState) -> Unit,
) {
    // Accumulates page indices whose images are wider than tall.
    // Reset whenever the page list changes (new chapter).
    var spreadPages by remember(pageUrls) { mutableStateOf(emptySet<Int>()) }

    // Rebuild the group structure whenever spreads are discovered.
    val dualState = remember(pageUrls.size, spreadPages) {
        DualPageState(pageUrls.size, spreadPages)
    }

    // Map the current logical page to a group index using the latest grouping.
    val safeCurrentPage = currentPage.coerceIn(0, (pageUrls.size - 1).coerceAtLeast(0))
    val initialGroupIndex = dualState.groupIndexForPage(safeCurrentPage)
        .coerceIn(0, (dualState.groupCount - 1).coerceAtLeast(0))

    // Re-create the pager whenever spread detection changes the group layout.
    // initialGroupIndex is recomputed above using the NEW dualState, so the
    // pager opens at the correct position after a spread is discovered.
    key(spreadPages) {
        val pagerState = rememberPagerState(
            initialPage = initialGroupIndex,
            pageCount = { dualState.groupCount },
        )

        // Pager swipe → update logical page counter (first page of the new group)
        LaunchedEffect(pagerState.currentPage) {
            val firstPage = dualState.firstPageInGroup(pagerState.currentPage)
            onPageChange(firstPage.coerceIn(0, pageUrls.size - 1))
        }

        // External navigation (slider / keyboard) → jump pager
        LaunchedEffect(currentPage) {
            val targetGroup = dualState.groupIndexForPage(currentPage.coerceIn(0, pageUrls.size - 1))
            if (pagerState.currentPage != targetGroup) {
                pagerState.scrollToPage(targetGroup.coerceIn(0, dualState.groupCount - 1))
            }
        }

        // Called by each ZoomablePageBox once Coil has decoded the image dimensions.
        val onSpreadDetected: (Int) -> Unit = { pageIndex ->
            if (pageIndex !in spreadPages) {
                spreadPages = spreadPages + pageIndex
            }
        }

        CompositionLocalProvider(
            LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { groupIndex ->
                val group = dualState.getGroup(groupIndex)
                if (group.size == 1) {
                    // Single-page slot: cover, detected spread, or last odd page
                    ZoomablePageBox(
                        url = pageUrls[group[0]],
                        pageLabel = "Page ${group[0] + 1}",
                        zoomState = zoomState,
                        onZoomChange = onZoomChange,
                        onSpreadDetected = { onSpreadDetected(group[0]) },
                    )
                } else {
                    // Two-page spread — pages are glued at the centre spine.
                    // group[0] is the "first" page in reading order (left in LTR, right in RTL).
                    // CenterEnd / CenterStart are layout-direction-aware, so RTL works without
                    // any extra logic: both images automatically stick toward the visual centre.
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        ZoomablePageBox(
                            url = pageUrls[group[0]],
                            pageLabel = "Page ${group[0] + 1}",
                            zoomState = zoomState,
                            onZoomChange = onZoomChange,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            imageAlignment = Alignment.CenterEnd,
                            onSpreadDetected = { onSpreadDetected(group[0]) },
                        )
                        ZoomablePageBox(
                            url = pageUrls[group[1]],
                            pageLabel = "Page ${group[1] + 1}",
                            zoomState = zoomState,
                            onZoomChange = onZoomChange,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            imageAlignment = Alignment.CenterStart,
                            onSpreadDetected = { onSpreadDetected(group[1]) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomablePageBox(
    url: String,
    pageLabel: String,
    zoomState: ZoomState,
    onZoomChange: (ZoomState) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    /**
     * Where to place the image within its box when it doesn't fill the full area
     * (e.g. portrait page in a landscape half-screen slot).
     *
     * Single-page viewer → [Alignment.Center] (default, centred on screen).
     * Dual-page left slot → [Alignment.CenterEnd]  (image sticks to centre spine).
     * Dual-page right slot → [Alignment.CenterStart] (image sticks to centre spine).
     *
     * [CenterStart] / [CenterEnd] are layout-direction-aware, so RTL spreads are
     * handled automatically without any extra logic.
     */
    imageAlignment: Alignment = Alignment.Center,
    /**
     * Called once when Coil finishes decoding the image and its dimensions show
     * width > height (landscape / double-page spread).  Only used in dual-page
     * mode; null in single-page mode.
     */
    onSpreadDetected: (() -> Unit)? = null,
) {
    // Always read the latest zoom state inside gesture lambdas
    val latestZoom by rememberUpdatedState(zoomState)

    // Reuse the same painter for both dimension detection and rendering
    val painter = rememberAsyncImagePainter(url)
    val painterState by painter.state.collectAsState()

    // Detect spread pages: if the decoded image is wider than tall, notify the parent
    if (onSpreadDetected != null) {
        LaunchedEffect(painterState) {
            val s = painterState
            if (s is AsyncImagePainter.State.Success) {
                val img = s.result.image
                if (img.width > img.height) {
                    onSpreadDetected()
                }
            }
        }
    }

    Box(
        modifier = modifier
            // detectTransformGestures handles:
            //   • macOS trackpad two-finger pinch → zoom
            //   • Single-finger drag while zoomed → pan
            //   • Mouse drag while zoomed → pan
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val current = latestZoom
                    val newScale = (current.scale * zoom).coerceIn(1f, ZoomState.MAX_SCALE)
                    val scaled = if (newScale <= 1f) ZoomState() else current.copy(scale = newScale)
                    // Only pan when zoomed in
                    onZoomChange(scaled.pan(pan.x, pan.y))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onZoomChange(ZoomState()) })
            },
        contentAlignment = imageAlignment,
    ) {
        Image(
            painter = painter,
            contentDescription = pageLabel,
            alignment = imageAlignment,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = zoomState.scale,
                    scaleY = zoomState.scale,
                    translationX = zoomState.offsetX,
                    translationY = zoomState.offsetY,
                ),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun WebtoonViewer(pageUrls: List<String>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(pageUrls) { url ->
            Image(
                painter = rememberAsyncImagePainter(url),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Text("Loading pages…", color = Color.White, modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ErrorState(message: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("Failed to load pages", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(message, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp))
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

@Composable
private fun ReaderBottomBar(
    currentPage: Int,
    totalPages: Int,
    onPageChange: (Int) -> Unit,
    isRtl: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${currentPage + 1} / $totalPages",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
            if (totalPages > 1) {
                // Wrap in RTL layout direction so the Slider renders its thumb and
                // filled-track from the right edge (page 0 = right, last page = left).
                // The value is kept as-is (currentPage); LayoutDirection handles the mirror.
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Slider(
                        value = currentPage.toFloat(),
                        onValueChange = { onPageChange(it.toInt().coerceIn(0, totalPages - 1)) },
                        valueRange = 0f..(totalPages - 1).toFloat(),
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}
