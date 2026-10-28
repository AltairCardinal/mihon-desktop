package mihon.desktop.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import mihon.desktop.reader.DesktopReaderPresentationImageState
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.ZoomState
import mihon.domain.reader.PixelBounds
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.Canvas as SkiaCanvas
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect as SkiaRect

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

/** Input remains in viewport coordinates even when either image crosses the spine. */
@Composable
internal fun Modifier.readerZoomInput(
    zoomState: ZoomState,
    onZoomChange: (ZoomState) -> Unit,
    onTap: ((Offset, Float, Float) -> Unit)? = null,
): Modifier {
    val latestZoom by rememberUpdatedState(zoomState)
    val latestChange by rememberUpdatedState(onZoomChange)
    val latestTap by rememberUpdatedState(onTap)
    return pointerInput(Unit) {
        var lastTapTime = 0L
        var lastTapPosition = Offset.Zero
        awaitPointerEventScope {
            var downPosition: Offset? = null
            var lastPanPosition = Offset.Zero
            var downTime = 0L
            var moved = false
            var resetTap = false
            var gestureZoom = latestZoom
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (event.type == PointerEventType.Press && downPosition == null) {
                    val down = event.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                    if (!event.isReaderPrimaryPress() && down.type != PointerType.Touch) continue
                    downPosition = down.position
                    lastPanPosition = down.position
                    downTime = System.currentTimeMillis()
                    moved = false
                    resetTap = downTime - lastTapTime < 300 &&
                        (down.position - lastTapPosition).getDistance() < 50
                    gestureZoom = if (resetTap) ZoomState() else latestZoom
                    if (resetTap) {
                        latestChange(gestureZoom)
                        lastTapTime = 0L
                        event.changes.forEach { it.consume() }
                    }
                }
                if (downPosition == null) continue
                if (pressed.size > 1) {
                    moved = true
                    val previousCentroid = event.calculateCentroid(useCurrent = false)
                    val pan = event.calculatePan()
                    val scale = (gestureZoom.scale * event.calculateZoom()).coerceIn(1f, ZoomState.MAX_SCALE)
                    val factor = scale / gestureZoom.scale
                    val center = Offset(size.width / 2f, size.height / 2f)
                    gestureZoom = if (scale <= 1f) {
                        ZoomState()
                    } else {
                        val focalOffset = previousCentroid - center
                        ZoomState(
                            scale,
                            gestureZoom.offsetX * factor + focalOffset.x * (1f - factor) + pan.x,
                            gestureZoom.offsetY * factor + focalOffset.y * (1f - factor) + pan.y,
                        )
                    }
                    latestChange(gestureZoom)
                    event.changes.forEach { it.consume() }
                    lastPanPosition = pressed.first().position
                } else if (pressed.isNotEmpty() &&
                    (
                        event.type == PointerEventType.Move || event.type == PointerEventType.Enter ||
                            event.type == PointerEventType.Exit
                        )
                ) {
                    // Desktop may deliver the first pressed movement as Enter after hit-test changes.
                    // It carries a real delta and remains part of the captured drag.
                    val change = pressed.first()
                    if ((change.position - requireNotNull(downPosition)).getDistance() > 15f) moved = true
                    if (moved && gestureZoom.scale > 1f) {
                        val delta = change.position - lastPanPosition
                        gestureZoom = gestureZoom.pan(delta.x, delta.y)
                        latestChange(gestureZoom)
                        event.changes.forEach { it.consume() }
                    }
                    lastPanPosition = change.position
                } else if (pressed.size == 1) {
                    lastPanPosition = pressed.first().position
                }
                if (pressed.isEmpty()) {
                    // Native cancellation releases are consumed; they cannot seed a later double tap.
                    val consumed = event.changes.any { it.isConsumed }
                    if (consumed) lastTapTime = 0L
                    if (!consumed && !moved && !resetTap && System.currentTimeMillis() - downTime < 400) {
                        lastTapTime = System.currentTimeMillis()
                        lastTapPosition = event.changes.first().position
                        if (gestureZoom.scale <= 1f) {
                            latestTap?.invoke(lastTapPosition, size.width.toFloat(), size.height.toFloat())
                        }
                    }
                    downPosition = null
                }
            }
        }
    }
}

