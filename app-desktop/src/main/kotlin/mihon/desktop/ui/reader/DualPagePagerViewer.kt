package mihon.desktop.ui.reader

import tachiyomi.i18n.MR
import java.util.Locale

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import mihon.desktop.reader.PagePreloader
import mihon.desktop.reader.ReaderKeyboardAction
import mihon.desktop.reader.ReaderPageAction
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.SinglePageSide
import mihon.desktop.reader.ZoomState
import mihon.desktop.reader.singlePageBoxOnRight
import mihon.desktop.ui.reader.presentation.DisplaySlot
import mihon.desktop.ui.reader.presentation.DisplaySlotId
import mihon.desktop.ui.reader.presentation.DisplayUnit
import mihon.desktop.ui.reader.presentation.DisplayUnitId
import mihon.desktop.ui.reader.presentation.ReaderPresentationMode
import mihon.desktop.ui.reader.presentation.ReaderPresentationSnapshot
import mihon.desktop.ui.reader.presentation.VisiblePageSet
import mihon.desktop.ui.reader.presentation.resolveDualVisiblePages
import mihon.domain.reader.ReaderNavigationCommand
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState

internal enum class DualPageLoadingIndicatorPlacement {
    None,
    Center,
    LeftHalfCenter,
    RightHalfCenter,
}

internal fun dualPageLoadingIndicatorPlacement(
    leftLoading: Boolean,
    rightLoading: Boolean,
): DualPageLoadingIndicatorPlacement = when {
    leftLoading && rightLoading -> DualPageLoadingIndicatorPlacement.Center
    leftLoading -> DualPageLoadingIndicatorPlacement.LeftHalfCenter
    rightLoading -> DualPageLoadingIndicatorPlacement.RightHalfCenter
    else -> DualPageLoadingIndicatorPlacement.None
}

internal fun singlePageImageAlignment(side: SinglePageSide, isRtl: Boolean): Alignment =
    if (singlePageBoxOnRight(side, isRtl)) Alignment.CenterStart else Alignment.CenterEnd

internal enum class DualPagePhysicalSlot { LEFT, RIGHT, FULL }

internal class DualPageDisplayUnitCompositionIdentity

internal val DualPageDisplayUnitCompositionIdentityKey =
    SemanticsPropertyKey<DualPageDisplayUnitCompositionIdentity>("DualPageDisplayUnitCompositionIdentity")
internal val DualPageDisplayUnitIdKey = SemanticsPropertyKey<DisplayUnitId>("DualPageDisplayUnitId")
internal val DualPagePhysicalSlotKey = SemanticsPropertyKey<DualPagePhysicalSlot>("DualPagePhysicalSlot")
internal val DualPageSlotIdKey = SemanticsPropertyKey<DisplaySlotId>("DualPageSlotId")

/**
 * Renders one presentation display unit inside a centered, fixed two-slot canvas.
 * A cover or forced single keeps both physical slots mounted; a full landscape spread uses the same centered frame.
 */
