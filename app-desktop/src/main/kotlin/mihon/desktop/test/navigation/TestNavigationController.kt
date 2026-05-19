package mihon.desktop.test.navigation

import cafe.adriel.voyager.navigator.tab.Tab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mihon.desktop.ui.browse.BrowseTab
import mihon.desktop.ui.history.HistoryTab
import mihon.desktop.ui.library.LibraryTab
import mihon.desktop.ui.more.MoreTab
import mihon.desktop.ui.updates.UpdatesTab

/**
 * Global navigation controller for test automation.
 *
 * HTTP API sets the target navigation, and the UI observes and executes it.
 */
object TestNavigationController {

    private val _pendingNavigation = MutableStateFlow<String?>(null)
    val pendingNavigation: StateFlow<String?> = _pendingNavigation.asStateFlow()

    private val _navigationHistory = MutableStateFlow<List<NavigationRequest>>(emptyList())
    val navigationHistory: StateFlow<List<NavigationRequest>> = _navigationHistory.asStateFlow()

    /**
     * Request navigation to a specific tab.
     * The UI should observe [pendingNavigation] and execute the navigation.
     */
    fun navigateTo(screenId: String): Boolean {
        val tab = getTab(screenId)
        if (tab != null) {
            _pendingNavigation.value = screenId
            _navigationHistory.value = _navigationHistory.value + NavigationRequest(
                screenId = screenId,
                success = true,
            )
            return true
        }
        _navigationHistory.value = _navigationHistory.value + NavigationRequest(
            screenId = screenId,
            success = false,
            error = "Unknown screen: $screenId",
        )
        return false
    }

    /**
     * Clear pending navigation after it's been processed by the UI.
     */
    fun clearPendingNavigation() {
        _pendingNavigation.value = null
    }

    /**
     * Get all available screen/tab IDs.
     */
    fun getAvailableScreens(): List<String> = listOf(
        "HomeScreen",
        "LibraryTab", "Library",
        "BrowseTab", "Browse",
        "UpdatesTab", "Updates",
        "HistoryTab", "History",
        "MoreTab", "More",
        "SettingsScreen",
        "ExtensionListScreen",
        "MigrationSearchScreen",
    )

    /**
     * Get Tab instance by screen ID.
     */
    private fun getTab(screenId: String): Tab? {
        return when (screenId.removeSuffix("Tab").removeSuffix("Screen")) {
            "Home" -> null // HomeScreen contains the TabNavigator
            "Library" -> LibraryTab
            "Browse" -> BrowseTab
            "Updates" -> UpdatesTab
            "History" -> HistoryTab
            "More" -> MoreTab
            else -> null
        }
    }

    /**
     * Get Tab instance for a given screen ID, or null if it's not a tab.
     */
    fun getTabOrNull(screenId: String): Tab? = getTab(screenId)

    /**
     * Reset navigation history.
     */
    fun reset() {
        _pendingNavigation.value = null
        _navigationHistory.value = emptyList()
    }
}

/**
 * Record of a navigation request.
 */
data class NavigationRequest(
    val screenId: String,
    val success: Boolean,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)
