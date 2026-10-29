package mihon.desktop.platform

import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.Canvas
import java.awt.Rectangle
import java.awt.Window

internal interface DesktopFullscreenAdapter {
    fun enter(window: Window): Boolean
    fun exit(window: Window): Boolean
    fun completeTransition(window: Window) = Unit
    fun synchronizeCanvas(window: Window, canvas: Canvas) = Unit
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
    private data class WindowedState(
        val style: Int,
        val placement: WinUser.WINDOWPLACEMENT,
        val logicalBounds: Rectangle,
        val physicalBounds: Rectangle,
    )

    private var saved: WindowedState? = null
    private var restoring: WindowedState? = null
    private var redraw: AutoCloseable? = null
    private var canvasToSynchronize: Canvas? = null

    override fun enter(window: Window): Boolean = runCatching {
        if (!window.isDisplayable) return false
        val hwnd = WinDef.HWND(Native.getComponentPointer(window))
        // A new entry can supersede an exit before its completion callback runs.
        // Reuse the original windowed state rather than snapshotting its provisional native placement.
        val state = restoring ?: run {
            val placement = WinUser.WINDOWPLACEMENT().apply {
                length = size()
                user32.GetWindowPlacement(hwnd, this)
            }
            val physical = WinDef.RECT().also { check(user32.GetWindowRect(hwnd, it)) }
            WindowedState(
                user32.GetWindowLong(hwnd, WinUser.GWL_STYLE),
                placement,
                Rectangle(window.bounds),
                physical.toRectangle(),
            )
        }
        val target = bounds(window, hwnd)

        saved = state
        restoring = null

        // Save the original visible style before WM_SETREDRAW temporarily removes it.
        // Keeping that bit clear prevents SetWindowLong from exposing the old D3D buffer.
        if (redraw == null) redraw = suspendWindowsWindowRedraw(window, user32)
        val fullscreenStyle = transitionStyle(state.style and WinUser.WS_OVERLAPPEDWINDOW.inv())
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
        val state = saved ?: return false
        if (!window.isDisplayable) return false
        val hwnd = WinDef.HWND(Native.getComponentPointer(window))

        // Restoring the frame moves the client origin before the smaller D3D frame is ready too.
        if (redraw == null) redraw = suspendWindowsWindowRedraw(window, user32)
        user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, transitionStyle(state.style))
        // SetWindowPlacement shows the window even while WM_SETREDRAW is suspended.
        // Restore its physical extent without showing it, then defer placement until the final frame is drawn.
        val bounds = state.physicalBounds
        user32.SetWindowPos(
            hwnd,
            null,
            bounds.x,
            bounds.y,
            bounds.width,
            bounds.height,
            WinUser.SWP_NOZORDER or WinUser.SWP_NOOWNERZORDER or WinUser.SWP_FRAMECHANGED,
        )
        window.bounds = state.logicalBounds
        restoring = state
        saved = null
        true
    }.onFailure { completeTransition(window) }.getOrDefault(false)

    override fun completeTransition(window: Window) {
        val current = redraw
        redraw = null
        val state = restoring
        restoring = null
        val canvas = canvasToSynchronize
        canvasToSynchronize = null
        try {
            if (state != null && window.isDisplayable && window.isVisible) {
                user32.SetWindowPlacement(WinDef.HWND(Native.getComponentPointer(window)), state.placement)
                // Maximize/work-area rounding can adjust the extent again while restoring placement.
                if (canvas != null) synchronizeNativeCanvas(window, canvas)
            }
        } finally {
            current?.close()
        }
    }

    override fun synchronizeCanvas(window: Window, canvas: Canvas) {
        canvasToSynchronize = canvas
        synchronizeNativeCanvas(window, canvas)
    }

    private fun synchronizeNativeCanvas(window: Window, canvas: Canvas) {
        if (!window.isDisplayable || !canvas.isDisplayable) return
        val frame = WinDef.HWND(Native.getComponentPointer(window))
        val child = WinDef.HWND(Native.getComponentPointer(canvas))
        val parent = user32.GetParent(child)
        val rect = WinDef.RECT()
        if (!user32.GetWindowRect(child, rect)) return
        val client = WinDef.RECT()
        if (!user32.GetClientRect(frame, client)) return
        val map = NativeLibrary.getInstance("user32").getFunction("MapWindowPoints", Function.ALT_CONVENTION)
        val actual = WinDef.POINT(rect.left, rect.top)
        val expected = WinDef.POINT()
        map.invokeInt(arrayOf(null, parent, actual, 1))
        map.invokeInt(arrayOf(frame, parent, expected, 1))
        // AWT may already report the desired bounds while queued inset changes displaced its native child.
        if (actual.x != expected.x || actual.y != expected.y ||
            rect.right - rect.left != client.right || rect.bottom - rect.top != client.bottom
        ) {
            user32.SetWindowPos(
                child,
                null,
                expected.x,
                expected.y,
                client.right,
                client.bottom,
                WinUser.SWP_NOACTIVATE or WinUser.SWP_NOZORDER or WinUser.SWP_NOOWNERZORDER,
            )
        }
    }

    private fun transitionStyle(style: Int): Int = if (redraw != null) style and WinUser.WS_VISIBLE.inv() else style
}

internal data class WindowsFullscreenBounds(val physical: Rectangle, val logical: () -> Rectangle)

internal fun defaultFullscreenAdapter(): DesktopFullscreenAdapter? {
    return if (Platform.isWindows()) WindowsBorderlessFullscreenAdapter() else null
}
