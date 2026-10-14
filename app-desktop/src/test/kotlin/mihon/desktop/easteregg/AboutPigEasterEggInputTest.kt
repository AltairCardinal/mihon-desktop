package mihon.desktop.easteregg

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import mihon.desktop.easteregg.gamepad.GamepadKonamiSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AboutPigEasterEggInputTest {

    @Test
    fun `every konami step round trips through its keyboard key`() {
        KonamiStep.entries.forEach { step ->
            assertEquals(step, konamiStepOf(step.key()))
        }
    }

    @Test
    fun `keys are ignored until the about screen attaches`() {
        val easterEgg = DesktopPigEasterEggController()
        val gamepad = FakeGamepadSource()
        val input = AboutPigEasterEggInput(easterEgg) { gamepad }

        KonamiCode.forEach { input.onKeyEvent(keyDown(it.key())) }
        KonamiCode.forEach { gamepad.press(it) }
        assertEquals(0, easterEgg.triggerCount.value)
        assertFalse(input.attached)
        assertEquals(0, gamepad.started)
    }

    @Test
    fun `the attached about screen fires once per complete code`() {
        val easterEgg = DesktopPigEasterEggController()
        val gamepad = FakeGamepadSource()
        val input = AboutPigEasterEggInput(easterEgg) { gamepad }
        input.attach()

        assertTrue(input.attached)
        assertEquals(1, gamepad.started)

        KonamiCode.forEach { input.onKeyEvent(keyDown(it.key())) }
        assertEquals(1, easterEgg.triggerCount.value)

        KonamiCode.forEach { input.onKeyEvent(keyDown(it.key())) }
        assertEquals(2, easterEgg.triggerCount.value)
    }

    @Test
    fun `key up events and unrelated keys never advance the code`() {
        val easterEgg = DesktopPigEasterEggController()
        val input = AboutPigEasterEggInput(easterEgg) { FakeGamepadSource() }
        input.attach()

        KonamiCode.dropLast(1).forEach { input.onKeyEvent(keyDown(it.key())) }
        KonamiCode.forEach { input.onKeyEvent(keyUp(it.key())) }
        input.onKeyEvent(keyDown(Key.Enter))
        assertEquals(0, easterEgg.triggerCount.value)

        input.onKeyEvent(keyDown(KonamiStep.B.key()))
        assertEquals(1, easterEgg.triggerCount.value)
    }

    @Test
    fun `gamepad presses drive the same code`() {
        val easterEgg = DesktopPigEasterEggController()
        val gamepad = FakeGamepadSource()
        val input = AboutPigEasterEggInput(easterEgg) { gamepad }
        input.attach()

        KonamiCode.forEach { gamepad.press(it) }
        assertEquals(1, easterEgg.triggerCount.value)

        KonamiCode.forEach { input.onKeyEvent(keyDown(it.key())) }
        assertEquals(2, easterEgg.triggerCount.value)
    }

    @Test
    fun `detaching stops the gamepad and abandons partial progress`() {
        val easterEgg = DesktopPigEasterEggController()
        val gamepad = FakeGamepadSource()
        val input = AboutPigEasterEggInput(easterEgg) { gamepad }
        input.attach()

        KonamiCode.dropLast(1).forEach { input.onKeyEvent(keyDown(it.key())) }
        input.detach()

        assertFalse(input.attached)
        assertEquals(1, gamepad.stopped)

        gamepad.press(KonamiStep.B)
        assertEquals(0, easterEgg.triggerCount.value)

        input.attach()
        input.onKeyEvent(keyDown(KonamiStep.B.key()))
        assertEquals(0, easterEgg.triggerCount.value)

        KonamiCode.forEach { input.onKeyEvent(keyDown(it.key())) }
        assertEquals(1, easterEgg.triggerCount.value)
    }

    @Test
    fun `the code never swallows the keys the about page still needs`() {
        val input = AboutPigEasterEggInput(DesktopPigEasterEggController()) { FakeGamepadSource() }
        input.attach()

        KonamiCode.forEach { assertFalse(input.onKeyEvent(keyDown(it.key()))) }
    }

    private fun keyDown(key: Key) = desktopKeyEvent(key, KeyEventType.KeyDown)

    private fun keyUp(key: Key) = desktopKeyEvent(key, KeyEventType.KeyUp)

    private fun desktopKeyEvent(key: Key, type: KeyEventType): KeyEvent {
        val events = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt")
        val eventType = Class.forName("androidx.compose.ui.input.key.KeyEventType")
            .getMethod(if (type == KeyEventType.KeyDown) "access\$getKeyDown\$cp" else "access\$getKeyUp\$cp")
            .invoke(null)
        val factory = events.declaredMethods.single { it.name.startsWith("KeyEvent-") && !it.name.endsWith("\$default") }
        return KeyEvent(factory.invoke(null, key.keyCode, eventType, 0, false, false, false, false, null))
    }

    private class FakeGamepadSource : GamepadKonamiSource {
        var started = 0
        var stopped = 0
        private var sink: ((KonamiStep) -> Unit)? = null

        override fun start(onStep: (KonamiStep) -> Unit) {
            started++
            sink = onStep
        }

        override fun stop() {
            stopped++
            sink = null
        }

        fun press(step: KonamiStep) {
            sink?.invoke(step)
        }
    }
}
