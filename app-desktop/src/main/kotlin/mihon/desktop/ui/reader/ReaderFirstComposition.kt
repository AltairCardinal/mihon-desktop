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
import mihon.desktop.reader.ReaderBackgroundTheme
import mihon.desktop.reader.ReaderStartupTrace
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.ZoomState
import mihon.domain.reader.session.ReaderChapterLoadState

internal val LocalReaderFirstCompositionReady = staticCompositionLocalOf { false }

/** No waiting or retry: only the snapshot sampled by the first actual composition is eligible. */
internal fun firstCompositionSnapshotReady(state: ReaderState): Boolean =
    state.context.isDownloaded && state.session.activeChapter.loadState is ReaderChapterLoadState.Loaded &&
        state.session.activeChapter.id.value == state.context.chapterId &&
        state.currentPage in state.session.activeChapter.pages.indices &&
        state.context.initialPage == state.currentPage && !state.resumePageUnavailable &&
        state.readingMode in setOf(ReadingMode.RTL, ReadingMode.LTR) &&
        !state.automaticLayout && !state.dualPageMode && !state.autoSplitPages

@Composable
internal fun ReaderFirstCompositionHost(
    snapshotReady: Boolean,
    handoffBlocked: Boolean = false,
    startupContent: @Composable () -> Unit,
    fullContent: @Composable () -> Unit,
) {
    var applied by remember { mutableStateOf(false) }
    if (snapshotReady && (!applied || handoffBlocked)) {
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
                    "FIRST_COMPOSITION_FALLBACK"
                },
                target,
                state.session.generation,
                state.session.activeChapter.pages.size,
            )
        }
    }
    val ready = remember(runtime) { allowStartup && firstCompositionSnapshotReady(initial) }
    val registry = remember(runtime) { ReaderPageImageContentRegistry() }
    var inputActive by remember(runtime) { mutableStateOf(false) }
    var requestedPage by remember(runtime) { mutableStateOf<Int?>(null) }
    var toggleUI by remember(runtime) { mutableStateOf(false) }
    var zoom by remember(runtime) { mutableStateOf(ZoomState()) }
    CompositionLocalProvider(
        LocalReaderPageImageContent provides registry,
        LocalReaderFirstCompositionReady provides ready,
    ) {
        ReaderFirstCompositionHost(ready, inputActive, startupContent = {
            val published by runtime.session.state.collectAsState()
            val chapter = published.snapshot.activeChapter
            val target = initial.currentPage
            val sameChapter = published.snapshot.generation == initial.session.generation &&
                chapter.id == initial.session.activeChapter.id
            val page = chapter.pages.getOrNull(target).takeIf { sameChapter }
            val focus = remember { FocusRequester() }
            LaunchedEffect(focus) { focus.requestFocus() }
            val background = when (initial.backgroundTheme) {
                ReaderBackgroundTheme.WHITE -> Color.White
                ReaderBackgroundTheme.GRAY -> Color(0xFF444444)
                else -> Color.Black
            }
            Box(
                Modifier.fillMaxSize().background(background).focusRequester(focus).focusable().onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onBack()
                        true
                    } else {
                        false
                    }
                },
            ) {
                if (page != null) {
                    ReaderViewportColorLayer(initial.colorFilter) {
                        StartupSinglePageViewport(
                            page = page, generation = published.snapshot.generation,
                            pagerPresentationBinding = null, presentationImageOwner = runtime.presentationImageOwner,
                            zoomState = zoom, onZoomChange = { zoom = it }, cropBorders = initial.cropBordersPager,
                            contextMenuScope = contextMenuScope, mangaTitle = initial.context.mangaTitle,
                            chapterTitle = initial.context.chapterTitle, scaleType = initial.scaleType,
                            navigationMode = initial.navigationMode, isRtl = initial.readingMode == ReadingMode.RTL,
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
        }) {
            fullContent(requestedPage, zoom, toggleUI)
        }
    }
}
