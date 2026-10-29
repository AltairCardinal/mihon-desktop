package mihon.desktop.platform

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.Rectangle
import java.awt.Window

internal interface DesktopFullscreenAdapter {
    fun enter(window: Window): Boolean
    fun exit(window: Window): Boolean
    fun completeTransition(window: Window) = Unit
}

internal class WindowsBorderlessFullscreenAdapter(
    private val user32: User32 = User32.INSTANCE,
    private val bounds: (Window, WinDef.HWND) -> WindowsFullscreenBounds = { window, hwnd ->
        val hMonitor = user32.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST)
        val mi = WinUser.MONITORINFO().apply {
            cbSize = size()
            user32.GetMonitorInfo(hMonitor, this)
        }
        WindowsFullscreenBounds(mi.rcMonitor.toRectangle()) { window.graphicsConfiguration.bounds }
    },
) : DesktopFullscreenAdapter {
    private var savedStyle: Int? = null
    private var savedPlacement: WinUser.WINDOWPLACEMENT? = null
    private var savedBounds: Rectangle? = null
    private var redraw: AutoCloseable? = null

    override fun enter(window: Window): Boolean = runCatching {
        if (!window.isDisplayable) return false
        val hwnd = WinDef.HWND(Native.getComponentPointer(window))
        val currentStyle = user32.GetWindowLong(hwnd, WinUser.GWL_STYLE)
        val placement = WinUser.WINDOWPLACEMENT().apply {
            length = size()
            user32.GetWindowPlacement(hwnd, this)
        }
        val target = bounds(window, hwnd)

        savedStyle = currentStyle
        savedPlacement = placement
        savedBounds = Rectangle(window.bounds)

        // Save the original visible style before WM_SETREDRAW temporarily removes it.
        // Keeping that bit clear prevents SetWindowLong from exposing the old D3D buffer.
        if (redraw == null) redraw = suspendWindowsWindowRedraw(window, user32)
        val fullscreenStyle = transitionStyle(currentStyle and WinUser.WS_OVERLAPPEDWINDOW.inv())
        user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, fullscreenStyle)

        val x = target.physical.x
        val y = target.physical.y
        val width = target.physical.width
        val height = target.physical.height

        user32.SetWindowPos(
            hwnd,
            WinDef.HWND(Pointer.createConstant(0)),
            x,
            y,
            width,
            height,
            WinUser.SWP_NOOWNERZORDER or WinUser.SWP_FRAMECHANGED,
        )
        // Keep AWT's logical bounds in sync with the native frame. Changing only the
        // HWND leaves the heavyweight Skiko canvas laid out against stale dimensions.
        window.bounds = target.logical()
        true
    }.onFailure { completeTransition(window) }.getOrDefault(false)

    override fun exit(window: Window): Boolean = runCatching {
        val style = savedStyle ?: return false
        val placement = savedPlacement ?: return false
        val bounds = savedBounds ?: return false
        if (!window.isDisplayable) return false
        val hwnd = WinDef.HWND(Native.getComponentPointer(window))

        user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, transitionStyle(style))
        user32.SetWindowPlacement(hwnd, placement)
        user32.SetWindowPos(
            hwnd,
            null,
            0,
            0,
            0,
            0,
            WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOZORDER or
                WinUser.SWP_NOOWNERZORDER or WinUser.SWP_FRAMECHANGED,
        )
        window.bounds = bounds
        savedStyle = null
        savedPlacement = null
        savedBounds = null
        true
    }.onFailure { completeTransition(window) }.getOrDefault(false)

    override fun completeTransition(window: Window) {
        val current = redraw
        redraw = null
        current?.close()
    }

    private fun transitionStyle(style: Int): Int = if (redraw != null) style and WinUser.WS_VISIBLE.inv() else style
}

internal data class WindowsFullscreenBounds(val physical: Rectangle, val logical: () -> Rectangle)

internal fun defaultFullscreenAdapter(): DesktopFullscreenAdapter? {
    return if (Platform.isWindows()) WindowsBorderlessFullscreenAdapter() else null
}
