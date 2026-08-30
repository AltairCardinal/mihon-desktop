package mihon.desktop.ui.reader

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import mihon.desktop.reader.ZoomState
import mihon.domain.reader.PixelBounds

internal sealed interface ReaderRegionViewport {
    data object Full : ReaderRegionViewport

    data object Hidden : ReaderRegionViewport

    data class Visible(
        val top: Int,
        val bottomExclusive: Int,
    ) : ReaderRegionViewport {
        init {
            require(top >= 0) { "Visible viewport top must be non-negative" }
            require(bottomExclusive > top) { "Visible viewport must have positive height" }
        }
    }
}

internal data class ReaderRegionDrawGeometry(
    val viewportSize: IntSize,
    val drawOffset: IntOffset,
    val drawSize: IntSize,
    val renderedSourceBounds: PixelBounds,
) {
    init {
        require(viewportSize.width > 0 && viewportSize.height > 0) { "Viewport dimensions must be positive" }
        require(drawSize.width > 0 && drawSize.height > 0) { "Draw dimensions must be positive" }
        require(renderedSourceBounds.width > 0 && renderedSourceBounds.height > 0) {
            "Rendered source dimensions must be positive"
        }
    }
}

internal data class ReaderRegionTileLevel(
    val sampleSize: Int,
    val sourceTileExtent: Int,
) {
    init {
        require(sampleSize > 0 && sampleSize.countOneBits() == 1) { "Tile sample size must be a power of two" }
        require(sourceTileExtent > 0) { "Source tile extent must be positive" }
    }
}

internal fun resolveReaderRegionTileLevel(
    sourcePixelsPerScreenPixel: Float,
    tileOutputExtent: Int,
): ReaderRegionTileLevel {
    require(sourcePixelsPerScreenPixel.isFinite() && sourcePixelsPerScreenPixel > 0f)
    require(tileOutputExtent > 0)
    var sampleSize = 1
    while (sampleSize <= Int.MAX_VALUE / 2 && sampleSize * 2f <= sourcePixelsPerScreenPixel) {
        sampleSize *= 2
    }
    return ReaderRegionTileLevel(
        sampleSize = sampleSize,
        sourceTileExtent = Math.multiplyExact(tileOutputExtent, sampleSize),
    )
}

internal fun resolveReaderVisibleSourceBounds(
    viewportSize: IntSize,
    renderedBitmapSize: IntSize,
    renderedSourceBounds: PixelBounds,
    contentScale: ContentScale,
    alignment: Alignment,
    zoomState: ZoomState,
): PixelBounds? {
    val geometry = resolveReaderRegionDrawGeometry(
        viewportSize = viewportSize,
        renderedBitmapSize = renderedBitmapSize,
        renderedSourceBounds = renderedSourceBounds,
        contentScale = contentScale,
        alignment = alignment,
    )
    return resolveReaderVisibleSourceBounds(
        geometry = geometry,
        viewport = ReaderRegionViewport.Full,
        zoomState = zoomState,
    )
}

internal fun resolveReaderRegionDrawGeometry(
    viewportSize: IntSize,
    renderedBitmapSize: IntSize,
    renderedSourceBounds: PixelBounds,
    contentScale: ContentScale,
    alignment: Alignment,
): ReaderRegionDrawGeometry {
    require(viewportSize.width > 0 && viewportSize.height > 0) { "Viewport dimensions must be positive" }
    require(renderedBitmapSize.width > 0 && renderedBitmapSize.height > 0) {
        "Rendered bitmap dimensions must be positive"
    }
    require(renderedSourceBounds.width > 0 && renderedSourceBounds.height > 0) {
        "Rendered source dimensions must be positive"
    }
    val scaleFactor = contentScale.computeScaleFactor(
        srcSize = Size(renderedBitmapSize.width.toFloat(), renderedBitmapSize.height.toFloat()),
        dstSize = Size(viewportSize.width.toFloat(), viewportSize.height.toFloat()),
    )
    val drawSize = IntSize(
        width = (renderedBitmapSize.width * scaleFactor.scaleX).roundToInt().coerceAtLeast(1),
        height = (renderedBitmapSize.height * scaleFactor.scaleY).roundToInt().coerceAtLeast(1),
    )
    return ReaderRegionDrawGeometry(
        viewportSize = viewportSize,
        drawOffset = alignment.align(
            size = drawSize,
            space = viewportSize,
            layoutDirection = LayoutDirection.Ltr,
        ),
        drawSize = drawSize,
        renderedSourceBounds = renderedSourceBounds,
    )
}

