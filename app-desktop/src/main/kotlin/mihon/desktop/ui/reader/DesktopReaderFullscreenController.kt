package mihon.desktop.ui.reader

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import mihon.desktop.platform.DesktopFullscreenAdapter
import mihon.desktop.platform.DesktopWindowTransitions
import mihon.desktop.platform.WindowsWindowTransitions
import mihon.desktop.platform.defaultFullscreenAdapter
import javax.swing.SwingUtilities

/**
 * Coordinates native window placement transitions for the desktop reader screen.
 * Dispatches placement mutations to the event queue to prevent re-entrant event loops
 * during Compose layout and pointer event handling, and safely ignores operations when
 * the native window is detached or closing.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal class DesktopReaderFullscreenController(
    private val getWindow: () -> ComposeWindow?,
    private val dispatch: (Runnable) -> Unit = SwingUtilities::invokeLater,
    private val transitions: DesktopWindowTransitions = WindowsWindowTransitions,
    private val fullscreenAdapter: DesktopFullscreenAdapter? = defaultFullscreenAdapter(),
    private val setNativeFullscreen: (ComposeWindow, Boolean) -> Boolean = ::defaultSetNativeFullscreen,
) {
    var previousPlacement: WindowPlacement = WindowPlacement.Floating
        internal set
    private var disposed = false
    private var targetFullscreen: Boolean? = null
    private var isAdapterFullscreen = false

    fun syncPlacement(isFullscreen: Boolean) {
        targetFullscreen = isFullscreen
        val window = getWindow() ?: return
        dispatch(
            Runnable {
                if (disposed || !window.isDisplayable || targetFullscreen != isFullscreen) return@Runnable
                runCatching {
                    if (isFullscreen) {
                        if (!isAdapterFullscreen) {
                            transitions.setDisabled(window, true)
                            try {
                                if (fullscreenAdapter != null && fullscreenAdapter.enter(window)) {
                                    isAdapterFullscreen = true
                                } else {
                                    val current = window.placement
                                    if (current != WindowPlacement.Fullscreen) {
                                        previousPlacement = current
                                        window.placement = WindowPlacement.Fullscreen
                                    }
                                }
                                presentFinalFrame(window)
                            } finally {
                                transitions.setDisabled(window, false)
                            }
                        }
                    } else {
                        transitions.setDisabled(window, true)
                        try {
                            if (isAdapterFullscreen) {
                                fullscreenAdapter?.exit(window)
                                isAdapterFullscreen = false
                                presentFinalFrame(window)
                            } else if (window.placement == WindowPlacement.Fullscreen) {
                                restoreNonFullscreenPlacement(window, previousPlacement)
                                presentFinalFrame(window)
                            }
                        } finally {
                            transitions.setDisabled(window, false)
                        }
                    }
                }
            },
        )
    }

    fun onDispose(wasFullscreen: Boolean) {
        disposed = true
        targetFullscreen = false
        val window = getWindow() ?: return
        if (!wasFullscreen || !window.isDisplayable) return
        runCatching {
            transitions.setDisabled(window, true)
            try {
                if (isAdapterFullscreen) {
                    fullscreenAdapter?.exit(window)
                    isAdapterFullscreen = false
                } else {
                    restoreNonFullscreenPlacement(window, previousPlacement)
                }
                presentFinalFrame(window)
            } finally {
                transitions.setDisabled(window, false)
            }
        }
    }

    private fun presentFinalFrame(window: ComposeWindow) {
        // Native frame changes resize the client area before the queued Compose repaint.
        // Draw that final layout before DwmFlush can expose an empty or stale surface.
        window.validate()
        window.renderImmediately()
    }

    private fun restoreNonFullscreenPlacement(window: ComposeWindow, target: WindowPlacement) {
        // If the previous state was Maximized, directly clear native fullscreen on the window
        // without collapsing through Floating. This prevents the window from rapidly resizing
        // from Fullscreen -> Floating -> Maximized (which causes multi-frame flashing).
        if (target == WindowPlacement.Maximized) {
            val cleared = setNativeFullscreen(window, false)
            if (cleared) {
                window.placement = WindowPlacement.Maximized
                return
            }
        }

        // Fallback or Floating target: transition through Floating to release native fullscreen
        window.placement = WindowPlacement.Floating
        if (target == WindowPlacement.Maximized) {
            window.placement = WindowPlacement.Maximized
        }
    }
}

internal fun defaultSetNativeFullscreen(window: ComposeWindow, enabled: Boolean): Boolean {
    return runCatching {
        val method = ComposeWindow::class.java.getDeclaredMethod("setFullscreen", Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(window, enabled)
        true
    }.recoverCatching {
        val panel = window.contentPane.components.firstOrNull {
            it.javaClass.name.contains("ComposeWindowPanel")
        } ?: return@recoverCatching false
        val panelMethod = panel.javaClass.getMethod("setFullscreen", Boolean::class.javaPrimitiveType)
        panelMethod.invoke(panel, enabled)
        true
    }.getOrDefault(false)
}
