package mihon.desktop.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import mihon.desktop.reader.ZoomState

/**
 * Dual-page spread pager.  Shows two manga pages side-by-side per pager slot,
 * with the cover page (index 0) always displayed alone.
 *
 * ──────────────────────────────────────────────────────────
 * Layout
 * ──────────────────────────────────────────────────────────
 * Pager slot 0 → [ page 0 ]             (cover — always solo)
 * Pager slot 1 → [ page 1 | page 2 ]    (first spread)
 * Pager slot 2 → [ page 3 | page 4 ]
 * …
 *
 * Landscape images (width > height) are treated as "double-page spread" scans
 * and shown alone in their own slot via [DualPageState.spreadPages].
 *
 * ──────────────────────────────────────────────────────────
 * Spread detection
 * ──────────────────────────────────────────────────────────
 * Each [ZoomablePageBox] reports back once Coil has decoded the image
 * dimensions.  If width > height the page index is added to [spreadPages].
 * Adding a spread index rebuilds [DualPageState] and re-keys the pager, so the
 * correct grouping is reflected immediately.  The new [initialGroupIndex] is
 * computed from the *updated* [DualPageState] so the pager lands on the same
 * logical page after recreation.
 *
 * ──────────────────────────────────────────────────────────
 * RTL design note
 * ──────────────────────────────────────────────────────────
 * RTL is handled entirely by [LocalLayoutDirection] — no URL reversal and no
 * inverted index math.  [Alignment.CenterEnd] / [Alignment.CenterStart] are
 * layout-direction-aware, so the spine alignment is automatically correct for
 * both LTR and RTL without extra logic.
 *
 * ──────────────────────────────────────────────────────────
 * Android migration guide
 * ──────────────────────────────────────────────────────────
 * All dependencies are either Compose Multiplatform APIs or pure-Kotlin classes:
 *  • [DualPageState]   — pure Kotlin, no Android dependency (copy as-is)
 *  • [ZoomablePageBox] — Compose Multiplatform (copy as-is)
 *  • [ZoomState]       — pure Kotlin data class (copy as-is)
 *  • [HorizontalPager] — `androidx.compose.foundation.pager` (same on Android)
 *
 * Steps to migrate:
 *  1. Copy [DualPageState] to the Android shared/domain layer.
 *  2. Copy [ZoomablePageBox] to the Android reader package.
 *  3. Copy this file to the Android reader package; adjust the package declaration.
 *  4. Replace any desktop-only DI or preference calls that live *above* this
 *     composable (they are all in [DesktopReaderScreen], not here).
 *
 * @param pageUrls      Ordered list of page image URLs in logical reading order.
 * @param currentPage   Currently-visible page index (logical, 0-based).
 * @param isRtl         When true, the pager renders right-to-left.
 * @param zoomState     Zoom/pan state shared across all pages in a group.
 * @param onPageChange  Called with the first logical page of the visible group.
 * @param onZoomChange  Called when the user changes the zoom/pan state.
 */
@Composable
internal fun DualPagePagerViewer(
    pageUrls: List<String>,
    currentPage: Int,
    isRtl: Boolean,
    zoomState: ZoomState,
    onPageChange: (Int) -> Unit,
    onZoomChange: (ZoomState) -> Unit,
) {
    // Accumulates page indices whose decoded image is wider than tall (spread scans).
    // Reset to empty whenever the chapter changes (new pageUrls reference).
    var spreadPages by remember(pageUrls) { mutableStateOf(emptySet<Int>()) }

    // Rebuild the group structure whenever new spreads are discovered.
    val dualState = remember(pageUrls.size, spreadPages) {
        DualPageState(pageUrls.size, spreadPages)
    }

    // Map the current logical page to a group index using the latest grouping.
    val safeCurrentPage = currentPage.coerceIn(0, (pageUrls.size - 1).coerceAtLeast(0))
    val initialGroupIndex = dualState.groupIndexForPage(safeCurrentPage)
        .coerceIn(0, (dualState.groupCount - 1).coerceAtLeast(0))

    // Re-create the pager whenever spread detection changes the group layout.
    // initialGroupIndex is recomputed above with the NEW dualState, so the pager
    // opens at the correct position even after a spread page is discovered.
    key(spreadPages) {
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
                    // Single-page slot: cover page, a detected spread, or the last odd page.
                    ZoomablePageBox(
                        url = pageUrls[group[0]],
                        pageLabel = "Page ${group[0] + 1}",
                        zoomState = zoomState,
                        onZoomChange = onZoomChange,
                        onSpreadDetected = { onSpreadDetected(group[0]) },
                    )
                } else {
                    // Two-page spread — pages are glued at the centre spine with no gap.
                    //
                    // group[0] = "first" page in reading order (left in LTR, right in RTL).
                    // CenterEnd / CenterStart are layout-direction-aware:
                    //   LTR: group[0] → CenterEnd  (sticks to right/spine)
                    //        group[1] → CenterStart (sticks to left/spine)
                    //   RTL: automatically swapped by LocalLayoutDirection — no extra logic.
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