internal fun resolveReaderVisibleSourceBounds(
    geometry: ReaderRegionDrawGeometry,
    viewport: ReaderRegionViewport,
    zoomState: ZoomState,
): PixelBounds? {
    require(zoomState.scale > 0f && zoomState.scale.isFinite()) { "Zoom scale must be finite and positive" }
    val viewportBounds = viewport.resolveBounds(geometry.viewportSize) ?: return null
    val drawLeft = geometry.drawOffset.x.toFloat()
    val drawTop = geometry.drawOffset.y.toFloat()
    val drawWidth = geometry.drawSize.width.toFloat()
    val drawHeight = geometry.drawSize.height.toFloat()
    val centerX = geometry.viewportSize.width / 2f
    val centerY = geometry.viewportSize.height / 2f

    fun inverseX(screenX: Float): Float =
        (screenX - zoomState.offsetX - centerX) / zoomState.scale + centerX

    fun inverseY(screenY: Float): Float =
        (screenY - zoomState.offsetY - centerY) / zoomState.scale + centerY

    val visibleLeft = maxOf(drawLeft, inverseX(viewportBounds.x.toFloat()))
    val visibleTop = maxOf(drawTop, inverseY(viewportBounds.y.toFloat()))
    val visibleRight = minOf(drawLeft + drawWidth, inverseX((viewportBounds.x + viewportBounds.width).toFloat()))
    val visibleBottom = minOf(drawTop + drawHeight, inverseY((viewportBounds.y + viewportBounds.height).toFloat()))
    if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) return null

    val sourceBounds = geometry.renderedSourceBounds
    val sourceLeft = sourceBounds.x + floor((visibleLeft - drawLeft) * sourceBounds.width / drawWidth).toInt()
    val sourceTop = sourceBounds.y + floor((visibleTop - drawTop) * sourceBounds.height / drawHeight).toInt()
    val sourceRight = sourceBounds.x + ceil((visibleRight - drawLeft) * sourceBounds.width / drawWidth).toInt()
    val sourceBottom = sourceBounds.y + ceil((visibleBottom - drawTop) * sourceBounds.height / drawHeight).toInt()
    val left = sourceLeft.coerceIn(sourceBounds.x, sourceBounds.x + sourceBounds.width)
    val top = sourceTop.coerceIn(sourceBounds.y, sourceBounds.y + sourceBounds.height)
    val right = sourceRight.coerceIn(left, sourceBounds.x + sourceBounds.width)
    val bottom = sourceBottom.coerceIn(top, sourceBounds.y + sourceBounds.height)
    return if (right > left && bottom > top) PixelBounds(left, top, right - left, bottom - top) else null
}

internal fun resolveReaderRegionTileDrawBounds(
    geometry: ReaderRegionDrawGeometry,
    region: PixelBounds,
): PixelBounds {
    val sourceBounds = geometry.renderedSourceBounds
    require(region.width > 0 && region.height > 0) { "Region dimensions must be positive" }
    require(
        region.x >= sourceBounds.x &&
            region.y >= sourceBounds.y &&
            region.x.toLong() + region.width <= sourceBounds.x.toLong() + sourceBounds.width &&
            region.y.toLong() + region.height <= sourceBounds.y.toLong() + sourceBounds.height,
    ) { "Region must be contained by rendered source bounds" }

    fun drawX(sourceX: Int): Int = geometry.drawOffset.x +
        ((sourceX - sourceBounds.x).toDouble() * geometry.drawSize.width / sourceBounds.width).roundToInt()

    fun drawY(sourceY: Int): Int = geometry.drawOffset.y +
        ((sourceY - sourceBounds.y).toDouble() * geometry.drawSize.height / sourceBounds.height).roundToInt()

    val left = drawX(region.x)
    val top = drawY(region.y)
    val right = drawX(region.x + region.width)
    val bottom = drawY(region.y + region.height)
    return PixelBounds(left, top, right - left, bottom - top)
}

