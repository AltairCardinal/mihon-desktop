package mihon.desktop.platform

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.launchApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.desktop.settings.DesktopAppPreferences
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import java.awt.Frame
import java.awt.Rectangle
import java.awt.event.WindowEvent
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.prefs.Preferences
import javax.swing.SwingUtilities

/** Opens only isolated, temporary production window shells with an empty body. */
@Tag("native-window")
@EnabledOnOs(OS.WINDOWS)
class DesktopWindowGeometryTest {
    private val root = Preferences.userRoot().node("/mihon/window-geometry-test/${UUID.randomUUID()}")

    @AfterEach
    fun cleanup() = root.removeNode()

    private fun preferences() = DesktopAppPreferences(DesktopPreferenceStore(root.node("store")), root.node("legacy"))

    @Test
    fun `native close and restart restores resized moved production window`() = runBlocking {
        val expected = Rectangle(140, 110, 860, 610)
        window(preferences()) { frame ->
            onEdt { frame.bounds = expected }
            waitFor { frame.bounds == expected }
        }
        assertTrue(preferences().windowGeometry.get().isNotBlank(), "Native close must persist geometry")
        assertTrue(Preference.isAppState(preferences().windowGeometry.key()))
        window(preferences()) { frame ->
            waitFor { frame.bounds == expected }
            assertEquals(expected, onEdt { frame.bounds })
            assertEquals(Frame.NORMAL, onEdt { frame.extendedState })
        }
    }

    @Test
    fun `maximized close preserves normal bounds and restores maximized placement`() = runBlocking {
        val expected = Rectangle(160, 130, 850, 600)
        window(preferences()) { frame ->
            onEdt { frame.bounds = expected }
            waitFor { frame.bounds == expected }
            // Drain the resize event before maximizing.
            onEdt {}
            onEdt { frame.extendedState = Frame.MAXIMIZED_BOTH }
            waitFor { frame.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH }
        }
        window(preferences()) { frame ->
            waitFor { frame.extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH }
            onEdt { frame.extendedState = Frame.NORMAL }
            waitFor { frame.bounds == expected && frame.extendedState == Frame.NORMAL }
            assertEquals(expected, onEdt { frame.bounds })
        }
    }

    @Test
    fun `minimized close restores a visible ordinary window`() = runBlocking {
        val expected = Rectangle(120, 90, 840, 590)
        window(preferences()) { frame ->
            onEdt { frame.bounds = expected }
            waitFor { frame.bounds == expected }
            onEdt {}
            onEdt { frame.extendedState = Frame.ICONIFIED }
            waitFor { frame.extendedState and Frame.ICONIFIED != 0 }
        }
        window(preferences()) { frame ->
            waitFor { frame.bounds == expected }
            assertFalse(onEdt { frame.extendedState and Frame.ICONIFIED != 0 })
        }
    }

    private suspend fun window(preferences: DesktopAppPreferences, assertions: (ComposeWindow) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ready = CompletableDeferred<ComposeWindow>()
        val job = scope.launchApplication {
            DesktopMainWindow(preferences, onCloseRequest = ::exitApplication, title = "Mihon isolated geometry test") {
                LaunchedEffect(window) { ready.complete(window) }
            }
        }
        var frame: ComposeWindow? = null
        try {
            frame = withTimeout(10_000) { ready.await() }
            waitFor { frame.isShowing }
            assertions(frame)
        } finally {
            try {
                if (frame == null) job.cancel()
                frame?.let { opened -> onEdt { opened.dispatchEvent(WindowEvent(opened, WindowEvent.WINDOW_CLOSING)) } }
                withTimeout(10_000) { job.join() }
            } finally {
                scope.cancel()
            }
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!onEdt(condition) && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(onEdt(condition), "Native window did not reach the expected geometry")
    }

    private fun <T> onEdt(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return action()
        val task = FutureTask(Callable(action))
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}
