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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import mihon.desktop.reader.DesktopReaderPresentationImageOwner
import mihon.desktop.reader.DesktopReaderPresentationImageState
import mihon.desktop.reader.WebtoonSidePadding
import mihon.desktop.reader.ZoomState
import mihon.desktop.ui.reader.presentation.DisplaySlot
import mihon.desktop.ui.reader.presentation.DisplayUnit
import mihon.desktop.ui.reader.presentation.DisplayUnitId
import mihon.desktop.ui.reader.presentation.ReaderPresentationSnapshot
import mihon.desktop.ui.reader.presentation.WebtoonScrollAnchor
import mihon.desktop.ui.reader.presentation.WebtoonInitialViewportBootstrapGate
import mihon.desktop.ui.reader.presentation.WebtoonViewportUpdate
import mihon.desktop.ui.reader.presentation.WebtoonVisibleItem
import mihon.desktop.ui.reader.presentation.resolveWebtoonViewport
import mihon.desktop.ui.reader.presentation.restoreWebtoonAnchorIndex
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState

internal class WebtoonDisplayUnitCompositionIdentity

internal val WebtoonDisplayUnitCompositionIdentityKey =
    SemanticsPropertyKey<WebtoonDisplayUnitCompositionIdentity>("WebtoonDisplayUnitCompositionIdentity")
internal val WebtoonDisplayUnitIdKey = SemanticsPropertyKey<DisplayUnitId>("WebtoonDisplayUnitId")
internal val WebtoonDisplayUnitLoadStateKey =
    SemanticsPropertyKey<ReaderPageLoadState>("WebtoonDisplayUnitLoadState")
private val LocalWebtoonReaderRegionViewport = staticCompositionLocalOf<ReaderRegionViewport> {
    ReaderRegionViewport.Hidden
}

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
    presentationImageOwner: DesktopReaderPresentationImageOwner,
    onViewportChanged: (WebtoonViewportUpdate) -> Unit,
    onRetryPage: (ReaderPageId) -> Unit,
    onSpreadDetected: ((Int) -> Unit)? = null,
    onNextChapter: (() -> Unit)? = null,
    generation: Long = 0L,
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
            presentationImageOwner = presentationImageOwner,
            onRetryPage = onRetryPage,
            onSpreadDetected = onSpreadDetected,
            generation = generation,
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
    val initialViewportBootstrapGate = remember(currentPageId.chapterId) {
        WebtoonInitialViewportBootstrapGate()
    }
    val autoScrollPauseState = remember { WebtoonAutoScrollPauseState() }
    val measuredItemSizes = remember { mutableStateMapOf<DisplayUnitId, Int>() }
    var regionViewports by remember(listState, displayUnitIds) {
        mutableStateOf<Map<DisplayUnitId, ReaderRegionViewport.Visible>>(emptyMap())
    }
    var lastRestoredAnchor by remember { mutableStateOf<WebtoonAnchorRestoration?>(null) }
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    val isScrollInProgress = listState.isScrollInProgress
    val autoScrollLoopEnabled = autoScrollPauseState.loopEnabled(
        enabled = autoScroll,
        isUserDragging = isUserDragging,
        isScrollInProgress = isScrollInProgress,
    )

    LaunchedEffect(currentPageId, currentDisplayUnitId, initialAnchor, displayUnitIds) {
        initialViewportBootstrapGate.take(
            presentation = presentation,
            currentPageId = currentPageId,
            currentDisplayUnitId = currentDisplayUnitId,
            initialAnchor = initialAnchor,
        )?.let(onViewportChanged)
    }

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
        onViewportChanged = { update ->
            initialViewportBootstrapGate.onSettledViewport(update)
            onViewportChanged(update)
        },
    )

    LaunchedEffect(listState, displayUnitIds) {
        snapshotFlow {
            val layout = listState.layoutInfo
            buildMap<DisplayUnitId, ReaderRegionViewport.Visible> {
                layout.visibleItemsInfo.forEach { item ->
                    val displayUnitId = displayUnits.getOrNull(item.index)?.id ?: return@forEach
                    val visibleTop = maxOf(layout.viewportStartOffset, item.offset)
                    val visibleBottom = minOf(layout.viewportEndOffset, item.offset + item.size)
                    if (visibleBottom > visibleTop) {
                        put(
                            displayUnitId,
                            ReaderRegionViewport.Visible(
                                top = visibleTop - item.offset,
                                bottomExclusive = visibleBottom - item.offset,
                            ),
                        )
                    }
                }
            }
        }
            .distinctUntilChanged()
            .collect { current -> regionViewports = current }
    }

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
            CompositionLocalProvider(
                LocalWebtoonReaderRegionViewport provides
                    (regionViewports[unit.id] ?: ReaderRegionViewport.Hidden),
            ) {
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
    if (unit.transitionDirection != null) {
        val compositionIdentity = remember(unit.id) { WebtoonDisplayUnitCompositionIdentity() }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { size -> if (size.height > 0) onMeasured(size.height) }
                .semantics {
                    this[WebtoonDisplayUnitCompositionIdentityKey] = compositionIdentity
                    this[WebtoonDisplayUnitIdKey] = unit.id
                    this[ReaderDisplayUnitTransitionDirectionKey] = unit.transitionDirection
                },
            contentAlignment = Alignment.Center,
        ) {
            ReaderChapterTransitionItem(
                direction = unit.transitionDirection,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 128.dp),
            )
        }
        return
    }
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

