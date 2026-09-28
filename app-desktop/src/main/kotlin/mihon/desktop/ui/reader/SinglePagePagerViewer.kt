package mihon.desktop.ui.reader

import tachiyomi.i18n.MR
import java.util.Locale

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.ReaderKeyboardAction
import mihon.desktop.reader.ReaderPageAction
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.ZoomState
import mihon.desktop.ui.reader.presentation.DisplaySlot
import mihon.desktop.ui.reader.presentation.DisplayUnit
import mihon.desktop.ui.reader.presentation.DisplayUnitId
import mihon.desktop.ui.reader.presentation.ReaderPresentationSnapshot
import mihon.desktop.ui.reader.presentation.VisiblePageSet
import mihon.domain.reader.ReaderNavigationCommand
import mihon.domain.reader.ReaderTransitionDirection
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState

internal class ReaderDisplayUnitCompositionIdentity

internal val ReaderDisplayUnitCompositionIdentityKey =
    SemanticsPropertyKey<ReaderDisplayUnitCompositionIdentity>("ReaderDisplayUnitCompositionIdentity")
internal val ReaderDisplayUnitIdKey = SemanticsPropertyKey<DisplayUnitId>("ReaderDisplayUnitId")
internal val ReaderDisplayUnitLoadStateKey = SemanticsPropertyKey<ReaderPageLoadState>("ReaderDisplayUnitLoadState")
internal val ReaderDisplayUnitTransitionDirectionKey =
    SemanticsPropertyKey<ReaderTransitionDirection>("ReaderDisplayUnitTransitionDirection")

@Composable
internal fun SinglePagePagerViewer(
    presentation: ReaderPresentationSnapshot,
    currentPageId: ReaderPageId,
    currentDisplayUnitId: DisplayUnitId? = null,
    isRtl: Boolean,
    zoomState: ZoomState,
    cropBorders: Boolean = false,
    contextMenuScope: CoroutineScope? = null,
    mangaTitle: String = "",
    chapterTitle: String = "",
    scaleType: ScaleType = ScaleType.FIT_SCREEN,
    navigationMode: NavigationMode = NavigationMode.RightAndLeft,
    presentationImageOwner: DesktopReaderPresentationImageOwner,
    onVisiblePagesChanged: (VisiblePageSet) -> Unit,
    onZoomChange: (ZoomState) -> Unit,
    onRetryPage: (ReaderPageId) -> Unit,
    onSpreadDetected: ((Int) -> Unit)? = null,
    onTapCenter: (() -> Unit)? = null,
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
    generation: Long = 0L,
    pageTurnAnimation: Boolean = true,
    allowAdjacentViewport: Boolean = true,
) {
    val displayUnits = presentation.displayUnits
    if (displayUnits.isEmpty()) return

    val maxPageIndex = displayUnits.lastIndex

    fun pageToPager(page: Int): Int = if (isRtl) maxPageIndex - page else page
    fun pagerToPage(pagerIndex: Int): Int = if (isRtl) maxPageIndex - pagerIndex else pagerIndex

    val currentDisplayUnit = presentation
        .restoreDisplayUnitIndex(currentPageId, currentDisplayUnitId)
        .coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = pageToPager(currentDisplayUnit.coerceIn(0, maxPageIndex)),
        pageCount = { displayUnits.size },
    )
    val scope = rememberCoroutineScope()
    val animateTurns by rememberUpdatedState(pageTurnAnimation)
    val programmaticTarget = remember { mutableStateOf<Int?>(null) }
    val userTurnTarget = remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(pagerState.interactionSource) {
        pagerState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                programmaticTarget.value = null
                userTurnTarget.value = null
            }
        }
    }

    LaunchedEffect(currentPageId, currentDisplayUnitId, displayUnits.map(DisplayUnit::id), isRtl, pageTurnAnimation) {
        val targetPage = presentation
            .restoreDisplayUnitIndex(currentPageId, currentDisplayUnitId)
            .coerceAtLeast(0)
            .coerceIn(0, maxPageIndex)
        val targetPagerIndex = pageToPager(targetPage)
        userTurnTarget.value = null
        programmaticTarget.value = targetPagerIndex
        pagerState.turnToPage(targetPagerIndex, animateTurns)
        programmaticTarget.value = null
    }

    SinglePageSettledVisiblePageReporter(
        presentation = presentation,
        isRtl = isRtl,
        settledPagerIndex = { pagerState.settledPage },
        shouldReportSettledPage = { pagerIndex ->
            programmaticTarget.value?.let { it == pagerIndex } ?: true
        },
        onVisiblePagesChanged = onVisiblePagesChanged,
    )

    fun executeTapCommand(command: ReaderNavigationCommand) {
        val basePage = userTurnTarget.value ?: if (programmaticTarget.value != null) pagerState.settledPage else pagerState.currentPage
        when (val action = ReaderKeyboardAction.forPagerCommand(command, isRtl, basePage, displayUnits.size)) {
            is ReaderPageAction.GoToPage -> {
                userTurnTarget.value = action.page
                programmaticTarget.value = action.page
                scope.launch {
                    pagerState.turnToPage(action.page, animateTurns)
                    if (programmaticTarget.value == action.page) {
                        programmaticTarget.value = null
                        userTurnTarget.value = null
                    }
                }
            }
            ReaderPageAction.NoPrevPage -> {
                programmaticTarget.value = null
                userTurnTarget.value = null
                onPrevChapter?.invoke()
            }
            ReaderPageAction.NoNextPage -> {
                programmaticTarget.value = null
                userTurnTarget.value = null
                onNextChapter?.invoke()
            }
        }
    }

    HorizontalPager(
        state = pagerState,
        // Keep the neighboring viewports' image leases mounted so instant turns do not expose a decode gap.
        beyondViewportPageCount = if (allowAdjacentViewport) 1 else 0,
        modifier = Modifier.fillMaxSize().readerPrimaryTapInput(zoomState.scale, navigationMode, isRtl) {
            when (it) {
                TapNavRegion.PREV -> executeTapCommand(ReaderNavigationCommand.Previous)
                TapNavRegion.NEXT -> executeTapCommand(ReaderNavigationCommand.Next)
                TapNavRegion.MENU -> onTapCenter?.invoke()
            }
        },
        key = { pagerIndex -> displayUnits[pagerToPage(pagerIndex)].id },
    ) { pagerIndex ->
        val unit = displayUnits[pagerToPage(pagerIndex)]
        Box(modifier = Modifier.fillMaxSize()) {
            SinglePageDisplayUnitContainer(
                unit = unit,
                onRetry = onRetryPage,
            ) { readySlot ->
                val readyPage = requireNotNull(readySlot.page)
                val presentationImage = rememberReaderPresentationImage(
                    owner = presentationImageOwner,
                    page = readyPage,
                    generation = generation,
                    splitHalf = readySlot.splitHalf,
                    sourceBounds = readySlot.sourceBounds,
                )
                ZoomablePageBox(
                    presentationImage = presentationImage,
                    pageLabel = MR.strings.desktop_ui_page_number.localized(Locale.getDefault(), readyPage.id.sourcePageIndex + 1),
                    zoomState = zoomState,
                    onZoomChange = onZoomChange,
                    cropBorders = cropBorders,
                    contextMenuScope = contextMenuScope,
                    mangaTitle = mangaTitle,
                    chapterTitle = chapterTitle,
                    pageIndex = readyPage.id.sourcePageIndex,
                    onRetry = { onRetryPage(readyPage.id) },
                    onSpreadDetected = if (readySlot.splitHalf == null && onSpreadDetected != null) {
                        { onSpreadDetected(readyPage.id.sourcePageIndex) }
                    } else {
                        null
                    },
                    scaleType = scaleType,
                    navigationMode = navigationMode,
                    isRtl = isRtl,
                    handlesTapNavigation = false,
                    onTapPrevious = { executeTapCommand(ReaderNavigationCommand.Previous) },
                    onTapNext = { executeTapCommand(ReaderNavigationCommand.Next) },
                    onTapCenter = onTapCenter,
                )
            }
        }
    }
}

