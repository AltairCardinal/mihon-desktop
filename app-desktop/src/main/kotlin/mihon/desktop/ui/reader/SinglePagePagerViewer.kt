package mihon.desktop.ui.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mihon.desktop.reader.PagePreloader
import mihon.desktop.reader.ReaderKeyboardAction
import mihon.desktop.reader.ReaderPageAction
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.VirtualPage
import mihon.desktop.reader.ZoomState

/**
 * Standard single-page pager.  Each swipe advances exactly one manga page.
 *
 * ──────────────────────────────────────────────────────────
 * RTL design note
 * ──────────────────────────────────────────────────────────
 * RTL scroll is implemented by reversing the pager index mapping, NOT by
 * using `reverseLayout` or `CompositionLocalProvider(RTL)`.  Both of those
 * inject an RTL LayoutDirection into the content, which can flip alignment.
 *
 * The pager always runs in LTR.  For RTL mode:
 *   pagerIndex 0  →  last page  (rightmost = manga start)
 *   pagerIndex N  →  first page (leftmost = manga end)
 * Swiping RIGHT decreases pagerIndex → later page → forward in manga.
 *
 * ──────────────────────────────────────────────────────────
 * Android migration note
 * ──────────────────────────────────────────────────────────
 * This composable has no desktop-specific code.  It can be moved to a shared
 * Compose Multiplatform module and used on Android unchanged.  The only
 * dependency besides Compose itself is [ZoomablePageBox] (same package) and
 * [ZoomState] (pure Kotlin).
 *
 * @param pageUrls        Ordered list of page image URLs in logical reading order (LTR).
 * @param currentPage     Currently-visible page index (logical, 0-based, LTR order).
 *                        When [virtualPages] is non-null, this is the **virtual** index.
 * @param isRtl           When true, the pager renders right-to-left.
 * @param zoomState       Current zoom/pan state shared across all pages.
 * @param virtualPages    Optional virtual page mapping for split-wide-pages mode.
 *                        When non-null, pager count and page lookup use this list.
 * @param onPageChange    Called when the pager settles on a new page (virtual index if split active).
 * @param onZoomChange    Called when the user changes the zoom/pan state.
 * @param onSpreadDetected Called when a page is detected as wide (width > height).
 *                        Receives the **real** page index.
 * @param onTapCenter     Called when the user taps the center zone.
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
    scaleType: ScaleType = ScaleType.FIT_SCREEN,
    navigationMode: NavigationMode = NavigationMode.RightAndLeft,
    virtualPages: List<VirtualPage>? = null,
    preloader: PagePreloader? = null,
    onPageChange: (Int) -> Unit,
    onZoomChange: (ZoomState) -> Unit,
    onSpreadDetected: ((Int) -> Unit)? = null,
    onTapCenter: (() -> Unit)? = null,
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
) {
    val effectivePageCount = virtualPages?.size ?: pageUrls.size
    val maxPageIndex = (effectivePageCount - 1).coerceAtLeast(0)

    // Index mapping: pager always runs LTR; RTL reverses the mapping.
    fun pageToPager(page: Int): Int = if (isRtl) maxPageIndex - page else page
    fun pagerToPage(pagerIdx: Int): Int = if (isRtl) maxPageIndex - pagerIdx else pagerIdx

    val pagerState = rememberPagerState(
        initialPage = pageToPager(currentPage.coerceIn(0, maxPageIndex)),
        pageCount = { effectivePageCount },
    )
    val scope = rememberCoroutineScope()

    // External navigation (slider / keyboard) → jump pager to the new page.
    LaunchedEffect(currentPage, maxPageIndex, isRtl) {
        val target = pageToPager(currentPage.coerceIn(0, maxPageIndex))
        if (pagerState.currentPage != target) {
            pagerState.scrollToPage(target)
        }
    }

    // Pager swipe → update logical page counter in the parent.
    LaunchedEffect(pagerState.currentPage) {
        onPageChange(pagerToPage(pagerState.currentPage))
    }

    // Tap-zone navigation: left tap decreases pager index, right tap increases it.
    // At chapter boundaries, RTL direction is accounted for:
    //   LTR: left boundary → PrevChapter, right boundary → NextChapter
    //   RTL: left boundary → NextChapter, right boundary → PrevChapter
    val onTapLeft: () -> Unit = {
        when (val action = ReaderKeyboardAction.forPagerLeft(isRtl, pagerState.currentPage, effectivePageCount)) {
            is ReaderPageAction.GoToPage -> scope.launch { pagerState.animateScrollToPage(action.page) }
            ReaderPageAction.NoPrevPage -> onPrevChapter?.invoke()
            ReaderPageAction.NoNextPage -> onNextChapter?.invoke()
        }
    }
    val onTapRight: () -> Unit = {
        when (val action = ReaderKeyboardAction.forPagerRight(isRtl, pagerState.currentPage, effectivePageCount)) {
            is ReaderPageAction.GoToPage -> scope.launch { pagerState.animateScrollToPage(action.page) }
            ReaderPageAction.NoPrevPage -> onPrevChapter?.invoke()
            ReaderPageAction.NoNextPage -> onNextChapter?.invoke()
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
    ) { pagerIndex ->
        val page = pagerToPage(pagerIndex)
        val vp = virtualPages?.get(page)
        val realIndex = vp?.realIndex ?: page
        val splitHalf = vp?.splitHalf
        val sourceBounds = vp?.sourceBounds
        ZoomablePageBox(
            url = pageUrls[realIndex],
            pageLabel = "Page ${realIndex + 1}",
            zoomState = zoomState,
            onZoomChange = onZoomChange,
            cropBorders = cropBorders,
            splitHalf = splitHalf,
            sourceBounds = sourceBounds,
            contextMenuScope = contextMenuScope,
            mangaTitle = mangaTitle,
            chapterTitle = chapterTitle,
            pageIndex = realIndex,
            preloader = preloader,
            // Detect spreads only on full pages (not already-split halves).
            onSpreadDetected = if (splitHalf == null && onSpreadDetected != null) {
                { onSpreadDetected.invoke(realIndex) }
            } else {
                null
            },
            scaleType = scaleType,
            navigationMode = navigationMode,
            onTapLeft = onTapLeft,
            onTapRight = onTapRight,
            onTapCenter = onTapCenter,
        )
    }
}
