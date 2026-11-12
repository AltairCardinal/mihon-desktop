package mihon.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.FrameWindowScope
import kotlinx.coroutines.launch
import mihon.desktop.platform.DesktopMainWindow
import mihon.desktop.platform.DesktopWindowGeometryStore

/** Keep the owner composition alive until background checkpoint cleanup has finished. */
@Composable
internal fun ApplicationScope.DesktopApplicationWindow(
    geometryStore: DesktopWindowGeometryStore?,
    requestClose: DesktopApplicationCloseCoordinator,
    title: String,
    icon: Painter? = null,
    content: @Composable FrameWindowScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val closing by requestClose.closing.collectAsState()
    DesktopMainWindow(
        geometryStore = geometryStore,
        onCloseRequest = { scope.launch { requestClose { exitApplication() } } },
        title = title,
        icon = icon,
        visible = !closing,
        content = content,
    )
}
