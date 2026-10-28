package mihon.desktop.ui.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.window.WindowPlacement
import cafe.adriel.voyager.navigator.Navigator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import mihon.desktop.platform.DesktopFullscreenAdapter
import mihon.desktop.platform.DesktopWindowTransitions
import mihon.desktop.platform.WindowsBorderlessFullscreenAdapter
import mihon.desktop.reader.desktopReaderSessionState
import mihon.desktop.ui.tutorial.DesktopTutorial
import mihon.desktop.ui.tutorial.DesktopTutorialState
import mihon.desktop.ui.tutorial.DesktopTutorialToastBubble
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.i18n.MR
import java.util.prefs.Preferences

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class ReaderFullscreenTest {

    @Test
    fun `reader state defaults to non-fullscreen`() {
        val state = ReaderState()
        assertFalse(state.isFullscreen, "Initial reader state must not be fullscreen")
    }

    @Test
    fun `model toggles fullscreen state and auto-hides UI on entry`() {
        val reader = desktopReaderSessionState(chapterId = 1L, pageCount = 3)
        val model = ReaderScreenModel(initialSessionState = reader)

        assertFalse(model.state.value.isFullscreen)

        // Enter fullscreen: UI should be auto-hidden for immersive reading
        model.toggleFullscreen()
        assertTrue(model.state.value.isFullscreen)
        assertFalse(model.state.value.showUI, "Entering fullscreen should hide the reader bars by default")

        // Exit fullscreen: UI should be shown
        model.toggleFullscreen()
        assertFalse(model.state.value.isFullscreen)
        assertTrue(model.state.value.showUI, "Exiting fullscreen should restore reader bars")
    }

    @Test
    fun `F11 key down toggles fullscreen and Escape exits fullscreen`() {
        val reader = desktopReaderSessionState(chapterId = 1L, pageCount = 3)
        val model = ReaderScreenModel(initialSessionState = reader)
        val navigator = mockk<Navigator>(relaxed = true)

        val f11Down = keyEvent(Key.F11, KeyEventType.KeyDown)
        val handledF11 = handleReaderKeyEvent(
            event = f11Down,
            state = model.state.value,
            model = model,
            navigator = navigator,
            readerNav = null,
            onPrevChapter = {},
            onNextChapter = {},
        )

        assertTrue(handledF11, "F11 must be handled by the reader")
        assertTrue(model.state.value.isFullscreen, "F11 must toggle fullscreen to true")

        // When in fullscreen, Escape must exit fullscreen rather than popping navigator
        val escDown = keyEvent(Key.Escape, KeyEventType.KeyDown)
        val handledEsc = handleReaderKeyEvent(
            event = escDown,
            state = model.state.value,
            model = model,
            navigator = navigator,
            readerNav = null,
            onPrevChapter = {},
            onNextChapter = {},
        )

        assertTrue(handledEsc, "Escape in fullscreen must be handled")
        assertFalse(model.state.value.isFullscreen, "Escape must exit fullscreen")
        verify(exactly = 0) { navigator.pop() }

        // When not in fullscreen, Escape pops navigator
        val handledEscWindowed = handleReaderKeyEvent(
            event = escDown,
            state = model.state.value,
            model = model,
            navigator = navigator,
            readerNav = null,
            onPrevChapter = {},
            onNextChapter = {},
        )
        assertTrue(handledEscWindowed)
        verify(exactly = 1) { navigator.pop() }
    }

    @Test
    fun `fullscreen tutorial is registered in tutorial system and acknowledged once`() {
        val tutorial = DesktopTutorial.READER_FULLSCREEN
        assertEquals("reader-fullscreen", tutorial.id)
        assertEquals(MR.strings.desktop_tutorial_fullscreen_title, tutorial.title)
        assertEquals(MR.strings.desktop_tutorial_fullscreen_message, tutorial.message)

        val root = Preferences.userRoot().node("/mihon/fullscreen-tutorial/${System.nanoTime()}")
        try {
            val store = DesktopPreferenceStore(root)
            val pref = store.getStringSet("acknowledged_tutorials", emptySet())
            val state = DesktopTutorialState(pref)

            assertTrue(state.shouldShow(tutorial, eligible = true))
            assertFalse(state.shouldShow(tutorial, eligible = false))

            // Acknowledge tutorial (e.g. after 3-second display or user confirmation)
            assertTrue(state.acknowledge(tutorial))
            assertFalse(state.shouldShow(tutorial, eligible = true), "Tutorial must not show once acknowledged")

            // Persists in new state instance
            val restoredState = DesktopTutorialState(pref)
            assertFalse(restoredState.shouldShow(tutorial, eligible = true), "Tutorial acknowledgement must persist")
        } finally {
            root.removeNode()
        }
    }

    @Test
    fun `DesktopTutorialToastBubble renders title message and confirm button`() {
        val tutorial = DesktopTutorial.READER_FULLSCREEN
        var confirmed = false
        val scene = ImageComposeScene(400, 200) {
            MaterialTheme {
                DesktopTutorialToastBubble(
                    tutorial = tutorial,
                    onConfirm = { confirmed = true },
                )
            }
        }
        try {
            scene.render()
            val nodes = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
            val hasToast = nodes.any {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" } ==
                    "desktop-tutorial-reader-fullscreen"
            }
            val confirmButton = nodes.single {
                it.config.getOrElse(SemanticsProperties.TestTag) { "" } ==
                    "desktop-tutorial-confirm"
            }
            assertTrue(hasToast, "Toast bubble must be rendered with correct test tag")

            val action = confirmButton.config.getOrNull(SemanticsActions.OnClick)
            assertNotNull(action, "Confirm button must have OnClick action")
            action?.action?.invoke()
            assertTrue(confirmed, "Clicking confirm must invoke onConfirm callback")
        } finally {
            scene.close()
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `WindowsBorderlessFullscreenAdapter enter and exit safely handle undisplayable window`() {
        val adapter = WindowsBorderlessFullscreenAdapter()
        val window = mockk<ComposeWindow>(relaxed = true)
        every { window.isDisplayable } returns false

        assertFalse(adapter.enter(window))
        assertFalse(adapter.exit(window))
    }

    @Test
    fun `syncPlacement uses borderless fullscreen adapter when available without placement mutations`() {
        val window = mockk<ComposeWindow>(relaxed = true)
        every { window.isDisplayable } returns true
        val adapter = mockk<DesktopFullscreenAdapter>(relaxed = true)
        every { adapter.enter(window) } returns true
        every { adapter.exit(window) } returns true

        val transitions = mockk<DesktopWindowTransitions>(relaxed = true)
        val controller = DesktopReaderFullscreenController(
            getWindow = { window },
            dispatch = { it.run() },
            transitions = transitions,
            fullscreenAdapter = adapter,
        )

        // Enter fullscreen
        controller.syncPlacement(isFullscreen = true)
        verify { adapter.enter(window) }
        verify { transitions.setDisabled(window, true) }
        verify(exactly = 0) { window.placement = any() }

        // Exit fullscreen
        controller.syncPlacement(isFullscreen = false)
        verify { adapter.exit(window) }
        verify { transitions.setDisabled(window, false) }
        verify(exactly = 0) { window.placement = any() }
    }

    @Test
    fun `onDispose delegates to fullscreen adapter if adapter was active`() {
        val window = mockk<ComposeWindow>(relaxed = true)
        every { window.isDisplayable } returns true
        val adapter = mockk<DesktopFullscreenAdapter>(relaxed = true)
        every { adapter.enter(window) } returns true
        every { adapter.exit(window) } returns true

        val transitions = mockk<DesktopWindowTransitions>(relaxed = true)
        val controller = DesktopReaderFullscreenController(
            getWindow = { window },
            dispatch = { it.run() },
            transitions = transitions,
            fullscreenAdapter = adapter,
        )

        controller.syncPlacement(isFullscreen = true)
        verify { adapter.enter(window) }

        controller.onDispose(wasFullscreen = true)
        verify { adapter.exit(window) }
        verify(exactly = 0) { window.placement = any() }
    }

    @Test
    fun `syncPlacement restores previous placement directly when native hook is available`() {
        val placements = mutableListOf<WindowPlacement>()
        var currentPlacement = WindowPlacement.Maximized
        val window = mockk<ComposeWindow>(relaxed = true)
        every { window.isDisplayable } returns true
        every { window.placement } answers { currentPlacement }
        every { window.placement = any() } answers {
            val next = firstArg<WindowPlacement>()
            placements.add(next)
            currentPlacement = next
        }

        val transitions = mockk<DesktopWindowTransitions>(relaxed = true)
        val controller = DesktopReaderFullscreenController(
            getWindow = { window },
            dispatch = { it.run() },
            transitions = transitions,
            fullscreenAdapter = null,
            setNativeFullscreen = { _, _ -> true },
        )

        // Enter fullscreen
        controller.syncPlacement(isFullscreen = true)
        assertEquals(WindowPlacement.Fullscreen, currentPlacement)
        assertEquals(WindowPlacement.Maximized, controller.previousPlacement)
        assertEquals(listOf(WindowPlacement.Fullscreen), placements)
        verify { transitions.setDisabled(window, true) }

        // Exit fullscreen: directly to Maximized without intermediate Floating flash
        placements.clear()
        controller.syncPlacement(isFullscreen = false)
        assertEquals(
            listOf(WindowPlacement.Maximized),
            placements,
            "Direct restoration must not emit intermediate Floating placement",
        )
        assertEquals(WindowPlacement.Maximized, currentPlacement)
        verify { transitions.setDisabled(window, false) }
    }

    @Test
    fun `syncPlacement safely falls back through floating transition if native fullscreen hook is unavailable`() {
        val placements = mutableListOf<WindowPlacement>()
        var currentPlacement = WindowPlacement.Maximized
        val window = mockk<ComposeWindow>(relaxed = true)
        every { window.isDisplayable } returns true
        every { window.placement } answers { currentPlacement }
        every { window.placement = any() } answers {
            val next = firstArg<WindowPlacement>()
            placements.add(next)
            currentPlacement = next
        }

        val controller = DesktopReaderFullscreenController(
            getWindow = { window },
            dispatch = { it.run() },
            fullscreenAdapter = null,
            setNativeFullscreen = { _, _ -> false },
        )

        controller.syncPlacement(isFullscreen = true)
        placements.clear()

        controller.syncPlacement(isFullscreen = false)
        assertEquals(listOf(WindowPlacement.Floating, WindowPlacement.Maximized), placements)
        assertEquals(WindowPlacement.Maximized, currentPlacement)
    }

    @Test
    fun `onDispose restores maximized placement directly without intermediate floating flash`() {
        val placements = mutableListOf<WindowPlacement>()
        var currentPlacement = WindowPlacement.Maximized
        val window = mockk<ComposeWindow>(relaxed = true)
        every { window.isDisplayable } returns true
        every { window.placement } answers { currentPlacement }
        every { window.placement = any() } answers {
            val next = firstArg<WindowPlacement>()
            placements.add(next)
            currentPlacement = next
        }

        val transitions = mockk<DesktopWindowTransitions>(relaxed = true)
        val controller = DesktopReaderFullscreenController(
            getWindow = { window },
            dispatch = { it.run() },
            transitions = transitions,
            fullscreenAdapter = null,
            setNativeFullscreen = { _, _ -> true },
        )

        controller.syncPlacement(isFullscreen = true)
        placements.clear()

        controller.onDispose(wasFullscreen = true)
        assertEquals(listOf(WindowPlacement.Maximized), placements)
        assertEquals(WindowPlacement.Maximized, currentPlacement)
        verify { transitions.setDisabled(window, false) }
    }

    @Test
    fun `onDispose safely falls back through floating transition if native hook is unavailable`() {
        val placements = mutableListOf<WindowPlacement>()
        var currentPlacement = WindowPlacement.Maximized
        val window = mockk<ComposeWindow>(relaxed = true)
        every { window.isDisplayable } returns true
        every { window.placement } answers { currentPlacement }
        every { window.placement = any() } answers {
            val next = firstArg<WindowPlacement>()
            placements.add(next)
            currentPlacement = next
        }

        val controller = DesktopReaderFullscreenController(
            getWindow = { window },
            dispatch = { it.run() },
            fullscreenAdapter = null,
            setNativeFullscreen = { _, _ -> false },
        )

        controller.syncPlacement(isFullscreen = true)
        placements.clear()

        controller.onDispose(wasFullscreen = true)
        assertEquals(listOf(WindowPlacement.Floating, WindowPlacement.Maximized), placements)
        assertEquals(WindowPlacement.Maximized, currentPlacement)
    }

    @Test
    fun `syncPlacement preserves previous placement across multiple rapid toggles`() {
        var currentPlacement = WindowPlacement.Floating
        val window = mockk<ComposeWindow>(relaxed = true)
        every { window.isDisplayable } returns true
        every { window.placement } answers { currentPlacement }
        every { window.placement = any() } answers { currentPlacement = firstArg() }

        val controller = DesktopReaderFullscreenController(
            getWindow = { window },
            dispatch = { it.run() },
        )

        // Toggle 1: enter fullscreen
        controller.syncPlacement(isFullscreen = true)
        assertEquals(WindowPlacement.Fullscreen, currentPlacement)

        // Toggle 1: exit fullscreen
        controller.syncPlacement(isFullscreen = false)
        assertEquals(WindowPlacement.Floating, currentPlacement)

        // Toggle 2: enter fullscreen again
        controller.syncPlacement(isFullscreen = true)
        assertEquals(WindowPlacement.Fullscreen, currentPlacement)

        // Toggle 2: exit fullscreen again
        controller.syncPlacement(isFullscreen = false)
        assertEquals(WindowPlacement.Floating, currentPlacement)
    }

    @Test
    fun `syncPlacement and onDispose safely handle undisplayable or throwing window without crashing`() {
        val window = mockk<ComposeWindow>(relaxed = true)
        every { window.isDisplayable } returns false
        every { window.placement } throws NullPointerException("null cannot be cast to non-null type java.awt.Window")

        val controller = DesktopReaderFullscreenController(
            getWindow = { window },
            dispatch = { it.run() },
        )

        // Does not crash when window is not displayable or throws
        controller.syncPlacement(isFullscreen = true)
        controller.syncPlacement(isFullscreen = false)
        controller.onDispose(wasFullscreen = true)
        controller.onDispose(wasFullscreen = false)

        // Even with null window
        val nullController = DesktopReaderFullscreenController(
            getWindow = { null },
            dispatch = { it.run() },
        )
        nullController.syncPlacement(isFullscreen = true)
        nullController.onDispose(wasFullscreen = true)
    }

    @Test
    fun `repeated fullscreen toggle cycle remains consistent and never desynchronizes`() {
        val reader = desktopReaderSessionState(chapterId = 1L, pageCount = 3)
        val model = ReaderScreenModel(initialSessionState = reader)
        try {
            repeat(10) { cycle ->
                model.toggleFullscreen()
                assertTrue(model.state.value.isFullscreen, "Cycle $cycle: must enter fullscreen")
                assertFalse(model.state.value.showUI, "Cycle $cycle: entering fullscreen must hide UI")

                model.toggleFullscreen()
                assertFalse(model.state.value.isFullscreen, "Cycle $cycle: must exit fullscreen")
                assertTrue(model.state.value.showUI, "Cycle $cycle: exiting fullscreen must restore UI")
            }
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `setFullscreen is idempotent and preserves UI state on repeated identical calls`() {
        val reader = desktopReaderSessionState(chapterId = 1L, pageCount = 3)
        val model = ReaderScreenModel(initialSessionState = reader)
        try {
            // Already false: no-op
            model.setFullscreen(false)
            assertFalse(model.state.value.isFullscreen)
            assertFalse(model.state.value.showUI)

            // Enter fullscreen
            model.setFullscreen(true)
            assertTrue(model.state.value.isFullscreen)
            assertFalse(model.state.value.showUI)

            // Reveal UI manually in fullscreen (e.g. mouse hover or tap)
            model.toggleUI()
            assertTrue(model.state.value.showUI)

            // Repeated setFullscreen(true) must NOT clobber manually revealed UI
            model.setFullscreen(true)
            assertTrue(model.state.value.isFullscreen)
            assertTrue(model.state.value.showUI)

            // Exit fullscreen
            model.setFullscreen(false)
            assertFalse(model.state.value.isFullscreen)
            assertTrue(model.state.value.showUI)

            // Repeated setFullscreen(false) must NOT change UI
            model.setFullscreen(false)
            assertFalse(model.state.value.isFullscreen)
            assertTrue(model.state.value.showUI)
        } finally {
            model.onDispose()
        }
    }

    @Test
    fun `syncPlacement ignores delayed async executions if disposed or overridden by newer intent`() {
        val runnables = mutableListOf<Runnable>()
        var currentPlacement = WindowPlacement.Floating
        var placementSetCount = 0
        val window = mockk<ComposeWindow>(relaxed = true)
        every { window.isDisplayable } returns true
        every { window.placement } answers { currentPlacement }
        every { window.placement = any() } answers {
            currentPlacement = firstArg()
            placementSetCount++
        }

        val controller = DesktopReaderFullscreenController(
            getWindow = { window },
            dispatch = { runnables.add(it) },
        )

        // Case 1: Disposed before queued execution runs
        controller.syncPlacement(isFullscreen = true)
        assertEquals(1, runnables.size)
        controller.onDispose(wasFullscreen = false)

        // Execute the delayed runnable after disposal
        runnables.removeAt(0).run()
        assertEquals(
            WindowPlacement.Floating,
            currentPlacement,
            "Delayed runnable must not enter fullscreen after disposal",
        )
        assertEquals(0, placementSetCount)

        // Case 2: Rapid toggle true -> false before any runnable runs
        val activeController = DesktopReaderFullscreenController(
            getWindow = { window },
            dispatch = { runnables.add(it) },
        )
        activeController.syncPlacement(isFullscreen = true)
        activeController.syncPlacement(isFullscreen = false)
        assertEquals(2, runnables.size)

        // Run the obsolete first runnable (which intended isFullscreen=true)
        runnables.removeAt(0).run()
        assertEquals(
            WindowPlacement.Floating,
            currentPlacement,
            "Obsolete first intent must be discarded without mutating window",
        )

        // Run the second runnable (isFullscreen=false)
        runnables.removeAt(0).run()
        assertEquals(WindowPlacement.Floating, currentPlacement)
    }

    private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)

    private fun keyEvent(key: Key, type: KeyEventType): KeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp")
            .invoke(null)
        val factory = events.declaredMethods.single {
            it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default")
        }
        val native = factory.invoke(null, key.keyCode, eventType, 0, false, false, false, false, null)
        return KeyEvent(native)
    }
}
