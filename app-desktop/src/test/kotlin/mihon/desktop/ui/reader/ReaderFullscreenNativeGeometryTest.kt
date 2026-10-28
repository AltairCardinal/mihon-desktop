package mihon.desktop.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import mihon.desktop.platform.DesktopWindowTransitions
import mihon.desktop.platform.WindowsWindowTransitions
import mihon.desktop.platform.setWindowsWindowCloaked
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.Canvas
import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.math.abs

/** Cloaked peers exercise real Win32, AWT and Skiko geometry without showing or focusing a test window. */
@Tag("native-window")
@EnabledOnOs(OS.WINDOWS)
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ReaderFullscreenNativeGeometryTest {
    @Test
    fun `repeated floating fullscreen cycles keep the native canvas aligned with the client`() = runCycles(false)

    @Test
    fun `repeated maximized fullscreen cycles keep the native canvas aligned with the client`() = runCycles(true)

    private fun runCycles(maximized: Boolean) {
        for (device in GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.take(2)) {
            val window = onEdt {
                ComposeWindow(device.defaultConfiguration).apply {
                    val screen = device.defaultConfiguration.bounds
                    setBounds(screen.x + 40, screen.y + 40, 640, 480)
                    setContent { Box(Modifier.fillMaxSize()) }
                    isAutoRequestFocus = false
                    focusableWindowState = false
                    addNotify()
                    assertTrue(setWindowsWindowCloaked(this, true), "Test window must remain cloaked")
                    if (maximized) extendedState = Frame.MAXIMIZED_BOTH
                    isVisible = true
                    validate()
                    assertFalse(isActive, "Geometry regression must not activate its test peer")
                }
            }
            val completed = AtomicReference<CountDownLatch>()
            val transitions = DesktopWindowTransitions { frame, disabled ->
                val result = WindowsWindowTransitions.setDisabled(frame, disabled)
                if (!disabled) completed.get()?.countDown()
                result
            }
            val controller = DesktopReaderFullscreenController({ window }, transitions = transitions)
            fun switch(fullscreen: Boolean) {
                val completion = CountDownLatch(1)
                completed.set(completion)
                onEdt { controller.syncPlacement(fullscreen) }
                assertTrue(completion.await(5, TimeUnit.SECONDS), "Native transition must finish")
            }
            try {
                Thread.sleep(100)
                onEdt { assertCanvasFillsClient(window, "initial ${device.iDstring}") }
                repeat(6) { cycle ->
                    switch(true)
                    onEdt { assertCanvasFillsClient(window, "fullscreen ${device.iDstring} cycle=$cycle") }
                    switch(false)
                    onEdt { assertCanvasFillsClient(window, "restored ${device.iDstring} cycle=$cycle") }
                }
            } finally {
                onEdt {
                    controller.onDispose(wasFullscreen = false)
                    window.dispose()
                }
            }
        }
    }

    private fun assertCanvasFillsClient(window: ComposeWindow, context: String) {
        val canvas = descendants(window).filterIsInstance<Canvas>().single()
        val user32 = User32.INSTANCE
        val frameHandle = WinDef.HWND(Native.getComponentPointer(window))
        val canvasHandle = WinDef.HWND(Native.getComponentPointer(canvas))
        val client = WinDef.RECT()
        val origin = WinDef.POINT()
        val drawn = WinDef.RECT()
        assertTrue(user32.GetClientRect(frameHandle, client))
        assertTrue(
            NativeLibrary.getInstance("user32").getFunction("ClientToScreen", Function.ALT_CONVENTION)
                .invokeInt(arrayOf(frameHandle, origin)) != 0,
        )
        assertTrue(user32.GetWindowRect(canvasHandle, drawn))
        val expected = listOf(origin.x, origin.y, origin.x + client.right, origin.y + client.bottom)
        val actual = listOf(drawn.left, drawn.top, drawn.right, drawn.bottom)
        assertTrue(client.right > 0 && client.bottom > 0, "A real native client is required")
        assertTrue(
            expected.zip(actual).all { (want, have) -> abs(want - have) <= 2 },
            "$context: client=$expected canvas=$actual awtInsets=${window.insets} " +
                "awtWindow=${window.bounds} awtCanvas=${canvas.bounds} content=${window.contentPane.bounds}",
        )
        assertFalse(window.isActive)
    }

    private fun descendants(container: Container): Sequence<Component> = sequence {
        for (child in container.components) {
            yield(child)
            if (child is Container) yieldAll(descendants(child))
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(action)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}
