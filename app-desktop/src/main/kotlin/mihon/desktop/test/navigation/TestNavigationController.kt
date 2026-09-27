package mihon.desktop.test.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.Tab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import mihon.desktop.domain.ReaderProgressTracker
import mihon.desktop.test.http.ReaderIoTestModeBridge
import mihon.desktop.test.state.readerState
import mihon.desktop.ui.authors.AuthorsTab
import mihon.desktop.ui.browse.BrowseTab
import mihon.desktop.ui.history.HistoryTab
import mihon.desktop.ui.library.LibraryTab
import mihon.desktop.ui.more.MoreTab
import mihon.desktop.ui.settings.GeneralSettingsScreen
import mihon.desktop.ui.updates.UpdatesTab
import tachiyomi.domain.reader.interactor.RecordReadingProgress
import tachiyomi.domain.reader.model.ReadingProgressEvent
import tachiyomi.domain.reader.repository.ReadingProgressRepository
import java.util.concurrent.atomic.AtomicLong

/**
 * Global navigation controller for test automation.
 *
 * HTTP API sets the target navigation, and the UI observes and executes it.
 */
object TestNavigationController {
    private val nextExtensionRequestId = AtomicLong()
    private val _pendingExtensions = MutableStateFlow<Long?>(null)
    val pendingExtensions = _pendingExtensions.asStateFlow()
    private val _displayedExtensions = MutableStateFlow<Long?>(null)
    val displayedExtensions = _displayedExtensions.asStateFlow()

    fun requestExtensions(): Long {
        check(mihon.desktop.test.state.applicationState.testMode)
        navigateToTab("Browse")
        return nextExtensionRequestId.incrementAndGet().also { _pendingExtensions.value = it }
    }

    fun acknowledgeExtensionsDisplayed(requestId: Long) {
        if (_pendingExtensions.compareAndSet(requestId, null)) _displayedExtensions.value = requestId
    }

    // Test Mode fixture chapters are intentionally absent from the production database.
    private val syntheticReaderProgressTracker = ReaderProgressTracker(
        RecordReadingProgress(
            object : ReadingProgressRepository {
                override suspend fun record(event: ReadingProgressEvent) = Unit
            },
        ),
    )

    private val _pendingTabNavigation = MutableStateFlow<String?>(null)
    val pendingTabNavigation: StateFlow<String?> = _pendingTabNavigation.asStateFlow()

    private val nextScreenRequestId = AtomicLong()
    private val _pendingScreenRequest = MutableStateFlow<ScreenNavigationRequest?>(null)
    val pendingScreenRequest: StateFlow<ScreenNavigationRequest?> = _pendingScreenRequest.asStateFlow()

    private val _rootResetGeneration = MutableStateFlow(0L)
    val rootResetGeneration: StateFlow<Long> = _rootResetGeneration.asStateFlow()

    private val _navigationHistory = MutableStateFlow<List<NavigationRequest>>(emptyList())
    val navigationHistory: StateFlow<List<NavigationRequest>> = _navigationHistory.asStateFlow()

    // Store manga ID for read operations
    private var _pendingMangaId = MutableStateFlow<Long?>(null)
    val pendingMangaId: StateFlow<Long?> = _pendingMangaId.asStateFlow()

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
            _pendingScreenRequest.value = null // Clear any pending screen
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
        publishScreenNavigation(screen)
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
        _pendingScreenRequest.value = null
        _pendingTabNavigation.value = null
        _pendingPop.value = true
        return true
    }

    /**
     * Clear pending navigation after it's been processed by the UI.
     */
    fun clearPendingNavigation() {
        _pendingTabNavigation.value = null
        _pendingScreenRequest.value = null
    }

    /**
     * Clear only pending tab navigation after the tab navigator consumes it.
     */
    fun clearPendingTabNavigation() {
        _pendingTabNavigation.value = null
    }

    /**
     * Clear only pending screen navigation after the root navigator consumes it.
     */
    fun clearPendingScreenNavigation() {
        _pendingScreenRequest.value = null
    }

    /**
     * Acknowledge only the exact screen instance consumed by the outer navigator.
     * A newer request published while the old screen is being pushed must remain pending.
     */
    fun acknowledgeScreenNavigation(requestId: Long) {
        _pendingScreenRequest.update { pending -> if (pending?.id == requestId) null else pending }
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
        "AuthorsTab", "Authors",
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
            "Authors" -> AuthorsTab
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
        localChapterPath: String? = null,
        dualPage: Boolean = false,
    ) {
        ReaderIoTestModeBridge.beginScenario(initialPage)
        val readerGeneration = readerState.open(
            chapterId = chapterId,
            page = initialPage,
            totalPages = pageCount,
            isWebtoon = false,
            mangaTitle = mangaTitle,
            chapterTitle = chapterTitle,
            hasNext = true,
            hasPrev = false,
        )
        val readerScreen = mihon.desktop.ui.reader.DesktopReaderScreen(
            chapterTitle = chapterTitle,
            mangaTitle = mangaTitle,
            isWebtoon = false,
            sourceId = sourceId,
            chapterUrl = chapterUrl,
            chapterId = chapterId,
            mangaId = mangaId,
            chapters = emptyList(),
            currentChapterIndex = 0,
            initialPage = initialPage,
            mangaViewerFlags = 0L,
            isRtl = false,
            isDualPage = dualPage,
            localChapterPath = localChapterPath,
            progressTracker = syntheticReaderProgressTracker,
            onProductionClosed = { readerState.markProductionClosed(readerGeneration) },
        )
        publishScreenNavigation(readerScreen)
        _pushedScreens.update { it + readerScreen }
    }

    /**
     * Reset navigation history.
     */
    fun reset() {
        _pendingExtensions.value = null
        _displayedExtensions.value = null
        _pendingTabNavigation.value = null
        _pendingScreenRequest.value = null
        _pendingMangaId.value = null
        _pushedScreens.value = emptyList()
        _pendingPop.value = false
        _navigationHistory.value = emptyList()
        _rootResetGeneration.update { it + 1L }
    }

    private fun publishScreenNavigation(screen: Screen) {
        _pendingScreenRequest.value = ScreenNavigationRequest(
            id = nextScreenRequestId.incrementAndGet(),
            screen = screen,
        )
    }
}

/**
 * Binds test navigation to the process-lifetime outer navigator.
 *
 * This must be mounted outside [mihon.desktop.ui.home.HomeScreen], because a Reader screen replaces
 * HomeScreen and would otherwise dispose the collectors needed for reset, a subsequent push, or pop.
 */
@Composable
internal fun BindTestNavigationController(navigator: Navigator) {
    LaunchedEffect(navigator) {
        var appliedResetGeneration = Long.MIN_VALUE
        combine(
            TestNavigationController.rootResetGeneration,
            TestNavigationController.pendingScreenRequest,
        ) { resetGeneration, pendingRequest -> resetGeneration to pendingRequest }
            .collect { (resetGeneration, pendingRequest) ->
                if (resetGeneration != appliedResetGeneration) {
                    navigator.popUntilRoot()
                    appliedResetGeneration = resetGeneration
                }
                if (pendingRequest != null) {
                    navigator.push(pendingRequest.screen)
                    TestNavigationController.acknowledgeScreenNavigation(pendingRequest.id)
                }
            }
    }
    LaunchedEffect(navigator) {
        TestNavigationController.pendingPop.collect { shouldPop ->
            if (shouldPop) {
                if (navigator.size > 1) navigator.pop()
                TestNavigationController.clearPendingPop()
            }
        }
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

data class ScreenNavigationRequest(
    val id: Long,
    val screen: Screen,
)
