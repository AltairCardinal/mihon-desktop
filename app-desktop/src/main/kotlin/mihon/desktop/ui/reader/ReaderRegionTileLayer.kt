package mihon.desktop.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import mihon.desktop.reader.DesktopReaderRegionPresentationHolder
import mihon.desktop.reader.ZoomState
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.ReaderPageDecodeKey

@Composable
internal fun ReaderRegionTileLayer(
    presentationImage: ReaderPresentationImage,
    renderedImage: ReaderPresentationRenderedImage,
    contentScale: ContentScale,
    alignment: Alignment,
    zoomState: ZoomState,
    viewport: ReaderRegionViewport = ReaderRegionViewport.Full,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val holder = presentationImage.regionHolder ?: return
    val regionState = presentationImage.regionState
    var viewportSize by remember(holder) { mutableStateOf(IntSize.Zero) }
    val renderedBitmapSize = IntSize(renderedImage.bitmap.width, renderedImage.bitmap.height)
    val renderedSourceBounds = renderedImage.renderedSourceBounds
    val geometry = remember(
        viewportSize,
        renderedBitmapSize,
        renderedSourceBounds,
        contentScale,
        alignment,
    ) {
        if (viewportSize.width <= 0 || viewportSize.height <= 0) {
            null
        } else {
            resolveReaderRegionDrawGeometry(
                viewportSize = viewportSize,
                renderedBitmapSize = renderedBitmapSize,
                renderedSourceBounds = renderedSourceBounds,
                contentScale = contentScale,
                alignment = alignment,
            )
        }
    }
    val requestedKeys = remember(
        holder,
        regionState.regionTilesEnabled,
        geometry,
        viewport,
        zoomState,
    ) {
        if (!regionState.regionTilesEnabled || geometry == null) {
            emptySet()
        } else {
            val sourcePixelsPerScreenPixel = renderedSourceBounds.width /
                (geometry.drawSize.width * zoomState.scale)
            val level = resolveReaderRegionTileLevel(
                sourcePixelsPerScreenPixel = sourcePixelsPerScreenPixel,
                tileOutputExtent = TILE_OUTPUT_EXTENT,
            )
            val visible = resolveReaderVisibleSourceBounds(
                geometry = geometry,
                viewport = viewport,
                zoomState = zoomState,
            )
            visible?.let {
                buildReaderRegionTileGrid(
                    visibleSourceBounds = it,
                    renderedSourceBounds = renderedSourceBounds,
                    sourceTileExtent = level.sourceTileExtent,
                )
                    .filter { region ->
                        readerRegionTileIntersectsViewport(
                            geometry = geometry,
                            tileDrawBounds = resolveReaderRegionTileDrawBounds(geometry, region),
                            viewport = viewport,
                            zoomState = zoomState,
                        )
                    }
                    .mapTo(linkedSetOf()) { region ->
                        ReaderPageDecodeKey(
                            contentKey = presentationImage.holder.decodeKey.contentKey,
                            purpose = PageDecodePurpose.REGION_TILE,
                            maxWidth = ceil(region.width.toDouble() / level.sampleSize).toInt(),
                            maxHeight = ceil(region.height.toDouble() / level.sampleSize).toInt(),
                            region = region,
                        )
                    }
            } ?: emptySet()
        }
    }

    LaunchedEffect(holder, requestedKeys) {
        holder.updateViewportTiles(requestedKeys)
    }
    DisposableEffect(holder) {
        onDispose { holder.updateViewportTiles(emptySet()) }
    }

    val layerModifier = modifier
        .onSizeChanged { viewportSize = it }
        .graphicsLayer(
            scaleX = zoomState.scale,
            scaleY = zoomState.scale,
            translationX = zoomState.offsetX,
            translationY = zoomState.offsetY,
        )
    Canvas(layerModifier) {}
    regionState.readyTiles.forEach { (tileKey, _) ->
        if (tileKey !in requestedKeys || geometry == null) return@forEach
        val region = tileKey.region ?: return@forEach
        key(tileKey) {
            ReaderRegionTile(
                holder = holder,
                key = tileKey,
                drawBounds = resolveReaderRegionTileDrawBounds(geometry, region),
                modifier = layerModifier,
            )
        }
    }
}

@Composable
private fun ReaderRegionTile(
    holder: DesktopReaderRegionPresentationHolder,
    key: ReaderPageDecodeKey,
    drawBounds: PixelBounds,
    modifier: Modifier,
) {
    val lease = remember(holder, key) { holder.retainReadyTileForRender(key) } ?: return
    DisposableEffect(lease) {
        onDispose(lease::close)
    }
    Canvas(modifier) {
        drawImage(
            image = lease.asset.bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(lease.asset.bitmap.width, lease.asset.bitmap.height),
            dstOffset = IntOffset(drawBounds.x, drawBounds.y),
            dstSize = IntSize(drawBounds.width, drawBounds.height),
        )
    }
}

private const val TILE_OUTPUT_EXTENT = 2_048