@Composable
internal fun DualPageDisplayUnitFrame(
    unit: DisplayUnit,
    onRetry: (ReaderPageId) -> Unit,
    readyContent: @Composable (DisplaySlot, Modifier) -> Unit,
) {
    require(unit.id.mode == ReaderPresentationMode.DUAL_PAGED) { "A dual frame requires a dual display unit" }
    require(unit.slots.size in 1..2) { "A dual display unit must contain one full-span slot or two physical slots" }
    val identity = remember(unit.id) { DualPageDisplayUnitCompositionIdentity() }
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(horizontal = DUAL_PAGE_FRAME_HORIZONTAL_INSET),
        contentAlignment = Alignment.Center,
    ) {
        val frameWidth = minOf(maxWidth, maxHeight * DUAL_PAGE_FRAME_ASPECT_RATIO)
        Box(
            modifier = Modifier
                .width(frameWidth)
                .fillMaxHeight()
                .semantics {
                    this[DualPageDisplayUnitCompositionIdentityKey] = identity
                    this[DualPageDisplayUnitIdKey] = unit.id
                },
            contentAlignment = Alignment.Center,
        ) {
            if (unit.slots.size == 1) {
                DualPageSlotContainer(
                    slot = unit.slots.single(),
                    physicalSlot = DualPagePhysicalSlot.FULL,
                    showLoadingIndicator = true,
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxSize(),
                    readyContent = readyContent,
                )
            } else {
                val leftLoading = unit.slots[0].isLoading()
                val rightLoading = unit.slots[1].isLoading()
                val placement = dualPageLoadingIndicatorPlacement(leftLoading, rightLoading)
                DualPageSlotContainer(
                    slot = unit.slots[0],
                    physicalSlot = DualPagePhysicalSlot.LEFT,
                    showLoadingIndicator = placement == DualPageLoadingIndicatorPlacement.LeftHalfCenter,
                    onRetry = onRetry,
                    modifier = Modifier
                        .align(AbsoluteAlignment.CenterLeft)
                        .fillMaxWidth(0.5f)
                        .fillMaxHeight(),
                    readyContent = readyContent,
                )
                DualPageSlotContainer(
                    slot = unit.slots[1],
                    physicalSlot = DualPagePhysicalSlot.RIGHT,
                    showLoadingIndicator = placement == DualPageLoadingIndicatorPlacement.RightHalfCenter,
                    onRetry = onRetry,
                    modifier = Modifier
                        .align(AbsoluteAlignment.CenterRight)
                        .fillMaxWidth(0.5f)
                        .fillMaxHeight(),
                    readyContent = readyContent,
                )
                if (placement == DualPageLoadingIndicatorPlacement.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun DualPageSlotContainer(
    slot: DisplaySlot,
    physicalSlot: DualPagePhysicalSlot,
    showLoadingIndicator: Boolean,
    onRetry: (ReaderPageId) -> Unit,
    modifier: Modifier,
    readyContent: @Composable (DisplaySlot, Modifier) -> Unit,
) {
    Box(
        modifier = modifier.semantics {
            this[DualPagePhysicalSlotKey] = physicalSlot
            this[DualPageSlotIdKey] = slot.id
        },
        contentAlignment = Alignment.Center,
    ) {
        val page = slot.page ?: return@Box
        when (page.loadState) {
            ReaderPageLoadState.Queued,
            ReaderPageLoadState.ResolvingImage,
            is ReaderPageLoadState.Downloading,
            -> if (showLoadingIndicator) CircularProgressIndicator(color = Color.White)

            ReaderPageLoadState.Ready -> readyContent(slot, Modifier.fillMaxSize())
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

private fun DisplaySlot.isLoading(): Boolean = when (page?.loadState) {
    ReaderPageLoadState.Queued,
    ReaderPageLoadState.ResolvingImage,
    is ReaderPageLoadState.Downloading,
    -> true
    else -> false
}

@Composable
internal fun DualPagePagerViewer(
    presentation: ReaderPresentationSnapshot,
    currentPageId: ReaderPageId,
    currentDisplayUnitId: DisplayUnitId?,
    isRtl: Boolean,
    zoomState: ZoomState,
    cropBorders: Boolean = false,
    contextMenuScope: CoroutineScope? = null,
    mangaTitle: String = "",
    chapterTitle: String = "",
    preloader: PagePreloader? = null,
    scaleType: ScaleType = ScaleType.FIT_SCREEN,
    navigationMode: NavigationMode = NavigationMode.RightAndLeft,
    onVisiblePagesChanged: (VisiblePageSet) -> Unit,
    onZoomChange: (ZoomState) -> Unit,
    onRetryPage: (ReaderPageId) -> Unit,
    onSpreadDetected: ((Int) -> Unit)? = null,
    onTapCenter: (() -> Unit)? = null,
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
) {
    require(presentation.mode == ReaderPresentationMode.DUAL_PAGED) { "Dual viewer requires a dual presentation" }
    val displayUnits = presentation.displayUnits
    if (displayUnits.isEmpty()) return
    val maxPagerIndex = displayUnits.lastIndex
    fun unitToPager(unitIndex: Int): Int = if (isRtl) maxPagerIndex - unitIndex else unitIndex
    fun pagerToUnit(pagerIndex: Int): Int = if (isRtl) maxPagerIndex - pagerIndex else pagerIndex

    val initialUnitIndex = presentation.restoreDisplayUnitIndex(currentPageId, currentDisplayUnitId)
        .coerceAtLeast(0)
        .coerceIn(displayUnits.indices)
    val pagerState = rememberPagerState(
        initialPage = unitToPager(initialUnitIndex),
        pageCount = { displayUnits.size },
    )
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentPageId, currentDisplayUnitId, displayUnits.map(DisplayUnit::id), isRtl) {
        val targetUnit = presentation.restoreDisplayUnitIndex(currentPageId, currentDisplayUnitId)
            .coerceAtLeast(0)
            .coerceIn(displayUnits.indices)
        val targetPager = unitToPager(targetUnit)
        if (pagerState.currentPage != targetPager) pagerState.scrollToPage(targetPager)
    }

    DualPageSettledVisiblePageReporter(
        presentation = presentation,
        isRtl = isRtl,
        settledPagerIndex = { pagerState.settledPage },
        onVisiblePagesChanged = onVisiblePagesChanged,
    )

    fun executeTapCommand(command: ReaderNavigationCommand) {
        when (val action = ReaderKeyboardAction.forPagerCommand(command, isRtl, pagerState.currentPage, displayUnits.size)) {
            is ReaderPageAction.GoToPage -> scope.launch { pagerState.animateScrollToPage(action.page) }
            ReaderPageAction.NoPrevPage -> onPrevChapter?.invoke()
            ReaderPageAction.NoNextPage -> onNextChapter?.invoke()
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        key = { pagerIndex -> displayUnits[pagerToUnit(pagerIndex)].id },
    ) { pagerIndex ->
        val unit = displayUnits[pagerToUnit(pagerIndex)]
        Box(
            modifier = Modifier.fillMaxSize().readerPrimaryTapInput(zoomState.scale, navigationMode, isRtl) {
                when (it) {
                    TapNavRegion.PREV -> executeTapCommand(ReaderNavigationCommand.Previous)
                    TapNavRegion.NEXT -> executeTapCommand(ReaderNavigationCommand.Next)
                    TapNavRegion.MENU -> onTapCenter?.invoke()
                }
            },
        ) {
            DualPageDisplayUnitFrame(
                unit = unit,
                onRetry = onRetryPage,
            ) { slot, modifier ->
                val page = requireNotNull(slot.page)
                val physicalSlot = unit.slots.indexOf(slot)
                ZoomablePageBox(
                    url = page.encodedContentUri(),
                    pageLabel = MR.strings.desktop_ui_page_number.localized(
                        Locale.getDefault(),
                        page.id.sourcePageIndex + 1,
                    ),
                    zoomState = zoomState,
                    onZoomChange = onZoomChange,
                    cropBorders = cropBorders,
                    splitHalf = slot.splitHalf,
                    sourceBounds = slot.sourceBounds,
                    contextMenuScope = contextMenuScope,
                    mangaTitle = mangaTitle,
                    chapterTitle = chapterTitle,
                    pageIndex = page.id.sourcePageIndex,
                    preloader = preloader,
                    modifier = modifier,
                    imageAlignment = when {
                        unit.slots.size == 1 -> Alignment.Center
                        physicalSlot == 0 -> AbsoluteAlignment.CenterRight
                        else -> AbsoluteAlignment.CenterLeft
                    },
                    onSpreadDetected = if (slot.splitHalf == null && onSpreadDetected != null) {
                        { onSpreadDetected(page.id.sourcePageIndex) }
                    } else {
                        null
                    },
                    scaleType = scaleType,
                    navigationMode = navigationMode,
                    isRtl = isRtl,
                    handlesTapNavigation = false,
                )
            }
        }
    }
}

@Composable
internal fun DualPageSettledVisiblePageReporter(
    presentation: ReaderPresentationSnapshot,
    isRtl: Boolean,
    settledPagerIndex: () -> Int,
    onVisiblePagesChanged: (VisiblePageSet) -> Unit,
) {
    val currentCallback by rememberUpdatedState(onVisiblePagesChanged)
    val displayUnitIds = presentation.displayUnits.map(DisplayUnit::id)
    LaunchedEffect(displayUnitIds, isRtl) {
        snapshotFlow { settledPagerIndex() }
            .distinctUntilChanged()
            .collect { pagerIndex ->
                val displayUnits = presentation.displayUnits
                if (displayUnits.isEmpty()) return@collect
                val safePagerIndex = pagerIndex.coerceIn(displayUnits.indices)
                val unitIndex = if (isRtl) displayUnits.lastIndex - safePagerIndex else safePagerIndex
                currentCallback(presentation.resolveDualVisiblePages(displayUnits[unitIndex].id))
            }
    }
}

private fun Modifier.readerPrimaryTapInput(
    zoomScale: Float,
    navigationMode: NavigationMode,
    isRtl: Boolean,
    onTap: (TapNavRegion) -> Unit,
): Modifier = pointerInput(zoomScale, navigationMode, isRtl) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (!event.isReaderPrimaryPress()) continue

            val down = event.changes.first()
            val downPos = down.position
            val downTime = System.currentTimeMillis()
            var gestureComplete = false
            var moved = false
            var isTap = false
            var releasePos = downPos

            while (!gestureComplete) {
                val nextEvent = awaitPointerEvent(PointerEventPass.Main)
                when (nextEvent.type) {
                    PointerEventType.Move -> {
                        val change = nextEvent.changes.first()
                        val dx = change.position.x - downPos.x
                        val dy = change.position.y - downPos.y
                        if (dx * dx + dy * dy > 225f) {
                            moved = true
                            gestureComplete = true
                        }
                    }
                    PointerEventType.Release -> {
                        releasePos = nextEvent.changes.first().position
                        if (System.currentTimeMillis() - downTime < 400) isTap = true
                        gestureComplete = true
                    }
                    PointerEventType.Exit -> gestureComplete = true
                }
            }

            if (isTap && !moved && zoomScale <= 1f) {
                tapNavRegionForPointerButton(
                    button = PointerButton.Primary,
                    x = releasePos.x,
                    y = releasePos.y,
                    width = size.width.toFloat(),
                    height = size.height.toFloat(),
                    mode = navigationMode,
                    isRtl = isRtl,
                )?.let(onTap)
            }
        }
    }
}

private const val DUAL_PAGE_FRAME_ASPECT_RATIO = 4f / 3f
private val DUAL_PAGE_FRAME_HORIZONTAL_INSET = 24.dp