internal fun shouldShowWebtoonPageContextMenu(
    hasContextMenuScope: Boolean,
    hasReadyImageAsset: Boolean,
): Boolean = hasContextMenuScope && hasReadyImageAsset

@Composable
private fun WebtoonPageItem(
    slot: DisplaySlot,
    cropBorders: Boolean,
    modifier: Modifier,
    contextMenuScope: CoroutineScope?,
    mangaTitle: String,
    chapterTitle: String,
    presentationImageOwner: DesktopReaderPresentationImageOwner,
    onRetryPage: (ReaderPageId) -> Unit,
    onSpreadDetected: ((Int) -> Unit)?,
    generation: Long,
) {
    val page = requireNotNull(slot.page)
    val pageIndex = page.id.sourcePageIndex
    val regionViewport = LocalWebtoonReaderRegionViewport.current
    val presentationImage = rememberReaderPresentationImage(
        owner = presentationImageOwner,
        page = page,
        generation = generation,
        splitHalf = slot.splitHalf,
        sourceBounds = slot.sourceBounds,
    )
    val readyState = presentationImage.state as? DesktopReaderPresentationImageState.Ready
    val asset = readyState?.asset
    val renderedImage = rememberReaderPresentationRenderedImage(presentationImage, cropBorders)

    LaunchedEffect(asset, slot.splitHalf, onSpreadDetected) {
        if (slot.splitHalf != null || onSpreadDetected == null) return@LaunchedEffect
        if (asset != null && asset.sourceWidth > asset.sourceHeight) onSpreadDetected(pageIndex)
    }

    val pageContent: @Composable () -> Unit = {
        if (presentationImage.state is DesktopReaderPresentationImageState.Failed) {
            ReaderPageRetryContent(
                onRetry = { onRetryPage(page.id) },
                modifier = modifier.aspectRatio(2f / 3f),
            )
        } else if (renderedImage != null) {
            Box {
                Image(
                    bitmap = renderedImage.bitmap,
                    contentDescription = null,
                    modifier = modifier.observeReaderPageDraw(renderedImage.acknowledgeDraw),
                    contentScale = ContentScale.FillWidth,
                )
                ReaderRegionTileLayer(
                    presentationImage = presentationImage,
                    renderedImage = renderedImage,
                    contentScale = ContentScale.FillWidth,
                    alignment = Alignment.Center,
                    zoomState = ZoomState(),
                    viewport = regionViewport,
                    modifier = Modifier.matchParentSize(),
                )
            }
        } else if (presentationImage.state !is DesktopReaderPresentationImageState.Closed) {
            Box(
                modifier = modifier.aspectRatio(2f / 3f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        } else {
            Box(modifier = modifier.aspectRatio(2f / 3f))
        }
    }

    val scope = contextMenuScope
    if (
        shouldShowWebtoonPageContextMenu(
            hasContextMenuScope = scope != null,
            hasReadyImageAsset = asset != null,
        )
    ) {
        val binding = readerPageContextMenuBinding(presentationImage)
        PageContextMenu(
            imageLeaseProvider = binding.imageLeaseProvider,
            mangaTitle = mangaTitle,
            chapterTitle = chapterTitle,
            pageIndex = pageIndex,
            scope = requireNotNull(scope),
            onSetAsCover = null,
            splitHalf = binding.splitHalf,
            sourceBounds = binding.sourceBounds,
            content = pageContent,
        )
    } else {
        pageContent()
    }
}
