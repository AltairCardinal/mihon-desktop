package mihon.desktop.platform

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.desktop.settings.DesktopAppPreferences
import mihon.desktop.settings.saveDesktopPreference
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowStateListener
import javax.swing.SwingUtilities

@Serializable
internal data class DesktopWindowGeometry(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val maximized: Boolean = false,
)

private val windowGeometryJson = Json { ignoreUnknownKeys = true }

/** AWT and Compose window bounds use desktop logical coordinates, including negative monitor origins. */
internal fun restoreDesktopWindowGeometry(raw: String, workAreas: List<Rectangle>): DesktopWindowGeometry? {
    val saved = try {
        windowGeometryJson.decodeFromString<DesktopWindowGeometry>(raw)
    } catch (_: Exception) {
        return null
    }
    if (saved.width <= 0 || saved.height <= 0 || workAreas.isEmpty()) return null
    val areas = workAreas.filter { it.width > 0 && it.height > 0 }
    if (areas.isEmpty()) return null
    fun overlap(area: Rectangle): Long {
        val width = (minOf(saved.x.toLong() + saved.width, area.x.toLong() + area.width) -
            maxOf(saved.x.toLong(), area.x.toLong())).coerceAtLeast(0)
        val height = (minOf(saved.y.toLong() + saved.height, area.y.toLong() + area.height) -
            maxOf(saved.y.toLong(), area.y.toLong())).coerceAtLeast(0)
        return width * height
    }
    val target = areas.maxBy(::overlap)
    val width = saved.width.coerceAtMost(target.width)
    val height = saved.height.coerceAtMost(target.height)
    val disconnected = overlap(target) == 0L
    return saved.copy(
        x = if (disconnected) target.x + (target.width - width) / 2 else
            saved.x.coerceIn(target.x, target.x + target.width - width),
        y = if (disconnected) target.y + (target.height - height) / 2 else
            saved.y.coerceIn(target.y, target.y + target.height - height),
        width = width,
        height = height,
    )
}

internal fun desktopWindowWorkAreas(): List<Rectangle> {
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val primary = environment.defaultScreenDevice
    return (listOf(primary) + environment.screenDevices.filter { it != primary }).map { device ->
        val configuration = device.defaultConfiguration
        val bounds = configuration.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        Rectangle(
            bounds.x + insets.left,
            bounds.y + insets.top,
            bounds.width - insets.left - insets.right,
            bounds.height - insets.top - insets.bottom,
        )
    }
}

internal class DesktopWindowGeometryController(
    private val preferences: DesktopAppPreferences,
    workAreas: List<Rectangle> = desktopWindowWorkAreas(),
) {
    private var geometry = restoreDesktopWindowGeometry(
        runCatching { preferences.windowGeometry.get() }.getOrDefault(""),
        workAreas,
    )
    private var boundWindow: ComposeWindow? = null

    val windowState: WindowState = WindowState(
        placement = if (geometry?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
        position = geometry?.let { WindowPosition.Absolute(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
        size = geometry?.let { DpSize(it.width.dp, it.height.dp) } ?: DpSize(1024.dp, 768.dp),
    )

    fun bind(window: ComposeWindow): AutoCloseable {
        check(boundWindow == null) { "Window geometry already has an owner" }
        boundWindow = window
        var closed = false
        val components = object : ComponentAdapter() {
            private fun captureAfterEvent() {
                SwingUtilities.invokeLater { if (!closed) capture() }
            }
            override fun componentMoved(event: ComponentEvent) = captureAfterEvent()
            override fun componentResized(event: ComponentEvent) = captureAfterEvent()
            override fun componentShown(event: ComponentEvent) = captureAfterEvent()
        }
        val placement = WindowStateListener { capture() }
        window.addComponentListener(components)
        window.addWindowStateListener(placement)
        capture()
        return AutoCloseable {
            if (!closed) {
                save()
                closed = true
                window.removeComponentListener(components)
                window.removeWindowStateListener(placement)
                boundWindow = null
            }
        }
    }

    private fun capture() {
        val window = boundWindow ?: return
        if (!window.isShowing || window.extendedState and Frame.ICONIFIED != 0) return
        when (window.placement) {
            WindowPlacement.Floating -> if (window.width > 0 && window.height > 0) {
                geometry = DesktopWindowGeometry(window.x, window.y, window.width, window.height)
            }
            WindowPlacement.Maximized -> geometry = geometry?.copy(maximized = true)
            WindowPlacement.Fullscreen -> Unit
        }
    }

    fun save(): Boolean {
        capture()
        val snapshot = geometry ?: return true
        val saved = saveDesktopPreference(preferences.windowGeometry, windowGeometryJson.encodeToString(snapshot))
        if (!saved) System.err.println("Desktop window geometry could not be saved")
        return saved
    }
}
