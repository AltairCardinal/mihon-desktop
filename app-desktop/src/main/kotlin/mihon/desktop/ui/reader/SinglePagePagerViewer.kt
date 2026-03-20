package mihon.desktop.ui.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineScope
import mihon.desktop.reader.ZoomState

/**
 * Standard single-page pager.  Each swipe advances exactly one manga page.
 *
 * ──────────────────────────────────────────────────────────
 * RTL design note
 * ──────────────────────────────────────────────────────────
 * Right-to-left mode is handled entirely by wrapping the [HorizontalPager] in
 * [CompositionLocalProvider]([LocalLayoutDirection] = [LayoutDirection.Rtl]).
 * The page URLs are **never reversed** — the pager mirrors itself automatically
 * when the layout direction is RTL.
 *
 * This avoids index-inversion math that would create feedback loops between the
 * two [LaunchedEffect]s when the reading direction changes mid-session.
 *
 * ──────────────────────────────────────────────────────────
 * Android migration note
 * ──────────────────────────────────────────────────────────
 * This composable has no desktop-specific code.  It can be moved to a shared
 * Compose Multiplatform module and used on Android unchanged.  The only
 * dependency besides Compose itself is [ZoomablePageBox] (same package) and
 * [ZoomState] (pure Kotlin).
 *
 * @param pageUrls      Ordered list of page image URLs in logical reading order (LTR).
 * @param currentPage   Currently-visible page index (logical, 0-based, LTR order).
 * @param isRtl         When true, the pager renders right-to-left.
 * @param zoomState     Current zoom/pan state shared across all pages.
 * @param onPageChange  Called when the pager settles on a new page.
 * @param onZoomChange  Called when the user changes the zoom/pan state.
 */
@Composable
internal fun SinglePagePagerViewer(
    pageUrls: List<String>,
    currentPage: Int,
    isRtl: Boolean,
    zoomState: ZoomState,
    cropBorders: Boolean = false,
    contextMenuScope: CoroutineScope? = null,
    mangaTitle: String = "",
    chapterTitle: String = "",
    onPageChange: (Int) -> Unit,
    onZoomChange: (ZoomState) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = currentPage.coerceIn(0, pageUrls.size - 1),
        pageCount = { pageUrls.size },
    )

    // External navigation (slider / keyboard) → jump pager to the new page.
    LaunchedEffect(currentPage) {
        if (pagerState.currentPage != currentPage) {
            pagerState.scrollToPage(currentPage.coerceIn(0, pageUrls.size - 1))
        }
    }

    // Pager swipe → update logical page counter in the parent.
    LaunchedEffect(pagerState.currentPage) {
        onPageChange(pagerState.currentPage)
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            ZoomablePageBox(
                url = pageUrls[page],
                pageLabel = "Page ${page + 1}",
                zoomState = zoomState,
                onZoomChange = onZoomChange,
                cropBorders = cropBorders,
                contextMenuScope = contextMenuScope,
                mangaTitle = mangaTitle,
                chapterTitle = chapterTitle,
                pageIndex = page,
                // No spread detection in single-page mode (null = zero overhead).
                onSpreadDetected = null,
            )
        }
    }
}
