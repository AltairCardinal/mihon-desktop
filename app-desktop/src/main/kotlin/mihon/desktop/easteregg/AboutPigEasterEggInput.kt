package mihon.desktop.easteregg

import androidx.compose.ui.input.key.KeyEvent
import mihon.desktop.easteregg.gamepad.GamepadKonamiSource
import mihon.desktop.easteregg.gamepad.gamepadKonamiSource

/**
 * Owns the easter egg code while the about page is on screen. Keyboard events reach it from the
 * window, gamepad presses from the polling source it starts on attach, and both feed the same
 * detector so the code can be entered with either device.
 */
internal class AboutPigEasterEggInput(
    val easterEgg: DesktopPigEasterEggController = DesktopPigEasterEggController.Default,
    private val gamepadFactory: () -> GamepadKonamiSource = ::gamepadKonamiSource,
) {
    private val detector = KonamiCodeDetector()
    private var attachments = 0
    private var gamepad: GamepadKonamiSource? = null

    val attached: Boolean get() = attachments > 0

    fun attach() {
        attachments++
        if (attachments == 1) gamepad = gamepadFactory().also { it.start(::submit) }
    }

    fun detach() {
        if (attachments == 0) return
        attachments--
        if (attachments == 0) {
            gamepad?.stop()
            gamepad = null
            detector.reset()
        }
    }

    /** Always reports "not handled": the about page keeps its own arrow key scrolling. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        submit(konamiStepOf(event))
        return false
    }

    /** Returns true when this step completed the code. */
    fun submit(step: KonamiStep?): Boolean {
        if (!attached || step == null) return false
        if (!detector.accept(step)) return false
        easterEgg.fire()
        return true
    }

    companion object {
        val Default = AboutPigEasterEggInput()
    }
}
