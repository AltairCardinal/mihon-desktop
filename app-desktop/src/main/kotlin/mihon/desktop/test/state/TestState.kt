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
    private val drawCaptureRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    internal val slotDrawCapture: kotlinx.coroutines.flow.StateFlow<Long> = drawCaptureRevision
    private var drawCaptureId = 0L
    private var drawCapacity = 120
    private var drawDropped = 0L
    private var drawSequence = 0L
    private val drawRecords = java.util.ArrayDeque<ReaderSlotDrawRecord>()
    private val drawMountIds = java.util.concurrent.atomic.AtomicLong()

    internal fun nextDrawMountId(): Long = drawMountIds.incrementAndGet()

    @Synchronized
    internal fun acceptsSlotDraw(model: mihon.desktop.ui.reader.ReaderScreenModel, capture: Long): Boolean =
        applicationState.testMode && mounted === model && capture != 0L && capture == drawCaptureRevision.value

    @Synchronized
    internal fun startSlotDrawObservation(capacity: Int = 120): Boolean {
        require(capacity in 1..512)
        if (!applicationState.testMode || mounted == null) return false
        drawCaptureId += 1
        drawCapacity = capacity
        drawDropped = 0
        drawSequence = 0
        drawRecords.clear()
        drawCaptureRevision.value = drawCaptureId
        return true
    }

    @Synchronized
    internal fun stopSlotDrawObservation(): Boolean {
        if (!applicationState.testMode || mounted == null) return false
        drawCaptureRevision.value = 0L
        return true
    }

    @Synchronized
    internal fun recordSlotDraw(
        model: mihon.desktop.ui.reader.ReaderScreenModel,
        capture: Long,
        record: ReaderSlotDrawRecord,
    ): Boolean {
        if (!applicationState.testMode || mounted !== model || capture == 0L ||
            capture != drawCaptureRevision.value
        ) {
            return false
        }
        if (drawRecords.size == drawCapacity) {
            drawRecords.removeFirst()
            drawDropped += 1
        }
        drawRecords.addLast(record.copy(sequence = ++drawSequence))
        return true
    }

    @Synchronized
    internal fun slotDrawSnapshot() = ReaderSlotDrawSnapshot(
        active = drawCaptureRevision.value != 0L && applicationState.testMode,
        captureId = drawCaptureId,
        capacity = drawCapacity,
        dropped = drawDropped,
        records = drawRecords.toList(),
    )

    private fun clearSlotDrawObservation() {
        drawCaptureRevision.value = 0L
        drawRecords.clear()
        drawDropped = 0L
    }
    private var openGeneration: Long = 0L
    private var openedChapterId: Long = 0L
    private var observingProductEntry: Boolean = false

    @Volatile private var mounted: mihon.desktop.ui.reader.ReaderScreenModel? = null

    @Volatile private var previous: (() -> Unit)? = null

    @Volatile private var next: (() -> Unit)? = null

    @Volatile internal var geometry: ReaderMountedGeometry? = null
        private set
    private var geometryPublisher: Any? = null

    @Volatile internal var ownerFocused: Boolean = false
        private set

    @Volatile internal var composeWindowFocused: Boolean = false
        private set

    internal fun controlledContentOwner(chapterId: Long, generation: Long): Long? {
        if (!applicationState.testMode) return null
        val model = mounted ?: return null
        val state = model.state.value
        if (state.context.chapterId != chapterId || state.session.generation != generation) return null
        return model.runtime?.pageContentOwner?.controlOwnerId?.takeIf { it != 0L }
    }

    internal fun mountedSnapshot() = mounted?.state?.value

    /** Observe the existing product model in Test Mode without replacing its runtime or tracker. */
    @Synchronized
    internal fun mount(model: mihon.desktop.ui.reader.ReaderScreenModel) {
        if (applicationState.testMode && (!isOpen || (observingProductEntry && mounted !== model))) {
            val state = model.state.value
            open(
                state.context.chapterId,
                state.currentPage,
                state.session.activeChapter.pages.size,
                state.readingMode == mihon.desktop.reader.ReadingMode.WEBTOON,
                state.context.mangaTitle,
                state.context.chapterTitle,
                false,
                false,
            )
            observingProductEntry = true
        }
        if (isOpen && model.state.value.context.chapterId == openedChapterId) {
            if (mounted !== model) clearSlotDrawObservation()
            mounted = model
        }
    }

    @Synchronized
    internal fun unmount(model: mihon.desktop.ui.reader.ReaderScreenModel) {
        if (mounted !== model) return
        clearSlotDrawObservation()
        mounted = null
        previous = null
        next = null
        geometry = null
        geometryPublisher = null
        ownerFocused = false
        composeWindowFocused = false
        if (observingProductEntry) {
            observingProductEntry = false
            isOpen = false
            openedChapterId = 0L
            mangaTitle = ""
            chapterTitle = ""
            productionClosed = true
        }
    }
    internal fun updateActions(
        model: mihon.desktop.ui.reader.ReaderScreenModel,
        previous: (() -> Unit)?,
        next: (() -> Unit)?,
    ) {
        if (mounted !== model) return
        this.previous = previous
        this.next = next
    }
    internal fun updateGeometry(
        model: mihon.desktop.ui.reader.ReaderScreenModel,
        publisher: Any,
        value: ReaderMountedGeometry,
    ) {
        if (mounted !== model) return
        geometryPublisher = publisher
        geometry = value
    }
    internal fun clearGeometry(model: mihon.desktop.ui.reader.ReaderScreenModel, publisher: Any) {
        if (mounted !== model || geometryPublisher !== publisher) return
        geometry = null
        geometryPublisher = null
    }
    internal fun updateFocus(
        model: mihon.desktop.ui.reader.ReaderScreenModel,
        owner: Boolean? = null,
        window: Boolean? = null,
    ) {
        if (mounted !== model) return
        owner?.let { ownerFocused = it }
        window?.let { composeWindowFocused = it }
    }

    /** HTTP is an application action, never evidence of physical key/mouse delivery. */
    internal suspend fun perform(action: String, page: Int? = null): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            java.awt.EventQueue.invokeLater {
                if (!continuation.isActive) return@invokeLater
                val model = mounted
                val result = if (model == null) {
                    false
                } else {
                    val state = model.state.value
                    when (action) {
                        "go_to_page" -> if (page != null && page in state.session.activeChapter.pages.indices) {
                            model.goToPage(page)
                            true
                        } else {
                            false
                        }
                        "next_chapter" -> next?.let {
                            it()
                            true
                        } ?: false
                        "prev_chapter" -> previous?.let {
                            it()
                            true
                        } ?: false
                        "next_page", "prev_page" -> {
                            val position = mihon.desktop.ui.reader.readerKeyboardNavigationPosition(
                                state,
                                previous != null,
                                next != null,
                            )
                            val command = if (action == "next_page") {
                                mihon.desktop.reader.ReaderKeyboardAction.forNext(position.current, position.total)
                            } else {
                                mihon.desktop.reader.ReaderKeyboardAction.forPrevious(position.current)
                            }
                            mihon.desktop.ui.reader.applyReaderPageAction(
                                command,
                                state,
                                model,
                                { previous?.invoke() },
                                { next?.invoke() },
                                previous != null,
                                next != null,
                            )
                        }
                        else -> false
                    }
                }
                continuation.resumeWith(Result.success(result))
            }
        }

    var isOpen: Boolean = false
        private set

    @Volatile
    var closeRequested: Boolean = false
        private set

    @Volatile
    var productionClosed: Boolean = false
        private set
    val currentChapterId: Long get() = mountedSnapshot()?.context?.chapterId ?: 0L
    val currentPage: Int get() = mountedSnapshot()?.currentPage ?: 0
    val totalPages: Int get() = mountedSnapshot()?.session?.activeChapter?.pages?.size ?: 0
    val isWebtoon: Boolean get() = mountedSnapshot()?.readingMode == mihon.desktop.reader.ReadingMode.WEBTOON
    var mangaTitle: String = ""
        private set
    var chapterTitle: String = ""
        private set
    val hasNextChapter: Boolean get() = next != null
    val hasPrevChapter: Boolean get() = previous != null

    @Synchronized
    fun open(
        chapterId: Long,
        page: Int,
        totalPages: Int,
        isWebtoon: Boolean,
        mangaTitle: String,
        chapterTitle: String,
        hasNext: Boolean,
        hasPrev: Boolean,
    ): Long {
        clearSlotDrawObservation()
        openGeneration += 1L
        observingProductEntry = false
        this.isOpen = true
        this.closeRequested = false
        this.productionClosed = false
        this.openedChapterId = chapterId
        mounted?.let(::unmount)
        this.mangaTitle = mangaTitle
        this.chapterTitle = chapterTitle
        return openGeneration
    }

    fun requestClose() {
        closeRequested = true
    }

    @Synchronized
    fun markProductionClosed(generation: Long) {
        if (generation != openGeneration) return
        isOpen = false
        mounted?.let(::unmount)
        openedChapterId = 0L
        mangaTitle = ""
        chapterTitle = ""
        productionClosed = true
    }

    @Synchronized
    fun reset() {
        openGeneration += 1L
        observingProductEntry = false
        isOpen = false
        closeRequested = false
        productionClosed = false
        mounted?.let(::unmount)
        openedChapterId = 0L
        mangaTitle = ""
        chapterTitle = ""
    }
}

