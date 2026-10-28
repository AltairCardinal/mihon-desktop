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
            transitions.setDisabled(window, false)
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
            transitions.setDisabled(window, false)
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
        verify(exactly = 0) { transitions.setDisabled(window, false) }

        pending.removeAt(0).run()
        verify(exactly = 1) { window.renderImmediately() }
        verify(exactly = 1) { transitions.setDisabled(window, false) }
    }

    @Test
    fun `draw failure still restores transitions`() {
        every { window.renderImmediately() } throws IllegalStateException("Detached render surface")

        controller.syncPlacement(true)

        verify { window.renderImmediately() }
        verifyOrder {
            transitions.setDisabled(window, true)
            transitions.setDisabled(window, false)
        }
    }
}
