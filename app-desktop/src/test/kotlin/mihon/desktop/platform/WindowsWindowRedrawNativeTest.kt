package mihon.desktop.platform

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.Frame
import java.awt.Rectangle
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities

@Tag("native-window")
@EnabledOnOs(OS.WINDOWS)
class WindowsWindowRedrawNativeTest {
    @Test
    fun `redraw hold restores the original visible peer and closes only once`() = withWindow { window ->
        val hwnd = WinDef.HWND(Native.getComponentPointer(window))
        assertTrue(User32.INSTANCE.IsWindowVisible(hwnd))
        val hold = requireNotNull(suspendWindowsWindowRedraw(window))
        assertTrue(window.isVisible, "AWT must still permit the real canvas to render")
        assertFalse(User32.INSTANCE.IsWindowVisible(hwnd), "The native representation must be held")
        hold.close()
        assertTrue(User32.INSTANCE.IsWindowVisible(hwnd))
        window.isVisible = false
        hold.close()
        assertFalse(User32.INSTANCE.IsWindowVisible(hwnd), "A repeated close must not resurrect a hidden window")
    }

    @Test
    fun `originally hidden or hidden during a hold stays hidden`() = withWindow { window ->
        val hwnd = WinDef.HWND(Native.getComponentPointer(window))
        val hold = requireNotNull(suspendWindowsWindowRedraw(window))
        window.isVisible = false
        hold.close()
        assertFalse(User32.INSTANCE.IsWindowVisible(hwnd))
        assertNull(suspendWindowsWindowRedraw(window))
        assertFalse(User32.INSTANCE.IsWindowVisible(hwnd))
    }

    @Test
    fun `closing a hold after native disposal does not recreate the peer`() = withWindow { window ->
        val hold = requireNotNull(suspendWindowsWindowRedraw(window))
        window.dispose()
        hold.close()
        assertFalse(window.isDisplayable)
        assertFalse(window.isVisible)
    }

    @Test
    fun `deferred exit completion cannot reshow a window hidden during the transition`() = withWindow { window ->
        val target = Rectangle(-20000, -20000, 640, 480)
        val transform = window.graphicsConfiguration.defaultTransform
        val physical = Rectangle(
            (target.x * transform.scaleX).toInt(),
            (target.y * transform.scaleY).toInt(),
            (target.width * transform.scaleX).toInt(),
            (target.height * transform.scaleY).toInt(),
        )
        val adapter = WindowsBorderlessFullscreenAdapter(
            bounds = { _, _ -> WindowsFullscreenBounds(physical) { target } },
        )
        assertTrue(adapter.enter(window))
        adapter.completeTransition(window)
        assertTrue(adapter.exit(window))
        window.isVisible = false
        adapter.completeTransition(window)
        assertFalse(User32.INSTANCE.IsWindowVisible(WinDef.HWND(Native.getComponentPointer(window))))
    }

    private fun withWindow(action: (Frame) -> Unit) {
        val task = FutureTask {
            val window = Frame().apply {
                setBounds(-20000, -20000, 320, 240)
                focusableWindowState = false
                isAutoRequestFocus = false
                isVisible = true
            }
            try {
                action(window)
                assertFalse(window.isActive)
            } finally {
                window.dispose()
            }
        }
        SwingUtilities.invokeAndWait(task)
        task.get()
    }
}
