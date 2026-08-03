package mihon.desktop.ui.reader

import mihon.desktop.ui.source.desktopSourceErrorMessage
import tachiyomi.i18n.MR
import java.util.Locale

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import mihon.desktop.reader.PagePreloader
import mihon.desktop.reader.ReaderColorFilter
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.ZoomState
import mihon.desktop.ui.reader.presentation.DesktopReaderPresentationRegistry
import mihon.desktop.ui.reader.presentation.DisplayUnitId
import mihon.desktop.ui.reader.presentation.ReaderPresentationMode
import mihon.desktop.ui.reader.presentation.VisiblePageSet
import mihon.desktop.ui.reader.presentation.WebtoonScrollAnchor
import mihon.desktop.ui.reader.presentation.WebtoonViewportUpdate
import mihon.desktop.ui.reader.presentation.desktopReaderPresentationRequest
import mihon.domain.error.AppError
import mihon.domain.reader.ReaderChapterState
import mihon.domain.reader.ReaderChapterTransitionModel
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderTransitionDirection
import mihon.domain.reader.session.ReaderChapterSession
import mihon.domain.reader.session.ReaderPageId

@Composable
internal fun ColorFilterOverlay(colorFilter: ReaderColorFilter) {
    if (!colorFilter.isEffective) return
    val overlayColor = if (colorFilter.tintEnabled && colorFilter.alpha > 0) {
        Color(red = colorFilter.r / 255f, green = colorFilter.g / 255f, blue = colorFilter.b / 255f, alpha = colorFilter.alpha / 255f)
    } else {
        Color.Transparent
    }
    val brightnessColor = when {
        colorFilter.brightnessEnabled && colorFilter.brightness > 0f -> Color.White.copy(alpha = colorFilter.brightness)
        colorFilter.brightnessEnabled && colorFilter.brightness < 0f -> Color.Black.copy(alpha = -colorFilter.brightness)
        else -> Color.Transparent
    }
    if (overlayColor != Color.Transparent) Box(Modifier.fillMaxSize().background(overlayColor))
    if (brightnessColor != Color.Transparent) Box(Modifier.fillMaxSize().background(brightnessColor))
}

internal fun Modifier.readerColorTransform(colorFilter: ReaderColorFilter): Modifier {
    val matrix = readerColorMatrix(colorFilter) ?: return this
    return drawWithContent {
        val paint = Paint().apply { this.colorFilter = ColorFilter.colorMatrix(matrix) }
        drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
        drawContent()
        drawContext.canvas.restore()
    }
}

internal fun readerColorMatrix(colorFilter: ReaderColorFilter): ColorMatrix? {
    if (!colorFilter.grayscaleEnabled && !colorFilter.invertEnabled) return null
    val matrix = ColorMatrix()
    if (colorFilter.grayscaleEnabled) matrix.setToSaturation(0f)
    if (colorFilter.invertEnabled) {
        matrix *= ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }
    return matrix
}

