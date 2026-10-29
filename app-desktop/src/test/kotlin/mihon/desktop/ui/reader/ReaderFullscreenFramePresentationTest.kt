package mihon.desktop.ui.reader

import androidx.compose.ui.awt.ComposeWindow
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import mihon.desktop.platform.DesktopFullscreenAdapter
import mihon.desktop.platform.DesktopWindowTransitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ReaderFullscreenFramePresentationTest {
    private val window = mockk<ComposeWindow>(relaxed = true) {
        every { isDisplayable } returns true
    }
    private val adapter = mockk<DesktopFullscreenAdapter>(relaxed = true) {
        every { enter(window) } returns true
        every { exit(window) } returns true
    }
    private val transitions = mockk<DesktopWindowTransitions>(relaxed = true)
    private val controller = DesktopReaderFullscreenController(
        getWindow = { window },
        dispatch = { it.run() },
        transitions = transitions,
        fullscreenAdapter = adapter,
    )

    @Test
    fun `enter lays out and draws the final client area before restoring transitions`() {
        controller.syncPlacement(true)

        verifyOrder {
            transitions.setDisabled(window, true)
            adapter.enter(window)
            window.invalidate()
            window.validate()
            window.renderImmediately()
            adapter.completeTransition(window)
            transitions.setDisabled(window, false)
            adapter.releaseTransitionFrame(window)
        }
    }

    @Test
    fun `exit draws the restored client area before restoring transitions`() {
        controller.syncPlacement(true)
        controller.syncPlacement(false)

        verifyOrder {
            adapter.exit(window)
            window.invalidate()
            window.validate()
            window.renderImmediately()
            adapter.completeTransition(window)
            transitions.setDisabled(window, false)
            adapter.releaseTransitionFrame(window)
        }
    }

    @Test
    fun `leaving the reader draws its restored window before restoring transitions`() {
        controller.syncPlacement(true)
        controller.onDispose(wasFullscreen = true)

        verifyOrder {
            adapter.exit(window)
            window.invalidate()
            window.validate()
            window.renderImmediately()
            adapter.completeTransition(window)
            transitions.setDisabled(window, false)
        }
    }

    @Test
    fun `native resize events are processed before final layout and drawing`() {
        val pending = mutableListOf<Runnable>()
        val queuedController = DesktopReaderFullscreenController(
            getWindow = { window },
            dispatch = { pending += it },
            transitions = transitions,
            fullscreenAdapter = adapter,
        )
        queuedController.syncPlacement(true)
        pending.removeAt(0).run()

        verify { adapter.enter(window) }
        verify(exactly = 0) { window.renderImmediately() }
        verify(exactly = 0) { transitions.setDisabled(window, false) }
        assertEquals(1, pending.size, "A second event turn must complete the native resize")

        pending.removeAt(0).run()
        verifyOrder {
            window.invalidate()
            window.validate()
            window.renderImmediately()
            transitions.setDisabled(window, false)
        }
    }

    @Test
    fun `obsolete frame completion cannot redraw or restore transitions over a newer resize`() {
        val pending = mutableListOf<Runnable>()
        val queuedController = DesktopReaderFullscreenController(
            getWindow = { window },
            dispatch = { pending += it },
            transitions = transitions,
            fullscreenAdapter = adapter,
        )
        every { adapter.enter(window) } answers {
            queuedController.syncPlacement(false)
            true
        }
        queuedController.syncPlacement(true)
        pending.removeAt(0).run()
        pending.removeAt(0).run()

        pending.removeAt(0).run()
        verify(exactly = 0) { window.renderImmediately() }
        verify(exactly = 0) { adapter.completeTransition(window) }
        verify(exactly = 0) { transitions.setDisabled(window, false) }

        pending.removeAt(0).run()
        verify(exactly = 1) { window.renderImmediately() }
        verify(exactly = 1) { adapter.completeTransition(window) }
        verify(exactly = 1) { transitions.setDisabled(window, false) }
    }

    @Test
    fun `entry resizes and presents one live frame without an intermediate presentation`() {
        val pending = mutableListOf<Runnable>()
        val events = mutableListOf<String>()
        every { adapter.enter(window) } answers {
            events += "resize"
            true
        }
        every { window.renderImmediately() } answers { events += "draw" }
        every { transitions.setDisabled(window, false) } answers {
            events += "present"
            true
        }
        val queuedController = DesktopReaderFullscreenController(
            getWindow = { window },
            dispatch = { pending += it },
            transitions = transitions,
            fullscreenAdapter = adapter,
        )
        queuedController.syncPlacement(true)
        pending.removeAt(0).run()
        assertEquals(listOf("resize"), events)
        pending.removeAt(0).run()
        assertEquals(listOf("resize", "draw", "present"), events)
    }

    @Test
    fun `native entry failure restores transitions without drawing a detached frame`() {
        every { adapter.enter(window) } throws IllegalStateException("Native frame is detached")
        val failedController = DesktopReaderFullscreenController(
            getWindow = { window },
            dispatch = { it.run() },
            transitions = transitions,
            fullscreenAdapter = adapter,
        )
        failedController.syncPlacement(true)
        verify(exactly = 0) { window.renderImmediately() }
        verify { adapter.completeTransition(window) }
        verify { transitions.setDisabled(window, false) }
    }

    @Test
    fun `disposing a pending entry restores transitions without presenting its obsolete frame`() {
        val pending = mutableListOf<Runnable>()
        val queuedController = DesktopReaderFullscreenController(
            getWindow = { window },
            dispatch = { pending += it },
            transitions = transitions,
            fullscreenAdapter = adapter,
        )
        queuedController.syncPlacement(true)
        pending.removeAt(0).run()
        queuedController.onDispose(wasFullscreen = false)
        pending.removeAt(0).run()
        verify(exactly = 1) { transitions.setDisabled(window, false) }
        verify(exactly = 1) { adapter.completeTransition(window) }
        verify(exactly = 0) { window.renderImmediately() }
    }

    @Test
    fun `draw failure still restores transitions`() {
        every { window.renderImmediately() } throws IllegalStateException("Detached render surface")

        controller.syncPlacement(true)

        verify { window.renderImmediately() }
        verifyOrder {
            transitions.setDisabled(window, true)
            adapter.completeTransition(window)
            transitions.setDisabled(window, false)
        }
    }

    @Test
    fun `adapter completion failure still restores system transitions`() {
        every { adapter.completeTransition(window) } throws IllegalStateException("Native peer closed")

        controller.syncPlacement(true)

        verifyOrder {
            window.renderImmediately()
            adapter.completeTransition(window)
            transitions.setDisabled(window, false)
            adapter.releaseTransitionFrame(window)
        }
    }

    @Test
    fun `presentation flush failure still releases the retained reader frame`() {
        every { transitions.setDisabled(window, false) } throws IllegalStateException("DWM unavailable")
        controller.syncPlacement(true)
        verifyOrder {
            adapter.completeTransition(window)
            transitions.setDisabled(window, false)
            adapter.releaseTransitionFrame(window)
        }
    }
}
