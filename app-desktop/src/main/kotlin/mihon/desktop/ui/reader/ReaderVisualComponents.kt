package mihon.desktop.ui.reader

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
import mihon.desktop.reader.VirtualPage
import mihon.desktop.reader.ZoomState
import mihon.domain.reader.ReaderChapterState
import mihon.domain.reader.ReaderChapterTransitionModel
import mihon.domain.reader.ReaderTransitionDirection

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
    pageUrls: List<String>,
    currentPage: Int,
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
    virtualPages: List<VirtualPage>? = null,
    preloader: PagePreloader? = null,
    scaleType: ScaleType = ScaleType.FIT_SCREEN,
    navigationMode: NavigationMode = NavigationMode.RightAndLeft,
    onPageChange: (Int) -> Unit,
    onZoomChange: (ZoomState) -> Unit,
    onSpreadPagesChanged: ((Set<Int>) -> Unit)? = null,
    onSpreadDetected: ((Int) -> Unit)? = null,
    onTapCenter: (() -> Unit)? = null,
    onPrevChapter: (() -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
) {
    if (isDualPage && pageUrls.size > 1) {
        DualPagePagerViewer(
            pageUrls = pageUrls, currentPage = currentPage, isRtl = isRtl,
            autoSplitPages = autoSplitPages, cropBorders = cropBorders, contextMenuScope = contextMenuScope,
            mangaTitle = mangaTitle, chapterTitle = chapterTitle, zoomState = zoomState,
            forcedSinglePages = forcedSinglePages, matchedPairs = matchedPairs,
            preloader = preloader,
            scaleType = scaleType, navigationMode = navigationMode, onPageChange = onPageChange,
            onZoomChange = onZoomChange, onSpreadPagesChanged = onSpreadPagesChanged,
            onTapCenter = onTapCenter, onPrevChapter = onPrevChapter, onNextChapter = onNextChapter,
        )
    } else {
        SinglePagePagerViewer(
            pageUrls = pageUrls, currentPage = currentPage, isRtl = isRtl,
            cropBorders = cropBorders, contextMenuScope = contextMenuScope,
            mangaTitle = mangaTitle, chapterTitle = chapterTitle, zoomState = zoomState,
            virtualPages = virtualPages, preloader = preloader, scaleType = scaleType,
            navigationMode = navigationMode, onPageChange = onPageChange, onZoomChange = onZoomChange,
            onSpreadDetected = onSpreadDetected, onTapCenter = onTapCenter,
            onPrevChapter = onPrevChapter, onNextChapter = onNextChapter,
        )
    }
}

@Composable
internal fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Text("Loading pages…", color = Color.White, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ErrorState(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("Failed to load pages", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(message, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("Retry") }
            Button(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("Go Back") }
        }
    }
}

@Composable
internal fun ChapterTransitionFeedback(
    transition: ReaderChapterTransitionModel,
    onContinue: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val presentation = chapterTransitionPresentation(transition)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(presentation.message, color = Color.White, style = MaterialTheme.typography.titleMedium)
            if (presentation.showLoading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.padding(top = 12.dp))
            }
            if (presentation.showRetry && onRetry != null) {
                Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
            }
            if (presentation.showContinue && onContinue != null) {
                Button(onClick = onContinue, modifier = Modifier.padding(top = 12.dp)) { Text("Continue") }
            }
            Button(onClick = onDismiss, modifier = Modifier.padding(top = 12.dp)) { Text("Dismiss") }
        }
    }
}

internal data class ChapterTransitionPresentation(
    val message: String,
    val showLoading: Boolean,
    val showRetry: Boolean,
    val showContinue: Boolean,
    val isBoundary: Boolean,
    val missingChapterCount: Int,
)

internal fun chapterTransitionPresentation(
    transition: ReaderChapterTransitionModel,
): ChapterTransitionPresentation {
    val direction = if (transition.direction == ReaderTransitionDirection.NEXT) "next" else "previous"
    val target = transition.to?.name
    val isBoundary = target == null
    val state = transition.state
    val message = when {
        isBoundary -> "No $direction chapter available"
        state is ReaderChapterState.Error -> {
            val error = state.error
            "Failed to load $target: ${error.cause?.message ?: error}"
        }
        transition.missingChapterCount > 0 -> "$target · ${transition.missingChapterCount} missing chapter(s)"
        else -> target
    }
    return ChapterTransitionPresentation(
        message = message,
        showLoading = state is ReaderChapterState.Loading,
        showRetry = state is ReaderChapterState.Error,
        showContinue = !isBoundary &&
            (state is ReaderChapterState.Wait || state is ReaderChapterState.Loaded),
        isBoundary = isBoundary,
        missingChapterCount = transition.missingChapterCount,
    )
}

@Composable
internal fun EmptyState(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No pages available", color = Color.White)
            Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Go Back") }
        }
    }
}
