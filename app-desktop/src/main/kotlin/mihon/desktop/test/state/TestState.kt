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

    private val _appLocked = MutableStateFlow(false)
    val appLocked: StateFlow<Boolean> = _appLocked.asStateFlow()

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

    fun setAppLocked(locked: Boolean) {
        _appLocked.value = locked
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

    fun recordExternalAction(status: String, target: String? = null) {
        recordAction("ExternalAction$status", target?.let { mapOf("target" to it) }.orEmpty())
    }

    fun reset() {
        _currentScreen.value = null
        _isLoading.value = false
        _appLocked.value = false
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

/**
 * Download queue state for testing.
 */
class DownloadState {
    var queueSize: Int = 0
    var isPaused: Boolean = false

    fun reset() {
        queueSize = 0
        isPaused = false
    }
}

/**
 * Global download state accessible for testing.
 */
val downloadState = DownloadState()

/**
 * Updates tab state for testing.
 */
class UpdatesState {
    var count: Int = 0
    var hasUnread: Boolean = false

    fun reset() {
        count = 0
        hasUnread = false
    }
}

/**
 * Global updates state accessible for testing.
 */
val updatesState = UpdatesState()

/**
 * History state for testing.
 */
class HistoryState {
    var count: Int = 0

    fun reset() {
        count = 0
    }
}

/**
 * Global history state accessible for testing.
 */
val historyState = HistoryState()

/**
 * Reader state for testing.
 */
class ReaderState {
    var isOpen: Boolean = false
        private set
    var currentChapterId: Long = 0L
        private set
    var currentPage: Int = 0
        private set
    var totalPages: Int = 0
        private set
    var isWebtoon: Boolean = false
        private set
    var mangaTitle: String = ""
        private set
    var chapterTitle: String = ""
        private set
    var hasNextChapter: Boolean = false
        private set
    var hasPrevChapter: Boolean = false
        private set

    fun open(chapterId: Long, page: Int, totalPages: Int, isWebtoon: Boolean, mangaTitle: String, chapterTitle: String, hasNext: Boolean, hasPrev: Boolean) {
        this.isOpen = true
        this.currentChapterId = chapterId
        this.currentPage = page
        this.totalPages = totalPages
        this.isWebtoon = isWebtoon
        this.mangaTitle = mangaTitle
        this.chapterTitle = chapterTitle
        this.hasNextChapter = hasNext
        this.hasPrevChapter = hasPrev
    }

    fun updatePage(page: Int) {
        this.currentPage = page
    }

    fun close() {
        isOpen = false
        currentChapterId = 0L
        currentPage = 0
        totalPages = 0
        mangaTitle = ""
        chapterTitle = ""
    }

    fun reset() {
        close()
        hasNextChapter = false
        hasPrevChapter = false
    }
}

/**
 * Global reader state accessible for testing.
 */
val readerState = ReaderState()
