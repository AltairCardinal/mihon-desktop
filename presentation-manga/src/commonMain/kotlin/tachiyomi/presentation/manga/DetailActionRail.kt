package tachiyomi.presentation.manga

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import tachiyomi.domain.manga.model.MangaDetailAction
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.time.TimeSource

@Composable
internal fun DetailActionRail(
    mangaId: Long,
    primary: List<DetailActionItem>,
    all: List<DetailActionItem>,
    platform: DetailActionPlatform,
    labels: DetailActionLabels,
    onCustomize: () -> Unit,
    onReorder: (List<MangaDetailAction>) -> Unit,
    modifier: Modifier,
    preview: Boolean = false,
) {
    val actionTag = if (preview) "manga-action-preview" else "manga-action"
    val ids = primary.map { it.id }
    val motion = remember(mangaId, all.map { it.id }.toSet(), preview) { DetailActionMotionState() }
    val clock = remember { TimeSource.Monotonic.markNow() }
    val density = LocalDensity.current
    val desktop = platform == DetailActionPlatform.Desktop
    val twoLines = with(density) { (MaterialTheme.typography.labelSmall.lineHeight * 2).toDp() }
    val cellHeight = maxOf(72.dp, 46.dp + twoLines)
    val saved = primary + all.filterNot { it.id in ids }
    val currentSaved by rememberUpdatedState(saved)
    val saveOrder by rememberUpdatedState(onReorder)
    var reorderMoved by remember(mangaId) { mutableStateOf(false) }
    var longPressActive by remember(mangaId) { mutableStateOf(false) }
    var draft by remember(mangaId) { mutableStateOf<List<MangaDetailAction>?>(null) }
    var dragged by remember(mangaId) { mutableStateOf<MangaDetailAction?>(null) }
    var pointerX by remember(mangaId) { mutableFloatStateOf(0f) }
    var grabX by remember(mangaId) { mutableFloatStateOf(0f) }
    var failed by remember(mangaId) { mutableStateOf(false) }
    val ordered = draft?.mapNotNull { id -> saved.firstOrNull { it.id == id } } ?: saved
    val wheelInput = if (desktop) {
        Modifier.pointerInput(motion) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll || longPressActive) continue
                    val keys = event.keyboardModifiers
                    if (keys.isCtrlPressed || keys.isAltPressed || keys.isShiftPressed || keys.isMetaPressed) continue
                    val delta = event.changes.firstOrNull()?.scrollDelta ?: continue
                    val amount = if (abs(delta.x) > abs(delta.y)) delta.x else delta.y
                    if (amount == 0f) continue
                    motion.wheel(amount, clock.elapsedNow().inWholeMilliseconds)
                    event.changes.forEach { it.consume() }
                }
            }
        }
    } else {
        Modifier
    }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val arrowWidth = if (desktop) minOf(24.dp, maxWidth / 8) else 0.dp
        val arrowGap = if (desktop) minOf(6.dp, maxWidth / 32) else 0.dp
        val gutters = (arrowWidth + arrowGap) * 2
        val capacity = primary.size.coerceAtLeast(1)
        val width = minOf(maxWidth - gutters, (capacity * 78 + 14).dp)
        val slotPx = with(density) { ((width - 2.dp).roundToPx() / capacity).coerceAtLeast(1).toFloat() }
        val slot = with(density) { slotPx.toDp() }
        val businessWidth = slot * capacity
        val railWidth = businessWidth + 2.dp
        val trackSize = ordered.size + if (preview) 0 else 1
        val input = if (desktop) wheelInput else Modifier.mobileRailDrag(motion, slotPx, capacity, trackSize)
        LaunchedEffect(slotPx, trackSize, capacity) {
            motion.configure(slotPx, slotPx * capacity, trackSize)
        }
        Column(
            Modifier.width(railWidth + gutters)
                .testTag(if (preview) "manga-action-preview" else "manga-actions")
                .semantics {
                    if (!preview) {
                        customActions = listOf(
                            CustomAccessibilityAction(labels.customize) {
                                onCustomize()
                                true
                            },
                        )
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val edgeEffect = if (!desktop && !preview) Modifier.edgePullFeedback(motion) else Modifier
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(arrowGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (desktop) {
                    RailPageArrow(
                        direction = -1,
                        label = labels.previousPage,
                        enabled = motion.canPageBack && !longPressActive,
                        width = arrowWidth,
                        cellHeight = cellHeight,
                        tag = "$actionTag-page-previous",
                    ) { motion.turnPage(-1) }
                }
                Box(
                    Modifier.width(railWidth).clip(RoundedCornerShape(19.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .then(edgeEffect)
                        .padding(horizontal = 1.dp, vertical = 6.dp),
                ) {
                    fun moveDragged() {
                        val action = dragged ?: return
                        val order = draft ?: return
                        val from = order.indexOf(action)
                        val to = ((pointerX + motion.offset) / slotPx).toInt().coerceIn(0, order.lastIndex)
                        if (from != to) draft = order.toMutableList().apply { add(to, removeAt(from)) }
                    }
                    val viewportPx = with(density) { businessWidth.toPx() }
                    val edge = when {
                        dragged == null || !reorderMoved -> 0
                        pointerX < slotPx * .35f -> -1
                        pointerX > viewportPx - slotPx * .35f -> 1
                        else -> 0
                    }
                    LaunchedEffect(dragged, edge) {
                        while (dragged != null && edge != 0) {
                            delay(60)
                            motion.scrollForReorder(edge * slotPx * .2f)
                            moveDragged()
                        }
                    }
                    Box(
                        Modifier.width(businessWidth).height(cellHeight).clipToBounds().then(input)
                            .testTag(if (preview) "manga-action-preview-viewport" else "manga-action-viewport")
                            .pointerInput(motion, slotPx) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { point ->
                                        val order = currentSaved.map { it.id }
                                        val index = ((point.x + motion.offset) / slotPx).toInt()
                                        longPressActive = true
                                        reorderMoved = false
                                        if (index in order.indices) {
                                            draft = order
                                            dragged = order[index]
                                            pointerX = point.x
                                            grabX = point.x + motion.offset - index * slotPx
                                            failed = false
                                        }
                                    },
                                    onDrag = { change, delta ->
                                        change.consume()
                                        if (dragged != null && delta.x != 0f) {
                                            reorderMoved = true
                                            pointerX += delta.x
                                            moveDragged()
                                        }
                                    },
                                    onDragEnd = {
                                        longPressActive = false
                                        val result = draft
                                        dragged = null
                                        draft = null
                                        if (result != null && result != currentSaved.map { it.id }) {
                                            try {
                                                saveOrder(result)
                                            } catch (error: Exception) {
                                                if (error is kotlinx.coroutines.CancellationException) throw error
                                                failed = true
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        longPressActive = false
                                        dragged = null
                                        draft = null
                                    },
                                )
                            },
                    ) {
                        Row(
                            Modifier.offset { IntOffset(-motion.offset.roundToInt(), 0) }
                                .wrapContentWidth(Alignment.Start, unbounded = true).width(slot * trackSize),
                        ) {
                            ordered.forEachIndexed { index, item ->
                                key(item.id) {
                                    val dragging = dragged == item.id
                                    ActionCell(
                                        item.title,
                                        item.active,
                                        cellHeight,
                                        item.modifier.width(slot).zIndex(if (dragging) 1f else 0f)
                                            .offset {
                                                IntOffset(
                                                    if (dragging) {
                                                        (pointerX + motion.offset - grabX - index * slotPx).roundToInt()
                                                    } else {
                                                        0
                                                    },
                                                    0,
                                                )
                                            }
                                            .testTag("$actionTag-${item.id}").semantics {
                                                item.detail?.let { stateDescription = it }
                                            },
                                        item.icon,
                                        dragging = dragging,
                                        onClick = if (preview) {
                                            null
                                        } else {
                                            {
                                                // The child receives release before the viewport finishes its long drag.
                                                if (!longPressActive) item.onClick()
                                            }
                                        },
                                    )
                                }
                            }
                            if (!preview) {
                                Box(Modifier.width(slot)) {
                                    ActionCell(
                                        labels.customize,
                                        false,
                                        cellHeight,
                                        Modifier.fillMaxWidth().testTag("manga-action-customize"),
                                        { Icon(editSquareIcon, null, Modifier.size(22.dp)) },
                                        onClick = { if (!longPressActive) onCustomize() },
                                    )
                                    Box(
                                        Modifier.align(Alignment.CenterStart).height(42.dp).width(1.dp)
                                            .background(MaterialTheme.colorScheme.outlineVariant),
                                    )
                                }
                            }
                        }
                    }
                }
                if (desktop) {
                    RailPageArrow(
                        direction = 1,
                        label = labels.nextPage,
                        enabled = motion.canPageForward && !longPressActive,
                        width = arrowWidth,
                        cellHeight = cellHeight,
                        tag = "$actionTag-page-next",
                    ) { motion.turnPage(1) }
                }
            }
            if (failed) {
                Text(
                    labels.saveFailed,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("manga-action-reorder-error"),
                )
            }
            RailProgress(motion, desktop, actionTag)
        }
    }
}

private fun Modifier.mobileRailDrag(
    motion: DetailActionMotionState,
    slotPx: Float,
    capacity: Int,
    trackSize: Int,
): Modifier = pointerInput(motion, slotPx, capacity, trackSize) {
    val decay = splineBasedDecay<Float>(this)
    coroutineScope {
        var settling: Job? = null
        var firstDown: PointerInputChange? = null
        var eventTime = 0L
        // Observe touch-down before child buttons, without intercepting the scrolling or sorting gestures.
        launch(start = CoroutineStart.UNDISPATCHED) {
            awaitPointerEventScope {
                while (true) {
                    val changes = awaitPointerEvent(PointerEventPass.Initial).changes
                    val change = changes.firstOrNull() ?: continue
                    eventTime = change.uptimeMillis
                    if (change.pressed && changes.none { it.previousPressed }) {
                        settling?.cancel()
                        firstDown = change
                    }
                }
            }
        }
        val tracker = VelocityTracker()
        var lastMove: PointerInputChange? = null
        var lastDelta = 0f
        try {
            detectHorizontalDragGestures(
                onDragStart = {
                    tracker.resetTracking()
                    firstDown?.let(tracker::addPointerInputChange)
                    lastMove = firstDown
                    lastDelta = 0f
                },
                onDragEnd = {
                    motion.endDrag()
                    val direction = -lastDelta.sign
                    val lastMoveTime = lastMove?.uptimeMillis ?: eventTime
                    val velocity = if (eventTime - lastMoveTime <= 50L) -tracker.calculateVelocity().x else 0f
                    val travel = if (velocity.isFinite() && velocity.sign == direction) {
                        decay.calculateTargetValue(0f, velocity)
                    } else {
                        0f
                    }
                    val from = motion.offset
                    val target = motion.mobileSettleTarget(direction, travel)
                    if (from != target) {
                        val slots = abs(target - from) / slotPx
                        settling = launch {
                            animate(
                                initialValue = from,
                                targetValue = target,
                                animationSpec = tween(
                                    durationMillis = (160 + 55 * slots).roundToInt().coerceAtMost(320),
                                    easing = LinearOutSlowInEasing,
                                ),
                            ) { position, _ -> motion.moveTo(position) }
                        }
                    }
                },
                onDragCancel = motion::endDrag,
            ) { change, delta ->
                if (lastMove?.id != change.id) tracker.resetTracking()
                tracker.addPointerInputChange(change)
                if (delta != 0f) {
                    lastMove = change
                    lastDelta = delta
                    motion.drag(delta)
                }
                change.consume()
            }
        } finally {
            motion.endDrag()
        }
    }
}

@Composable
private fun RailPageArrow(
    direction: Int,
    label: String,
    enabled: Boolean,
    width: Dp,
    cellHeight: Dp,
    tag: String,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.width(width).height(cellHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainerLow.copy(alpha = if (enabled) 1f else .45f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (direction < 0) Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight,
            label,
            Modifier.size(17.dp),
            tint = colors.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f),
        )
    }
}

@Composable
private fun RailProgress(motion: DetailActionMotionState, desktop: Boolean, tag: String) {
    val colors = MaterialTheme.colorScheme
    if (desktop) {
        Row(
            Modifier.padding(top = 8.dp, bottom = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(motion.pageCount) { page ->
                val active = page == motion.currentPage
                Box(
                    Modifier.size(5.dp)
                        .background(if (active) colors.primary else colors.outlineVariant, CircleShape)
                        .testTag("$tag-page-dot-$page")
                        .semantics { selected = active },
                )
            }
        }
    } else {
        Canvas(Modifier.padding(top = 8.dp, bottom = 7.dp).width(36.dp).height(3.dp)) {
            val y = size.height / 2f
            drawLine(colors.outlineVariant, Offset(0f, y), Offset(size.width, y), strokeWidth = size.height)
            val thumb = size.width * motion.visibleFraction
            val x = (size.width - thumb) * motion.scrollFraction
            drawLine(colors.primary, Offset(x, y), Offset(x + thumb, y), strokeWidth = size.height)
        }
    }
}

@Composable
private fun Modifier.edgePullFeedback(motion: DetailActionMotionState): Modifier {
    val boundary by animateFloatAsState(
        targetValue = motion.boundaryPullProgress,
        animationSpec = tween(durationMillis = if (motion.boundaryPullProgress != 0f) 0 else 160),
    )
    val color = MaterialTheme.colorScheme.primary
    return drawWithContent {
        drawContent()
        val stretch = abs(boundary)
        if (stretch > 0f) {
            val eased = stretch * (2f - stretch)
            val extension = 9.dp.toPx() * eased
            val arcHeight = size.height * .65f
            val edgeX = if (boundary < 0f) 0f else size.width
            drawOval(
                color = color.copy(alpha = .15f * eased),
                topLeft = Offset(edgeX - extension, (size.height - arcHeight) / 2f),
                size = Size(2 * extension, arcHeight),
            )
        }
    }
}

@Composable
private fun ActionCell(
    title: String,
    active: Boolean,
    cellHeight: Dp,
    modifier: Modifier,
    icon: @Composable () -> Unit,
    dragging: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val color = if (active) colors.primary else colors.onSurfaceVariant
    val dragTint = lerp(colors.surfaceContainerLow, colors.primary, .12f)
    val highlight by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = tween(durationMillis = if (dragging) 90 else 140),
    )
    Column(
        modifier.height(cellHeight).clip(RoundedCornerShape(13.dp)).drawBehind {
            if (highlight > 0f) drawRect(dragTint.copy(alpha = highlight))
        }.then(
            if (onClick == null) {
                Modifier
            } else {
                Modifier.combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                )
            },
        ).padding(horizontal = 3.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CompositionLocalProvider(LocalContentColor provides color) { Box(Modifier.size(22.dp)) { icon() } }
        Text(
            title,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun DetailActionCustomizationDialog(
    all: List<DetailActionItem>,
    primary: List<DetailActionItem>,
    labels: DetailActionLabels,
    platform: DetailActionPlatform,
    onDismiss: () -> Unit,
    onSave: (List<MangaDetailAction>, List<MangaDetailAction>) -> Unit,
) {
    var order by remember { mutableStateOf(all.map { it.id }) }
    var failed by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val reorder = rememberReorderableLazyGridState(grid) { from, to ->
        order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    AlertDialog(
        modifier = Modifier.testTag("manga-action-customization"),
        onDismissRequest = onDismiss,
        title = { Text(labels.customize) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val previewItems = order.map { id -> all.first { it.id == id } }
                DetailActionRail(
                    mangaId = 0,
                    primary = previewItems.take(MangaDetailAction.MAX_PRIMARY),
                    all = previewItems,
                    platform = platform,
                    labels = labels,
                    onCustomize = {},
                    onReorder = { order = it },
                    modifier = Modifier,
                    preview = true,
                )
                Text(
                    labels.orderHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (failed) {
                    Text(
                        labels.saveFailed,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("manga-action-save-error"),
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(1),
                    state = grid,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 330.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(order, key = { it.name }) { id ->
                        val item = all.first { it.id == id }
                        ReorderableItem(reorder, id.name) { dragging ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(
                                        if (dragging) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerLow
                                        },
                                    )
                                    .longPressDraggableHandle()
                                    .testTag("manga-action-order-$id").semantics {
                                        customActions = listOf(-1 to "↑", 1 to "↓").map { (step, label) ->
                                            CustomAccessibilityAction("${item.title} $label") {
                                                val index = order.indexOf(id)
                                                val destination = (index + step).coerceIn(0, order.lastIndex)
                                                order = order.toMutableList().apply {
                                                    add(destination, removeAt(index))
                                                }
                                                true
                                            }
                                        }
                                    }.padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.DragIndicator, null, Modifier.size(18.dp))
                                val iconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                CompositionLocalProvider(LocalContentColor provides iconColor) {
                                    Box(Modifier.size(20.dp)) { item.icon() }
                                }
                                Text(
                                    item.title,
                                    Modifier.weight(1f).padding(start = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    onSave(order.take(MangaDetailAction.MAX_PRIMARY), order)
                    onDismiss()
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    failed = true
                }
            }) { Text(labels.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(labels.cancel) } },
    )
}

// Google Material Symbols Outlined, edit_square (Apache-2.0).
private val editSquareIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "EditSquare",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
    ).apply {
        // The original SVG viewBox starts at y = -960.
        group(translationY = 960f) {
            addPath(
                pathData = addPathNodes(
                    "M200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h357l-80 80H200v560h560v-278" +
                        "l80-80v358q0 33-23.5 56.5T760-120H200Z" +
                        "M360-360v-170l367-367q12-12 27-18t30-6q16 0 30.5 6t26.5 18l56 57q11 12 17 26.5t6 29.5" +
                        "q0 15-5.5 29.5T897-728L530-360H360Z" +
                        "M440-440h56l232-232-28-28-29-28-231 231v57Z",
                ),
                fill = SolidColor(Color.Black),
            )
        }
    }.build()
}
