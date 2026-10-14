package mihon.desktop.easteregg.gamepad

import mihon.desktop.easteregg.KonamiStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

class GamepadKonamiSourceTest {

    @Test
    fun `a dpad press raises one rising edge and holding it raises nothing`() {
        val edges = GamepadKonamiEdgeDetector()

        assertNull(edges.onSnapshot(null))
        assertEquals(KonamiStep.UP, edges.onSnapshot(pressed(XInputGamepadButtons.DPAD_UP)))
        assertNull(edges.onSnapshot(pressed(XInputGamepadButtons.DPAD_UP)))
        assertNull(edges.onSnapshot(GamepadSnapshot()))
        assertEquals(KonamiStep.LEFT, edges.onSnapshot(pressed(XInputGamepadButtons.DPAD_LEFT)))
        assertEquals(KonamiStep.DOWN, edges.onSnapshot(pressed(XInputGamepadButtons.DPAD_DOWN)))
        assertEquals(KonamiStep.RIGHT, edges.onSnapshot(pressed(XInputGamepadButtons.DPAD_RIGHT)))
    }

    @Test
    fun `the face buttons map to the konami a and b steps`() {
        val edges = GamepadKonamiEdgeDetector()

        assertEquals(KonamiStep.A, edges.onSnapshot(pressed(XInputGamepadButtons.A)))
        assertEquals(KonamiStep.B, edges.onSnapshot(pressed(XInputGamepadButtons.B)))
    }

    @Test
    fun `other buttons never produce a step`() {
        val edges = GamepadKonamiEdgeDetector()

        assertNull(edges.onSnapshot(pressed(XInputGamepadButtons.START)))
        assertNull(edges.onSnapshot(pressed(XInputGamepadButtons.RIGHT_SHOULDER)))
        assertNull(edges.onSnapshot(pressed(XInputGamepadButtons.LEFT_THUMB)))
        assertEquals(KonamiStep.A, edges.onSnapshot(pressed(XInputGamepadButtons.DPAD_UP or XInputGamepadButtons.A)))
        assertEquals(KonamiStep.UP, edges.onSnapshot(pressed(XInputGamepadButtons.DPAD_UP or XInputGamepadButtons.A)))
        assertNull(edges.onSnapshot(pressed(XInputGamepadButtons.DPAD_UP or XInputGamepadButtons.A)))
    }

    @Test
    fun `the left stick acts as a dpad beyond the deadzone`() {
        val edges = GamepadKonamiEdgeDetector()

        assertNull(edges.onSnapshot(GamepadSnapshot(leftX = 12_000, leftY = 8_000)))
        assertEquals(KonamiStep.UP, edges.onSnapshot(GamepadSnapshot(leftY = -30_000)))
        assertEquals(KonamiStep.DOWN, edges.onSnapshot(GamepadSnapshot(leftY = 30_000)))
        assertEquals(KonamiStep.LEFT, edges.onSnapshot(GamepadSnapshot(leftX = -30_000)))
        assertEquals(KonamiStep.RIGHT, edges.onSnapshot(GamepadSnapshot(leftX = 30_000)))
    }

    @Test
    fun `a frame with two fresh presses drains one step per frame`() {
        val edges = GamepadKonamiEdgeDetector()

        assertEquals(KonamiStep.A, edges.onSnapshot(pressed(XInputGamepadButtons.DPAD_UP or XInputGamepadButtons.A)))
        assertEquals(KonamiStep.UP, edges.onSnapshot(pressed(XInputGamepadButtons.DPAD_UP or XInputGamepadButtons.A)))
        assertNull(edges.onSnapshot(pressed(XInputGamepadButtons.DPAD_UP or XInputGamepadButtons.A)))
    }

    @Test
    fun `the polling source forwards controller steps and stops on request`() = kotlinx.coroutines.runBlocking {
        val frames = ArrayDeque(
            listOf(
                pressed(XInputGamepadButtons.DPAD_UP),
                pressed(XInputGamepadButtons.DPAD_UP),
                GamepadSnapshot(),
                pressed(XInputGamepadButtons.A),
                pressed(XInputGamepadButtons.B),
                pressed(XInputGamepadButtons.B),
                null,
                null,
                null,
            ),
        )
        val source = PollingGamepadKonamiSource(
            calls = { frames.removeFirstOrNull() },
            pollIntervalMillis = 1,
        )
        val steps = CopyOnWriteArrayList<KonamiStep>()
        source.start(steps::add)
        try {
            withTimeout(10.seconds) {
                while (steps.size < 3) delay(1)
            }
            assertEquals(listOf(KonamiStep.UP, KonamiStep.A, KonamiStep.B), steps.toList())
        } finally {
            source.stop()
        }

        val seen = steps.size
        delay(50)
        assertEquals(seen, steps.size, "stopping must silence the poller")
    }

    @Test
    fun `the factory is inert where xinput is unavailable`() = runBlocking {
        val calls = ArrayDeque<GamepadSnapshot?>(listOf(GamepadSnapshot(buttons = XInputGamepadButtons.A)))
        val windows = gamepadKonamiSource(windows = true, native = { { calls.removeFirstOrNull() } })
        val other = gamepadKonamiSource(windows = false, native = { { calls.removeFirstOrNull() } })
        assertInstanceOf(PollingGamepadKonamiSource::class.java, windows)
        assertInstanceOf(UnavailableGamepadKonamiSource::class.java, other)

        val steps = CopyOnWriteArrayList<KonamiStep>()
        val unsupported = gamepadKonamiSource(windows = false, native = { error("native must not load off Windows") })
        unsupported.start(steps::add)
        delay(20)
        unsupported.stop()
        assertTrue(steps.isEmpty())
        assertTrue(calls.size == 1, "the unsupported source must never poll the controller")
    }

    @Test
    fun `a native read failure reports no controller instead of throwing`() {
        val calls = WindowsNativeGamepadCalls(api = { WindowsXInputApi { _, _ -> XInputGamepadButtons.ERROR_DEVICE_NOT_CONNECTED } })

        assertNull(calls.snapshot(0))
    }

    @Test
    fun `a native read exposes the packed gamepad state`() {
        // Written at the documented XINPUT_STATE offsets so the reader proves it maps the
        // native layout correctly instead of agreeing with itself.
        val api = WindowsXInputApi { _, pointer ->
            pointer.setShort(4, XInputGamepadButtons.A.toShort())
            pointer.setShort(8, -30_000)
            pointer.setShort(10, 0)
            0
        }

        val snapshot = WindowsNativeGamepadCalls(api = { api }).snapshot(0)
        assertEquals(XInputGamepadButtons.A, snapshot?.buttons)
        assertEquals(-30_000, snapshot?.leftX)
        assertEquals(0, snapshot?.leftY)
    }

    @Test
    fun `xinput structures match the documented native layout`() {
        assertEquals(12, XInputGamepad().size())
        assertEquals(16, XInputState().size())
    }

    private fun pressed(buttons: Int) = GamepadSnapshot(buttons = buttons)
}