internal fun readerRegionTileIntersectsViewport(
    geometry: ReaderRegionDrawGeometry,
    tileDrawBounds: PixelBounds,
    viewport: ReaderRegionViewport,
    zoomState: ZoomState,
): Boolean {
    val viewportBounds = viewport.resolveBounds(geometry.viewportSize) ?: return false
    val centerX = geometry.viewportSize.width / 2f
    val centerY = geometry.viewportSize.height / 2f

    fun transformedX(localX: Int): Float =
        (localX - centerX) * zoomState.scale + centerX + zoomState.offsetX

    fun transformedY(localY: Int): Float =
        (localY - centerY) * zoomState.scale + centerY + zoomState.offsetY

    val left = transformedX(tileDrawBounds.x)
    val top = transformedY(tileDrawBounds.y)
    val right = transformedX(tileDrawBounds.x + tileDrawBounds.width)
    val bottom = transformedY(tileDrawBounds.y + tileDrawBounds.height)
    return right > viewportBounds.x &&
        left < viewportBounds.x + viewportBounds.width &&
        bottom > viewportBounds.y &&
        top < viewportBounds.y + viewportBounds.height
}

internal fun buildReaderRegionTileGrid(
    visibleSourceBounds: PixelBounds,
    renderedSourceBounds: PixelBounds,
    sourceTileExtent: Int,
): List<PixelBounds> {
    require(sourceTileExtent > 0) { "Source tile extent must be positive" }
    val visible = visibleSourceBounds.intersect(renderedSourceBounds) ?: return emptyList()
    val renderedRight = renderedSourceBounds.x.toLong() + renderedSourceBounds.width
    val renderedBottom = renderedSourceBounds.y.toLong() + renderedSourceBounds.height
    val visibleRight = visible.x.toLong() + visible.width
    val visibleBottom = visible.y.toLong() + visible.height
    val startColumn = Math.floorDiv(visible.x - renderedSourceBounds.x, sourceTileExtent)
    val startRow = Math.floorDiv(visible.y - renderedSourceBounds.y, sourceTileExtent)
    val tiles = mutableListOf<PixelBounds>()
    var row = startRow
    while (true) {
        val top = renderedSourceBounds.y.toLong() + row.toLong() * sourceTileExtent
        if (top >= visibleBottom || top >= renderedBottom) break
        var column = startColumn
        while (true) {
            val left = renderedSourceBounds.x.toLong() + column.toLong() * sourceTileExtent
            if (left >= visibleRight || left >= renderedRight) break
            val width = minOf(sourceTileExtent.toLong(), renderedRight - left).toInt()
            val height = minOf(sourceTileExtent.toLong(), renderedBottom - top).toInt()
            if (width > 0 && height > 0) tiles += PixelBounds(left.toInt(), top.toInt(), width, height)
            column++
        }
        row++
    }
    return tiles
}

private fun PixelBounds.intersect(other: PixelBounds): PixelBounds? {
    val left = maxOf(x, other.x)
    val top = maxOf(y, other.y)
    val right = minOf(x.toLong() + width, other.x.toLong() + other.width).toInt()
    val bottom = minOf(y.toLong() + height, other.y.toLong() + other.height).toInt()
    return if (right > left && bottom > top) PixelBounds(left, top, right - left, bottom - top) else null
}

private fun ReaderRegionViewport.resolveBounds(viewportSize: IntSize): PixelBounds? = when (this) {
    ReaderRegionViewport.Full -> PixelBounds(0, 0, viewportSize.width, viewportSize.height)
    ReaderRegionViewport.Hidden -> null
    is ReaderRegionViewport.Visible -> {
        val clampedTop = top.coerceIn(0, viewportSize.height)
        val clampedBottom = bottomExclusive.coerceIn(clampedTop, viewportSize.height)
        if (clampedBottom > clampedTop) {
            PixelBounds(0, clampedTop, viewportSize.width, clampedBottom - clampedTop)
        } else {
            null
        }
    }
}
