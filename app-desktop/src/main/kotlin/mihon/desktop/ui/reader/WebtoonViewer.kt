package mihon.desktop.ui.reader

import tachiyomi.i18n.MR

import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.BitmapImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import mihon.desktop.reader.PagePreloader
import mihon.desktop.reader.WebtoonSidePadding
import mihon.desktop.ui.reader.presentation.DisplaySlot
import mihon.desktop.ui.reader.presentation.DisplayUnit
import mihon.desktop.ui.reader.presentation.DisplayUnitId
import mihon.desktop.ui.reader.presentation.ReaderPresentationSnapshot
import mihon.desktop.ui.reader.presentation.WebtoonScrollAnchor
import mihon.desktop.ui.reader.presentation.WebtoonViewportUpdate
import mihon.desktop.ui.reader.presentation.WebtoonVisibleItem
import mihon.desktop.ui.reader.presentation.resolveWebtoonViewport
import mihon.desktop.ui.reader.presentation.restoreWebtoonAnchorIndex
import mihon.desktop.image.LocalDesktopSourceImageId
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState

internal class WebtoonDisplayUnitCompositionIdentity

internal val WebtoonDisplayUnitCompositionIdentityKey =
    SemanticsPropertyKey<WebtoonDisplayUnitCompositionIdentity>("WebtoonDisplayUnitCompositionIdentity")
internal val WebtoonDisplayUnitIdKey = SemanticsPropertyKey<DisplayUnitId>("WebtoonDisplayUnitId")
internal val WebtoonDisplayUnitLoadStateKey =
    SemanticsPropertyKey<ReaderPageLoadState>("WebtoonDisplayUnitLoadState")

