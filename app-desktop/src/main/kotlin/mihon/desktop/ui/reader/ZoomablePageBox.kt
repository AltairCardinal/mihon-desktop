package mihon.desktop.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import androidx.compose.runtime.collectAsState
import mihon.desktop.reader.ZoomState

/**
 * A single manga page image with pinch-to-zoom, drag-to-pan, and double-tap-to-reset.
 *
 * This is the fundamental building block shared by both [SinglePagePagerViewer]
 * and [DualPagePagerViewer].  It is self-contained and carries no knowledge of
 * which viewer hosts it.
 *
 * ──────────────────────────────────────────────────────────
 * Android migration note
 * ──────────────────────────────────────────────────────────
 * When porting to Android, replace:
 *  • [rememberAsyncImagePainter] → Coil's `AsyncImage` or the same painter API
 *    (Coil 3 supports both platforms)
 *  • [detectTransformGestures] → same API (Compose Multiplatform ≈ Compose Android)
 *  • [ZoomState] → same pure-Kotlin data class, no platform dependency
 *
 * @param url           Remote or local URL of the page image.
 * @param pageLabel     Accessibility / content description (e.g. "Page 3").
 * @param zoomState     Current zoom/pan state driven by the parent.
 * @param onZoomChange  Called whenever the user changes the zoom/pan state.
 * @param modifier      Outer modifier — defaults to [Modifier.fillMaxSize].
 * @param imageAlignment
 *   Where to place the image when it doesn't fill the full box.
 *   - [Alignment.Center] → default; single-page viewer.
 *   - [Alignment.CenterEnd] → left page in a dual-page spread (sticks to spine).
 *   - [Alignment.CenterStart] → right page in a dual-page spread (sticks to spine).
 *   These values are [LocalLayoutDirection]-aware, so RTL mode works without
 *   any extra logic.
 * @param onSpreadDetected
 *   Called once after Coil decodes the image and its width > height
 *   (landscape / double-page spread image).  Pass `null` to skip detection
 *   (single-page viewer; zero overhead).
 */
@Composable
internal fun ZoomablePageBox(
    url: String,
    pageLabel: String,
    zoomState: ZoomState,
    onZoomChange: (ZoomState) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    imageAlignment: Alignment = Alignment.Center,
    onSpreadDetected: (() -> Unit)? = null,
) {
    // Always read the latest zoom state inside gesture lambdas to avoid stale closures.
    val latestZoom by rememberUpdatedState(zoomState)

    // Reuse the same painter instance for both dimension detection and rendering.
    val painter = rememberAsyncImagePainter(url)
    val painterState by painter.state.collectAsState()

    // Detect spread pages: if the decoded image is wider than tall, notify the parent.
    // The LaunchedEffect is only composed when the caller wants spread detection.
    if (onSpreadDetected != null) {
        LaunchedEffect(painterState) {
            val s = painterState
            if (s is AsyncImagePainter.State.Success) {
                val img = s.result.image
                if (img.width > img.height) {
                    onSpreadDetected()
                }
            }
        }
    }

    Box(
        modifier = modifier
            // detectTransformGestures handles:
            //   • macOS trackpad two-finger pinch → zoom
            //   • Single-finger drag while zoomed → pan
            //   • Mouse drag while zoomed → pan
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val current = latestZoom
                    val newScale = (current.scale * zoom).coerceIn(1f, ZoomState.MAX_SCALE)
                    val scaled = if (newScale <= 1f) ZoomState() else current.copy(scale = newScale)
                    onZoomChange(scaled.pan(pan.x, pan.y))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onZoomChange(ZoomState()) })
            },
        contentAlignment = imageAlignment,
    ) {
        Image(
            painter = painter,
            contentDescription = pageLabel,
            alignment = imageAlignment,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = zoomState.scale,
                    scaleY = zoomState.scale,
                    translationX = zoomState.offsetX,
                    translationY = zoomState.offsetY,
                ),
            contentScale = ContentScale.Fit,
        )
    }
}
