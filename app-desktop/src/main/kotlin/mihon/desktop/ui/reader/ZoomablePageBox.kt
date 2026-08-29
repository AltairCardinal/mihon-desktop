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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CoroutineScope
import mihon.desktop.reader.DesktopReaderPresentationImageState
import mihon.desktop.reader.PreloadedPageBitmap
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.ZoomState
import mihon.domain.reader.PixelBounds
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.Canvas as SkiaCanvas
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect as SkiaRect
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap

internal data class ZoomableGestureCapabilities(
    val transformEnabled: Boolean,
    val tapNavigationEnabled: Boolean,
    val doubleTapResetEnabled: Boolean,
)

internal fun zoomableGestureCapabilities(
    handlesTapNavigation: Boolean,
    hasNavigationCallbacks: Boolean,
): ZoomableGestureCapabilities = ZoomableGestureCapabilities(
    transformEnabled = true,
    tapNavigationEnabled = handlesTapNavigation && hasNavigationCallbacks,
    doubleTapResetEnabled = true,
)

/**
 * A single manga page image with pinch-to-zoom, drag-to-pan, double-tap-to-reset,
 * and optional white-border cropping.
 *
 * This is the fundamental building block shared by both [SinglePagePagerViewer]
 * and [DualPagePagerViewer].  It is self-contained and carries no knowledge of
 * which viewer hosts it.
 *
 * ──────────────────────────────────────────────────────────
 * The presentation supplies a stable decoded asset owned by the reader runtime; this component
 * performs only viewport transforms and gesture handling.
 *
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
 *   Called once after the shared pipeline decodes an image whose width > height
 *   (landscape / double-page spread image).  Pass `null` to skip detection.
 * @param onTapCenter
 *   Called when the user taps the center zone (for toggling UI visibility).
 */
