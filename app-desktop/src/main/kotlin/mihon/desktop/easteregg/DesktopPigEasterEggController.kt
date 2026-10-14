package mihon.desktop.easteregg

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared trigger for the pig overlay. A monotonic counter instead of a boolean so a second
 * activation while the pigs are still on screen restarts the parade with a fresh layout.
 */
internal class DesktopPigEasterEggController {
    private val triggers = MutableStateFlow(0)

    val triggerCount: StateFlow<Int> = triggers.asStateFlow()

    fun fire() {
        triggers.value = triggers.value + 1
    }

    companion object {
        val Default = DesktopPigEasterEggController()
    }
}
