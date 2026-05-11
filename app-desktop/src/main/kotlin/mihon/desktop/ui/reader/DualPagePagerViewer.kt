package mihon.desktop.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mihon.desktop.reader.DualPageState
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.SinglePageSide
import mihon.desktop.reader.ZoomState


/**
 * Dual-page spread pager.  Shows two manga pages side-by-side per pager slot,
 * with the cover page (index 0) always displayed alone.
 *
 * Three mechanisms control page grouping (highest priority first):
 * 1. **Forced singles** ([forcedSinglePages]) — user manually forced via "Adjust Spread" button
 * 2. **Spread detection** (width > height) — detected by [ZoomablePageBox] after Coil decode
 * 3. **Matched pairs** ([matchedPairs]) — detected by edge-pixel scanning
 * 4. **Default** — sequential pairing [1,2], [3,4], etc.
 *
 * ──────────────────────────────────────────────────────────
 * RTL design note
 * ──────────────────────────────────────────────────────────
 * RTL scroll is implemented by **reversing the pager index mapping**, NOT by
 * using `reverseLayout` or `CompositionLocalProvider(RTL)`.  Both of those
 * approaches inject an RTL LayoutDirection into the content subtree, which
 * flips all direction-aware Alignment values (CenterEnd ↔ CenterStart) and
 * breaks image positioning.
 *
 * Instead, the pager always runs in LTR.  For RTL mode:
 *   pagerIndex 0  →  last group  (rightmost position = manga start)
 *   pagerIndex N  →  first group (leftmost position = manga end)
 * Swiping RIGHT decreases pagerIndex → later group → forward in manga.
 *
 * @param pageUrls            Ordered list of page image URLs in logical reading order.
 * @param currentPage         Currently-visible page index (logical, 0-based).
 * @param isRtl               When true, the pager renders right-to-left.
 * @param zoomState           Zoom/pan state shared across all pages in a group.
 * @param forcedSinglePages   Pages manually forced to display alone (from "Adjust Spread" button).
 * @param matchedPairs        Page pairs detected by edge-pixel matching.
 * @param onPageChange        Called with the first logical page of the visible group.
 * @param onZoomChange        Called when the user changes the zoom/pan state.
 * @param onSpreadPagesChanged Called when the set of detected spread pages changes,
 *                             so the parent can maintain a consistent DualPageState
 *                             for keyboard navigation.
 * @param onTapCenter         Called when the user taps the center zone.
 */