@Composable
internal fun ZoomablePageBox(
    presentationImage: ReaderPresentationImage,
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
    loadingAlignment: Alignment = Alignment.Center,
    showLoadingIndicator: Boolean = true,
    onLoadingStateChange: ((Boolean) -> Unit)? = null,
    onSpreadDetected: (() -> Unit)? = null,
    scaleType: ScaleType = ScaleType.FIT_SCREEN,
    navigationMode: NavigationMode = NavigationMode.RightAndLeft,
    isRtl: Boolean = false,
    handlesTapNavigation: Boolean = true,
    onTapPrevious: (() -> Unit)? = null,
    onTapNext: (() -> Unit)? = null,
    onTapCenter: (() -> Unit)? = null,
) {
    val gestureCapabilities = zoomableGestureCapabilities(
        handlesTapNavigation = handlesTapNavigation,
        hasNavigationCallbacks = onTapPrevious != null || onTapNext != null || onTapCenter != null,
    )

    val latestZoom by rememberUpdatedState(zoomState)
    val identity = presentationImage.holder.identity
    val splitHalf = identity.splitHalf
    val sourceBounds = identity.sourceBounds
    val readyState = presentationImage.state as? DesktopReaderPresentationImageState.Ready
    val asset = readyState?.asset
    val renderedImage = rememberReaderPresentationRenderedImage(presentationImage, cropBorders)

    LaunchedEffect(asset, splitHalf, onSpreadDetected) {
        if (asset != null && onSpreadDetected != null && splitHalf == null && asset.sourceWidth > asset.sourceHeight) {
            onSpreadDetected()
        }
    }

    val innerContent: @Composable () -> Unit = {
        // Unified gesture handler using detectTapGestures for tap detection
        // combined with transform gesture handling in a single pointerInput.
        val gestureModifier = if (gestureCapabilities.transformEnabled) {
            Modifier.pointerInput(navigationMode, isRtl, gestureCapabilities.tapNavigationEnabled) {
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
                            if (gestureCapabilities.tapNavigationEnabled && isTap && !moved) {
                                val tapX = releasePos.x
                                val tapY = releasePos.y
                                val tapWidth = size.width.toFloat()
                                val tapHeight = size.height.toFloat()
                                if (latestZoom.scale <= 1f) {
                                    when (tapNavRegion(tapX, tapY, tapWidth, tapHeight, navigationMode, isRtl)) {
                                        TapNavRegion.PREV -> onTapPrevious?.invoke()
                                        TapNavRegion.NEXT -> onTapNext?.invoke()
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

        // Double-tap reset stays local even when single-tap navigation is delegated to a parent Row.
        val doubleTapModifier = if (gestureCapabilities.doubleTapResetEnabled) {
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
            val shouldShowLoading = asset == null || renderedImage == null
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
                ScaleType.SMART_FIT ->
                    if (asset != null && asset.sourceHeight > asset.sourceWidth) {
                        ContentScale.FillWidth
                    } else {
                        ContentScale.Fit
                    }
            }

            val imageModifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = zoomState.scale,
                    scaleY = zoomState.scale,
                    translationX = zoomState.offsetX,
                    translationY = zoomState.offsetY,
                )
                .observeReaderPageDraw(renderedImage?.acknowledgeDraw)
            if (renderedImage != null) {
                Image(
                    bitmap = renderedImage.bitmap,
                    contentDescription = pageLabel,
                    alignment = imageAlignment,
                    modifier = imageModifier,
                    contentScale = resolvedScale,
                )
            }
        }
    }

    val scope = contextMenuScope
    if (scope != null && asset != null) {
        val binding = readerPageContextMenuBinding(presentationImage)
        PageContextMenu(
            imageLeaseProvider = binding.imageLeaseProvider,
            mangaTitle = mangaTitle,
            chapterTitle = chapterTitle,
            pageIndex = pageIndex,
            scope = scope,
            onSetAsCover = onSetAsCover,
            splitHalf = binding.splitHalf,
            sourceBounds = binding.sourceBounds,
            content = innerContent,
        )
    } else {
        innerContent()
    }
}

internal fun transformCachedPageBitmap(
    bitmap: ImageBitmap,
    splitHalf: PageSplitHalf? = null,
    sourceBounds: PixelBounds? = null,
    cropBorders: Boolean = false,
    sourceWidth: Int = bitmap.width,
    sourceHeight: Int = bitmap.height,
): ImageBitmap {
    val skiaBitmap = bitmap.asSkiaBitmap()
    val bounded = when {
        sourceBounds != null -> {
            val mappedBounds = sourceBounds.mapToBitmap(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                bitmapWidth = bitmap.width,
                bitmapHeight = bitmap.height,
            )
            requireNotNull(extractSkiaSubBitmap(skiaBitmap, mappedBounds)) {
                "Mapped source bounds $mappedBounds exceed cached bitmap ${bitmap.width}x${bitmap.height}"
            }
        }
        splitHalf != null -> splitSkiaBitmap(skiaBitmap, splitHalf) ?: bitmap
        else -> bitmap
    }
    if (!cropBorders) return bounded
    return selectPresentationCropResult(
        base = bitmap,
        bounded = bounded,
        cropped = cropBordersFromSkiaBitmap(bounded.asSkiaBitmap()),
    )
}

internal fun selectPresentationCropResult(
    base: ImageBitmap,
    bounded: ImageBitmap,
    cropped: ImageBitmap?,
): ImageBitmap {
    if (cropped == null) return bounded
    if (bounded !== base && cropped !== bounded) bounded.asSkiaBitmap().close()
    return cropped
}

private fun splitSkiaBitmap(src: SkiaBitmap, half: PageSplitHalf): ImageBitmap? {
    val bounds = splitBounds(src.width, src.height, half)
    return extractSkiaSubBitmap(src, bounds.x, bounds.y, bounds.width, bounds.height)
}

internal fun transformCachedPageBitmap(
    cachedPage: PreloadedPageBitmap,
    splitHalf: PageSplitHalf? = null,
    sourceBounds: PixelBounds? = null,
    cropBorders: Boolean = false,
): ImageBitmap = transformCachedPageBitmap(
    bitmap = cachedPage.bitmap,
    splitHalf = splitHalf,
    sourceBounds = sourceBounds,
    cropBorders = cropBorders,
    sourceWidth = cachedPage.sourceWidth,
    sourceHeight = cachedPage.sourceHeight,
)

internal fun PixelBounds.mapToBitmap(
    sourceWidth: Int,
    sourceHeight: Int,
    bitmapWidth: Int,
    bitmapHeight: Int,
): PixelBounds {
    require(sourceWidth > 0 && sourceHeight > 0) { "source dimensions must be positive" }
    require(bitmapWidth > 0 && bitmapHeight > 0) { "bitmap dimensions must be positive" }
    require(
        x >= 0 &&
            y >= 0 &&
            width > 0 &&
            height > 0 &&
            x.toLong() + width <= sourceWidth &&
            y.toLong() + height <= sourceHeight,
    ) {
        "Source bounds $this exceed original image ${sourceWidth}x$sourceHeight"
    }

    val left = scaleCoordinate(x, sourceWidth, bitmapWidth)
    val top = scaleCoordinate(y, sourceHeight, bitmapHeight)
    val right = scaleCoordinate(x + width, sourceWidth, bitmapWidth)
    val bottom = scaleCoordinate(y + height, sourceHeight, bitmapHeight)
    require(right > left && bottom > top) {
        "Source bounds $this collapse in cached bitmap ${bitmapWidth}x$bitmapHeight"
    }
    return PixelBounds(left, top, right - left, bottom - top)
}

private fun scaleCoordinate(coordinate: Int, sourceExtent: Int, bitmapExtent: Int): Int =
    // Round shared edges identically so odd virtual halves remain contiguous after sampling.
    ((coordinate.toLong() * bitmapExtent + sourceExtent / 2L) / sourceExtent).toInt()

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
    SkiaCanvas(dst).use { canvas ->
        SkiaImage.makeFromBitmap(src).use { sourceImage ->
            canvas.drawImageRect(
                sourceImage,
                SkiaRect.makeLTRB(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()),
                SkiaRect.makeWH(w.toFloat(), h.toFloat()),
            )
        }
    }
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
