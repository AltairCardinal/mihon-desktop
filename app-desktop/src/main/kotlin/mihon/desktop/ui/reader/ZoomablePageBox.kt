package mihon.desktop.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.desktop.reader.CropBorderScanner
import mihon.desktop.reader.ZoomState
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * A single manga page image with pinch-to-zoom, drag-to-pan, double-tap-to-reset,
 * and optional white-border cropping.
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
 *  • Crop borders: Android uses SubsamplingScaleImageView built-in support;
 *    desktop re-loads with ImageIO (same URL, disk-cached) and uses [CropBorderScanner].
 *
 * @param url             Remote or local URL of the page image.
 * @param pageLabel       Accessibility / content description (e.g. "Page 3").
 * @param zoomState       Current zoom/pan state driven by the parent.
 * @param onZoomChange    Called whenever the user changes the zoom/pan state.
 * @param cropBorders     When true, white borders are trimmed after image decode.
 * @param modifier        Outer modifier — defaults to [Modifier.fillMaxSize].
 * @param imageAlignment
 *   Where to place the image when it doesn't fill the full box.
 *   - [Alignment.Center] → default; single-page viewer.
 *   - [Alignment.CenterEnd] → left page in a dual-page spread (sticks to spine).
 *   - [Alignment.CenterStart] → right page in a dual-page spread (sticks to spine).
 *   The viewer always runs in LTR layout direction (RTL scroll is handled
 *   by reversing pager indices), so these values use physical semantics.
 * @param onSpreadDetected
 *   Called once after Coil decodes the image and its width > height
 *   (landscape / double-page spread image).  Pass `null` to skip detection.
 */
@Composable
internal fun ZoomablePageBox(
    url: String,
    pageLabel: String,
    zoomState: ZoomState,
    onZoomChange: (ZoomState) -> Unit,
    cropBorders: Boolean = false,
    contextMenuScope: CoroutineScope? = null,
    mangaTitle: String = "",
    chapterTitle: String = "",
    pageIndex: Int = 0,
    onSetAsCover: (() -> Unit)? = null,
    modifier: Modifier = Modifier.fillMaxSize(),
    imageAlignment: Alignment = Alignment.Center,
    onSpreadDetected: (() -> Unit)? = null,
    onTapLeft: (() -> Unit)? = null,
    onTapRight: (() -> Unit)? = null,
) {
    val latestZoom by rememberUpdatedState(zoomState)

    val painter = rememberAsyncImagePainter(url)
    val painterState by painter.state.collectAsState()

    // Cropped bitmap: non-null when cropBorders is enabled and crop was applied.
    // Keyed on url so it resets when the page changes.
    var croppedBitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    // Detect spread pages and optionally apply crop borders after Coil decodes the image.
    LaunchedEffect(painterState, cropBorders) {
        val s = painterState
        if (s is AsyncImagePainter.State.Success) {
            val img = s.result.image

            // Spread detection
            if (onSpreadDetected != null && img.width > img.height) {
                onSpreadDetected()
            }

            // Crop borders: reload with ImageIO on the IO thread, scan, crop
            if (cropBorders) {
                croppedBitmap = withContext(Dispatchers.IO) {
                    loadAndCrop(url)
                }
            } else {
                croppedBitmap = null
            }
        }
    }

    val innerContent: @Composable () -> Unit = {
        Box(
            modifier = modifier
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val current = latestZoom
                        val newScale = (current.scale * zoom).coerceIn(1f, ZoomState.MAX_SCALE)
                        val scaled = if (newScale <= 1f) ZoomState() else current.copy(scale = newScale)
                        onZoomChange(scaled.pan(pan.x, pan.y))
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            // Only trigger tap navigation when at default zoom (not zoomed in)
                            if (latestZoom.scale <= 1f) {
                                when (tapZoneFor(offset.x, size.width.toFloat())) {
                                    TapZone.LEFT -> onTapLeft?.invoke()
                                    TapZone.RIGHT -> onTapRight?.invoke()
                                    TapZone.CENTER -> {}
                                }
                            }
                        },
                        onDoubleTap = { onZoomChange(ZoomState()) },
                    )
                },
            contentAlignment = imageAlignment,
        ) {
            // Loading indicator — shown while the image is loading
            val isLoading = painterState is AsyncImagePainter.State.Loading ||
                painterState is AsyncImagePainter.State.Empty
            if (isLoading && croppedBitmap == null) {
                CircularProgressIndicator(color = Color.White)
            }

            val cropped = croppedBitmap
            if (cropped != null) {
                Image(
                    bitmap = cropped,
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
            } else {
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
    }

    val scope = contextMenuScope
    if (scope != null) {
        PageContextMenu(
            pageUrl = url,
            mangaTitle = mangaTitle,
            chapterTitle = chapterTitle,
            pageIndex = pageIndex,
            scope = scope,
            onSetAsCover = onSetAsCover,
            content = innerContent,
        )
    } else {
        innerContent()
    }
}

/**
 * Loads [url] with [ImageIO], runs [CropBorderScanner], and returns a cropped
 * [ImageBitmap].  Returns null if nothing was cropped or if loading failed.
 *
 * Using [ImageIO] (same as [mihon.desktop.reader.EdgePixelMatcher.loadImage]) means
 * the OS/JVM URL cache may already have the bytes, avoiding a second network round-trip
 * for images loaded from local disk.  For network images Coil's disk cache is separate,
 * but the trade-off is acceptable given crop is toggled infrequently.
 */
private fun loadAndCrop(url: String): ImageBitmap? = try {
    val awtImage: BufferedImage = ImageIO.read(java.net.URL(url)) ?: return null
    val rect = CropBorderScanner().detectCropRect(awtImage)

    // Skip if no meaningful crop
    if (rect.top == 0 && rect.left == 0 &&
        rect.bottom == awtImage.height && rect.right == awtImage.width
    ) {
        return null
    }

    val cropped = awtImage.getSubimage(rect.left, rect.top, rect.width, rect.height)
    cropped.toComposeImageBitmap()
} catch (_: Exception) {
    null
}
