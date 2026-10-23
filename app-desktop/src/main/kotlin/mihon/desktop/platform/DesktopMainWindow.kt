package mihon.desktop.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Platform
import mihon.desktop.settings.DesktopAppPreferences

@Composable
internal fun ApplicationScope.DesktopMainWindow(
    geometryStore: DesktopWindowGeometryStore?,
    onCloseRequest: () -> Unit,
    title: String,
    icon: Painter? = null,
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    windowTransitions: DesktopWindowTransitions = WindowsWindowTransitions,
    content: @Composable FrameWindowScope.() -> Unit,
) {
    // The window is created before the dependency graph is ready, so the persisted
    // geometry is applied through a store that does not need the container.
    val geometry = remember(geometryStore, windowTransitions) {
        if (geometryStore != null && Platform.isWindows()) {
            DesktopWindowGeometryController(geometryStore, transitions = windowTransitions)
        } else {
            null
        }
    }
    val defaultState = rememberWindowState(width = 1024.dp, height = 768.dp)
    Window(
        onCloseRequest = {
            geometry?.save()
            onCloseRequest()
        },
        title = title,
        icon = icon,
        state = geometry?.windowState ?: defaultState,
        onPreviewKeyEvent = onPreviewKeyEvent,
    ) {
        if (geometry != null) {
            DisposableEffect(window, geometry) {
                val binding = geometry.bind(window)
                onDispose(binding::close)
            }
        }
        content()
    }
}
