package mihon.desktop.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import mihon.desktop.reader.DualPageState
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
 */
@Composable
internal fun DualPagePagerViewer(
    pageUrls: List<String>,
    currentPage: Int,
    isRtl: Boolean,
    zoomState: ZoomState,
    forcedSinglePages: Set<Int> = emptySet(),
    matchedPairs: Set<Pair<Int, Int>> = emptySet(),
    onPageChange: (Int) -> Unit,
    onZoomChange: (ZoomState) -> Unit,
    onSpreadPagesChanged: ((Set<Int>) -> Unit)? = null,
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

    // Map the current logical page to a group index using the latest grouping.
    val safeCurrentPage = currentPage.coerceIn(0, (pageUrls.size - 1).coerceAtLeast(0))
    val initialGroupIndex = dualState.groupIndexForPage(safeCurrentPage)
        .coerceIn(0, (dualState.groupCount - 1).coerceAtLeast(0))

    // Re-create the pager whenever the group layout changes.
    // initialGroupIndex is recomputed above with the NEW dualState, so the pager
    // opens at the correct position even after a spread page is discovered.
    key(spreadPages, forcedSinglePages, matchedPairs) {
        val pagerState = rememberPagerState(
            initialPage = initialGroupIndex,
            pageCount = { dualState.groupCount },
        )

        // Pager swipe → report the first logical page of the new group to the parent.
        LaunchedEffect(pagerState.currentPage) {
            val firstPage = dualState.firstPageInGroup(pagerState.currentPage)
            onPageChange(firstPage.coerceIn(0, pageUrls.size - 1))
        }

        // External navigation (slider / keyboard) → jump pager to the correct group.
        LaunchedEffect(currentPage) {
            val targetGroup = dualState.groupIndexForPage(currentPage.coerceIn(0, pageUrls.size - 1))
            if (pagerState.currentPage != targetGroup) {
                pagerState.scrollToPage(targetGroup.coerceIn(0, dualState.groupCount - 1))
            }
        }

        // Callback passed to each ZoomablePageBox; called once the image is decoded.
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
                    val pageIndex = group[0]
                    when (dualState.singlePageSide(groupIndex)) {
                        SinglePageSide.CENTER -> {
                            // Landscape spread: full-width, centred.
                            ZoomablePageBox(
                                url = pageUrls[pageIndex],
                                pageLabel = "Page ${pageIndex + 1}",
                                zoomState = zoomState,
                                onZoomChange = onZoomChange,
                                onSpreadDetected = { onSpreadDetected(pageIndex) },
                            )
                        }
                        SinglePageSide.TRAILING -> {
                            // Page connects forward → reading-end side.
                            // Alignment.CenterEnd is direction-aware:
                            //   LTR → physical RIGHT,  RTL → physical LEFT.
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                                ZoomablePageBox(
                                    url = pageUrls[pageIndex],
                                    pageLabel = "Page ${pageIndex + 1}",
                                    zoomState = zoomState,
                                    onZoomChange = onZoomChange,
                                    modifier = Modifier.fillMaxWidth(0.5f).fillMaxHeight(),
                                    imageAlignment = Alignment.CenterStart,
                                    onSpreadDetected = { onSpreadDetected(pageIndex) },
                                )
                            }
                        }
                        SinglePageSide.LEADING -> {
                            // Page connects backward → reading-start side.
                            // Alignment.CenterStart is direction-aware:
                            //   LTR → physical LEFT,  RTL → physical RIGHT.
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                                ZoomablePageBox(
                                    url = pageUrls[pageIndex],
                                    pageLabel = "Page ${pageIndex + 1}",
                                    zoomState = zoomState,
                                    onZoomChange = onZoomChange,
                                    modifier = Modifier.fillMaxWidth(0.5f).fillMaxHeight(),
                                    imageAlignment = Alignment.CenterEnd,
                                    onSpreadDetected = { onSpreadDetected(pageIndex) },
                                )
                            }
                        }
                    }
                } else {
                    // Two-page spread — pages are glued at the centre spine with no gap.
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        ZoomablePageBox(
                            url = pageUrls[group[0]],
                            pageLabel = "Page ${group[0] + 1}",
                            zoomState = zoomState,
                            onZoomChange = onZoomChange,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            imageAlignment = Alignment.CenterEnd,
                            onSpreadDetected = { onSpreadDetected(group[0]) },
                        )
                        ZoomablePageBox(
                            url = pageUrls[group[1]],
                            pageLabel = "Page ${group[1] + 1}",
                            zoomState = zoomState,
                            onZoomChange = onZoomChange,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            imageAlignment = Alignment.CenterStart,
                            onSpreadDetected = { onSpreadDetected(group[1]) },
                        )
                    }
                }
            }
        }
    }
}