internal fun Modifier.readerImageTransform(
    zoomState: ZoomState,
    viewport: ReaderImageViewport?,
): Modifier = graphicsLayer {
    scaleX = zoomState.scale
    scaleY = zoomState.scale
    translationX = zoomState.offsetX
    translationY = zoomState.offsetY
    transformOrigin = if (viewport == null || size.width <= 0f || size.height <= 0f) {
        TransformOrigin.Center
    } else {
        TransformOrigin(
            (viewport.size.width / 2f - viewport.slotOrigin.x) / size.width,
            (viewport.size.height / 2f - viewport.slotOrigin.y) / size.height,
        )
    }
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
    onPageDrawn: (() -> Unit)? = null,
    onPagePrepared: ((Int, Int) -> Boolean)? = null,
    canObserveFrameGeometry: ((Long) -> Boolean)? = null,
    onObservedImageDraw: (
        (
            ReaderBaseImageDraw,
            mihon.desktop.reader.DesktopReaderPresentationImageSlotIdentity,
            mihon.domain.reader.ReaderPageDecodeKey,
        ) -> Unit
    )? = null,
    pageLabel: String,
    zoomState: ZoomState,
    imageViewport: ReaderImageViewport? = null,
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
    onFeedbackVisibilityChange: ((Boolean) -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onSpreadDetected: (() -> Unit)? = null,
    scaleType: ScaleType = ScaleType.FIT_SCREEN,
    navigationMode: NavigationMode = NavigationMode.RightAndLeft,
    isRtl: Boolean = false,
    handlesTapNavigation: Boolean = true,
    onTapPrevious: (() -> Unit)? = null,
    onTapNext: (() -> Unit)? = null,
    onTapCenter: (() -> Unit)? = null,
    renderedImage: ReaderPresentationRenderedImage? =
        rememberReaderPresentationRenderedImage(presentationImage, cropBorders),
) {
    val gestureCapabilities = zoomableGestureCapabilities(
        handlesTapNavigation = handlesTapNavigation,
        hasNavigationCallbacks = onTapPrevious != null || onTapNext != null || onTapCenter != null,
    )

    val identity = presentationImage.holder.identity
    val splitHalf = identity.splitHalf
    val sourceBounds = identity.sourceBounds
    val presentationState = presentationImage.state
    val readyState = presentationState as? DesktopReaderPresentationImageState.Ready
    val asset = readyState?.asset
    val mountedCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val renderTrace = LocalReaderRenderTrace.current
    val traceRevision = if (renderTrace != null) renderTrace.captureRevision.collectAsState().value else 0L
    val observesImage = onObservedImageDraw != null || renderTrace != null
    val ioCapture = if (onObservedImageDraw != null) {
        mihon.desktop.test.state.readerState.slotDrawCapture.collectAsState().value
    } else {
        0L
    }
    val observedCapture = ioCapture.takeIf { it != 0L } ?: if (renderTrace != null) 1L else 0L

    LaunchedEffect(asset, splitHalf, onSpreadDetected) {
        if (asset != null && onSpreadDetected != null && splitHalf == null && asset.sourceWidth > asset.sourceHeight) {
            onSpreadDetected()
        }
    }

    val innerContent: @Composable () -> Unit = {
        val gestureModifier = if (imageViewport == null) {
            Modifier.readerZoomInput(
                zoomState = zoomState,
                onZoomChange = onZoomChange,
                onTap = if (gestureCapabilities.tapNavigationEnabled) {
                    { point, width, height ->
                        when (tapNavRegion(point.x, point.y, width, height, navigationMode, isRtl)) {
                            TapNavRegion.PREV -> onTapPrevious?.invoke()
                            TapNavRegion.NEXT -> onTapNext?.invoke()
                            TapNavRegion.MENU -> onTapCenter?.invoke()
                        }
                    }
                } else {
                    null
                },
            )
        } else {
            Modifier
        }

        Box(
            modifier = modifier
                .then(gestureModifier),
            contentAlignment = imageAlignment,
        ) {
            val fullDecodeFailed = presentationState is DesktopReaderPresentationImageState.Failed
            val presentationClosed = presentationState is DesktopReaderPresentationImageState.Closed
            val shouldShowLoading = !fullDecodeFailed && !presentationClosed &&
                (asset == null || renderedImage == null)
            LaunchedEffect(shouldShowLoading, fullDecodeFailed) {
                onLoadingStateChange?.invoke(shouldShowLoading)
                onFeedbackVisibilityChange?.invoke(shouldShowLoading || fullDecodeFailed)
            }
            if (showLoadingIndicator && shouldShowLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = loadingAlignment,
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            if (fullDecodeFailed) {
                ReaderPageRetryContent(
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxSize(),
                )
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

            val imageModifier = Modifier.fillMaxSize().readerImageTransform(zoomState, imageViewport)
            if (renderedImage != null && !fullDecodeFailed && !presentationClosed) {
                val observedMount = if (onObservedImageDraw != null) {
                    remember { mihon.desktop.test.state.readerState.nextDrawMountId() }
                } else if (renderTrace != null) {
                    remember(renderTrace) { renderTrace.nextMountId() }
                } else {
                    0L
                }
                val observedCoordinates = if (observesImage) {
                    remember { arrayOfNulls<LayoutCoordinates>(1) }
                } else {
                    null
                }
                val observedPainter = if (observesImage) {
                    remember(renderedImage.bitmap, observedCapture, presentationImage.holder, identity) {
                        ReaderObservedBitmapPainter(renderedImage.bitmap)
                    }
                        .apply {
                            capture = observedCapture
                            mountId = observedMount
                            coordinates = { observedCoordinates?.get(0) }
                            onDraw = { command ->
                                onObservedImageDraw?.invoke(command, identity, presentationImage.holder.decodeKey)
                                renderTrace?.guard("image-command") {
                                    renderTrace.imageDraw(readerRenderTraceImage(command, identity))
                                }
                            }
                        }
                } else {
                    null
                }
                if (observedCapture != 0L && observedPainter != null) {
                    val latestObservedDraw by rememberUpdatedState(onObservedImageDraw)
                    val latestCanObserve by rememberUpdatedState(canObserveFrameGeometry)
                    LaunchedEffect(
                        observedCapture,
                        observedPainter,
                        observedMount,
                        identity,
                        renderTrace,
                        traceRevision,
                    ) {
                        while (isActive &&
                            (renderTrace?.isCapturing == true || latestCanObserve?.invoke(observedCapture) == true)
                        ) {
                            withFrameNanos { clock ->
                                observedPainter.sampleAppliedGeometry(clock)?.let { sample ->
                                    latestObservedDraw?.invoke(sample, identity, presentationImage.holder.decodeKey)
                                    renderTrace?.guard("image-geometry") {
                                        renderTrace.imageDraw(readerRenderTraceImage(sample, identity))
                                    }
                                }
                            }
                        }
                    }
                }
                val imageContent: @Composable () -> Unit = {
                    // The base bitmap and region tiles share one preparation boundary. A mounted
                    // prefetch page may decode, but cannot acknowledge clipped-away pixels.
                    Box(
                        Modifier.fillMaxSize().observeReaderPageDraw(
                            {
                                val accepted = renderedImage.acknowledgeDraw()
                                if (onPageDrawn != null && renderedImage.currentPixels()) onPageDrawn.invoke()
                                accepted
                            },
                            prepareDraw = {
                                val coordinates = mountedCoordinates[0]?.takeIf { it.isAttached }
                                val localGeometry = coordinates?.takeIf {
                                    it.size.width > 0 && it.size.height > 0
                                }?.let {
                                    resolveReaderRegionDrawGeometry(
                                        viewportSize = it.size,
                                        renderedBitmapSize = IntSize(
                                            renderedImage.bitmap.width,
                                            renderedImage.bitmap.height,
                                        ),
                                        renderedSourceBounds = renderedImage.renderedSourceBounds,
                                        contentScale = resolvedScale,
                                        alignment = imageAlignment,
                                    )
                                }
                                val geometry = localGeometry?.let { imageViewport?.mapGeometry(it) ?: it }
                                coordinates?.boundsInRoot()?.isEmpty == false && geometry != null &&
                                    resolveReaderVisibleSourceBounds(
                                        geometry,
                                        ReaderRegionViewport.Full,
                                        zoomState,
                                    ) != null &&
                                    (
                                        onPagePrepared == null ||
                                            asset?.let { onPagePrepared(it.bitmap.width, it.bitmap.height) } == true
                                        )
                            },
                        ),
                    ) {
                        if (observedPainter != null) {
                            Image(
                                painter = observedPainter,
                                contentDescription = pageLabel,
                                alignment = imageAlignment,
                                modifier = Modifier.fillMaxSize().onGloballyPositioned {
                                    observedCoordinates?.set(0, it)
                                    observedPainter.lastLayoutCallbackMonotonicNanos = System.nanoTime()
                                },
                                contentScale = resolvedScale,
                            )
                        } else {
                            Image(
                                bitmap = renderedImage.bitmap,
                                contentDescription = pageLabel,
                                alignment = imageAlignment,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = resolvedScale,
                            )
                        }
                        ReaderRegionTileLayer(
                            presentationImage = presentationImage,
                            renderedImage = renderedImage,
                            contentScale = resolvedScale,
                            alignment = imageAlignment,
                            zoomState = zoomState,
                            imageViewport = imageViewport,
                            transformedByParent = true,
                        )
                    }
                }
                Box(imageModifier.onGloballyPositioned { mountedCoordinates[0] = it }) {
                    if (imageViewport != null && contextMenuScope != null) {
                        val binding = readerPageContextMenuBinding(presentationImage)
                        PageContextMenu(
                            imageLeaseProvider = binding.imageLeaseProvider,
                            mangaTitle = mangaTitle,
                            chapterTitle = chapterTitle,
                            pageIndex = pageIndex,
                            scope = contextMenuScope,
                            onSetAsCover = onSetAsCover,
                            splitHalf = binding.splitHalf,
                            sourceBounds = binding.sourceBounds,
                            content = imageContent,
                        )
                    } else {
                        imageContent()
                    }
                }
            }
        }
    }

    val scope = contextMenuScope
    if (imageViewport == null && scope != null && asset != null) {
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

internal data class ReaderPresentationBitmapTransform(
    val bitmap: ImageBitmap,
    val renderedSourceBounds: PixelBounds,
)

internal fun transformCachedPageBitmapWithSourceBounds(
    bitmap: ImageBitmap,
    splitHalf: PageSplitHalf? = null,
    sourceBounds: PixelBounds? = null,
    cropBorders: Boolean = false,
    sourceWidth: Int = bitmap.width,
    sourceHeight: Int = bitmap.height,
): ReaderPresentationBitmapTransform {
    val skiaBitmap = bitmap.asSkiaBitmap()
    val boundedSource = sourceBounds
        ?: splitHalf?.let { splitBounds(sourceWidth, sourceHeight, it) }
        ?: PixelBounds(0, 0, sourceWidth, sourceHeight)
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
    if (!cropBorders) return ReaderPresentationBitmapTransform(bounded, boundedSource)
    val cropped = cropBordersFromSkiaBitmap(bounded.asSkiaBitmap())
        ?: return ReaderPresentationBitmapTransform(bounded, boundedSource)
    return ReaderPresentationBitmapTransform(
        bitmap = selectPresentationCropResult(
            base = bitmap,
            bounded = bounded,
            cropped = cropped.bitmap,
        ),
        renderedSourceBounds = cropped.bounds.mapBitmapBoundsToSource(
            bitmapWidth = bounded.width,
            bitmapHeight = bounded.height,
            sourceBounds = boundedSource,
        ),
    )
}

private fun PixelBounds.mapBitmapBoundsToSource(
    bitmapWidth: Int,
    bitmapHeight: Int,
    sourceBounds: PixelBounds,
): PixelBounds {
    val left = sourceBounds.x + (x.toLong() * sourceBounds.width / bitmapWidth).toInt()
    val top = sourceBounds.y + (y.toLong() * sourceBounds.height / bitmapHeight).toInt()
    val right = sourceBounds.x +
        ((x.toLong() + width) * sourceBounds.width + bitmapWidth - 1L).div(bitmapWidth).toInt()
    val bottom = sourceBounds.y +
        ((y.toLong() + height) * sourceBounds.height + bitmapHeight - 1L).div(bitmapHeight).toInt()
    return PixelBounds(left, top, right - left, bottom - top)
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
 * Scans [src] for white borders in the active Skia presentation transform and returns a cropped
 * [ImageBitmap]. Returns null if no meaningful crop is found.
 *
 * Border scanning reads pixel colours via [SkiaBitmap.getColor] — no AWT conversion needed.
 */
private data class CroppedPresentationBitmap(
    val bitmap: ImageBitmap,
    val bounds: PixelBounds,
)

private fun cropBordersFromSkiaBitmap(src: SkiaBitmap): CroppedPresentationBitmap? {
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
        for (x in 0 until w) {
            if (!isLight(x, y)) {
                top = y
                break@outer
            }
        }
        if (y == h - 1) top = 0
    }
    var bottom = h
    outer@ for (y in h - 1 downTo 0) {
        for (x in 0 until w) {
            if (!isLight(x, y)) {
                bottom = y + 1
                break@outer
            }
        }
        if (y == 0) bottom = h
    }
    var left = 0
    outer@ for (x in 0 until w) {
        for (y in top until bottom) {
            if (!isLight(x, y)) {
                left = x
                break@outer
            }
        }
        if (x == w - 1) left = 0
    }
    var right = w
    outer@ for (x in w - 1 downTo 0) {
        for (y in top until bottom) {
            if (!isLight(x, y)) {
                right = x + 1
                break@outer
            }
        }
        if (x == 0) right = w
    }

    if (top == 0 && left == 0 && bottom == h && right == w) return null
    if (right <= left || bottom <= top) return null

    val bounds = PixelBounds(left, top, right - left, bottom - top)
    return CroppedPresentationBitmap(
        bitmap = extractSkiaSubBitmap(src, bounds.x, bounds.y, bounds.width, bounds.height),
        bounds = bounds,
    )
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
