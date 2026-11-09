package mihon.desktop.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import kotlinx.coroutines.CoroutineScope
import mihon.desktop.reader.DesktopReaderRuntime
import mihon.desktop.reader.DesktopReaderSessionState
import mihon.desktop.reader.ReaderBackgroundTheme
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReaderStartupTrace
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.ZoomState
import mihon.desktop.reader.dualPageFromViewerFlags
import mihon.domain.reader.session.ReaderChapterLoadState

/** Captured opening admission shared with the full viewport during the same runtime. */
internal class ReaderFirstCompositionAdmission(val initialReady: Boolean) {
    var selected: ReaderState? = null
    val ready: Boolean get() = initialReady || selected != null
}

internal val LocalReaderFirstCompositionReady = staticCompositionLocalOf<ReaderFirstCompositionAdmission?> { null }

/** A loaded target must resolve the captured downloaded fixed single-page opening intent. */
internal fun firstCompositionSnapshotReady(state: ReaderState): Boolean =
    state.context.isDownloaded && state.session.activeChapter.loadState is ReaderChapterLoadState.Loaded &&
        state.session.activeChapter.id.value == state.context.chapterId &&
        state.currentPage in state.session.activeChapter.pages.indices &&
        resolveInitialPage(state.context.initialPage, state.session.activeChapter.pages.size) == state.currentPage &&
        !state.resumePageUnavailable &&
        state.readingMode in setOf(ReadingMode.RTL, ReadingMode.LTR) &&
        !state.automaticLayout && !state.dualPageMode && !state.autoSplitPages

/** Reuse the projection; admit only a newly published page list for the identical opening intent. */
internal fun rereadFirstCompositionState(
    initial: ReaderState,
    latest: DesktopReaderSessionState,
    prefs: ReaderPreferences,
    isWebtoon: Boolean,
    mangaViewerFlags: Long,
    dualPageOverride: Boolean?,
): ReaderState? {
    if (latest.snapshot.activeChapter.loadState !is ReaderChapterLoadState.Loaded ||
        initial.session.generation != latest.snapshot.generation ||
        initial.session.activeChapter.id != latest.snapshot.activeChapter.id ||
        initial.context.copy(isDownloaded = latest.context.isDownloaded) != latest.context
    ) {
        return null
    }
    val candidate = initial.copy(
        context = latest.context,
        session = latest.snapshot,
        currentPage = resolveInitialPage(latest.context.initialPage, latest.snapshot.activeChapter.pages.size),
        resumePageUnavailable = invalidSavedPage(latest.context.initialPage, latest.snapshot.activeChapter.pages.size),
    )
    if (!firstCompositionSnapshotReady(candidate)) return null
    if (initialReaderMode(prefs, isWebtoon, mangaViewerFlags, dualPageOverride) != initial.readingMode ||
        (dualPageFromViewerFlags(mangaViewerFlags) ?: dualPageOverride ?: prefs.isDualPage) != initial.dualPageMode ||
        prefs.autoSplitPages != initial.autoSplitPages ||
        prefs.isAutoSpreadMatching != initial.autoSpreadMatching ||
        prefs.cropBordersPager != initial.cropBordersPager || prefs.scaleType != initial.scaleType ||
        prefs.navigationMode != initial.navigationMode || prefs.backgroundTheme != initial.backgroundTheme ||
        prefs.loadColorFilter() != initial.colorFilter
    ) {
        return null
    }
    return candidate
}

@Composable
internal fun ReaderFirstCompositionHost(
    useStartup: Boolean,
    startupContent: @Composable () -> Unit,
    fullContent: @Composable () -> Unit,
) {
    // A single startup call site preserves the active gesture and image content owners.
    if (useStartup) startupContent() else fullContent()
}

internal sealed interface ReaderEarlyInput {
    data class Keyboard(val event: KeyEvent) : ReaderEarlyInput
    data class Wheel(val event: java.awt.event.MouseWheelEvent) : ReaderEarlyInput
    data class PageTurn(val forward: Boolean) : ReaderEarlyInput
}

