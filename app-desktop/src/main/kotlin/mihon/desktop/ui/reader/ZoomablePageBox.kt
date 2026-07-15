package mihon.desktop.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import coil3.BitmapImage
import coil3.Image as CoilImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.desktop.reader.PagePreloader
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.SkiaImageDecoder
import mihon.desktop.reader.ZoomState
import mihon.domain.reader.PixelBounds
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.Canvas as SkiaCanvas
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect as SkiaRect
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

// Kept for binary-compat with existing tests that call it directly.
internal fun loadSplitHalf(url: String, half: PageSplitHalf): ImageBitmap? = try {
    val bytes = java.net.URL(url).readBytes()
    splitHalfFromBytes(bytes, half)
} catch (_: Exception) {
    null
}

internal fun loadLocalPageBitmap(url: String): ImageBitmap? = runCatching {
    val uri = URI(url)
    if (!uri.scheme.equals("file", ignoreCase = true)) return null
    SkiaImageDecoder.decode(Files.readAllBytes(Path.of(uri)))
}.getOrNull()

/** Crops the already-decoded Coil image to the requested half — no re-download. */
internal fun splitHalfFromCoilImage(image: CoilImage, half: PageSplitHalf): ImageBitmap? {
    val skiaBitmap = image.toSkiaBitmap() ?: return null
    return splitSkiaBitmap(skiaBitmap, half)
}

private fun splitHalfFromBytes(bytes: ByteArray, half: PageSplitHalf): ImageBitmap? {
    val imageBitmap = mihon.desktop.reader.SkiaImageDecoder.decode(bytes)
    return splitSkiaBitmap(imageBitmap.asSkiaBitmap(), half)
}

