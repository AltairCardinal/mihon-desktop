package mihon.desktop.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global test screen navigation state.
 * This is separate from Compose state to ensure reliable cross-component communication.
 */
object TestScreenNavigator {
    private val _pendingScreen = MutableStateFlow<String?>(null)
    val pendingScreen: StateFlow<String?> = _pendingScreen.asStateFlow()

    fun navigateTo(screen: String) {
        _pendingScreen.value = screen
    }

    fun clear() {
        _pendingScreen.value = null
    }
}