@Composable
internal fun WebtoonViewer(
    presentation: ReaderPresentationSnapshot,
    currentPageId: ReaderPageId,
    currentDisplayUnitId: DisplayUnitId?,
    initialAnchor: WebtoonScrollAnchor?,
    cropBorders: Boolean = false,
    sidePadding: WebtoonSidePadding = WebtoonSidePadding.NONE,
    autoScroll: Boolean = false,
    autoScrollSpeed: WebtoonAutoScrollSpeed = WebtoonAutoScrollSpeed.Normal,
    contextMenuScope: CoroutineScope? = null,
    mangaTitle: String = "",
    chapterTitle: String = "",
    preloader: PagePreloader? = null,
    onViewportChanged: (WebtoonViewportUpdate) -> Unit,
    onRetryPage: (ReaderPageId) -> Unit,
    onSpreadDetected: ((Int) -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
) {
    WebtoonDisplayUnitList(
        presentation = presentation,
        currentPageId = currentPageId,
        currentDisplayUnitId = currentDisplayUnitId,
        initialAnchor = initialAnchor,
        sidePadding = sidePadding,
        autoScroll = autoScroll,
        autoScrollSpeed = autoScrollSpeed,
        onViewportChanged = onViewportChanged,
        onRetryPage = onRetryPage,
        onNextChapter = onNextChapter,
    ) { slot, modifier ->
        WebtoonPageItem(
            slot = slot,
            cropBorders = cropBorders,
            modifier = modifier,
            contextMenuScope = contextMenuScope,
            mangaTitle = mangaTitle,
            chapterTitle = chapterTitle,
            preloader = preloader,
            onSpreadDetected = onSpreadDetected,
        )
    }
}

@Composable
internal fun WebtoonDisplayUnitList(
    presentation: ReaderPresentationSnapshot,
    currentPageId: ReaderPageId,
    currentDisplayUnitId: DisplayUnitId?,
    initialAnchor: WebtoonScrollAnchor?,
    sidePadding: WebtoonSidePadding,
    autoScroll: Boolean,
    autoScrollSpeed: WebtoonAutoScrollSpeed,
    listStateOverride: LazyListState? = null,
    onViewportChanged: (WebtoonViewportUpdate) -> Unit,
    onRetryPage: (ReaderPageId) -> Unit,
    onNextChapter: (() -> Unit)? = null,
    readyContent: @Composable (DisplaySlot, Modifier) -> Unit,
) {
    val displayUnits = presentation.displayUnits
    if (displayUnits.isEmpty()) return

    val displayUnitIds = displayUnits.map(DisplayUnit::id)
    val exactInitialAnchorIndex = initialAnchor?.displayUnitId
        ?.let { anchorId -> displayUnits.indexOfFirst { it.id == anchorId } }
        ?.takeIf { it >= 0 }
    val initialAnchorIndex = exactInitialAnchorIndex
        ?: initialAnchor?.let(presentation::restoreWebtoonAnchorIndex)?.takeIf { it >= 0 }
    val initialIndex = initialAnchorIndex
        ?: presentation.firstDisplayUnitIndex(currentPageId).coerceAtLeast(0)
    val rememberedListState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialIndex.coerceIn(displayUnits.indices),
        initialFirstVisibleItemScrollOffset = initialAnchor?.scrollOffset?.takeIf { exactInitialAnchorIndex != null } ?: 0,
    )
    val listState = listStateOverride ?: rememberedListState
    val autoScrollGate = remember { WebtoonAutoScrollGate() }
    val autoScrollPauseState = remember { WebtoonAutoScrollPauseState() }
    val measuredItemSizes = remember { mutableStateMapOf<DisplayUnitId, Int>() }
    var lastRestoredAnchor by remember { mutableStateOf<WebtoonAnchorRestoration?>(null) }
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    val isScrollInProgress = listState.isScrollInProgress
    val autoScrollLoopEnabled = autoScrollPauseState.loopEnabled(
        enabled = autoScroll,
        isUserDragging = isUserDragging,
        isScrollInProgress = isScrollInProgress,
    )

    val anchorTargetIndex = initialAnchor
        ?.let(presentation::restoreWebtoonAnchorIndex)
        ?.takeIf { it >= 0 }
    val anchorTargetUnitId = anchorTargetIndex?.let { displayUnits[it].id }
    val anchorTargetSize = anchorTargetUnitId?.let(measuredItemSizes::get)
    val anchorIsExact = initialAnchor != null && anchorTargetUnitId == initialAnchor.displayUnitId
    val anchorRestoration = if (
        currentDisplayUnitId != null &&
        initialAnchor != null &&
        anchorTargetIndex != null &&
        anchorTargetUnitId != null &&
        anchorTargetSize != null &&
        (
            !anchorIsExact ||
                initialAnchor.itemSize?.let { it != anchorTargetSize } == true ||
                initialAnchor.scrollOffset >= anchorTargetSize
            )
    ) {
        WebtoonAnchorRestoration(
            source = initialAnchor,
            targetIndex = anchorTargetIndex,
            targetDisplayUnitId = anchorTargetUnitId,
            targetItemSize = anchorTargetSize,
        )
    } else {
        null
    }
    val awaitsAnchorMeasurement =
        currentDisplayUnitId != null && initialAnchor != null && anchorTargetIndex != null && anchorTargetSize == null
    val restoresAnchor = anchorRestoration != null && anchorRestoration != lastRestoredAnchor

    LaunchedEffect(
        currentPageId,
        currentDisplayUnitId,
        initialAnchor,
        displayUnitIds,
        anchorTargetIndex,
        anchorTargetSize,
        anchorRestoration,
        isUserDragging,
    ) {
        when {
            currentDisplayUnitId == null -> {
                val target = presentation.firstDisplayUnitIndex(currentPageId).coerceAtLeast(0)
                listState.scrollToItem(target.coerceIn(displayUnits.indices))
            }
            initialAnchor != null && anchorTargetIndex != null && anchorTargetSize == null && !anchorIsExact -> {
                snapshotFlow { listState.isScrollInProgress }.first { !it }
                listState.scrollToItem(anchorTargetIndex, 0)
            }
            anchorRestoration != null && anchorRestoration != lastRestoredAnchor -> {
                snapshotFlow { listState.isScrollInProgress }.first { !it }
                listState.scrollToItem(
                    anchorRestoration.targetIndex,
                    anchorRestoration.source.restoreOffsetFor(anchorRestoration.targetItemSize),
                )
                lastRestoredAnchor = anchorRestoration
            }
        }
    }

    WebtoonSettledViewportReporter(
        presentation = presentation,
        listState = listState,
        enabled = !awaitsAnchorMeasurement && !restoresAnchor,
        onViewportChanged = onViewportChanged,
    )

    LaunchedEffect(autoScroll, autoScrollSpeed, autoScrollLoopEnabled) {
        if (!autoScroll) {
            autoScrollGate.reset()
            return@LaunchedEffect
        }
        if (!autoScrollLoopEnabled) return@LaunchedEffect

        val tickMs = 16L
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
        items(
            items = displayUnits,
            key = DisplayUnit::id,
        ) { unit ->
            WebtoonDisplayUnitContainer(
                unit = unit,
                sidePadding = sidePadding,
                onMeasured = { itemSize ->
                    if (measuredItemSizes[unit.id] != itemSize) measuredItemSizes[unit.id] = itemSize
                },
                onRetry = onRetryPage,
                readyContent = readyContent,
            )
        }
    }
}

