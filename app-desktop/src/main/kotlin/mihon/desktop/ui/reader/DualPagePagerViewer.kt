package mihon.desktop.ui.reader

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
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
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import tachiyomi.i18n.MR
import java.util.Locale

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

internal enum class DualPagePhysicalSlot { LEFT, RIGHT, FULL }

internal class DualPageDisplayUnitCompositionIdentity

internal val DualPageDisplayUnitCompositionIdentityKey =
    SemanticsPropertyKey<DualPageDisplayUnitCompositionIdentity>("DualPageDisplayUnitCompositionIdentity")
internal val DualPageDisplayUnitIdKey = SemanticsPropertyKey<DisplayUnitId>("DualPageDisplayUnitId")
internal val DualPagePhysicalSlotKey = SemanticsPropertyKey<DualPagePhysicalSlot>("DualPagePhysicalSlot")
internal val DualPageSlotIdKey = SemanticsPropertyKey<DisplaySlotId>("DualPageSlotId")

/**
 * Renders one presentation display unit across the complete reader viewport.
 * A cover or forced single keeps both physical slots mounted; a full landscape spread uses the same stable frame.
 */
@Composable
internal fun DualPageDisplayUnitFrame(
    unit: DisplayUnit,
    onRetry: (ReaderPageId) -> Unit,
    mountIdentity: Any = unit.id,
    readyContent: @Composable (DisplaySlot, Modifier) -> Unit,
) {
    require(unit.id.mode == ReaderPresentationMode.DUAL_PAGED) { "A dual frame requires a dual display unit" }
    require(unit.slots.size in 1..2) { "A dual display unit must contain one full-span slot or two physical slots" }
    val identity = remember(mountIdentity) { DualPageDisplayUnitCompositionIdentity() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                this[DualPageDisplayUnitCompositionIdentityKey] = identity
                this[DualPageDisplayUnitIdKey] = unit.id
                unit.transitionDirection?.let { this[ReaderDisplayUnitTransitionDirectionKey] = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (unit.transitionDirection != null) {
            ReaderChapterTransitionItem(
                direction = unit.transitionDirection,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 64.dp),
            )
            return
        }
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
    pagerPresentationBinding: ReaderPagerPresentationBinding? = null,
    currentPageId: ReaderPageId,
    currentDisplayUnitId: DisplayUnitId?,
    isRtl: Boolean,
    zoomState: ZoomState,
    cropBorders: Boolean = false,
    contextMenuScope: CoroutineScope? = null,
    mangaTitle: String = "",
    chapterTitle: String = "",
    presentationImageOwner: DesktopReaderPresentationImageOwner,
    scaleType: ScaleType = ScaleType.FIT_SCREEN,
    navigationMode: NavigationMode = NavigationMode.RightAndLeft,
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
    require(presentation.mode == ReaderPresentationMode.DUAL_PAGED) { "Dual viewer requires a dual presentation" }
    val displayUnits = presentation.displayUnits
    if (displayUnits.isEmpty()) return
    val maxPagerIndex = displayUnits.lastIndex
    fun unitToPager(unitIndex: Int): Int = if (isRtl) maxPagerIndex - unitIndex else unitIndex
    fun pagerToUnit(pagerIndex: Int): Int = if (isRtl) maxPagerIndex - pagerIndex else pagerIndex
    fun pageToPager(pageId: ReaderPageId): Int? =
        displayUnits.indexOfFirst { unit -> unit.slots.any { it.page?.id == pageId } }
            .takeIf { it >= 0 }?.let(::unitToPager)

    fun unitKey(unit: DisplayUnit): Any =
        unit.slots.mapNotNull { it.page?.id }.minByOrNull { it.sourcePageIndex } ?: unit.id

    val initialUnitIndex = presentation.restoreDisplayUnitIndex(currentPageId, currentDisplayUnitId)
        .coerceAtLeast(0)
        .coerceIn(displayUnits.indices)
    val pagerState = rememberPagerState(
        initialPage = unitToPager(initialUnitIndex),
        pageCount = { displayUnits.size },
    )
    val scope = rememberCoroutineScope()
    val animateTurns by rememberUpdatedState(pageTurnAnimation)
    val programmaticTarget = remember { mutableStateOf<Int?>(null) }
    val userTurnTarget = remember { mutableStateOf<ReaderPageId?>(null) }
    val navigationIntent = remember { mutableStateOf<ReaderPagerNavigationIntent?>(null) }
    val nativeLocation by rememberUpdatedState(pagerPresentationBinding?.model?.state?.value?.dualPageLocation)
    val nativeUnits by rememberUpdatedState(displayUnits.indices.map { displayUnits[pagerToUnit(it)].id })

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
        displayUnits[initialUnitIndex].id,
        displayUnits.map(DisplayUnit::id),
        isRtl,
        pageTurnAnimation,
    ) {
        val targetUnit = presentation.restoreDisplayUnitIndex(currentPageId, currentDisplayUnitId)
            .coerceAtLeast(0)
            .coerceIn(displayUnits.indices)
        val targetPager = unitToPager(targetUnit)
        if (userTurnTarget.value != currentPageId) userTurnTarget.value = null
        programmaticTarget.value = targetPager
        pagerState.turnToPage(targetPager, animateTurns)
        programmaticTarget.value = null
        if (userTurnTarget.value == currentPageId) userTurnTarget.value = null
    }

    // A local projection can change RTL indices while Pager retains the original visible key.
    // Bind the presenter to that actual item until the location effect updates the native index.
    val visibleKey = if (!pagerState.isScrollInProgress) {
        pagerState.layoutInfo.visiblePagesInfo.minByOrNull { kotlin.math.abs(it.offset) }?.key
    } else {
        null
    }
    val visibleUnitIndex = displayUnits.indexOfFirst { unitKey(it) == visibleKey }
    val selectedIndex = if (visibleUnitIndex >= 0) {
        unitToPager(visibleUnitIndex)
    } else {
        pagerState.currentPage.coerceIn(displayUnits.indices)
    }
    val selectedUnit = displayUnits[pagerToUnit(selectedIndex)]

    SideEffect {
        val intent = navigationIntent.value
        if (intent != null && !pagerState.isScrollInProgress && pagerState.settledPage == selectedIndex &&
            intent.finished(selectedIndex)
        ) {
            navigationIntent.value = null
        }
    }

    fun executeTapCommand(command: ReaderNavigationCommand) {
        val forward = when (command) {
            ReaderNavigationCommand.Next -> true
            ReaderNavigationCommand.Previous -> false
            else -> null
        }
        if (forward != null && pagerPresentationBinding?.model?.navigateDualPage(forward) == true) {
            userTurnTarget.value = pagerPresentationBinding.model.state.value.dualPageLocation?.pageId
            programmaticTarget.value = null
            return
        }
        val basePage =
            userTurnTarget.value?.let(::pageToPager)
                ?: if (programmaticTarget.value != null) pagerState.settledPage else pagerState.currentPage
        when (val action = ReaderKeyboardAction.forPagerCommand(command, isRtl, basePage, displayUnits.size)) {
            is ReaderPageAction.GoToPage -> {
                navigationIntent.value =
                    ReaderPagerNavigationIntent(nativeUnits, basePage, action.page, logicalLocation = nativeLocation)
                val targetPage = presentation.resolveDualVisiblePages(displayUnits[pagerToUnit(action.page)].id)
                    .activePageId
                userTurnTarget.value = targetPage
                programmaticTarget.value = action.page
                if (pagerPresentationBinding != null && targetPage != null) {
                    // Record the user's logical destination before animation or metadata can reflow
                    // the native indices. The location-driven effect remaps and animates that page.
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
        key = { pagerIndex ->
            val unit = displayUnits[pagerToUnit(pagerIndex)]
            unitKey(unit)
        },
    ) { pagerIndex ->
        val unit = displayUnits[pagerToUnit(pagerIndex)]
        val selected = unit.id == selectedUnit.id
        val nativePresentation = rememberPagerNativePresentation(
            unit = unit,
            visible = presentation.resolveDualVisiblePages(unit.id),
            binding = pagerPresentationBinding,
            userNavigation = selected && navigationIntent.value?.selects(
                selectedIndex,
                unit.id,
                nativeLocation,
            ) == true,
            idle = selected && !pagerState.isScrollInProgress && pagerState.settledPage == selectedIndex &&
                (programmaticTarget.value?.let { it == selectedIndex } ?: true),
            onVisiblePagesChanged = onVisiblePagesChanged,
            selected = selected,
            navigationLocation = navigationIntent.value?.logicalLocation ?: nativeLocation,
        )
        Box(
            modifier = Modifier.fillMaxSize().clipToBounds().onGloballyPositioned {
                nativePresentation.setExposed(!it.boundsInRoot().isEmpty)
            }.semantics {
                if (unit.id == selectedUnit.id) this[ReaderPagerNativePresentationKey] = nativePresentation
            },
        ) {
            DualPageDisplayUnitFrame(
                unit = unit,
                onRetry = onRetryPage,
                mountIdentity = nativePresentation,
            ) { slot, modifier ->
                val page = requireNotNull(slot.page)
                val physicalSlot = unit.slots.indexOf(slot)
                val presentationImage = rememberReaderPresentationImage(
                    owner = presentationImageOwner,
                    page = page,
                    generation = generation,
                    splitHalf = slot.splitHalf,
                    sourceBounds = slot.sourceBounds,
                )
                ZoomablePageBox(
                    onPagePrepared = { width, height ->
                        nativePresentation.setExposed(
                            kotlin.math.abs(pagerState.getOffsetDistanceInPages(pagerIndex)) < 1f,
                        )
                        nativePresentation.prepare(slot.id, page, width, height)
                    },
                    onPageDrawn = { nativePresentation.drawn(slot.id) },
                    canObserveFrameGeometry = if (mihon.desktop.test.state.applicationState.testMode) {
                        { capture -> nativePresentation.canSampleGeometry(capture) }
                    } else {
                        null
                    },
                    onObservedImageDraw = if (mihon.desktop.test.state.applicationState.testMode) {
                        { command, identity, key ->
                            nativePresentation.observeImageDraw(slot.id, command, identity, key)
                        }
                    } else {
                        null
                    },
                    presentationImage = presentationImage,
                    pageLabel = MR.strings.desktop_ui_page_number.localized(
                        Locale.getDefault(),
                        page.id.sourcePageIndex + 1,
                    ),
                    zoomState = zoomState,
                    onZoomChange = onZoomChange,
                    cropBorders = cropBorders,
                    contextMenuScope = contextMenuScope,
                    mangaTitle = mangaTitle,
                    chapterTitle = chapterTitle,
                    pageIndex = page.id.sourcePageIndex,
                    onRetry = { onRetryPage(page.id) },
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
    shouldReportSettledPage: (Int) -> Boolean = { true },
    positionRequest: Pair<ReaderPageId, DisplayUnitId?>? = null,
    onVisiblePagesChanged: (VisiblePageSet) -> Unit,
) {
    val currentCallback by rememberUpdatedState(onVisiblePagesChanged)
    val currentShouldReport by rememberUpdatedState(shouldReportSettledPage)
    val displayUnitIds = presentation.displayUnits.map(DisplayUnit::id)
    // Reconfirm explicit positioning even when rapid regrouping returns to the same numeric index.
    LaunchedEffect(displayUnitIds, isRtl, positionRequest) {
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
                val unitIndex = if (isRtl) displayUnits.lastIndex - safePagerIndex else safePagerIndex
                currentCallback(presentation.resolveDualVisiblePages(displayUnits[unitIndex].id))
            }
    }
}

internal fun Modifier.readerPrimaryTapInput(
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
