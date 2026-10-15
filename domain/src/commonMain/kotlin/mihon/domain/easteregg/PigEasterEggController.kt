package mihon.domain.easteregg

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PigEasterEggController {
    private val triggers = MutableStateFlow(0)

    val triggerCount: StateFlow<Int> = triggers.asStateFlow()

    fun fire() {
        triggers.value = triggers.value + 1
    }

    companion object {
        val Default = PigEasterEggController()
    }
}
