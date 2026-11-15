package mihon.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.launchApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.event.WindowEvent
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

@Tag("native-window")
@EnabledOnOs(OS.WINDOWS)
class DesktopApplicationWindowCloseNativeTest {
    @Test
    fun `real window hides before checkpoint finishes and disposes only after cleanup`() = runBlocking {
        val ready = CompletableDeferred<ComposeWindow>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val composed = AtomicBoolean()
        val coordinator = DesktopApplicationCloseCoordinator({
            cleanupStarted.complete(Unit)
            releaseCleanup.await()
        }, null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launchApplication {
            DesktopApplicationWindow(null, coordinator, "Mihon isolated close test") {
                DisposableEffect(window) {
                    window.focusableWindowState = false
                    window.isAutoRequestFocus = false
                    window.setLocation(-20_000, -20_000)
                    composed.set(true)
                    ready.complete(window)
                    onDispose { composed.set(false) }
                }
            }
        }
        var frame: ComposeWindow? = null
        try {
            val opened = withTimeout(10_000) { ready.await() }
            frame = opened
            waitFor { opened.isShowing }
            onEdt { opened.dispatchEvent(WindowEvent(opened, WindowEvent.WINDOW_CLOSING)) }
            withTimeout(5_000) { cleanupStarted.await() }
            waitFor { !opened.isVisible }
            assertTrue(composed.get(), "Hiding must retain the owner composition during checkpoint cleanup")
            assertFalse(job.isCompleted)
            assertFalse(onEdt { opened.isActive })
            releaseCleanup.complete(Unit)
            withTimeout(10_000) { job.join() }
            assertFalse(composed.get())
            assertFalse(onEdt { opened.isDisplayable })
        } finally {
            releaseCleanup.complete(Unit)
            try {
                if (frame == null) job.cancel()
                frame?.takeIf { job.isActive }?.let { opened ->
                    onEdt { opened.dispatchEvent(WindowEvent(opened, WindowEvent.WINDOW_CLOSING)) }
                }
                withTimeout(10_000) { job.join() }
            } finally {
                scope.cancel()
            }
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!onEdt(condition) && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(onEdt(condition), "Native close state did not settle")
    }

    private fun <T> onEdt(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return action()
        val task = FutureTask(Callable(action))
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}
