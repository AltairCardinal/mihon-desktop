package mihon.desktop.startup

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class WindowsStartupSplashTest {
    @Test
    fun `startup splash is centered in the cursor monitor work area without activating or resizing it`() {
        val desktop = NativeDesktop()

        assertTrue(positionWindowsStartupSplash(desktop.user32, 42))

        assertEquals(listOf(-1216, 392, 0, 0), desktop.moves.single())
        assertEquals(
            WinUser.SWP_NOSIZE or WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE,
            desktop.flags,
        )
        assertEquals(listOf(desktop.splash), desktop.movedWindows)
    }

    @Test
    fun `monitor above primary retains its negative vertical origin`() {
        val desktop = NativeDesktop(left = 0, top = -1200)
        assertTrue(positionWindowsStartupSplash(desktop.user32, 42))
        assertEquals(listOf(704, -808, 0, 0), desktop.moves.single())
    }

    @Test
    fun `no splash in this process leaves all windows untouched`() {
        val desktop = NativeDesktop(hasSplash = false)
        assertFalse(positionWindowsStartupSplash(desktop.user32, 42))
        assertTrue(desktop.moves.isEmpty())
    }

    @Test
    fun `failed native queries do not move windows or prevent startup`() {
        for (failure in listOf("GetCursorPos", "GetMonitorInfo", "GetWindowRect", "SetWindowPos")) {
            val desktop = NativeDesktop(failure = failure)
            assertFalse(positionWindowsStartupSplash(desktop.user32, 42), failure)
            if (failure != "SetWindowPos") assertTrue(desktop.moves.isEmpty(), failure)
        }
    }

    private class NativeDesktop(
        private val left: Int = -1920,
        private val top: Int = 0,
        private val hasSplash: Boolean = true,
        private val failure: String? = null,
    ) {
        val splash = WinDef.HWND(Pointer.createConstant(3))
        val moves = mutableListOf<List<Int>>()
        val movedWindows = mutableListOf<WinDef.HWND>()
        var flags: Int? = null
        private val foreignSplash = WinDef.HWND(Pointer.createConstant(1))
        private val main = WinDef.HWND(Pointer.createConstant(2))

        val user32 = Proxy.newProxyInstance(
            User32::class.java.classLoader,
            arrayOf(User32::class.java),
        ) { _, method, arguments ->
            val args = arguments.orEmpty()
            if (method.name == failure) {
                if (method.name == "GetMonitorInfo") WinDef.BOOL(false) else false
            } else {
                when (method.name) {
                    "GetCursorPos" -> {
                        (args[0] as WinDef.POINT).apply { x = left + 100; y = top + 100 }
                        true
                    }
                    "MonitorFromPoint" -> {
                        val point = args[0] as WinDef.POINT
                        assertEquals(left + 100, point.x)
                        assertEquals(top + 100, point.y)
                        WinUser.HMONITOR(Pointer.createConstant(10))
                    }
                    "GetMonitorInfo" -> {
                        (args[1] as WinUser.MONITORINFO).rcWork.apply {
                            this.left = this@NativeDesktop.left
                            this.top = this@NativeDesktop.top
                            right = left + 1920
                            bottom = top + 1040
                        }
                        WinDef.BOOL(true)
                    }
                    "EnumWindows" -> {
                        val callback = args[0] as WinUser.WNDENUMPROC
                        val windows = listOf(foreignSplash, main) + if (hasSplash) listOf(splash) else emptyList()
                        for (window in windows) {
                            if (!callback.callback(window, null)) break
                        }
                        true
                    }
                    "GetWindowThreadProcessId" -> {
                        (args[1] as IntByReference).value = if (args[0] == foreignSplash) 99 else 42
                        1
                    }
                    "GetClassName" -> {
                        val name = if (args[0] == main) "SunAwtFrame" else "JavaSplash"
                        name.toCharArray().copyInto(args[1] as CharArray)
                        name.length
                    }
                    "GetWindowRect" -> {
                        (args[1] as WinDef.RECT).apply { left = 0; top = 0; right = 512; bottom = 256 }
                        true
                    }
                    "SetWindowPos" -> {
                        movedWindows += args[0] as WinDef.HWND
                        moves += (2..5).map { args[it] as Int }
                        flags = args[6] as Int
                        true
                    }
                    else -> error("Unexpected native call: ${method.name}")
                }
            }
        } as User32
    }
}