/** Every discrete pager input uses the same animation policy. Initial placement never scrolls. */
internal suspend fun PagerState.turnToPage(page: Int, animated: Boolean) {
    if (currentPage == page && currentPageOffsetFraction == 0f) return
    if (animated) animateScrollToPage(page) else scrollToPage(page)
}

@Composable
internal fun SinglePageSettledVisiblePageReporter(
    presentation: ReaderPresentationSnapshot,
    isRtl: Boolean,
    settledPagerIndex: () -> Int,
    shouldReportSettledPage: (Int) -> Boolean = { true },
    onVisiblePagesChanged: (VisiblePageSet) -> Unit,
) {
    val currentCallback by rememberUpdatedState(onVisiblePagesChanged)
    val currentShouldReport by rememberUpdatedState(shouldReportSettledPage)
    val displayUnitIds = presentation.displayUnits.map(DisplayUnit::id)
    LaunchedEffect(displayUnitIds, isRtl) {
        snapshotFlow {
            val displayUnits = presentation.displayUnits
            if (displayUnits.isEmpty()) {
                null
            } else {
                val safePagerIndex = settledPagerIndex().coerceIn(displayUnits.indices)
                safePagerIndex to currentShouldReport(safePagerIndex)
            }
        }
            .distinctUntilChanged()
            .collect { settled ->
                val displayUnits = presentation.displayUnits
                val (safePagerIndex, shouldReport) = settled ?: return@collect
                if (!shouldReport) return@collect
                val displayUnitIndex = if (isRtl) displayUnits.lastIndex - safePagerIndex else safePagerIndex
                currentCallback(presentation.visiblePages(displayUnits[displayUnitIndex].id))
            }
    }
}

@Composable
internal fun SinglePageDisplayUnitContainer(
    unit: DisplayUnit,
    onRetry: (ReaderPageId) -> Unit,
    readyContent: @Composable (DisplaySlot) -> Unit,
) {
    if (unit.transitionDirection != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics { this[ReaderDisplayUnitTransitionDirectionKey] = unit.transitionDirection },
        ) {
            ReaderChapterTransitionItem(
                direction = unit.transitionDirection,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 64.dp),
            )
        }
        return
    }
    val slot = unit.slots.single()
    val page = requireNotNull(slot.page)
    val compositionIdentity = remember(unit.id) { ReaderDisplayUnitCompositionIdentity() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                this[ReaderDisplayUnitCompositionIdentityKey] = compositionIdentity
                this[ReaderDisplayUnitIdKey] = unit.id
                this[ReaderDisplayUnitLoadStateKey] = page.loadState
            },
        contentAlignment = Alignment.Center,
    ) {
        when (page.loadState) {
            ReaderPageLoadState.Queued,
            ReaderPageLoadState.ResolvingImage,
            is ReaderPageLoadState.Downloading,
            -> CircularProgressIndicator(color = Color.White)

            ReaderPageLoadState.Ready -> readyContent(slot)
            is ReaderPageLoadState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(MR.strings.desktop_ui_failed_to_load_pages.localized(), color = Color.White)
                Button(
                    onClick = { onRetry(page.id) },
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text(MR.strings.action_retry.localized())
                }
            }
        }
    }
}
