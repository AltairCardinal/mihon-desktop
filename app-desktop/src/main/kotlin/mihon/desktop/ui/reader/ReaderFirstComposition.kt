package mihon.desktop.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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

/** One bounded choice; the same owner is installed before the final full-model branch. */
internal class ReaderFirstCompositionAdmission(val initialReady: Boolean) {
    var rereadChecked = false
    var selected: ReaderState? = null
    val ready: Boolean get() = initialReady || selected != null
}

internal val LocalReaderFirstCompositionReady = staticCompositionLocalOf<ReaderFirstCompositionAdmission?> { null }

/** Both bounded samples must resolve a downloaded target with a fixed single-page presentation. */
internal fun firstCompositionSnapshotReady(state: ReaderState): Boolean =
    state.context.isDownloaded && state.session.activeChapter.loadState is ReaderChapterLoadState.Loaded &&
        state.session.activeChapter.id.value == state.context.chapterId &&
        state.currentPage in state.session.activeChapter.pages.indices &&
        state.context.initialPage == state.currentPage && !state.resumePageUnavailable &&
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
        currentPage = latest.context.initialPage,
        resumePageUnavailable = false,
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
    snapshotReady: Boolean,
    handoffBlocked: Boolean = false,
    admission: ReaderFirstCompositionAdmission? = null,
    rereadBeforeFullContent: (() -> ReaderState?)? = null,
    startupContent: @Composable () -> Unit,
    fullContent: @Composable () -> Unit,
) {
    var applied by remember { mutableStateOf(false) }
    val finalAdmission = admission ?: remember { ReaderFirstCompositionAdmission(snapshotReady) }
    // Observe this even on the first late-admission branch so apply schedules the handoff.
    val alreadyApplied = applied
    val useStartup = if (finalAdmission.ready) {
        !alreadyApplied || handoffBlocked
    } else {
        // Common providers and host setup have already run. This branch was about
        // to call fullContent; take its only nonblocking reread immediately here.
        if (!finalAdmission.rereadChecked) {
            ReaderStartupTrace.mark("FIRST_COMPOSITION_FULL_MODEL_DECISION")
            finalAdmission.rereadChecked = true
            finalAdmission.selected = rereadBeforeFullContent?.invoke()
        }
        finalAdmission.ready
    }
    // Keep one startup call site so an active gesture never changes composition owner.
    if (useStartup) {
        startupContent()
        SideEffect { applied = true }
    } else {
        fullContent()
    }
}

/** The registry and the runtime survive the lightweight viewport and full model boundary. */
@Composable
internal fun ReaderFirstComposition(
    runtime: DesktopReaderRuntime,
    allowStartup: Boolean,
    isWebtoon: Boolean,
    mangaViewerFlags: Long,
    dualPageOverride: Boolean?,
    contextMenuScope: CoroutineScope,
    onBack: () -> Unit,
    fullContent: @Composable (Int?, ZoomState, Boolean) -> Unit,
) {
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
    var requestedPage by remember(runtime) { mutableStateOf<Int?>(null) }
    var toggleUI by remember(runtime) { mutableStateOf(false) }
    var zoom by remember(runtime) { mutableStateOf(ZoomState()) }
    val admission = remember(runtime) {
        ReaderFirstCompositionAdmission(allowStartup && firstCompositionSnapshotReady(initial))
    }
    CompositionLocalProvider(
        LocalReaderPageImageContent provides registry,
        LocalReaderFirstCompositionReady provides admission,
    ) {
        ReaderFirstCompositionHost(
            snapshotReady = admission.initialReady,
            handoffBlocked = inputActive,
            admission = admission,
            rereadBeforeFullContent = {
                val latest = runtime.session.state.value
                val accepted = if (allowStartup) {
                    rereadFirstCompositionState(
                        initial,
                        latest,
                        runtime.prefs,
                        isWebtoon,
                        mangaViewerFlags,
                        dualPageOverride,
                    )
                } else {
                    null
                }
                ReaderStartupTrace.mark(
                    if (accepted != null) "FIRST_COMPOSITION_REREAD_READY" else "FIRST_COMPOSITION_REREAD_FALLBACK",
                    latest.snapshot.activeChapter.pages.getOrNull(latest.context.initialPage)?.id,
                    latest.snapshot.generation,
                    latest.snapshot.activeChapter.pages.size,
                )
                accepted
            },
            startupContent = {
                val startup = admission.selected ?: initial
                val published by runtime.session.state.collectAsState()
                val chapter = published.snapshot.activeChapter
                val target = startup.currentPage
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
                        } else {
                            false
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
                                onTapPrevious = { requestedPage = target - 1 },
                                onTapNext = { requestedPage = target + 1 },
                                onInputActiveChanged = { inputActive = it },
                                onVisiblePagesChanged = { visible ->
                                    visible.activePageId?.let { runtime.session.settleViewport(visible.pageIds, it) }
                                },
                            )
                        }
                        SideEffect { runtime.session.scheduleViewportWithoutProgress(setOf(page.id), page.id) }
                    }
                }
            },
        ) {
            fullContent(requestedPage, zoom, toggleUI)
        }
    }
}