/** The registry and the runtime survive the lightweight viewport and full model boundary. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ReaderFirstComposition(
    runtime: DesktopReaderRuntime,
    allowStartup: Boolean,
    isWebtoon: Boolean,
    mangaViewerFlags: Long,
    dualPageOverride: Boolean?,
    contextMenuScope: CoroutineScope,
    onBack: () -> Unit,
    createModel: () -> ReaderScreenModel,
    fullContent: @Composable (ReaderScreenModel, ZoomState, Boolean, List<ReaderEarlyInput>) -> Unit,
) {
    val startupAllowed = remember(runtime) { allowStartup }
    val initial = remember(runtime) {
        val published = runtime.session.state.value
        initialReaderState(
            runtime.prefs,
            published,
            isWebtoon,
            mangaViewerFlags,
            dualPageOverride,
            false,
        ).also { state ->
            val target = state.session.activeChapter.pages.getOrNull(state.currentPage)?.id
            ReaderStartupTrace.mark(
                if (allowStartup &&
                    firstCompositionSnapshotReady(state)
                ) {
                    "FIRST_COMPOSITION_SNAPSHOT_READY"
                } else {
                    "FIRST_COMPOSITION_INITIAL_NOT_READY"
                },
                target,
                state.session.generation,
                state.session.activeChapter.pages.size,
            )
        }
    }
    val registry = remember(runtime) { ReaderPageImageContentRegistry() }
    var inputActive by remember(runtime) { mutableStateOf(false) }
    var toggleUI by remember(runtime) { mutableStateOf(false) }
    var zoom by remember(runtime) { mutableStateOf(ZoomState()) }
    val published by runtime.session.state.collectAsState()
    val presentedGeneration by runtime.presentationImageOwner.firstPresentedGeneration.collectAsState()
    val initialEligible = initial.context.isDownloaded &&
        initial.readingMode in setOf(ReadingMode.RTL, ReadingMode.LTR) &&
        !initial.automaticLayout && !initial.dualPageMode && !initial.autoSplitPages
    val selected = remember(runtime, published.snapshot.generation, published.snapshot.activeChapter.loadState) {
        if (startupAllowed && initialEligible) {
            rereadFirstCompositionState(
                initial,
                published,
                runtime.prefs,
                isWebtoon,
                mangaViewerFlags,
                dualPageOverride,
            ).also { accepted ->
                if (accepted != null) {
                    val page = accepted.session.activeChapter.pages[accepted.currentPage]
                    ReaderStartupTrace.mark(
                        "FIRST_COMPOSITION_TARGET_ADMITTED",
                        page.id,
                        accepted.session.generation,
                        attemptGeneration = page.attemptGeneration,
                    )
                } else if (published.snapshot.activeChapter.pages.isEmpty()) {
                    ReaderStartupTrace.markChapter("FIRST_COMPOSITION_WAIT_PAGE_LIST", published.context.chapterId)
                }
            }
        } else {
            null
        }
    }
    val sameOpening = published.snapshot.generation == initial.session.generation &&
        published.snapshot.activeChapter.id == initial.session.activeChapter.id &&
        initial.context.copy(isDownloaded = published.context.isDownloaded) == published.context
    val pending = published.snapshot.activeChapter.loadState is ReaderChapterLoadState.LoadingPageList ||
        published.snapshot.activeChapter.loadState is ReaderChapterLoadState.Wait
    val qualified = startupAllowed && initialEligible && sameOpening && (pending || selected != null)
    var requestedFullContent by remember(runtime) { mutableStateOf(false) }
    var earlyInputs by remember(runtime) { mutableStateOf(emptyList<ReaderEarlyInput>()) }
    val mustCreateModel = !qualified || requestedFullContent || toggleUI ||
        presentedGeneration == published.snapshot.generation
    var model by remember(runtime) { mutableStateOf<ReaderScreenModel?>(null) }
    if (mustCreateModel && model == null) {
        val page = selected?.currentPage?.let { published.snapshot.activeChapter.pages.getOrNull(it) }
        ReaderStartupTrace.mark(
            "FIRST_COMPOSITION_MODEL_UNLOCK",
            page?.id,
            published.snapshot.generation,
            attemptGeneration = page?.attemptGeneration,
        )
        model = createModel()
    }
    val activeModel = model
    // Connect the bridge as soon as the model exists, while the lightweight viewport still owns pixels.
    LaunchedEffect(runtime, activeModel) {
        if (activeModel != null) runtime.session.state.collect(activeModel::acceptSessionState)
    }
    val fullState = activeModel?.state?.collectAsState()?.value
    val target = selected?.currentPage
    val startupPage = target?.let { published.snapshot.activeChapter.pages.getOrNull(it) }
    val fullPage = target?.let { fullState?.session?.activeChapter?.pages?.getOrNull(it) }
    val fullReady = fullState != null && fullState.session.generation == published.snapshot.generation &&
        fullState.context.chapterId == published.context.chapterId && fullState.currentPage == target &&
        fullState.pairingLoad != PairingLoad.LOADING && readerViewportBody(fullState) == ReaderViewportBody.CONTENT &&
        fullPage?.id == startupPage?.id && fullPage?.attemptGeneration == startupPage?.attemptGeneration &&
        fullPage?.encodedPageRef == startupPage?.encodedPageRef
    var handedOff by remember(runtime) { mutableStateOf(false) }
    val awaitingFullReady = activeModel == null || (!fullReady && !requestedFullContent && !toggleUI)
    val useStartup = !handedOff && (inputActive || (qualified && awaitingFullReady))
    if (useStartup && qualified && pending) {
        DisposableEffect(runtime) {
            val demand = runtime.session.registerOpeningViewportDemand(
                DesktopReaderSessionState(initial.context, initial.session),
            ) { latest ->
                rereadFirstCompositionState(
                    initial,
                    latest,
                    runtime.prefs,
                    isWebtoon,
                    mangaViewerFlags,
                    dualPageOverride,
                )?.let { accepted -> accepted.session.activeChapter.pages[accepted.currentPage].id }
            }
            onDispose { demand.close() }
        }
    }
    val admission = remember(runtime) { ReaderFirstCompositionAdmission(initialEligible) }
    admission.selected = selected
    CompositionLocalProvider(
        LocalReaderPageImageContent provides registry,
        LocalReaderFirstCompositionReady provides admission,
    ) {
        ReaderFirstCompositionHost(
            useStartup = useStartup,
            startupContent = {
                val startup = selected ?: initial
                val chapter = published.snapshot.activeChapter
                val target = resolveInitialPage(published.context.initialPage, chapter.pages.size)
                val sameChapter = published.snapshot.generation == startup.session.generation &&
                    chapter.id == startup.session.activeChapter.id
                val page = chapter.pages.getOrNull(target).takeIf { sameChapter }
                val focus = remember { FocusRequester() }
                LaunchedEffect(focus) { focus.requestFocus() }
                val background = when (startup.backgroundTheme) {
                    ReaderBackgroundTheme.WHITE -> Color.White
                    ReaderBackgroundTheme.GRAY -> Color(0xFF444444)
                    else -> Color.Black
                }
                Box(
                    Modifier.fillMaxSize().background(
                        background,
                    ).focusRequester(focus).focusable().onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onBack()
                            true
                        } else if (event.type == KeyEventType.KeyDown) {
                            earlyInputs = earlyInputs + ReaderEarlyInput.Keyboard(event)
                            requestedFullContent = true
                            true
                        } else {
                            false
                        }
                    }.onPointerEvent(PointerEventType.Scroll) { event ->
                        val native = event.nativeEvent as? java.awt.event.MouseWheelEvent
                        if (native != null) {
                            earlyInputs = earlyInputs + ReaderEarlyInput.Wheel(native)
                            requestedFullContent = true
                        }
                    },
                ) {
                    if (page != null) {
                        ReaderViewportColorLayer(startup.colorFilter) {
                            StartupSinglePageViewport(
                                page = page, generation = published.snapshot.generation,
                                pagerPresentationBinding = null,
                                presentationImageOwner = runtime.presentationImageOwner,
                                zoomState = zoom, onZoomChange = { zoom = it }, cropBorders = startup.cropBordersPager,
                                contextMenuScope = contextMenuScope, mangaTitle = startup.context.mangaTitle,
                                chapterTitle = startup.context.chapterTitle, scaleType = startup.scaleType,
                                navigationMode = startup.navigationMode, isRtl = startup.readingMode == ReadingMode.RTL,
                                onRetryPage = runtime.session::retryPage,
                                onTapCenter = { toggleUI = !toggleUI },
                                onTapPrevious = {
                                    earlyInputs = earlyInputs + ReaderEarlyInput.PageTurn(false)
                                    requestedFullContent =
                                        true
                                },
                                onTapNext = {
                                    earlyInputs = earlyInputs + ReaderEarlyInput.PageTurn(true)
                                    requestedFullContent =
                                        true
                                },
                                onInputActiveChanged = { inputActive = it },
                                onVisiblePagesChanged = { visible ->
                                    visible.activePageId?.let { runtime.session.settleViewport(visible.pageIds, it) }
                                },
                            )
                        }
                        SideEffect { runtime.session.scheduleViewportWithoutProgress(setOf(page.id), page.id) }
                    } else {
                        LoadingState()
                    }
                }
            },
            fullContent = {
                SideEffect {
                    if (!handedOff) {
                        ReaderStartupTrace.mark(
                            "FIRST_COMPOSITION_VISUAL_HANDOFF",
                            startupPage?.id,
                            published.snapshot.generation,
                        )
                    }
                    handedOff = true
                }
                fullContent(requireNotNull(activeModel), zoom, toggleUI, earlyInputs.takeIf { sameOpening }.orEmpty())
            },
        )
    }
}
