package mihon.desktop.ui.reader

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
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
import tachiyomi.i18n.MR
import java.util.Locale

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
    pagerPresentationBinding: ReaderPagerPresentationBinding? = null,
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
    fun logicalPageToPager(pageId: ReaderPageId): Int? =
        displayUnits.indexOfFirst { unit -> unit.slots.any { it.page?.id == pageId } }
            .takeIf { it >= 0 }?.let(::pageToPager)

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
    val userTurnTarget = remember { mutableStateOf<ReaderPageId?>(null) }
    val navigationIntent = remember { mutableStateOf<ReaderPagerNavigationIntent?>(null) }
    val nativeLocation by rememberUpdatedState(pagerPresentationBinding?.model?.state?.value?.dualPageLocation)
    val nativeUnits by rememberUpdatedState(displayUnits.indices.map { displayUnits[pagerToPage(it)].id })

    LaunchedEffect(pagerState.interactionSource) {
        pagerState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                navigationIntent.value =
                    ReaderPagerNavigationIntent(nativeUnits, pagerState.currentPage, logicalLocation = nativeLocation)
                programmaticTarget.value = null
                userTurnTarget.value = null
            } else if (interaction is DragInteraction.Stop) {
                navigationIntent.value = navigationIntent.value?.copy(dragReleased = true)
            } else if (interaction is DragInteraction.Cancel) {
                navigationIntent.value = null
            }
        }
    }

    LaunchedEffect(
        currentPageId,
        nativeLocation,
        displayUnits[currentDisplayUnit.coerceIn(displayUnits.indices)].id,
        displayUnits.map(DisplayUnit::id),
        isRtl,
        pageTurnAnimation,
    ) {
        val targetPage = presentation
            .restoreDisplayUnitIndex(currentPageId, currentDisplayUnitId)
            .coerceAtLeast(0)
            .coerceIn(0, maxPageIndex)
        val targetPagerIndex = pageToPager(targetPage)
        if (userTurnTarget.value != currentPageId) userTurnTarget.value = null
        programmaticTarget.value = targetPagerIndex
        pagerState.turnToPage(targetPagerIndex, animateTurns)
        programmaticTarget.value = null
        if (userTurnTarget.value == currentPageId) userTurnTarget.value = null
    }

    val selectedIndex = pagerState.currentPage.coerceIn(displayUnits.indices)
    val selectedUnit = displayUnits[pagerToPage(selectedIndex)]
    val nativePresentation = rememberPagerNativePresentation(
        unit = selectedUnit,
        visible = presentation.visiblePages(selectedUnit.id),
        binding = pagerPresentationBinding,
        userNavigation = navigationIntent.value?.selects(selectedIndex, selectedUnit.id, nativeLocation) == true,
        idle = !pagerState.isScrollInProgress && pagerState.settledPage == selectedIndex &&
            (programmaticTarget.value?.let { it == selectedIndex } ?: true),
        onVisiblePagesChanged = onVisiblePagesChanged,
        navigationLocation = navigationIntent.value?.logicalLocation ?: nativeLocation,
    )

    SideEffect {
        val intent = navigationIntent.value
        if (intent != null && !pagerState.isScrollInProgress && pagerState.settledPage == selectedIndex &&
            intent.finished(selectedIndex)
        ) {
            navigationIntent.value = null
        }
    }

    fun executeTapCommand(command: ReaderNavigationCommand) {
        val basePage =
            userTurnTarget.value?.let(::logicalPageToPager)
                ?: if (programmaticTarget.value != null) pagerState.settledPage else pagerState.currentPage
        when (val action = ReaderKeyboardAction.forPagerCommand(command, isRtl, basePage, displayUnits.size)) {
            is ReaderPageAction.GoToPage -> {
                navigationIntent.value =
                    ReaderPagerNavigationIntent(nativeUnits, basePage, action.page, logicalLocation = nativeLocation)
                val targetPage = presentation.visiblePages(displayUnits[pagerToPage(action.page)].id).activePageId
                userTurnTarget.value = targetPage
                programmaticTarget.value = action.page
                if (pagerPresentationBinding != null && targetPage != null) {
                    pagerPresentationBinding.model.goToPage(targetPage.sourcePageIndex)
                    return
                }
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
        modifier = Modifier.fillMaxSize().readerMountedGeometry(
            pagerPresentationBinding,
        ).readerPrimaryTapInput(zoomState.scale, navigationMode, isRtl) {
            when (it) {
                TapNavRegion.PREV -> executeTapCommand(ReaderNavigationCommand.Previous)
                TapNavRegion.NEXT -> executeTapCommand(ReaderNavigationCommand.Next)
                TapNavRegion.MENU -> onTapCenter?.invoke()
            }
        },
        key = { pagerIndex -> displayUnits[pagerToPage(pagerIndex)].id },
    ) { pagerIndex ->
        val unit = displayUnits[pagerToPage(pagerIndex)]
        Box(
            modifier = Modifier.fillMaxSize().semantics {
                if (unit.id == selectedUnit.id) this[ReaderPagerNativePresentationKey] = nativePresentation
            },
        ) {
            SinglePageDisplayUnitContainer(
                unit = unit,
                onRetry = onRetryPage,
            ) { readySlot ->
                val readyPage = requireNotNull(readySlot.page)
                ReaderPageImageContent(
                    owner = presentationImageOwner,
                    page = readyPage,
                    generation = generation,
                    splitHalf = readySlot.splitHalf,
                    sourceBounds = readySlot.sourceBounds,
                    cropBorders = cropBorders,
                ) { presentationImage, renderedImage ->
                    ZoomablePageBox(
                        onPagePrepared = { width, height ->
                            kotlin.math.abs(pagerState.getOffsetDistanceInPages(pagerIndex)) < 1f &&
                                (
                                    unit.id != selectedUnit.id ||
                                        nativePresentation.prepare(readySlot.id, readyPage, width, height)
                                    )
                        },
                        onPageDrawn = if (unit.id == selectedUnit.id) {
                            { nativePresentation.drawn(readySlot.id) }
                        } else {
                            null
                        },
                        presentationImage = presentationImage,
                        renderedImage = renderedImage,
                        pageLabel = MR.strings.desktop_ui_page_number.localized(
                            Locale.getDefault(),
                            readyPage.id.sourcePageIndex + 1,
                        ),
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
