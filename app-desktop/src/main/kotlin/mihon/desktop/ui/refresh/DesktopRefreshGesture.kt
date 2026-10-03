@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package mihon.desktop.ui.refresh

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.platformScrollConfig
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.domain.library.service.TwoStageRefreshGesture
import tachiyomi.i18n.MR
import java.util.Locale

internal fun refreshGestureTime(): Long = System.nanoTime() / 1_000_000

internal class RefreshGestureController(
    private val now: () -> Long = ::refreshGestureTime,
    private val submit: () -> Unit,
) {
    private val policy = TwoStageRefreshGesture()
    var stage by mutableStateOf(policy.stage)
        private set
    var alive = true
    fun scroll(dp: Float) {
        if (!alive) return
        if (policy.scroll(dp, now())) submit()
        stage = policy.stage
    }
    fun cancel() {
        policy.cancel()
        stage = policy.stage
    }
    fun activity() {
        policy.activity(now())
    }
    fun tick() {
        policy.tick(now())
        stage = policy.stage
    }
    fun rejected() {
        policy.cancel(force = true)
        stage = policy.stage
    }
    fun complete() {
        policy.complete(now())
        stage = policy.stage
    }
}

@Composable
internal fun rememberRefreshGesture(
    scopeKey: Any?,
    enabled: Boolean,
    refresh: suspend () -> Boolean,
): RefreshGestureController {
    val scope = rememberCoroutineScope()
    val latestRefresh by rememberUpdatedState(refresh)
    val controller = remember(scopeKey) {
        lateinit var owner: RefreshGestureController
        owner = RefreshGestureController {
            scope.launch {
                try {
                    if (latestRefresh()) owner.complete() else owner.rejected()
                } catch (error: Exception) {
                    owner.rejected()
                    throw error
                }
            }
        }
        owner
    }
    DisposableEffect(controller) {
        onDispose {
            controller.alive = false
            controller.cancel()
        }
    }
    LaunchedEffect(controller, enabled) { if (!enabled) controller.cancel() }
    LaunchedEffect(controller) {
        while (true) {
            delay(50)
            controller.tick()
        }
    }
    return controller
}

/** The overlay takes no layout space. The actual lazy scroll owner remains the sole scroll consumer. */
@Composable
internal fun RefreshGestureContent(
    controller: RefreshGestureController,
    enabled: Boolean,
    atTop: Boolean,
    title: String,
    modifier: Modifier = Modifier,
    activityKey: Any? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    LaunchedEffect(controller, activityKey) { if (activityKey != null) controller.activity() }
    LaunchedEffect(controller, atTop, enabled) { if (!atTop || !enabled) controller.cancel() }
    val scrolling = remember(controller) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                controller.activity()
                // Wheel at the top is not dispatched by Compose; dispatched scrolling cannot arm this owner.
                controller.cancel()
                return Offset.Zero
            }
        }
    }
    val focusHeld = remember(controller) { booleanArrayOf(false) }
    Box(
        modifier.onFocusChanged {
            if (focusHeld[0] && !it.hasFocus) controller.cancel()
            focusHeld[0] = it.hasFocus
        }.focusGroup().nestedScroll(scrolling).then(RefreshWheelElement(controller, enabled, atTop))
            .onPreviewKeyEvent {
                controller.cancel()
                false
            },
    ) {
        content()
        val message = when (controller.stage) {
            TwoStageRefreshGesture.Stage.HINTING -> MR.strings.desktop_refresh_hint.localized()
            TwoStageRefreshGesture.Stage.ARMED -> MR.strings.desktop_refresh_armed.localized(Locale.getDefault(), title)
            else -> null
        }
        if (message != null) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
            ) { Text(message, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

private data class RefreshWheelElement(
    val controller: RefreshGestureController,
    val enabled: Boolean,
    val atTop: Boolean,
) : ModifierNodeElement<RefreshWheelNode>() {
    override fun create() = RefreshWheelNode(controller, enabled, atTop)
    override fun update(node: RefreshWheelNode) {
        node.controller = controller
        node.enabled = enabled
        node.atTop = atTop
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "twoStageRefresh"
    }
}

/** Compose 1.10.2's own platform config owns AWT precision, scrollAmount, viewport and density conversion. */
private class RefreshWheelNode(
    var controller: RefreshGestureController,
    var enabled: Boolean,
    var atTop: Boolean,
) : Modifier.Node(), PointerInputModifierNode, CompositionLocalConsumerModifierNode {
    private var startedAtTop = false
    override fun onPointerEvent(event: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (event.type == PointerEventType.Press) controller.cancel()
        if (event.type != PointerEventType.Scroll) return
        val delta = event.changes.firstOrNull()?.scrollDelta ?: return
        val unmodified = !event.keyboardModifiers.isCtrlPressed && !event.keyboardModifiers.isAltPressed &&
            !event.keyboardModifiers.isShiftPressed && delta.x == 0f && delta.y < 0f
        if (pass == PointerEventPass.Initial) {
            controller.activity()
            startedAtTop = atTop && enabled && unmodified
            if (!startedAtTop) controller.cancel()
        }
        if (pass == PointerEventPass.Final && startedAtTop && enabled && atTop &&
            event.changes.none { it.isConsumed }
        ) {
            val density = currentValueOf(LocalDensity)
            val pixelDelta = with(platformScrollConfig()) { density.calculateMouseWheelScroll(event, bounds) }
            val dp = pixelDelta.y / density.density
            if (dp.isFinite() && dp > 0f) controller.scroll(dp.coerceAtMost(80f))
        }
    }
    override fun onCancelPointerInput() {
        startedAtTop = false
        controller.cancel()
    }
}
