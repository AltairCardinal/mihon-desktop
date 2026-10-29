package mihon.desktop.platform

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.Window
import java.util.concurrent.atomic.AtomicBoolean

/** Retain the existing window representation while its client origin, canvas and D3D buffer change. */
internal fun suspendWindowsWindowRedraw(window: Window, user32: User32 = User32.INSTANCE): AutoCloseable? {
    if (!Platform.isWindows() || !window.isShowing || !window.isDisplayable) return null
    return runCatching {
        val pointer = Native.getComponentPointer(window)
        val hwnd = WinDef.HWND(pointer)
        // WM_SETREDRAW(TRUE) would make an originally hidden window visible.
        if (!user32.IsWindowVisible(hwnd)) return null
        user32.SendMessage(hwnd, WM_SETREDRAW, WinDef.WPARAM(0), WinDef.LPARAM(0))
        val closed = AtomicBoolean(false)
        AutoCloseable {
            if (!closed.compareAndSet(false, true)) return@AutoCloseable
            if (window.isDisplayable && window.isVisible &&
                Pointer.nativeValue(Native.getComponentPointer(window)) == Pointer.nativeValue(pointer) &&
                user32.IsWindow(hwnd)
            ) {
                user32.SendMessage(hwnd, WM_SETREDRAW, WinDef.WPARAM(1), WinDef.LPARAM(0))
                // The D3D canvas has already repainted; do not erase its completed frame.
                user32.RedrawWindow(
                    hwnd,
                    null,
                    null,
                    WinDef.DWORD((WinUser.RDW_INVALIDATE or WinUser.RDW_FRAME or WinUser.RDW_ALLCHILDREN).toLong()),
                )
            }
        }
    }.getOrNull()
}

private const val WM_SETREDRAW = 0x000B
