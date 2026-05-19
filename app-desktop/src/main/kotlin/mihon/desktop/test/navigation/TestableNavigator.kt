package mihon.desktop.test.navigation

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mihon.desktop.test.state.applicationState
import java.time.Instant

/**
 * A wrapper that adds test observability to any Navigator.
 * 
 * This records all navigation events for later inspection in tests.
 */
class TestableNavigator(
    private val delegate: Navigator,
) {
    private val _navigationHistory = MutableStateFlow<List<NavigationEvent>>(emptyList())
    val navigationHistory: StateFlow<List<NavigationEvent>> = _navigationHistory.asStateFlow()
    
    private val _navigationEvents = MutableStateFlow<List<String>>(emptyList())
    val navigationEvents: StateFlow<List<String>> = _navigationEvents.asStateFlow()
    
    private val _canPop = MutableStateFlow(false)
    val canPop: StateFlow<Boolean> = _canPop.asStateFlow()
    
    /**
     * Get current screen.
     */
    val currentScreen: Screen?
        get() = delegate.lastItem
    
    /**
     * Get screen key as string.
     */
    private fun ScreenKey?.toScreenName(): String {
        return this?.let { key -> key.toString() } ?: "unknown"
    }
    
    /**
     * Push a screen onto the stack.
     */
    fun push(screen: Screen) {
        val fromKey = delegate.lastItem?.key
        delegate.push(screen)
        recordEvent(
            NavigationEvent(
                type = NavigationEventType.PUSH,
                screenName = screen.key.toScreenName(),
                fromScreen = fromKey.toScreenName(),
                timestamp = Instant.now(),
            ),
        )
        _canPop.value = delegate.items.size > 1
    }
    
    /**
     * Pop the current screen from the stack.
     */
    fun pop(): Boolean {
        if (delegate.items.size <= 1) return false
        val fromKey = delegate.lastItem?.key
        delegate.pop()
        recordEvent(
            NavigationEvent(
                type = NavigationEventType.POP,
                screenName = delegate.lastItem?.key.toScreenName(),
                fromScreen = fromKey.toScreenName(),
                timestamp = Instant.now(),
            ),
        )
        _canPop.value = delegate.items.size > 1
        return true
    }
    
    /**
     * Replace current screen.
     */
    fun replace(screen: Screen) {
        val fromKey = delegate.lastItem?.key
        delegate.replace(screen)
        recordEvent(
            NavigationEvent(
                type = NavigationEventType.REPLACE,
                screenName = screen.key.toScreenName(),
                fromScreen = fromKey.toScreenName(),
                timestamp = Instant.now(),
            ),
        )
    }
    
    /**
     * Replace all screens with a new one.
     */
    fun replaceAll(screen: Screen) {
        val fromKey = delegate.lastItem?.key
        delegate.replaceAll(screen)
        recordEvent(
            NavigationEvent(
                type = NavigationEventType.REPLACE_ALL,
                screenName = screen.key.toScreenName(),
                fromScreen = fromKey.toScreenName(),
                timestamp = Instant.now(),
            ),
        )
    }
    
    /**
     * Pop all and push a new screen.
     */
    fun popAllAndPush(screen: Screen) {
        val fromKey = delegate.lastItem?.key
        delegate.popAll()
        delegate.push(screen)
        recordEvent(
            NavigationEvent(
                type = NavigationEventType.POP_ALL_AND_PUSH,
                screenName = screen.key.toScreenName(),
                fromScreen = fromKey.toScreenName(),
                timestamp = Instant.now(),
            ),
        )
        _canPop.value = delegate.items.size > 1
    }
    
    /**
     * Reset to the initial screen.
     */
    fun reset() {
        delegate.popAll()
        _navigationHistory.value = emptyList()
        _navigationEvents.value = emptyList()
        recordEvent(
            NavigationEvent(
                type = NavigationEventType.RESET,
                screenName = delegate.lastItem?.key.toScreenName() ?: "unknown",
                fromScreen = null,
                timestamp = Instant.now(),
            ),
        )
        _canPop.value = delegate.items.size > 1
    }
    
    /**
     * Record a navigation event.
     */
    private fun recordEvent(event: NavigationEvent) {
        _navigationHistory.value = _navigationHistory.value + event
        _navigationEvents.value = _navigationEvents.value + event.toString()
        
        // Update application state
        applicationState.setCurrentScreen(event.screenName)
        applicationState.recordAction(
            "navigation",
            mapOf(
                "type" to event.type.name,
                "screen" to event.screenName,
                "fromScreen" to (event.fromScreen ?: "none"),
            ),
        )
    }
    
    /**
     * Navigate to a specific screen by name.
     * This requires a screen registry to be implemented.
     */
    fun navigateTo(screenName: String): Boolean {
        // Would need screen registry implementation
        return false
    }
    
    /**
     * Get the delegate navigator.
     */
    fun getDelegate(): Navigator = delegate
}

/**
 * Navigation event types.
 */
enum class NavigationEventType {
    PUSH,
    POP,
    REPLACE,
    REPLACE_ALL,
    POP_ALL_AND_PUSH,
    RESET,
}

/**
 * A recorded navigation event.
 */
data class NavigationEvent(
    val type: NavigationEventType,
    val screenName: String,
    val fromScreen: String?,
    val timestamp: Instant,
) {
    override fun toString(): String {
        return "NavigationEvent(type=$type, screen=$screenName, from=$fromScreen, time=${timestamp})"
    }
}

/**
 * State of the testable navigator for test assertions.
 */
data class NavigatorTestState(
    val currentScreen: String?,
    val history: List<NavigationEvent>,
    val canPop: Boolean,
    val eventCount: Int,
) {
    companion object {
        fun from(navigator: TestableNavigator): NavigatorTestState {
            return NavigatorTestState(
                currentScreen = navigator.currentScreen?.key?.toString(),
                history = navigator.navigationHistory.value,
                canPop = navigator.canPop.value,
                eventCount = navigator.navigationEvents.value.size,
            )
        }
    }
}

/**
 * Extension function to make any Navigator testable.
 */
fun Navigator.asTestable(): TestableNavigator = TestableNavigator(this)
