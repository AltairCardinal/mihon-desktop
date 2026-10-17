package mihon.desktop.ui.reader

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import javax.swing.SwingUtilities

/**
 * Coordinates native window placement transitions for the desktop reader screen.
 * Dispatches placement mutations to the event queue to prevent re-entrant event loops
 * during Compose layout and pointer event handling, and safely ignores operations when
 * the native window is detached or closing.
 */
internal class DesktopReaderFullscreenController(
    private val getWindow: () -> ComposeWindow?,
    private val dispatch: (Runnable) -> Unit = SwingUtilities::invokeLater,
) {
    var previousPlacement: WindowPlacement = WindowPlacement.Floating
        internal set
    private var disposed = false
    private var targetFullscreen: Boolean? = null

    fun syncPlacement(isFullscreen: Boolean) {
        targetFullscreen = isFullscreen
        val window = getWindow() ?: return
        dispatch(Runnable {
            if (disposed || !window.isDisplayable || targetFullscreen != isFullscreen) return@Runnable
            runCatching {
                val current = window.placement
                if (isFullscreen) {
                    if (current != WindowPlacement.Fullscreen) {
                        previousPlacement = current
                        window.placement = WindowPlacement.Fullscreen
                    }
                } else {
                    if (current == WindowPlacement.Fullscreen) {
                        window.placement = previousPlacement
                    }
                }
            }
        })
    }

    fun onDispose(wasFullscreen: Boolean) {
        disposed = true
        targetFullscreen = false
        val window = getWindow() ?: return
        if (!wasFullscreen || !window.isDisplayable) return
        runCatching {
            window.placement = previousPlacement
        }
    }
}