/** Actual pager bounds are physical Compose pixels; screen points use the mounted AWT content origin. */
internal data class ReaderMountedGeometry(
    val bounds: androidx.compose.ui.geometry.Rect,
    val density: Float,
    val window: java.awt.Window?,
)

@kotlinx.serialization.Serializable
internal data class ReaderDrawPoint(val x: Float, val y: Float)

/** An actual base-bitmap canvas command; neither a vsync frame nor a GPU presentation receipt. */
@kotlinx.serialization.Serializable
internal data class ReaderSlotDrawRecord(
    val sourcePageIndex: Int,
    val chapterId: Long,
    val generation: Long,
    val readerGeneration: Long,
    val frameMatchesAssetGeneration: Boolean,
    val locationSequence: Long?,
    val ticketLocationSequence: Long?,
    val frameId: String?,
    val slot: String,
    val nativeMountId: Long,
    val mountId: Long,
    val assetIdentity: Int,
    val attemptGeneration: Long,
    val contentIdentity: Int,
    val sourceRect: List<Float>,
    val destinationRect: List<Float>,
    val rootQuad: List<ReaderDrawPoint>,
    val imageLayoutQuad: List<ReaderDrawPoint>,
    val clipCanvasTransform: List<Double>,
    val monotonicNanos: Long,
    val sequence: Long = 0L,
    val level: String,
    val canvasScope: String,
    val coordinatePhase: String,
    val composeFrameNanos: Long?,
    val lastActualCommand: Long?,
    val lastLayoutCallbackMonotonicNanos: Long?,
)

@kotlinx.serialization.Serializable
internal data class ReaderSlotDrawSnapshot(
    val active: Boolean,
    val captureId: Long,
    val capacity: Int,
    val dropped: Long,
    val records: List<ReaderSlotDrawRecord>,
)

/**
 * Global reader state accessible for testing.
 */
val readerState = ReaderState()
