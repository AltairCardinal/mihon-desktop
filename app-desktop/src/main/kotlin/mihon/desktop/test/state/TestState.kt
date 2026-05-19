package mihon.desktop.test.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Application state accessible for testing.
 */
class TestState {
    var testMode: Boolean = false
        internal set

    private val _currentScreen = MutableStateFlow<String?>(null)
    val currentScreen: StateFlow<String?> = _currentScreen.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _notifications = MutableStateFlow<List<String>>(emptyList())
    val notifications: StateFlow<List<String>> = _notifications.asStateFlow()

    private val _screens = MutableStateFlow<List<String>>(emptyList())
    val screens: StateFlow<List<String>> = _screens.asStateFlow()

    private val _actions = MutableStateFlow<List<String>>(emptyList())
    val actions: StateFlow<List<String>> = _actions.asStateFlow()

    private val _actionHistory = MutableStateFlow<List<ActionRecord>>(emptyList())
    val actionHistory: StateFlow<List<ActionRecord>> = _actionHistory.asStateFlow()

    fun setCurrentScreen(name: String) {
        _currentScreen.value = name
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun addNotification(message: String) {
        _notifications.value = _notifications.value + message
    }

    fun clearNotifications() {
        _notifications.value = emptyList()
    }

    fun registerScreens(screenNames: List<String>) {
        _screens.value = screenNames
    }

    fun registerActions(actionNames: List<String>) {
        _actions.value = actionNames
    }

    fun recordAction(action: String, params: Map<String, Any>) {
        _actionHistory.value = _actionHistory.value + ActionRecord(
            action = action,
            params = params,
            timestamp = Instant.now(),
        )
    }

    fun reset() {
        _currentScreen.value = null
        _isLoading.value = false
        _notifications.value = emptyList()
        _actionHistory.value = emptyList()
    }
}

/**
 * Record of an action performed.
 */
data class ActionRecord(
    val action: String,
    val params: Map<String, Any>,
    val timestamp: Instant,
)

/**
 * Global application state accessible for testing.
 */
val applicationState = TestState()