private fun splitSkiaBitmap(src: SkiaBitmap, half: PageSplitHalf): ImageBitmap? {
    val bounds = splitBounds(src.width, src.height, half)
    return extractSkiaSubBitmap(src, bounds.x, bounds.y, bounds.width, bounds.height)
}

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
 * @param onTapCenter
 *   Called when the user taps the center zone (for toggling UI visibility).
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
    splitHalf: PageSplitHalf? = null,
    sourceBounds: PixelBounds? = null,
    preloader: PagePreloader? = null,
    modifier: Modifier = Modifier.fillMaxSize(),
    imageAlignment: Alignment = Alignment.Center,
    loadingAlignment: Alignment = Alignment.Center,
    showLoadingIndicator: Boolean = true,
    onLoadingStateChange: ((Boolean) -> Unit)? = null,
    onSpreadDetected: (() -> Unit)? = null,
    scaleType: ScaleType = ScaleType.FIT_SCREEN,
    navigationMode: NavigationMode = NavigationMode.RightAndLeft,
    onTapLeft: (() -> Unit)? = null,
    onTapRight: (() -> Unit)? = null,
    onTapCenter: (() -> Unit)? = null,
) {
    // Blank URL = page not yet downloaded. Show a loading spinner directly
    // rather than letting Coil attempt a request and return an error state.
    if (url.isBlank()) {
        LaunchedEffect(url) {
            onLoadingStateChange?.invoke(true)
        }
        Box(modifier = modifier, contentAlignment = loadingAlignment) {
            if (showLoadingIndicator) {
                CircularProgressIndicator(color = Color.White)
            }
        }
        return
    }

    val latestZoom by rememberUpdatedState(zoomState)

    // Fast path: if the preloader already has this page decoded, use it directly.
    // This eliminates the Coil loading indicator for pre-warmed pages.
    val preloadRevision = if (preloader != null) {
        preloader.cacheRevision.collectAsState().value
    } else {
        0L
    }
    val preloadedBitmap = remember(url, pageIndex, preloader, preloadRevision) { preloader?.get(pageIndex) }

    val transformedPreloadedBitmap by produceState<ImageBitmap?>(
        initialValue = preloadedBitmap.takeIf { splitHalf == null && sourceBounds == null && !cropBorders },
        url,
        preloadedBitmap,
        splitHalf,
        sourceBounds,
        cropBorders,
    ) {
        value = preloadedBitmap?.let { bitmap ->
            withContext(Dispatchers.Default) {
                transformCachedPageBitmap(bitmap, splitHalf, sourceBounds, cropBorders)
            }
        }
    }

    val painter = rememberAsyncImagePainter(readerPagePainterModel(url, preloadedBitmap))
    val painterState by painter.state.collectAsState()

    val localBitmap by produceState<ImageBitmap?>(initialValue = null, url, splitHalf, sourceBounds, cropBorders) {
        value = withContext(Dispatchers.IO) {
            val bitmap = loadLocalPageBitmap(url) ?: return@withContext null
            transformCachedPageBitmap(bitmap, splitHalf, sourceBounds, cropBorders)
        }
    }

    // Transformed Coil bitmap. Reset for every virtual half/bounds change so pager slot reuse
    // cannot briefly display the previous half of the same source URL.
    var croppedBitmap by remember(url, splitHalf, sourceBounds, cropBorders) { mutableStateOf<ImageBitmap?>(null) }

    // Detect spread pages and optionally apply crop borders after Coil decodes the image.
    LaunchedEffect(painterState, cropBorders, splitHalf, sourceBounds) {
        val s = painterState
        if (s is AsyncImagePainter.State.Success) {
            val img = s.result.image

            // Spread detection (only when not already splitting)
            if (onSpreadDetected != null && splitHalf == null && img.width > img.height) {
                onSpreadDetected()
            }

            when {
                sourceBounds != null -> {
                    croppedBitmap = s.result.image.toSkiaBitmap()?.let { bitmap ->
                        extractSkiaSubBitmap(bitmap, sourceBounds)
                    }
                }
                // Split half: crop the already-decoded Coil image — no re-download
                splitHalf != null -> {
                    croppedBitmap = withContext(Dispatchers.Default) {
                        splitHalfFromCoilImage(s.result.image, splitHalf)
                    }
                }
                // Crop borders: scan the already-decoded Coil image — no re-download
                cropBorders -> {
                    croppedBitmap = withContext(Dispatchers.Default) {
                        cropBordersFromCoilImage(s.result.image)
                    }
                }
                else -> croppedBitmap = null
            }
        }
    }

    LaunchedEffect(localBitmap, splitHalf) {
        val bitmap = localBitmap
        if (bitmap != null && onSpreadDetected != null && splitHalf == null && bitmap.width > bitmap.height) {
            onSpreadDetected()
        }
    }

    val innerContent: @Composable () -> Unit = {
        // Unified gesture handler using detectTapGestures for tap detection
        // combined with transform gesture handling in a single pointerInput.
        val gestureModifier = if (onTapLeft != null || onTapRight != null || onTapCenter != null) {
            Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)

                        // Multi-touch → zoom/pan
                        if (event.changes.size > 1) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                val current = latestZoom
                                val newScale = (current.scale * zoom).coerceIn(1f, ZoomState.MAX_SCALE)
                                val scaled = if (newScale <= 1f) ZoomState() else current.copy(scale = newScale)
                                onZoomChange(scaled)
                            }
                            val pressedChanges = event.changes.filter { it.pressed }
                            if (pressedChanges.isNotEmpty()) {
                                val panX = pressedChanges.sumOf { (it.position.x - it.previousPosition.x).toDouble() }.toFloat() / pressedChanges.size
                                val panY = pressedChanges.sumOf { (it.position.y - it.previousPosition.y).toDouble() }.toFloat() / pressedChanges.size
                                if (panX != 0f || panY != 0f) {
                                    val current = latestZoom
                                    onZoomChange(current.pan(panX, panY))
                                }
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        // Single-touch press → track for potential tap
                        if (event.isReaderPrimaryPress()) {
                            val down = event.changes.first()
                            val downPos = down.position
                            val downTime = System.currentTimeMillis()

                            // Track gesture completion
                            var gestureComplete = false
                            var moved = false
                            var isTap = false
                            var releasePos = downPos

                            while (!gestureComplete) {
                                val nextEvent = awaitPointerEvent(PointerEventPass.Main)
                                when (nextEvent.type) {
                                    PointerEventType.Move -> {
                                        val change = nextEvent.changes.first()
                                        val dx = change.position.x - downPos.x
                                        val dy = change.position.y - downPos.y
                                        // Movement threshold: ~15px
                                        if (dx * dx + dy * dy > 225f) {
                                            moved = true
                                            gestureComplete = true
                                            // Pan when zoomed in
                                            if (latestZoom.scale > 1f) {
                                                val current = latestZoom
                                                onZoomChange(current.pan(
                                                    change.position.x - change.previousPosition.x,
                                                    change.position.y - change.previousPosition.y,
                                                ))
                                            }
                                        }
                                    }
                                    PointerEventType.Release -> {
                                        releasePos = nextEvent.changes.first().position
                                        val elapsed = System.currentTimeMillis() - downTime
                                        // Tap: released quickly (<400ms) without significant movement
                                        if (elapsed < 400) {
                                            isTap = true
                                        }
                                        gestureComplete = true
                                    }
                                    PointerEventType.Exit -> {
                                        gestureComplete = true
                                    }
                                }
                            }

                            // Handle tap
                            if (isTap && !moved) {
                                val tapX = releasePos.x
                                val tapY = releasePos.y
                                val tapWidth = size.width.toFloat()
                                val tapHeight = size.height.toFloat()
                                if (latestZoom.scale <= 1f) {
                                    when (tapNavRegion(tapX, tapY, tapWidth, tapHeight, navigationMode)) {
                                        TapNavRegion.PREV -> onTapLeft?.invoke()
                                        TapNavRegion.NEXT -> onTapRight?.invoke()
                                        TapNavRegion.MENU -> onTapCenter?.invoke()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Modifier
        }

        // Double-tap to reset zoom (only when tap navigation is active)
        val doubleTapModifier = if (onTapLeft != null || onTapRight != null || onTapCenter != null) {
            Modifier.pointerInput(Unit) {
                var lastTapTime = 0L
                var lastTapPos = androidx.compose.ui.geometry.Offset.Zero
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.isReaderPrimaryPress()) {
                            val now = System.currentTimeMillis()
                            val pos = event.changes.first().position
                            if (now - lastTapTime < 300 &&
                                (pos - lastTapPos).getDistance() < 50
                            ) {
                                // Double-tap detected → reset zoom
                                onZoomChange(ZoomState())
                                lastTapTime = 0L
                                event.changes.forEach { it.consume() }
                            } else {
                                lastTapTime = now
                                lastTapPos = pos
                            }
                        }
                    }
                }
            }
        } else {
            Modifier
        }

        Box(
            modifier = modifier
                .then(gestureModifier)
                .then(doubleTapModifier),
            contentAlignment = imageAlignment,
        ) {
            // Loading indicator — suppressed when preloaded bitmap or crop is ready
            val isLoading = painterState is AsyncImagePainter.State.Loading ||
                painterState is AsyncImagePainter.State.Empty
            val isCachedTransformLoading = preloadedBitmap != null && transformedPreloadedBitmap == null
            val shouldShowLoading =
                (isLoading && preloadedBitmap == null || isCachedTransformLoading) &&
                    croppedBitmap == null &&
                    localBitmap == null
            LaunchedEffect(shouldShowLoading) {
                onLoadingStateChange?.invoke(shouldShowLoading)
            }
            if (showLoadingIndicator && shouldShowLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = loadingAlignment,
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }

            // Resolve ContentScale from ScaleType.
            // SmartFit: FillWidth for portrait images, Fit for landscape.
            val resolvedScale = when (scaleType) {
                ScaleType.FIT_SCREEN -> ContentScale.Fit
                ScaleType.FIT_WIDTH -> ContentScale.FillWidth
                ScaleType.FIT_HEIGHT -> ContentScale.FillHeight
                ScaleType.ORIGINAL_SIZE -> ContentScale.None
                ScaleType.SMART_FIT -> {
                    val s = painterState
                    if (s is AsyncImagePainter.State.Success && s.result.image.height > s.result.image.width) {
                        ContentScale.FillWidth
                    } else {
                        ContentScale.Fit
                    }
                }
            }

            val displayBitmap = croppedBitmap ?: transformedPreloadedBitmap ?: localBitmap
            val imageModifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = zoomState.scale,
                    scaleY = zoomState.scale,
                    translationX = zoomState.offsetX,
                    translationY = zoomState.offsetY,
                )
            if (displayBitmap != null) {
                Image(
                    bitmap = displayBitmap,
                    contentDescription = pageLabel,
                    alignment = imageAlignment,
                    modifier = imageModifier,
                    contentScale = resolvedScale,
                )
            } else {
                Image(
                    painter = painter,
                    contentDescription = pageLabel,
                    alignment = imageAlignment,
                    modifier = imageModifier,
                    contentScale = resolvedScale,
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

internal fun readerPagePainterModel(url: String, preloadedBitmap: ImageBitmap?): Any? =
    url.takeIf { preloadedBitmap == null }

internal fun transformCachedPageBitmap(
    bitmap: ImageBitmap,
    splitHalf: PageSplitHalf? = null,
    sourceBounds: PixelBounds? = null,
    cropBorders: Boolean = false,
): ImageBitmap {
    val skiaBitmap = bitmap.asSkiaBitmap()
    return when {
        sourceBounds != null -> extractSkiaSubBitmap(skiaBitmap, sourceBounds) ?: bitmap
        splitHalf != null -> splitSkiaBitmap(skiaBitmap, splitHalf) ?: bitmap
        cropBorders -> cropBordersFromSkiaBitmap(skiaBitmap) ?: bitmap
        else -> bitmap
    }
}

/** Crops the already-decoded Coil image's white borders — no re-download. */
internal fun cropBordersFromCoilImage(image: CoilImage): ImageBitmap? {
    val skiaBitmap = image.toSkiaBitmap() ?: return null
    return cropBordersFromSkiaBitmap(skiaBitmap)
}

/**
 * Extracts a [SkiaBitmap] from a [CoilImage].
 * For [BitmapImage] the backing SkiaBitmap is reused directly (zero-copy).
 */
private fun CoilImage.toSkiaBitmap(): SkiaBitmap? = when (this) {
    is BitmapImage -> bitmap
    else -> null
}

/**
 * Scans [src] for white borders using [CropBorderScanner] and returns a cropped
 * [ImageBitmap].  Returns null if no meaningful crop is found.
 *
 * Border scanning reads pixel colours via [SkiaBitmap.getColor] — no AWT conversion needed.
 */
private fun cropBordersFromSkiaBitmap(src: SkiaBitmap): ImageBitmap? {
    val w = src.width
    val h = src.height
    val threshold = 240

    fun isLight(x: Int, y: Int): Boolean {
        val c = src.getColor(x, y)
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r >= threshold && g >= threshold && b >= threshold
    }

    var top = 0
    outer@ for (y in 0 until h) {
        for (x in 0 until w) { if (!isLight(x, y)) { top = y; break@outer } }
        if (y == h - 1) top = 0
    }
    var bottom = h
    outer@ for (y in h - 1 downTo 0) {
        for (x in 0 until w) { if (!isLight(x, y)) { bottom = y + 1; break@outer } }
        if (y == 0) bottom = h
    }
    var left = 0
    outer@ for (x in 0 until w) {
        for (y in top until bottom) { if (!isLight(x, y)) { left = x; break@outer } }
        if (x == w - 1) left = 0
    }
    var right = w
    outer@ for (x in w - 1 downTo 0) {
        for (y in top until bottom) { if (!isLight(x, y)) { right = x + 1; break@outer } }
        if (x == 0) right = w
    }

    if (top == 0 && left == 0 && bottom == h && right == w) return null
    if (right <= left || bottom <= top) return null

    return extractSkiaSubBitmap(src, left, top, right - left, bottom - top)
}

/**
 * Copies a rectangular sub-region from [src] into a new [ImageBitmap].
 */
private fun extractSkiaSubBitmap(src: SkiaBitmap, x: Int, y: Int, w: Int, h: Int): ImageBitmap {
    val dst = SkiaBitmap()
    dst.allocN32Pixels(w, h)
    val canvas = SkiaCanvas(dst)
    canvas.drawImageRect(
        SkiaImage.makeFromBitmap(src),
        SkiaRect.makeLTRB(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()),
        SkiaRect.makeWH(w.toFloat(), h.toFloat()),
    )
    return dst.asComposeImageBitmap()
}

private fun extractSkiaSubBitmap(src: SkiaBitmap, bounds: PixelBounds): ImageBitmap? {
    if (
        bounds.x < 0 ||
        bounds.y < 0 ||
        bounds.width <= 0 ||
        bounds.height <= 0 ||
        bounds.x + bounds.width > src.width ||
        bounds.y + bounds.height > src.height
    ) {
        return null
    }
    return extractSkiaSubBitmap(src, bounds.x, bounds.y, bounds.width, bounds.height)
}