@Composable
internal fun ZoomablePagerViewer(
    chapter: ReaderChapterSession,
    currentPage: Int,
    currentDisplayUnitId: DisplayUnitId? = null,
    isRtl: Boolean,
    isDualPage: Boolean,
    autoSplitPages: Boolean = false,
    cropBorders: Boolean = false,
    contextMenuScope: CoroutineScope? = null,
    mangaTitle: String = "",
    chapterTitle: String = "",
    zoomState: ZoomState,
    forcedSinglePages: Set<Int> = emptySet(),
    matchedPairs: Set<Pair<Int, Int>> = emptySet(),
    splitPageIndices: Set<Int> = emptySet(),
    preloader: PagePreloader? = null,
    scaleType: ScaleType = ScaleType.FIT_SCREEN,
    navigationMode: NavigationMode = NavigationMode.RightAndLeft,
    onPageChange: (Int) -> Unit,
    onZoomChange: (ZoomState) -> Unit,
    onRetryPage: ((ReaderPageId) -> Unit)? = null,
    onSingleVisiblePagesChanged: ((VisiblePageSet) -> Unit)? = null,
    onDualVisiblePagesChanged: ((VisiblePageSet) -> Unit)? = null,
    onSpreadDetected: ((Int) -> Unit)? = null,
    onTapCenter: (() -> Unit)? = null,
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
) {
    if (chapter.pages.isEmpty()) return
    val direction = if (isRtl) ReaderDirection.RTL else ReaderDirection.LTR
    if (isDualPage) {
        val request = remember(
            chapter,
            direction,
            splitPageIndices,
            forcedSinglePages,
            matchedPairs,
            autoSplitPages,
        ) {
            desktopReaderPresentationRequest(
                chapter = chapter,
                direction = direction,
                spreadPageIndices = splitPageIndices,
                forcedSinglePageIndices = forcedSinglePages,
                matchedPagePairs = matchedPairs,
                splitWidePages = autoSplitPages,
            )
        }
        val presentation = remember(request) {
            DesktopReaderPresentationRegistry
                .require(ReaderPresentationMode.DUAL_PAGED)
                .present(request)
        }
        val currentPageId = request.chapter.pages[currentPage.coerceIn(chapter.pages.indices)].id
        DualPagePagerViewer(
            presentation = presentation, currentPageId = currentPageId,
            currentDisplayUnitId = currentDisplayUnitId, isRtl = isRtl,
            cropBorders = cropBorders, contextMenuScope = contextMenuScope,
            mangaTitle = mangaTitle, chapterTitle = chapterTitle, zoomState = zoomState,
            preloader = preloader,
            scaleType = scaleType, navigationMode = navigationMode,
            onVisiblePagesChanged = { visiblePages ->
                if (onDualVisiblePagesChanged != null) {
                    onDualVisiblePagesChanged(visiblePages)
                } else {
                    visiblePages.activePageId?.let { onPageChange(it.sourcePageIndex) }
                }
            },
            onZoomChange = onZoomChange,
            onRetryPage = { pageId -> onRetryPage?.invoke(pageId) },
            onSpreadDetected = onSpreadDetected,
            onTapCenter = onTapCenter, onPrevChapter = onPrevChapter, onNextChapter = onNextChapter,
        )
    } else {
        val request = remember(chapter, direction, autoSplitPages, splitPageIndices) {
            desktopReaderPresentationRequest(
                chapter = chapter,
                direction = direction,
                splitPageIndices = if (autoSplitPages) splitPageIndices else emptySet(),
            )
        }
        val presentation = remember(request) {
            DesktopReaderPresentationRegistry
                .require(ReaderPresentationMode.SINGLE_PAGED)
                .present(request)
        }
        val currentPageId = request.chapter.pages[currentPage.coerceIn(chapter.pages.indices)]
            .id
        SinglePagePagerViewer(
            presentation = presentation, currentPageId = currentPageId,
            currentDisplayUnitId = currentDisplayUnitId, isRtl = isRtl,
            cropBorders = cropBorders, contextMenuScope = contextMenuScope,
            mangaTitle = mangaTitle, chapterTitle = chapterTitle, zoomState = zoomState,
            preloader = preloader, scaleType = scaleType,
            navigationMode = navigationMode, onZoomChange = onZoomChange,
            onVisiblePagesChanged = { visiblePages ->
                if (onSingleVisiblePagesChanged != null) {
                    onSingleVisiblePagesChanged(visiblePages)
                } else {
                    visiblePages.pageIds.singleOrNull()?.let { onPageChange(it.sourcePageIndex) }
                }
            },
            onRetryPage = { pageId -> onRetryPage?.invoke(pageId) },
            onSpreadDetected = onSpreadDetected, onTapCenter = onTapCenter,
            onPrevChapter = onPrevChapter, onNextChapter = onNextChapter,
        )
    }
}

