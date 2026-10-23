package mihon.desktop.platform

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.launchApplication
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.ptr.IntByReference
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
import java.awt.AWTEvent
import java.awt.Dimension
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.HierarchyEvent
import java.awt.event.WindowEvent
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.prefs.Preferences
import javax.swing.SwingUtilities

/** Opens isolated production window shells with a measured drawing surface. */
@Tag("native-window")
@EnabledOnOs(OS.WINDOWS)
class DesktopWindowGeometryTest {
    private val root = Preferences.userRoot().node("/mihon/window-geometry-test/${UUID.randomUUID()}")

    @AfterEach
    fun cleanup() = root.removeNode()

    private fun preferences() = DesktopAppPreferences(DesktopPreferenceStore(root.node("store")), root.node("legacy"))

    @Test
    fun `maximized content is drawn at final size before window becomes visible`() = runBlocking {
        preferences().windowGeometry.set(
            """{"x":140,"y":110,"width":860,"height":610,"maximized":true}""",
        )
        var drawnSize: Dimension? = null
        val firstVisible = CompletableDeferred<Triple<Dimension?, Dimension, Boolean>>()
        val listener = AWTEventListener { event ->
            val frame = event.source as? ComposeWindow
            if (event is HierarchyEvent && event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L &&
                frame?.title == "Mihon isolated geometry test" && frame.isShowing
            ) {
                val insets = frame.insets
                val scale = frame.graphicsConfiguration.defaultTransform
                firstVisible.complete(
                    Triple(
                        drawnSize,
                        Dimension(
                            ((frame.width - insets.left - insets.right) * scale.scaleX).toInt(),
                            ((frame.height - insets.top - insets.bottom) * scale.scaleY).toInt(),
                        ),
                        isCloaked(frame),
                    ),
                )
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.HIERARCHY_EVENT_MASK)
        try {
            window(preferences(), onDraw = { drawnSize = it }) { frame ->
                val (drawn, expected, cloaked) = withTimeout(5_000) { firstVisible.await() }
                assertTrue(
                    cloaked || matchesPixels(expected, drawn),
                    "Undersized first frame must not be exposed: expected=$expected drawn=$drawn",
                )
                waitFor { !isCloaked(frame) }
                assertTrue(
                    onEdt { matchesPixels(expected, drawnSize) },
                    "Revealed content must already fill the maximized client area: expected=$expected drawn=$drawnSize",
                )
            }
        } finally {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
        }
    }

    private fun matchesPixels(expected: Dimension, actual: Dimension?): Boolean = actual != null &&
        // AWT logical bounds and Compose physical pixels can round differently at fractional DPI.
        kotlin.math.abs(expected.width - actual.width) <= 2 &&
        kotlin.math.abs(expected.height - actual.height) <= 2

    private fun isCloaked(frame: ComposeWindow): Boolean {
        val value = IntByReference()
        val result = NativeLibrary.getInstance("dwmapi")
            .getFunction("DwmGetWindowAttribute", Function.ALT_CONVENTION)
            .invokeInt(arrayOf(Native.getComponentPointer(frame), 14, value, Int.SIZE_BYTES))
        check(result >= 0) { "Could not read native cloak state: $result" }
        return value.value != 0
    }

    @Test
    fun `restored normal bounds are applied before first native visibility`() = runBlocking {
        assertFirstVisibleGeometry(maximized = false)
    }

    @Test
    fun `restored maximized bounds are applied before first native visibility`() = runBlocking {
        assertFirstVisibleGeometry(maximized = true)
    }

    private suspend fun assertFirstVisibleGeometry(maximized: Boolean) {
        preferences().windowGeometry.set(
            """{"x":140,"y":110,"width":860,"height":610,"maximized":$maximized}""",
        )
        val firstVisible = CompletableDeferred<Pair<Rectangle, Boolean>>()
        var disabled = false
        val transitions = DesktopWindowTransitions { frame, value ->
            assertTrue(WindowsWindowTransitions.setDisabled(frame, value), "Native DWM call must succeed")
            if (value) assertFalse(frame.isShowing, "Disable transitions before showing")
            disabled = value
            true
        }
        val listener = AWTEventListener { event ->
            val frame = event.source as? ComposeWindow
            if (event is HierarchyEvent && event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L &&
                frame?.title == "Mihon isolated geometry test" && frame.isShowing
            ) {
                firstVisible.complete(Rectangle(frame.bounds) to disabled)
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.HIERARCHY_EVENT_MASK)
        try {
            window(preferences(), transitions) { frame ->
                assertTrue(firstVisible.isCompleted, "Must observe the first native visibility")
                val (firstBounds, firstTransitionsDisabled) = withTimeout(5_000) { firstVisible.await() }
                val expected = if (maximized) {
                    // Windows maximized frames include their invisible resize borders.
                    waitFor { frame.extendedState and Frame.MAXIMIZED_BOTH != 0 }
                    onEdt { Rectangle(frame.bounds) }
                } else {
                    Rectangle(140, 110, 860, 610)
                }
                assertEquals(expected, firstBounds, "First visible bounds must already be restored")
                assertEquals(
                    maximized,
                    firstTransitionsDisabled,
                    "Only restored maximization must suppress the initial DWM transition",
                )
                waitFor { !disabled }
                if (maximized) assertTrue(expected.width > 860, "Expected a genuinely maximized window")
            }
        } finally {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
        }
    }

    @Test
    fun `unavailable native transitions do not prevent maximized startup`() = runBlocking {
        preferences().windowGeometry.set(
            """{"x":140,"y":110,"width":860,"height":610,"maximized":true}""",
        )
        window(preferences(), DesktopWindowTransitions { _, _ -> false }) { frame ->
            assertTrue(onEdt { frame.isShowing && frame.extendedState and Frame.MAXIMIZED_BOTH != 0 })
        }
    }

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

    private suspend fun window(
        preferences: DesktopAppPreferences,
        transitions: DesktopWindowTransitions = WindowsWindowTransitions,
        onDraw: (Dimension) -> Unit = {},
        assertions: suspend (ComposeWindow) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ready = CompletableDeferred<ComposeWindow>()
        val job = scope.launchApplication {
            DesktopMainWindow(
                preferences.windowGeometryStore(),
                onCloseRequest = ::exitApplication,
                title = "Mihon isolated geometry test",
                windowTransitions = transitions,
            ) {
                Canvas(Modifier.fillMaxSize()) { onDraw(Dimension(size.width.toInt(), size.height.toInt())) }
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
