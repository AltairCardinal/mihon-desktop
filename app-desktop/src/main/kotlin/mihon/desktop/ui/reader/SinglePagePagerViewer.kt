package mihon.desktop.ui.reader

import tachiyomi.i18n.MR
import java.util.Locale

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import mihon.desktop.reader.PagePreloader
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
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState

internal class ReaderDisplayUnitCompositionIdentity

internal val ReaderDisplayUnitCompositionIdentityKey =
    SemanticsPropertyKey<ReaderDisplayUnitCompositionIdentity>("ReaderDisplayUnitCompositionIdentity")
internal val ReaderDisplayUnitIdKey = SemanticsPropertyKey<DisplayUnitId>("ReaderDisplayUnitId")
internal val ReaderDisplayUnitLoadStateKey = SemanticsPropertyKey<ReaderPageLoadState>("ReaderDisplayUnitLoadState")

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
    preloader: PagePreloader? = null,
    onVisiblePagesChanged: (VisiblePageSet) -> Unit,
    onZoomChange: (ZoomState) -> Unit,
    onRetryPage: (ReaderPageId) -> Unit,
    onSpreadDetected: ((Int) -> Unit)? = null,
    onTapCenter: (() -> Unit)? = null,
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
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

    LaunchedEffect(currentPageId, currentDisplayUnitId, displayUnits.map(DisplayUnit::id), isRtl) {
        val targetPage = presentation
            .restoreDisplayUnitIndex(currentPageId, currentDisplayUnitId)
            .coerceAtLeast(0)
            .coerceIn(0, maxPageIndex)
        val targetPagerIndex = pageToPager(targetPage)
        if (pagerState.currentPage != targetPagerIndex) pagerState.scrollToPage(targetPagerIndex)
    }

    SinglePageSettledVisiblePageReporter(
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
        key = { pagerIndex -> displayUnits[pagerToPage(pagerIndex)].id },
    ) { pagerIndex ->
        val unit = displayUnits[pagerToPage(pagerIndex)]
        val slot = unit.slots.single()
        val page = requireNotNull(slot.page)
        SinglePageDisplayUnitContainer(
            unit = unit,
            onRetry = onRetryPage,
        ) { readySlot ->
            val readyPage = requireNotNull(readySlot.page)
            ZoomablePageBox(
                url = readyPage.encodedContentUri(),
                pageLabel = MR.strings.desktop_ui_page_number.localized(Locale.getDefault(), page.id.sourcePageIndex + 1),
                zoomState = zoomState,
                onZoomChange = onZoomChange,
                cropBorders = cropBorders,
                splitHalf = readySlot.splitHalf,
                sourceBounds = readySlot.sourceBounds,
                contextMenuScope = contextMenuScope,
                mangaTitle = mangaTitle,
                chapterTitle = chapterTitle,
                pageIndex = page.id.sourcePageIndex,
                preloader = preloader,
                onSpreadDetected = if (readySlot.splitHalf == null && onSpreadDetected != null) {
                    { onSpreadDetected(page.id.sourcePageIndex) }
                } else {
                    null
                },
                scaleType = scaleType,
                navigationMode = navigationMode,
                isRtl = isRtl,
                onTapPrevious = { executeTapCommand(ReaderNavigationCommand.Previous) },
                onTapNext = { executeTapCommand(ReaderNavigationCommand.Next) },
                onTapCenter = onTapCenter,
            )
        }
    }
}

@Composable
internal fun SinglePageSettledVisiblePageReporter(
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