private data class WebtoonAnchorRestoration(
    val source: WebtoonScrollAnchor,
    val targetIndex: Int,
    val targetDisplayUnitId: DisplayUnitId,
    val targetItemSize: Int,
)

@Composable
private fun WebtoonSettledViewportReporter(
    presentation: ReaderPresentationSnapshot,
    listState: LazyListState,
    enabled: Boolean,
    onViewportChanged: (WebtoonViewportUpdate) -> Unit,
) {
    val currentCallback by rememberUpdatedState(onViewportChanged)
    val displayUnitIds = presentation.displayUnits.map(DisplayUnit::id)
    LaunchedEffect(displayUnitIds, listState, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            if (listState.isScrollInProgress) {
                null
            } else {
                val layout = listState.layoutInfo
                presentation.resolveWebtoonViewport(
                    visibleItems = layout.visibleItemsInfo.map { item ->
                        WebtoonVisibleItem(index = item.index, offset = item.offset, size = item.size)
                    },
                    viewportStartOffset = layout.viewportStartOffset,
                    viewportEndOffset = layout.viewportEndOffset,
                )
            }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { update -> currentCallback(update) }
    }
}

@Composable
internal fun WebtoonDisplayUnitContainer(
    unit: DisplayUnit,
    sidePadding: WebtoonSidePadding,
    onMeasured: (Int) -> Unit = {},
    onRetry: (ReaderPageId) -> Unit,
    readyContent: @Composable (DisplaySlot, Modifier) -> Unit,
) {
    val slot = unit.slots.single()
    val page = requireNotNull(slot.page)
    val paddingFraction = sidePadding.ratio
    val widthModifier = if (paddingFraction > 0f) {
        Modifier.fillMaxWidth(1f - 2f * paddingFraction)
    } else {
        Modifier.fillMaxWidth()
    }
    val compositionIdentity = remember(unit.id) { WebtoonDisplayUnitCompositionIdentity() }
    val itemModifier = widthModifier
        .onSizeChanged { size -> if (size.height > 0) onMeasured(size.height) }
        .semantics {
            this[WebtoonDisplayUnitCompositionIdentityKey] = compositionIdentity
            this[WebtoonDisplayUnitIdKey] = unit.id
            this[WebtoonDisplayUnitLoadStateKey] = page.loadState
        }

    when (page.loadState) {
        ReaderPageLoadState.Queued,
        ReaderPageLoadState.ResolvingImage,
        is ReaderPageLoadState.Downloading,
        -> Box(
            modifier = itemModifier.aspectRatio(2f / 3f),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Color.White)
        }
        ReaderPageLoadState.Ready -> readyContent(slot, itemModifier)
        is ReaderPageLoadState.Error -> Box(
            modifier = itemModifier.aspectRatio(2f / 3f),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(MR.strings.desktop_ui_failed_to_load_pages.localized(), color = Color.White)
                Button(
                    onClick = { onRetry(page.id) },
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text(MR.strings.action_retry.localized())
                }
            }
        }
    }
}

