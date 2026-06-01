package mihon.desktop.test.navigation

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.tab.Tab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mihon.desktop.test.state.readerState
import mihon.desktop.ui.browse.BrowseTab
import mihon.desktop.ui.history.HistoryTab
import mihon.desktop.ui.library.LibraryTab
import mihon.desktop.ui.more.MoreTab
import mihon.desktop.ui.settings.GeneralSettingsScreen
import mihon.desktop.ui.updates.UpdatesTab

/**
 * Global navigation controller for test automation.
 *
 * HTTP API sets the target navigation, and the UI observes and executes it.
 */
object TestNavigationController {

    private val _pendingTabNavigation = MutableStateFlow<String?>(null)
    val pendingTabNavigation: StateFlow<String?> = _pendingTabNavigation.asStateFlow()

    private val _pendingScreenNavigation = MutableStateFlow<Screen?>(null)
    val pendingScreenNavigation: StateFlow<Screen?> = _pendingScreenNavigation.asStateFlow()

    private val _navigationHistory = MutableStateFlow<List<NavigationRequest>>(emptyList())
    val navigationHistory: StateFlow<List<NavigationRequest>> = _navigationHistory.asStateFlow()

    // Store manga ID for read operations
    private var _pendingMangaId = MutableStateFlow<Long?>(null)
    val pendingMangaId: StateFlow<Long?> = _pendingMangaId.asStateFlow()

    // Store pending reader screen for opening reader
    private val _pendingReaderScreen = MutableStateFlow<Screen?>(null)
    val pendingReaderScreen: StateFlow<Screen?> = _pendingReaderScreen.asStateFlow()

    // Track pushed screens for test navigation
    private val _pushedScreens = MutableStateFlow<List<Screen>>(emptyList())
    val pushedScreens: StateFlow<List<Screen>> = _pushedScreens.asStateFlow()

    // Flag to trigger navigator.pop() in UI
    private val _pendingPop = MutableStateFlow(false)
    val pendingPop: StateFlow<Boolean> = _pendingPop.asStateFlow()

    /**
     * Request navigation to a specific tab.
     * The UI should observe [pendingTabNavigation] and execute the navigation.
     */
    fun navigateToTab(screenId: String): Boolean {
        val tab = getTab(screenId)
        if (tab != null) {
            _pendingTabNavigation.value = screenId
            _pendingScreenNavigation.value = null // Clear any pending screen
            _navigationHistory.value = _navigationHistory.value + NavigationRequest(
                screenId = screenId,
                success = true,
            )
            return true
        }
        _navigationHistory.value = _navigationHistory.value + NavigationRequest(
            screenId = screenId,
            success = false,
            error = "Unknown or non-tab screen: $screenId",
        )
        return false
    }

    /**
     * Request navigation to a specific screen (push onto current navigator).
     * This requires being on the correct tab first.
     */
    fun navigateToScreen(screen: Screen): Boolean {
        _pendingScreenNavigation.value = screen
        _navigationHistory.value = _navigationHistory.value + NavigationRequest(
            screenId = screen::class.java.simpleName,
            success = true,
        )
        return true
    }

    /**
     * Legacy method for backward compatibility.
     */
    fun navigateTo(screenId: String): Boolean {
        return navigateToTab(screenId)
    }

    /**
     * Navigate back (pop from navigation stack).
     * Returns to the previous screen in the navigation history.
     */
    fun navigateBack(): Boolean {
        _pendingScreenNavigation.value = null
        _pendingTabNavigation.value = null
        _pendingPop.value = true
        return true
    }

    /**
     * Clear pending navigation after it's been processed by the UI.
     */
    fun clearPendingNavigation() {
        _pendingTabNavigation.value = null
        _pendingScreenNavigation.value = null
    }

    /**
     * Clear pending pop flag after it's been processed by the UI.
     */
    fun clearPendingPop() {
        _pendingPop.value = false
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
        "GeneralSettingsScreen",
        "DownloadSettingsScreen",
        "BackupSettingsScreen",
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
     * Get Screen instance by screen ID for nested navigation.
     */
    fun getScreen(screenId: String): Screen? {
        return when (screenId) {
            "GeneralSettingsScreen" -> GeneralSettingsScreen()
            "DownloadSettingsScreen" -> mihon.desktop.ui.settings.DownloadSettingsScreen()
            "BackupSettingsScreen" -> mihon.desktop.ui.settings.BackupSettingsScreen()
            else -> null
        }
    }

    /**
     * Navigate to MangaDetailScreen by manga ID.
     * First navigates to LibraryTab, then pushes MangaDetailScreen.
     */
    fun navigateToMangaDetail(mangaId: Long): Boolean {
        _pendingMangaId.value = mangaId
        navigateToTab("LibraryTab")
        val screen = mihon.desktop.ui.library.MangaDetailScreen(mangaId)
        navigateToScreen(screen)
        return true
    }

    /**
     * Get the pending manga ID for opening manga detail.
     */
    fun getPendingMangaId(): Long? = _pendingMangaId.value

    /**
     * Mock manga page URLs for testing.
     * Using placeholder images from picsum.photos
     */
    private val mockPageUrls: List<String> by lazy {
        // Generate 20 placeholder image URLs
        (1..20).map { page ->
            "https://picsum.photos/seed/manga$page/800/1200"
        }
    }

    /**
     * Get mock page URLs for testing.
     */
    fun getMockPageUrls(count: Int = 20): List<String> {
        return mockPageUrls.take(count)
    }

    /**
     * Open reader screen with mock data for testing.
     */
    fun openReader(
        mangaId: Long,
        chapterId: Long,
        chapterTitle: String,
        mangaTitle: String,
        chapterUrl: String,
        sourceId: Long,
        initialPage: Int = 0,
        pageCount: Int = 20,
    ) {
        // Use mock page URLs for testing
        val mockPages = getMockPageUrls(pageCount)

        val readerScreen = mihon.desktop.ui.reader.DesktopReaderScreen(
            chapterTitle = chapterTitle,
            mangaTitle = mangaTitle,
            pageUrls = mockPages,
            isWebtoon = false,
            sourceId = sourceId,
            chapterUrl = chapterUrl,
            chapterId = chapterId,
            chapters = emptyList(),
            currentChapterIndex = 0,
            initialPage = initialPage,
            mangaViewerFlags = 0L,
            isRtl = false,
            isDualPage = false,
        )
        _pendingReaderScreen.value = readerScreen
        _pushedScreens.value = _pushedScreens.value + readerScreen

        // Also update reader state
        readerState.open(
            chapterId = chapterId,
            page = initialPage,
            totalPages = pageCount,
            isWebtoon = false,
            mangaTitle = mangaTitle,
            chapterTitle = chapterTitle,
            hasNext = true,
            hasPrev = false,
        )
    }

    /**
     * Reset navigation history.
     */
    fun reset() {
        _pendingTabNavigation.value = null
        _pendingScreenNavigation.value = null
        _pendingMangaId.value = null
        _pendingReaderScreen.value = null
        _pushedScreens.value = emptyList()
        _pendingPop.value = false
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
