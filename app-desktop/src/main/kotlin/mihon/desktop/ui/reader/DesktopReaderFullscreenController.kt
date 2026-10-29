package mihon.desktop.ui.reader

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import mihon.desktop.diagnostics.ReaderRenderTrace
import mihon.desktop.platform.DesktopFullscreenAdapter
import mihon.desktop.platform.DesktopWindowTransitions
import mihon.desktop.platform.WindowsWindowTransitions
import mihon.desktop.platform.defaultFullscreenAdapter
import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import java.awt.Component
import java.awt.Container
import java.awt.event.ComponentEvent
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
    private val trace: ReaderRenderTrace? = null,
) {
    var previousPlacement: WindowPlacement = WindowPlacement.Floating
        internal set
    private var disposed = false
    private var targetFullscreen: Boolean? = null
    private var isAdapterFullscreen = false
    private var transitionRevision = 0L

    fun syncPlacement(isFullscreen: Boolean) {
        trace?.fullscreenRequest(isFullscreen)
        targetFullscreen = isFullscreen
        val window = getWindow() ?: return
        dispatch(
            Runnable {
                if (disposed || !window.isDisplayable || targetFullscreen != isFullscreen) return@Runnable
                runCatching {
                    if (isFullscreen) {
                        if (!isAdapterFullscreen) {
                            changePlacement(window, true) {
                                if (fullscreenAdapter != null && fullscreenAdapter.enter(window)) {
                                    isAdapterFullscreen = true
                                } else {
                                    val current = window.placement
                                    if (current != WindowPlacement.Fullscreen) {
                                        previousPlacement = current
                                        window.placement = WindowPlacement.Fullscreen
                                    }
                                }
                            }
                        }
                    } else if (isAdapterFullscreen || window.placement == WindowPlacement.Fullscreen) {
                        changePlacement(window, false) {
                            if (isAdapterFullscreen) {
                                fullscreenAdapter?.exit(window)
                                isAdapterFullscreen = false
                            } else if (window.placement == WindowPlacement.Fullscreen) {
                                restoreNonFullscreenPlacement(window, previousPlacement)
                            }
                        }
                    }
                }
            },
        )
    }

    fun onDispose(wasFullscreen: Boolean) {
        disposed = true
        targetFullscreen = false
        val window = getWindow()
        if (window == null || !wasFullscreen || !window.isDisplayable) return
        runCatching {
            changePlacement(window, false) {
                if (isAdapterFullscreen) {
                    fullscreenAdapter?.exit(window)
                    isAdapterFullscreen = false
                } else {
                    restoreNonFullscreenPlacement(window, previousPlacement)
                }
            }
        }
    }

    private fun changePlacement(window: ComposeWindow, fullscreen: Boolean, change: () -> Unit) {
        val revision = ++transitionRevision
        transitions.setDisabled(window, true)
        var posted = false
        try {
            findLayer(window)?.let { fullscreenAdapter?.prepareTransition(window, it.canvas) }
            trace?.windowEvent("placement-mutation-begin")
            change()
            if (fullscreen && isAdapterFullscreen) {
                // Removing the frame moves the client origin immediately. Reconcile the
                // heavyweight canvas before an already queued render can use its old size
                // at that new origin; the later pass still consumes native resize events.
                synchronizeEntryCanvas(window)
                trace?.windowEvent("entry-canvas-layout-synchronized")
            }
            trace?.windowEvent("placement-mutation-end")
            // Win32 also posts AWT bounds/inset updates. Finish reconciling after those
            // events, while system transitions are still disabled.
            dispatch(
                Runnable {
                    if (revision != transitionRevision) return@Runnable
                    runCatching {
                        try {
                            if (window.isDisplayable && targetFullscreen == fullscreen) presentFinalFrame(window)
                        } finally {
                            completeTransition(window)
                        }
                    }
                },
            )
            posted = true
        } finally {
            if (!posted) {
                completeTransition(window)
            }
        }
    }

    private fun completeTransition(window: ComposeWindow) {
        try {
            fullscreenAdapter?.completeTransition(window)
        } finally {
            try {
                transitions.setDisabled(window, false)
            } finally {
                fullscreenAdapter?.releaseTransitionFrame(window)
            }
        }
    }

    private fun presentFinalFrame(window: ComposeWindow) {
        // Native frame changes resize the client area before the queued Compose repaint.
        // Draw that final layout before DwmFlush can expose an empty or stale surface.
        trace?.windowEvent("final-frame-layout-begin")
        invalidateLayout(window)
        window.validate()
        findLayer(window)?.let { fullscreenAdapter?.synchronizeCanvas(window, it.canvas) }
        window.renderImmediately()
        trace?.windowEvent("final-frame-render-returned")
    }

    private fun invalidateLayout(component: Component) {
        if (component is Container) component.components.forEach(::invalidateLayout)
        component.invalidate()
    }

    private fun synchronizeEntryCanvas(window: ComposeWindow) {
        val layer = findLayer(window)
        val original = layer?.renderDelegate
        if (layer == null || original == null) {
            invalidateLayout(window)
            window.validate()
            return
        }
        var notified = false
        val synchronized = object : SkikoRenderDelegate {
            override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
                if (!notified) {
                    notified = true
                    // ComposeContainer.setBounds resizes SkiaLayer before notifying its
                    // scene. Its parent already has the new bounds during this callback.
                    layer.parent?.let { parent ->
                        parent.dispatchEvent(ComponentEvent(parent, ComponentEvent.COMPONENT_RESIZED))
                    }
                }
                original.onRender(canvas, width, height, nanoTime)
            }
        }
        layer.renderDelegate = synchronized
        try {
            val insets = window.insets
            // Direct3D reshape renders before doLayout resizes the heavyweight child.
            layer.canvas.setBounds(
                0,
                0,
                (window.width - insets.left - insets.right).coerceAtLeast(0),
                (window.height - insets.top - insets.bottom).coerceAtLeast(0),
            )
            invalidateLayout(window)
            window.validate()
        } finally {
            if (layer.renderDelegate === synchronized) layer.renderDelegate = original
        }
    }

    private fun findLayer(component: Component): SkiaLayer? {
        if (component is SkiaLayer) return component
        return (component as? Container)?.components?.firstNotNullOfOrNull(::findLayer)
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
