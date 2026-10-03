package mihon.presentation.sync

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag

/** Optional, read-only instrumentation. Never receives text or editable values. */
interface SyncUiObserver {
    fun observes(tag: String): Boolean
    fun update(token: Any, control: SyncUiControl)
    fun remove(token: Any)
}

data class SyncUiControl(
    val tag: String,
    val screenLeft: Float,
    val screenTop: Float,
    val width: Float,
    val height: Float,
    val density: Float,
    val focused: Boolean,
    val ownerFocused: Boolean,
    val enabled: Boolean,
    val currentScreenBounds: (() -> SyncUiScreenBounds?)? = null,
)

data class SyncUiScreenBounds(val left: Float, val top: Float, val width: Float, val height: Float)

val LocalSyncUiObserver = staticCompositionLocalOf<SyncUiObserver?> { null }

/** Keeps the existing semantics tag; the optional observer neither requests nor changes focus. */
fun Modifier.syncUiTag(tag: String, enabled: Boolean = true): Modifier = composed {
    val observer = LocalSyncUiObserver.current
    if (observer == null || !observer.observes(tag)) return@composed this.testTag(tag)
    val token = remember(observer, tag) { Any() }
    val density = LocalDensity.current.density
    val ownerFocused = LocalWindowInfo.current.isWindowFocused
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var focused by remember { mutableStateOf(false) }
    fun screenBounds(): SyncUiScreenBounds? {
        val layout = coordinates?.takeIf { it.isAttached } ?: return null
        val position = try {
            layout.localToScreen(Offset.Zero)
        } catch (_: RuntimeException) {
            return null
        }
        return SyncUiScreenBounds(position.x, position.y, layout.size.width.toFloat(), layout.size.height.toFloat())
    }
    fun publish() {
        val bounds = screenBounds()
        observer.update(
            token,
            SyncUiControl(
                tag, bounds?.left ?: Float.NaN, bounds?.top ?: Float.NaN,
                bounds?.width ?: 0f, bounds?.height ?: 0f, density, focused, ownerFocused, enabled, ::screenBounds,
            ),
        )
    }
    SideEffect { publish() }
    DisposableEffect(observer, token) { onDispose { observer.remove(token) } }
    this.testTag(tag).onFocusChanged {
        focused = it.isFocused
        publish()
    }.onGloballyPositioned {
        coordinates = it
        publish()
    }
}