internal enum class WebtoonAutoScrollAction { Idle, Scroll, NextChapter }

internal class WebtoonAutoScrollPauseState {
    private var userGestureActive = false

    fun loopEnabled(
        enabled: Boolean,
        isUserDragging: Boolean,
        isScrollInProgress: Boolean,
    ): Boolean {
        if (isUserDragging) {
            userGestureActive = true
        } else if (userGestureActive && !isScrollInProgress) {
            userGestureActive = false
        }
        return enabled && !userGestureActive
    }
}

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

@Composable
private fun WebtoonPageItem(
    slot: DisplaySlot,
    cropBorders: Boolean,
    modifier: Modifier,
    contextMenuScope: CoroutineScope?,
    mangaTitle: String,
    chapterTitle: String,
    preloader: PagePreloader?,
    onSpreadDetected: ((Int) -> Unit)?,
) {
    val page = requireNotNull(slot.page)
    val pageIndex = page.id.sourcePageIndex
    val url = page.encodedContentUri()
    val preloadRevision = if (preloader != null) {
        preloader.cacheRevision.collectAsState().value
    } else {
        0L
    }
    val preloadedPage = remember(url, pageIndex, preloader, preloadRevision) {
        preloader?.getCachedPage(pageIndex)
    }
    val transformedPreloadedBitmap by produceState<ImageBitmap?>(
        initialValue = preloadedPage?.bitmap.takeIf {
            slot.splitHalf == null && slot.sourceBounds == null && !cropBorders
        },
        url,
        preloadedPage,
        slot.splitHalf,
        slot.sourceBounds,
        cropBorders,
    ) {
        value = preloadedPage?.let { cachedPage ->
            withContext(Dispatchers.Default) {
                transformCachedPageBitmap(cachedPage, slot.splitHalf, slot.sourceBounds, cropBorders)
            }
        }
    }

    val sourceId = LocalDesktopSourceImageId.current
    val painter = rememberAsyncImagePainter(readerPagePainterModel(url, preloadedPage?.bitmap, sourceId))
    val painterState by painter.state.collectAsState()
    val transformedPainterBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        painterState,
        slot.splitHalf,
        slot.sourceBounds,
        cropBorders,
    ) {
        val success = painterState as? AsyncImagePainter.State.Success ?: return@produceState
        val bitmap = (success.result.image as? BitmapImage)?.bitmap ?: return@produceState
        value = withContext(Dispatchers.Default) {
            transformCachedPageBitmap(
                bitmap = bitmap.asComposeImageBitmap(),
                splitHalf = slot.splitHalf,
                sourceBounds = slot.sourceBounds,
                cropBorders = cropBorders,
            )
        }
    }

    LaunchedEffect(preloadedPage, painterState, slot.splitHalf, onSpreadDetected) {
        if (slot.splitHalf != null || onSpreadDetected == null) return@LaunchedEffect
        val preloadedIsWide = preloadedPage?.let { it.sourceWidth > it.sourceHeight } == true
        val painterIsWide = (painterState as? AsyncImagePainter.State.Success)?.result?.image?.let {
            it.width > it.height
        } == true
        if (preloadedIsWide || painterIsWide) onSpreadDetected(pageIndex)
    }

    val pageContent: @Composable () -> Unit = {
        val bitmap = transformedPreloadedBitmap ?: transformedPainterBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = modifier,
                contentScale = ContentScale.FillWidth,
            )
        } else {
            when (painterState) {
                is AsyncImagePainter.State.Loading,
                is AsyncImagePainter.State.Empty,
                -> Box(
                    modifier = modifier.aspectRatio(2f / 3f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
                else -> Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = modifier,
                    contentScale = ContentScale.FillWidth,
                )
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
            splitHalf = slot.splitHalf,
            sourceBounds = slot.sourceBounds,
            content = pageContent,
        )
    } else {
        pageContent()
    }
}
