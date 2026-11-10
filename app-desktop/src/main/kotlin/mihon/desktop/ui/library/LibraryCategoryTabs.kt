package mihon.desktop.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import tachiyomi.domain.category.model.Category
import kotlin.math.abs

private data class CategoryTabDrag(val id: Long, val pointerX: Float, val grabOffsetX: Float)

/** Material tabs retain their click/wheel behavior; only the Desktop horizontal-drag adapter is local. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun LibraryCategoryTabs(
    categories: List<Category>,
    selectedCategoryIndex: Int,
    label: (Category) -> String,
    onSelect: (Int) -> Unit,
    onReorder: suspend (Long, Int) -> Boolean,
) {
    val authoritative by rememberUpdatedState(categories)
    val reorder by rememberUpdatedState(onReorder)
    val selectedId = categories.getOrNull(selectedCategoryIndex)?.id
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val bounds = remember { mutableStateMapOf<Long, Rect>() }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    var preview by remember { mutableStateOf<List<Category>?>(null) }
    var drag by remember { mutableStateOf<CategoryTabDrag?>(null) }
    var saving by remember { mutableStateOf(false) }
    val edge = with(LocalDensity.current) { 48.dp.toPx() }
    val scrollSpeed = with(LocalDensity.current) { 600.dp.toPx() }
    val elevation = with(LocalDensity.current) { 4.dp.toPx() }

    fun cancel() {
        drag = null
        preview = null
    }

    fun project() {
        val active = drag ?: return
        val current = preview ?: authoritative
        val target = current.filterNot(Category::isSystemCategory).minByOrNull {
            bounds[it.id]?.let { rect -> abs(rect.center.x - active.pointerX) } ?: Float.MAX_VALUE
        } ?: return
        val from = current.indexOfFirst { it.id == active.id }
        val to = current.indexOfFirst { it.id == target.id }
        if (from >= 0 && from != to) {
            preview = current.toMutableList().also { it.add(to, it.removeAt(from)) }
        }
    }

    fun finish() {
        val active = drag ?: return
        val custom = (preview ?: authoritative).filterNot(Category::isSystemCategory)
        val target = custom.indexOfFirst { it.id == active.id }
        val original = authoritative.filterNot(Category::isSystemCategory).indexOfFirst { it.id == active.id }
        drag = null
        if (target < 0 || target == original) {
            preview = null
            return
        }
        saving = true
        scope.launch {
            try {
                // Existing model/use case owns persistence, authoritative refresh and error feedback.
                reorder(active.id, target)
            } finally {
                preview = null
                saving = false
            }
        }
    }

    // A repository change during a gesture invalidates its snapshot; never reorder a stale identity.
    LaunchedEffect(categories) {
        bounds.keys.retainAll(categories.map { it.id }.toSet())
        if (!saving) cancel()
    }
    LaunchedEffect(drag?.id) {
        var previous = withFrameNanos { it }
        while (drag != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
            previous = now
            val pointer = drag?.pointerX ?: break
            val direction = when {
                pointer < viewport.left + edge -> -((viewport.left + edge - pointer) / edge).coerceIn(0f, 1f)
                pointer > viewport.right - edge -> ((pointer - viewport.right + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (direction != 0f && scroll.scrollBy(direction * scrollSpeed * seconds) != 0f) {
                project()
            }
        }
    }

    val visible = preview ?: categories
    SecondaryScrollableTabRow(
        selectedTabIndex = visible.indexOfFirst { it.id == selectedId }.coerceAtLeast(0),
        scrollState = scroll,
        edgePadding = 8.dp,
        modifier = Modifier.fillMaxWidth()
            .onGloballyPositioned { viewport = it.boundsInWindow() }
            .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
                val delta = event.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                val distance = (if (delta.x != 0f) delta.x else delta.y) * edge
                if (distance != 0f) {
                    if (drag == null) scope.launch { scroll.scrollBy(distance) }
                    event.changes.forEach { it.consume() }
                }
            },
    ) {
        visible.forEach { category ->
            key(category.id) {
                val dragging = drag?.id == category.id
                val color = if (dragging) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surface
                }
                Tab(
                    selected = category.id == selectedId,
                    enabled = !saving,
                    onClick = {
                        if (drag == null) {
                            authoritative.indexOfFirst { it.id == category.id }.takeIf { it >= 0 }?.let(onSelect)
                        }
                    },
                    modifier = Modifier
                        .onGloballyPositioned {
                            val origin = it.positionInWindow()
                            bounds[category.id] =
                                Rect(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height)
                        }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            val active = drag?.takeIf { it.id == category.id }
                            translationX =
                                active?.let { it.pointerX - it.grabOffsetX - (bounds[category.id]?.left ?: 0f) } ?: 0f
                            shadowElevation = if (active != null) elevation else 0f
                        }
                        .background(color)
                        .then(
                            if (category.isSystemCategory) {
                                Modifier
                            } else {
                                Modifier.pointerInput(category.id, saving) {
                                    if (!saving) {
                                        detectHorizontalDragGestures(
                                            onDragStart = { point ->
                                                bounds[category.id]?.let { rect ->
                                                    preview = authoritative
                                                    drag = CategoryTabDrag(category.id, rect.left + point.x, point.x)
                                                }
                                            },
                                            onHorizontalDrag = { change, amount ->
                                                change.consume()
                                                drag = drag?.let { it.copy(pointerX = it.pointerX + amount) }
                                                project()
                                            },
                                            onDragEnd = ::finish,
                                            onDragCancel = ::cancel,
                                        )
                                    }
                                }
                            },
                        ),
                    text = { Text(label(category)) },
                )
            }
        }
    }
}
