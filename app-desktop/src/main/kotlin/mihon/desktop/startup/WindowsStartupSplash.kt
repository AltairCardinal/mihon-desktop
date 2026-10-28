package mihon.desktop.startup

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference

/** The JVM owns the splash lifetime; only adjust its position before application initialization. */
internal fun positionDesktopStartupSplash() {
    if (!Platform.isWindows()) return
    // A missing splash or unavailable native API must never prevent application startup.
    runCatching { positionWindowsStartupSplash(User32.INSTANCE, ProcessHandle.current().pid().toInt()) }
}

internal fun positionWindowsStartupSplash(user32: User32, processId: Int): Boolean = runCatching {
    val cursor = WinDef.POINT()
    if (!user32.GetCursorPos(cursor)) return@runCatching false
    val monitor = user32.MonitorFromPoint(
        WinDef.POINT.ByValue(cursor.x, cursor.y),
        WinUser.MONITOR_DEFAULTTONEAREST,
    ) ?: return@runCatching false
    val info = WinUser.MONITORINFO()
    if (!user32.GetMonitorInfo(monitor, info).booleanValue()) return@runCatching false

    var positioned = false
    user32.EnumWindows({ window, _ ->
        val owner = IntByReference()
        user32.GetWindowThreadProcessId(window, owner)
        if (owner.value != processId) return@EnumWindows true
        val className = CharArray(64)
        if (user32.GetClassName(window, className, className.size) == 0 ||
            Native.toString(className) != "JavaSplash"
        ) return@EnumWindows true
        val bounds = WinDef.RECT()
        if (user32.GetWindowRect(window, bounds)) {
            val work = info.rcWork
            positioned = user32.SetWindowPos(
                window,
                null,
                work.left + (work.right - work.left - (bounds.right - bounds.left)) / 2,
                work.top + (work.bottom - work.top - (bounds.bottom - bounds.top)) / 2,
                0,
                0,
                WinUser.SWP_NOSIZE or WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE,
            )
        }
        false
    }, null)
    positioned
}.getOrDefault(false)