@Composable
internal fun WebtoonPresentationViewer(
    chapter: ReaderChapterSession,
    currentPage: Int,
    currentDisplayUnitId: DisplayUnitId?,
    initialAnchor: WebtoonScrollAnchor?,
    autoSplitPages: Boolean = false,
    splitPageIndices: Set<Int> = emptySet(),
    cropBorders: Boolean = false,
    sidePadding: mihon.desktop.reader.WebtoonSidePadding = mihon.desktop.reader.WebtoonSidePadding.NONE,
    autoScroll: Boolean = false,
    autoScrollSpeed: WebtoonAutoScrollSpeed = WebtoonAutoScrollSpeed.Normal,
    contextMenuScope: CoroutineScope? = null,
    mangaTitle: String = "",
    chapterTitle: String = "",
    preloader: PagePreloader? = null,
    onViewportChanged: (WebtoonViewportUpdate) -> Unit,
    onRetryPage: ((ReaderPageId) -> Unit)? = null,
    onSpreadDetected: ((Int) -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
) {
    if (chapter.pages.isEmpty()) return
    val request = remember(chapter, autoSplitPages, splitPageIndices) {
        desktopReaderPresentationRequest(
            chapter = chapter,
            direction = ReaderDirection.RTL,
            splitPageIndices = if (autoSplitPages) splitPageIndices else emptySet(),
        )
    }
    val presentation = remember(request) {
        DesktopReaderPresentationRegistry
            .require(ReaderPresentationMode.WEBTOON)
            .present(request)
    }
    val currentPageId = request.chapter.pages[currentPage.coerceIn(chapter.pages.indices)].id
    WebtoonViewer(
        presentation = presentation,
        currentPageId = currentPageId,
        currentDisplayUnitId = currentDisplayUnitId,
        initialAnchor = initialAnchor,
        cropBorders = cropBorders,
        sidePadding = sidePadding,
        autoScroll = autoScroll,
        autoScrollSpeed = autoScrollSpeed,
        contextMenuScope = contextMenuScope,
        mangaTitle = mangaTitle,
        chapterTitle = chapterTitle,
        preloader = preloader,
        onViewportChanged = onViewportChanged,
        onRetryPage = { pageId -> onRetryPage?.invoke(pageId) },
        onSpreadDetected = onSpreadDetected,
        onNextChapter = onNextChapter,
    )
}

@Composable
internal fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Text(MR.strings.transition_pages_loading.localized(), color = Color.White, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ErrorState(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text(MR.strings.desktop_ui_failed_to_load_pages.localized(), color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(message, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text(MR.strings.action_retry.localized()) }
            Button(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text(MR.strings.desktop_ui_go_back.localized()) }
        }
    }
}

@Composable
internal fun ChapterTransitionFeedback(
    transition: ReaderChapterTransitionModel,
    onRetry: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val presentation = chapterTransitionPresentation(transition)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(presentation.message, color = Color.White, style = MaterialTheme.typography.titleMedium)
            if (presentation.showLoading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.padding(top = 12.dp))
            }
            if (presentation.showRetry && onRetry != null) {
                Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) { Text(MR.strings.action_retry.localized()) }
            }
            if (presentation.showClose) {
                Button(onClick = onClose, modifier = Modifier.padding(top = 12.dp)) { Text(MR.strings.action_close.localized()) }
            }
        }
    }
}

internal data class ChapterTransitionPresentation(
    val message: String,
    val showLoading: Boolean,
    val showRetry: Boolean,
    val showClose: Boolean,
    val isBoundary: Boolean,
    val missingChapterCount: Int,
)

internal fun chapterTransitionPresentation(
    transition: ReaderChapterTransitionModel,
): ChapterTransitionPresentation {
    val direction = if (transition.direction == ReaderTransitionDirection.NEXT) {
        MR.strings.desktop_ui_next.localized()
    } else {
        MR.strings.desktop_ui_previous.localized()
    }
    val target = transition.to?.name
    val isBoundary = target == null
    val state = transition.state
    val message = when {
        isBoundary -> MR.strings.desktop_ui_no_direction_chapter.localized(Locale.getDefault(), direction)
        state is ReaderChapterState.Error -> {
            val error = state.error
            MR.strings.desktop_ui_failed_to_load_target.localized(
                Locale.getDefault(),
                target,
                desktopSourceErrorMessage(error),
            )
        }
        transition.missingChapterCount > 0 -> MR.strings.desktop_ui_missing_chapters.localized(
            Locale.getDefault(),
            target,
            transition.missingChapterCount,
        )
        else -> target
    }
    return ChapterTransitionPresentation(
        message = message,
        showLoading = state is ReaderChapterState.Loading,
        showRetry = state is ReaderChapterState.Error,
        showClose = isBoundary || state is ReaderChapterState.Error,
        isBoundary = isBoundary,
        missingChapterCount = transition.missingChapterCount,
    )
}

@Composable
internal fun EmptyState(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(MR.strings.desktop_ui_no_pages_available.localized(), color = Color.White)
            Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text(MR.strings.desktop_ui_go_back.localized()) }
        }
    }
}
