package mihon.desktop.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import cafe.adriel.voyager.core.screen.Screen
import kotlin.reflect.KClass

internal val DesktopSettingsAnchorHighlighted = SemanticsPropertyKey<Boolean>("DesktopSettingsAnchorHighlighted")

internal data class DesktopSettingsLazyAnchor(
    val title: String,
    val key: Any,
    val index: Int? = null,
)

internal object DesktopSettingsAnchorOwner {
    private data class Request(val route: KClass<out Screen>, val title: String)
    private var pending: Request? = null

    fun publish(route: Screen, title: String) {
        pending = Request(route::class, title)
    }

    fun claim(route: Screen): String? {
        val request = pending
        pending = null
        return request?.title?.takeIf { request.route == route::class }
    }

    fun clear() {
        pending = null
    }
}

private data class AnchorTarget(val title: String, val offset: Int)

private class AnchorController(
    val title: String,
    val scrollState: ScrollState,
    val targets: SnapshotStateMap<Any, AnchorTarget> = mutableStateMapOf(),
) {
    var highlighted: Any? by mutableStateOf(null)
    var hostTop: Float = 0f
}

private val LocalDesktopSettingsAnchor = compositionLocalOf<AnchorController?> { null }

internal class DesktopSettingsLazyAnchorHost internal constructor(
    val listState: LazyListState,
    internal val target: DesktopSettingsLazyAnchor?,
) {
    var highlighted by mutableStateOf(false)
}

@Composable
internal fun DesktopSettingsAnchorColumn(
    route: Screen,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val requestedTitle = remember(route) { DesktopSettingsAnchorOwner.claim(route) }
    val scrollState = rememberScrollState()
    val controller = remember(requestedTitle) { requestedTitle?.let { AnchorController(it, scrollState) } }
    val targetCount = controller?.targets?.size

    LaunchedEffect(controller, targetCount) {
        if (controller != null) {
            withFrameNanos { }
            val target = controller.targets.entries
                .filter { it.value.title == controller.title }
                .minByOrNull { it.value.offset }
            if (target != null) {
                controller.highlighted = target.key
                scrollState.scrollTo(target.value.offset)
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalDesktopSettingsAnchor provides controller) {
        Column(
            modifier.onGloballyPositioned { controller?.hostTop = it.positionInRoot().y }.verticalScroll(scrollState),
            content = content,
        )
    }
}

@Composable
internal fun rememberDesktopSettingsAnchorLazyListHost(
    route: Screen,
    anchors: List<DesktopSettingsLazyAnchor>,
): DesktopSettingsLazyAnchorHost {
    val requestedTitle = remember(route) { DesktopSettingsAnchorOwner.claim(route) }
    val target = remember(requestedTitle, anchors) {
        requestedTitle?.let { title -> anchors.firstOrNull { it.title == title } }
    }
    val state = rememberLazyListState()
    val host = remember(state, target) { DesktopSettingsLazyAnchorHost(state, target) }

    LaunchedEffect(host) {
        if (host.target != null) {
            host.target.index?.let { state.scrollToItem(it) }
            withFrameNanos { }
            host.highlighted = true
        }
    }
    return host
}

@Composable
internal fun Modifier.desktopSettingsAnchor(
    title: String,
    lazyKey: Any? = null,
    lazyHost: DesktopSettingsLazyAnchorHost? = null,
): Modifier {
    val controller = LocalDesktopSettingsAnchor.current
    val identity = remember { Any() }
    DisposableEffect(controller, identity) {
        onDispose { controller?.targets?.remove(identity) }
    }
    val lazyHighlighted = lazyKey != null &&
        lazyHost?.highlighted == true &&
        lazyHost.target?.title == title &&
        lazyHost.target.key == lazyKey
    val highlighted = controller?.highlighted === identity || lazyHighlighted
    val color = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent
    return this
        .onGloballyPositioned { coordinates ->
            controller?.targets?.set(
                identity,
                AnchorTarget(title, (controller.scrollState.value + coordinates.positionInRoot().y - controller.hostTop).toInt()),
            )
        }
        .background(color)
        .semantics {
            if (highlighted) {
                this[DesktopSettingsAnchorHighlighted] = true
            }
        }
}
