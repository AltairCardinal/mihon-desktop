package mihon.desktop.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import coil3.BitmapImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mihon.desktop.reader.PagePreloader
import mihon.desktop.reader.WebtoonSidePadding
import mihon.desktop.image.LocalDesktopSourceImageId
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.Canvas as SkiaCanvas
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect as SkiaRect
import androidx.compose.ui.graphics.asComposeImageBitmap

/**
 * Vertical-scroll (webtoon) viewer.  All pages are stacked in a [LazyColumn]
 * and the user scrolls continuously through the chapter — no page swipes.
 *
 * @param pageUrls          Ordered list of page image URLs for the chapter.
 * @param cropBorders       When true, white borders are trimmed from each page.
 * @param sidePadding       Amount of horizontal padding on each side.
 * @param autoScroll        When true, the viewer scrolls automatically.
 * @param autoScrollSpeed   Speed preset for auto-scroll.
 * @param onNextChapter     Called when auto-scroll reaches the bottom.
 */
@Composable
internal fun WebtoonViewer(
    pageUrls: List<String>,
    cropBorders: Boolean = false,
    sidePadding: WebtoonSidePadding = WebtoonSidePadding.NONE,
    autoScroll: Boolean = false,
    autoScrollSpeed: WebtoonAutoScrollSpeed = WebtoonAutoScrollSpeed.Normal,
    contextMenuScope: CoroutineScope? = null,
    mangaTitle: String = "",
    chapterTitle: String = "",
    preloader: PagePreloader? = null,
    onNextChapter: (() -> Unit)? = null,
) {
    val paddingFraction = sidePadding.ratio
    val listState = rememberLazyListState()
    val autoScrollGate = remember { WebtoonAutoScrollGate() }

    // Auto-scroll: advances by pixelsPerSecond, triggers onNextChapter at bottom
    LaunchedEffect(autoScroll, autoScrollSpeed) {
        if (!autoScroll) {
            autoScrollGate.reset()
            return@LaunchedEffect
        }
        val tickMs = 16L // ~60 fps
        val pixelsPerTick = autoScrollSpeed.pixelsPerSecond * tickMs / 1000f
        while (true) {
            delay(tickMs)
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
            when (
                autoScrollGate.action(
                    enabled = autoScroll,
                    lastVisibleIndex = lastVisible?.index,
                    totalItemsCount = layoutInfo.totalItemsCount,
                    lastVisibleBottom = lastVisible?.let { it.offset + it.size },
                    viewportEnd = layoutInfo.viewportEndOffset,
                )
            ) {
                WebtoonAutoScrollAction.Idle -> Unit
                WebtoonAutoScrollAction.Scroll -> listState.scroll { scrollBy(pixelsPerTick) }
                WebtoonAutoScrollAction.NextChapter -> onNextChapter?.invoke()
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(pageUrls) { pageIndex, url ->
            val itemModifier = if (paddingFraction > 0f) {
                Modifier.fillMaxWidth(1f - 2f * paddingFraction)
            } else {
                Modifier.fillMaxWidth()
            }
            WebtoonPageItem(
                url = url,
                cropBorders = cropBorders,
                modifier = itemModifier,
                contextMenuScope = contextMenuScope,
                mangaTitle = mangaTitle,
                chapterTitle = chapterTitle,
                pageIndex = pageIndex,
                preloader = preloader,
            )
        }
    }
}

internal enum class WebtoonAutoScrollAction { Idle, Scroll, NextChapter }

/** Prevents the 60 fps auto-scroll loop from repeatedly requesting the same adjacent chapter. */
internal class WebtoonAutoScrollGate {
    private var requestedAtBottom = false

    fun action(
        enabled: Boolean,
        lastVisibleIndex: Int?,
        totalItemsCount: Int,
        lastVisibleBottom: Int?,
        viewportEnd: Int,
    ): WebtoonAutoScrollAction = when (
        val action = webtoonAutoScrollAction(
            enabled = enabled,
            lastVisibleIndex = lastVisibleIndex,
            totalItemsCount = totalItemsCount,
            lastVisibleBottom = lastVisibleBottom,
            viewportEnd = viewportEnd,
        )
    ) {
        WebtoonAutoScrollAction.NextChapter -> {
            if (requestedAtBottom) {
                WebtoonAutoScrollAction.Idle
            } else {
                requestedAtBottom = true
                action
            }
        }
        WebtoonAutoScrollAction.Scroll -> {
            requestedAtBottom = false
            action
        }
        WebtoonAutoScrollAction.Idle -> {
            requestedAtBottom = false
            action
        }
    }

    fun reset() {
        requestedAtBottom = false
    }
}

internal fun webtoonAutoScrollAction(
    enabled: Boolean,
    lastVisibleIndex: Int?,
    totalItemsCount: Int,
    lastVisibleBottom: Int?,
    viewportEnd: Int,
): WebtoonAutoScrollAction {
    if (!enabled) return WebtoonAutoScrollAction.Idle
    val atBottom =
        lastVisibleIndex != null &&
            totalItemsCount > 0 &&
            lastVisibleIndex == totalItemsCount - 1 &&
            lastVisibleBottom != null &&
            lastVisibleBottom <= viewportEnd
    return if (atBottom) WebtoonAutoScrollAction.NextChapter else WebtoonAutoScrollAction.Scroll
}

internal fun webtoonPageContextMenuLabels(): List<String> =
    pageContextMenuLabels(includeSetAsCover = false)

internal fun shouldShowWebtoonPageContextMenu(
    hasContextMenuScope: Boolean,
    pageUrl: String,
): Boolean = hasContextMenuScope && pageUrl.isNotBlank()

/**
 * A single item in the webtoon LazyColumn.
 * Shows a loading indicator while the image is loading,
 * and handles optional border cropping.
 */
@Composable
private fun WebtoonPageItem(
    url: String,
    cropBorders: Boolean,
    modifier: Modifier = Modifier,
    contextMenuScope: CoroutineScope? = null,
    mangaTitle: String = "",
    chapterTitle: String = "",
    pageIndex: Int = 0,
    preloader: PagePreloader? = null,
) {
    var croppedBitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    val preloadRevision = if (preloader != null) {
        preloader.cacheRevision.collectAsState().value
    } else {
        0L
    }
    val preloadedBitmap = remember(url, pageIndex, preloader, preloadRevision) { preloader?.get(pageIndex) }

    val sourceId = LocalDesktopSourceImageId.current
    val painter = rememberAsyncImagePainter(readerPagePainterModel(url, preloadedBitmap, sourceId))
    val painterState by painter.state.collectAsState()

    LaunchedEffect(painterState, preloadedBitmap, cropBorders) {
        if (cropBorders && preloadedBitmap != null) {
            croppedBitmap = withContext(Dispatchers.Default) {
                cropBordersWebtoon(preloadedBitmap.asSkiaBitmap())
            }
            return@LaunchedEffect
        }
        val s = painterState
        if (cropBorders && s is AsyncImagePainter.State.Success) {
            croppedBitmap = withContext(Dispatchers.Default) {
                val skiaBitmap = (s.result.image as? BitmapImage)?.bitmap ?: return@withContext null
                cropBordersWebtoon(skiaBitmap)
            }
        } else if (!cropBorders) {
            croppedBitmap = null
        }
    }

    val pageContent: @Composable () -> Unit = {
        val bitmap = croppedBitmap ?: preloadedBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = modifier,
                contentScale = ContentScale.FillWidth,
            )
        } else {
            when (painterState) {
                is AsyncImagePainter.State.Loading, is AsyncImagePainter.State.Empty -> {
                    Box(
                        modifier = modifier.aspectRatio(2f / 3f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
                else -> {
                    Image(
                        painter = painter,
                        contentDescription = null,
                        modifier = modifier,
                        contentScale = ContentScale.FillWidth,
                    )
                }
            }
        }
    }

    val scope = contextMenuScope
    if (scope != null && shouldShowWebtoonPageContextMenu(hasContextMenuScope = true, pageUrl = url)) {
        PageContextMenu(
            pageUrl = url,
            mangaTitle = mangaTitle,
            chapterTitle = chapterTitle,
            pageIndex = pageIndex,
            scope = scope,
            onSetAsCover = null,
            content = pageContent,
        )
    } else {
        pageContent()
    }
}

/**
 * Scans [src] for white borders using Skia pixel access and returns a cropped [ImageBitmap],
 * or null if no significant border was found.
 */
private fun cropBordersWebtoon(src: SkiaBitmap): ImageBitmap? {
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

    val cw = right - left
    val ch = bottom - top
    val dst = SkiaBitmap()
    dst.allocN32Pixels(cw, ch)
    val canvas = SkiaCanvas(dst)
    canvas.drawImageRect(
        SkiaImage.makeFromBitmap(src),
        SkiaRect.makeLTRB(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()),
        SkiaRect.makeWH(cw.toFloat(), ch.toFloat()),
    )
    return dst.asComposeImageBitmap()
}