@Composable
internal fun DualPagePagerViewer(
    pageUrls: List<String>,
    currentPage: Int,
    isRtl: Boolean,
    zoomState: ZoomState,
    autoSplitPages: Boolean = false,
    cropBorders: Boolean = false,
    contextMenuScope: CoroutineScope? = null,
    mangaTitle: String = "",
    chapterTitle: String = "",
    forcedSinglePages: Set<Int> = emptySet(),
    matchedPairs: Set<Pair<Int, Int>> = emptySet(),
    scaleType: ScaleType = ScaleType.FIT_SCREEN,
    navigationMode: NavigationMode = NavigationMode.RightAndLeft,
    onPageChange: (Int) -> Unit,
    onZoomChange: (ZoomState) -> Unit,
    onSpreadPagesChanged: ((Set<Int>) -> Unit)? = null,
    onTapCenter: (() -> Unit)? = null,
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
) {
    // Accumulates page indices whose decoded image is wider than tall (spread scans).
    // Reset to empty whenever the chapter changes (new pageUrls reference).
    var spreadPages by remember(pageUrls) { mutableStateOf(emptySet<Int>()) }

    // Notify parent whenever spread detection changes, so keyboard navigation
    // can build the same DualPageState as the viewer.
    LaunchedEffect(spreadPages) {
        onSpreadPagesChanged?.invoke(spreadPages)
    }

    // Rebuild the group structure whenever any input changes.
    val dualState = remember(pageUrls.size, spreadPages, forcedSinglePages, matchedPairs) {
        DualPageState(pageUrls.size, spreadPages, forcedSinglePages, matchedPairs)
    }

    // Helper: convert between group index and pager index.
    // In RTL the pager index is reversed so that group 0 is at the rightmost position.
    val maxPagerIndex = (dualState.groupCount - 1).coerceAtLeast(0)
    fun groupToPager(groupIdx: Int): Int = if (isRtl) maxPagerIndex - groupIdx else groupIdx
    fun pagerToGroup(pagerIdx: Int): Int = if (isRtl) maxPagerIndex - pagerIdx else pagerIdx

    // Map the current logical page to a pager index using the latest grouping.
    val safeCurrentPage = currentPage.coerceIn(0, (pageUrls.size - 1).coerceAtLeast(0))
    val initialGroupIndex = dualState.groupIndexForPage(safeCurrentPage)
        .coerceIn(0, maxPagerIndex)
    val initialPagerIndex = groupToPager(initialGroupIndex)

    // Re-create the pager whenever the group layout changes.
    key(spreadPages, forcedSinglePages, matchedPairs) {
        val pagerState = rememberPagerState(
            initialPage = initialPagerIndex,
            pageCount = { dualState.groupCount },
        )

        // Pager swipe → report the first logical page of the new group to the parent.
        LaunchedEffect(pagerState.currentPage) {
            val groupIndex = pagerToGroup(pagerState.currentPage)
            val firstPage = dualState.firstPageInGroup(groupIndex)
            onPageChange(firstPage.coerceIn(0, pageUrls.size - 1))
        }

        // External navigation (slider / keyboard) → jump pager to the correct group.
        LaunchedEffect(currentPage) {
            val targetGroup = dualState.groupIndexForPage(currentPage.coerceIn(0, pageUrls.size - 1))
            val targetPager = groupToPager(targetGroup)
            if (pagerState.currentPage != targetPager) {
                pagerState.scrollToPage(targetPager.coerceIn(0, maxPagerIndex))
            }
        }

        // Callback passed to each ZoomablePageBox; called once the image is decoded.
        val onSpreadDetected: (Int) -> Unit = { pageIndex ->
            if (pageIndex !in spreadPages) {
                spreadPages = spreadPages + pageIndex
            }
        }

        // Tap-zone navigation: same RTL-aware logic as SinglePagePagerViewer.
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val onTapLeft: () -> Unit = {
            when (val action = tapLeftAction(pagerState.currentPage)) {
                is PageNavAction.ScrollTo -> scope.launch { pagerState.animateScrollToPage(action.pagerIndex) }
                else -> when (chapterNavForTapLeft(pagerState.currentPage, isRtl)) {
                    PageNavAction.PrevChapter -> onPrevChapter?.invoke()
                    PageNavAction.NextChapter -> onNextChapter?.invoke()
                    else -> Unit
                }
            }
        }
        val onTapRight: () -> Unit = {
            when (val action = tapRightAction(pagerState.currentPage, dualState.groupCount)) {
                is PageNavAction.ScrollTo -> scope.launch { pagerState.animateScrollToPage(action.pagerIndex) }
                else -> when (chapterNavForTapRight(pagerState.currentPage, dualState.groupCount, isRtl)) {
                    PageNavAction.NextChapter -> onNextChapter?.invoke()
                    PageNavAction.PrevChapter -> onPrevChapter?.invoke()
                    else -> Unit
                }
            }
        }

        // The pager always runs in LTR.  All Alignment values use LTR/physical
        // semantics: CenterEnd = physical RIGHT, CenterStart = physical LEFT.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { pagerIndex ->
            val groupIndex = pagerToGroup(pagerIndex)
            val group = dualState.getGroup(groupIndex)

            if (group.size == 1) {
                val pageIndex = group[0]
                when (dualState.singlePageSide(groupIndex)) {
                    SinglePageSide.CENTER -> {
                        if (autoSplitPages) {
                            // Split the wide image into left/right halves.
                            val leftHalf = if (isRtl) PageSplitHalf.RIGHT else PageSplitHalf.LEFT
                            val rightHalf = if (isRtl) PageSplitHalf.LEFT else PageSplitHalf.RIGHT
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(zoomState.scale, navigationMode) {
                                        detectTapGestures { offset ->
                                            if (zoomState.scale <= 1f) {
                                                when (tapNavRegion(offset.x, offset.y, size.width.toFloat(), size.height.toFloat(), navigationMode)) {
                                                    TapNavRegion.PREV -> onTapLeft()
                                                    TapNavRegion.NEXT -> onTapRight()
                                                    TapNavRegion.MENU -> onTapCenter?.invoke()
                                                }
                                            }
                                        }
                                    },
                                horizontalArrangement = Arrangement.spacedBy(0.dp),
                            ) {
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    ZoomablePageBox(
                                        url = pageUrls[pageIndex],
                                        pageLabel = "Page ${pageIndex + 1} (L)",
                                        zoomState = zoomState,
                                        onZoomChange = onZoomChange,
                                        splitHalf = leftHalf,
                                        modifier = Modifier.fillMaxSize(),
                                        imageAlignment = Alignment.CenterEnd,
                                    )
                                }
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    ZoomablePageBox(
                                        url = pageUrls[pageIndex],
                                        pageLabel = "Page ${pageIndex + 1} (R)",
                                        zoomState = zoomState,
                                        onZoomChange = onZoomChange,
                                        splitHalf = rightHalf,
                                        modifier = Modifier.fillMaxSize(),
                                        imageAlignment = Alignment.CenterStart,
                                    )
                                }
                            }
                        } else {
                            // Landscape spread: full-width, centred.
                            ZoomablePageBox(
                                url = pageUrls[pageIndex],
                                pageLabel = "Page ${pageIndex + 1}",
                                zoomState = zoomState,
                                onZoomChange = onZoomChange,
                                cropBorders = cropBorders,
                                contextMenuScope = contextMenuScope,
                                mangaTitle = mangaTitle,
                                chapterTitle = chapterTitle,
                                pageIndex = pageIndex,
                                onSpreadDetected = { onSpreadDetected(pageIndex) },
                                navigationMode = navigationMode,
                                onTapLeft = onTapLeft,
                                onTapRight = onTapRight,
                                onTapCenter = onTapCenter,
                            )
                        }
                    }
                    SinglePageSide.TRAILING -> {
                        // LTR: physical RIGHT half; RTL: physical LEFT half (cover on reader's entry side).
                        // Touch area is full screen, but image is aligned to one side.
                        val imgAlign = if (isRtl) Alignment.CenterEnd else Alignment.CenterStart
                        ZoomablePageBox(
                            url = pageUrls[pageIndex],
                            pageLabel = "Page ${pageIndex + 1}",
                            zoomState = zoomState,
                            onZoomChange = onZoomChange,
                            cropBorders = cropBorders,
                            contextMenuScope = contextMenuScope,
                            mangaTitle = mangaTitle,
                            chapterTitle = chapterTitle,
                            pageIndex = pageIndex,
                            modifier = Modifier.fillMaxSize(),
                            imageAlignment = imgAlign,
                            onSpreadDetected = { onSpreadDetected(pageIndex) },
                            navigationMode = navigationMode,
                            onTapLeft = onTapLeft,
                            onTapRight = onTapRight,
                            onTapCenter = onTapCenter,
                        )
                    }
                    SinglePageSide.LEADING -> {
                        // LTR: physical LEFT half; RTL: physical RIGHT half.
                        // Touch area is full screen, but image is aligned to one side.
                        val imgAlign = if (isRtl) Alignment.CenterStart else Alignment.CenterEnd
                        ZoomablePageBox(
                            url = pageUrls[pageIndex],
                            pageLabel = "Page ${pageIndex + 1}",
                            zoomState = zoomState,
                            onZoomChange = onZoomChange,
                            cropBorders = cropBorders,
                            contextMenuScope = contextMenuScope,
                            mangaTitle = mangaTitle,
                            chapterTitle = chapterTitle,
                            pageIndex = pageIndex,
                            modifier = Modifier.fillMaxSize(),
                            imageAlignment = imgAlign,
                            onSpreadDetected = { onSpreadDetected(pageIndex) },
                            navigationMode = navigationMode,
                            onTapLeft = onTapLeft,
                            onTapRight = onTapRight,
                            onTapCenter = onTapCenter,
                        )
                    }
                }
            } else {
                // Two-page spread — pages glued at the centre spine.
                // In RTL, swap page order so lower-index page is on the physical RIGHT.
                val leftPage = if (isRtl) group[1] else group[0]
                val rightPage = if (isRtl) group[0] else group[1]
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(zoomState.scale, navigationMode) {
                            detectTapGestures { offset ->
                                if (zoomState.scale <= 1f) {
                                    when (tapNavRegion(offset.x, offset.y, size.width.toFloat(), size.height.toFloat(), navigationMode)) {
                                        TapNavRegion.PREV -> onTapLeft()
                                        TapNavRegion.NEXT -> onTapRight()
                                        TapNavRegion.MENU -> onTapCenter?.invoke()
                                    }
                                }
                            }
                        },
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    // weight(1f) MUST be on Row's direct child.  ZoomablePageBox
                    // is wrapped by ContextMenuArea which inserts an extra Box,
                    // so weight on ZoomablePageBox's modifier would be silently
                    // ignored.  Wrapping in an explicit Box keeps weight visible
                    // to the Row's measure policy.
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        ZoomablePageBox(
                            url = pageUrls[leftPage],
                            pageLabel = "Page ${leftPage + 1}",
                            zoomState = zoomState,
                            onZoomChange = onZoomChange,
                            cropBorders = cropBorders,
                            contextMenuScope = contextMenuScope,
                            mangaTitle = mangaTitle,
                            chapterTitle = chapterTitle,
                            pageIndex = leftPage,
                            modifier = Modifier.fillMaxSize(),
                            imageAlignment = Alignment.CenterEnd,
                            onSpreadDetected = { onSpreadDetected(leftPage) },
                        )
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        ZoomablePageBox(
                            url = pageUrls[rightPage],
                            pageLabel = "Page ${rightPage + 1}",
                            zoomState = zoomState,
                            onZoomChange = onZoomChange,
                            cropBorders = cropBorders,
                            contextMenuScope = contextMenuScope,
                            mangaTitle = mangaTitle,
                            chapterTitle = chapterTitle,
                            pageIndex = rightPage,
                            modifier = Modifier.fillMaxSize(),
                            imageAlignment = Alignment.CenterStart,
                            onSpreadDetected = { onSpreadDetected(rightPage) },
                        )
                    }
                }
            }
        }
    }
}
