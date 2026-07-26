package mihon.desktop.test.http

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import mihon.desktop.history.HistoryScreenModel
import mihon.desktop.test.navigation.TestNavigationController
import mihon.desktop.ui.updates.UpcomingScreen
import mihon.desktop.updates.UpdatesScreenModel
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.updates.model.UpdatesWithRelations
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Serializable
data class UpdatesTestRow(
    val chapterId: Long,
    val mangaId: Long,
    val mangaTitle: String,
    val chapterName: String,
    val read: Boolean,
)

@Serializable
data class UpdatesTestSnapshot(
    val refreshing: Boolean,
    val rows: List<UpdatesTestRow>,
    val unreadFilter: String,
    val downloadedFilter: String,
    val startedFilter: String,
    val bookmarkedFilter: String,
    val excludedScanlatorsFilter: Boolean,
    val upcomingOpened: Boolean,
)

@Serializable
enum class TimelineTestFailureCode {
    MISSING_PARAMETER,
    INVALID_PARAMETER,
    ROW_NOT_FOUND,
    OPERATION_REJECTED,
    PARTIAL_FAILURE,
    OWNER_CLOSED,
    UNSUPPORTED_ACTION,
}

@Serializable
data class UpdatesTestActionResult(
    val success: Boolean,
    val snapshot: UpdatesTestSnapshot,
    val failureCode: TimelineTestFailureCode? = null,
)

class UpdatesTestModeController(
    private val model: UpdatesScreenModel,
) {
    private val closed = AtomicBoolean(false)
    private val upcomingOpened = AtomicBoolean(false)

    suspend fun hydrate() {
        if (!closed.get()) model.loadUpdates()
    }

    fun snapshot(): UpdatesTestSnapshot {
        val state = model.state.value
        return UpdatesTestSnapshot(
            refreshing = state.isRefreshing,
            rows = state.items.map {
                UpdatesTestRow(
                    chapterId = it.chapterId,
                    mangaId = it.mangaId,
                    mangaTitle = it.mangaTitle,
                    chapterName = it.chapterName,
                    read = it.read,
                )
            },
            unreadFilter = state.filterUnread.name,
            downloadedFilter = state.filterDownloaded.name,
            startedFilter = state.filterStarted.name,
            bookmarkedFilter = state.filterBookmarked.name,
            excludedScanlatorsFilter = state.filterExcludedScanlators,
            upcomingOpened = upcomingOpened.get(),
        )
    }

    suspend fun execute(
        action: String,
        params: Map<String, String>,
    ): UpdatesTestActionResult {
        if (closed.get()) return failure(TimelineTestFailureCode.OWNER_CLOSED)
        val before = snapshot()
        val failureCode = try {
            when (action) {
                "updates_refresh" -> null.also { model.refreshUpdates() }
                "updates_mark_all_read" -> null.also { model.markAllRead() }
                "updates_filter" -> filter(params)
                "updates_clear_filters" -> null.also { clearFilters() }
                "updates_open_upcoming" -> openUpcoming()
                "updates_select" -> select(params)
                "updates_download" -> {
                    val update = item(params)
                    if (update == null) {
                        rowFailure(params)
                    } else {
                        model.enqueueDownload(update)
                        null
                    }
                }
                "updates_mark_read" -> {
                    val update = item(params)
                    if (update == null) {
                        rowFailure(params)
                    } else {
                        model.markRead(update)
                        null
                    }
                }
                else -> TimelineTestFailureCode.UNSUPPORTED_ACTION
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return failure(
                if (snapshot() != before) TimelineTestFailureCode.PARTIAL_FAILURE else TimelineTestFailureCode.OPERATION_REJECTED,
            )
        }
        return failureCode?.let(::failure) ?: UpdatesTestActionResult(true, snapshot())
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        UpdatesTestModeBridge.clear(this)
    }

    private suspend fun filter(params: Map<String, String>): TimelineTestFailureCode? {
        val type = params["type"] ?: return TimelineTestFailureCode.MISSING_PARAMETER
        val enabled = params["enabled"]?.toBooleanStrictOrNull()
            ?: return TimelineTestFailureCode.INVALID_PARAMETER
        val desired = if (enabled) TriState.ENABLED_IS else TriState.DISABLED
        when (type) {
            "unread" -> cycleTo(desired, { model.state.value.filterUnread }, model::toggleUnreadFilter)
            "downloaded" -> cycleTo(desired, { model.state.value.filterDownloaded }) { model.toggleDownloadedFilter() }
            "started" -> cycleTo(desired, { model.state.value.filterStarted }, model::toggleStartedFilter)
            "bookmarked" -> cycleTo(desired, { model.state.value.filterBookmarked }, model::toggleBookmarkedFilter)
            "excluded_scanlators" -> {
                if (model.state.value.filterExcludedScanlators != enabled) model.toggleExcludedScanlatorsFilter()
            }
            else -> return TimelineTestFailureCode.INVALID_PARAMETER
        }
        return null
    }

    private suspend fun clearFilters() {
        cycleTo(TriState.DISABLED, { model.state.value.filterUnread }, model::toggleUnreadFilter)
        cycleTo(TriState.DISABLED, { model.state.value.filterDownloaded }) { model.toggleDownloadedFilter() }
        cycleTo(TriState.DISABLED, { model.state.value.filterStarted }, model::toggleStartedFilter)
        cycleTo(TriState.DISABLED, { model.state.value.filterBookmarked }, model::toggleBookmarkedFilter)
        if (model.state.value.filterExcludedScanlators) model.toggleExcludedScanlatorsFilter()
    }

    private suspend fun cycleTo(
        desired: TriState,
        current: () -> TriState,
        toggle: suspend () -> Unit,
    ) {
        repeat(3) {
            if (current() == desired) return
            toggle()
        }
        check(current() == desired)
    }

    private fun openUpcoming(): TimelineTestFailureCode? =
        if (TestNavigationController.navigateToScreen(UpcomingScreen())) {
            upcomingOpened.set(true)
            null
        } else {
            TimelineTestFailureCode.OPERATION_REJECTED
        }

    private suspend fun select(params: Map<String, String>): TimelineTestFailureCode? {
        val selected = item(params)
        if (selected == null) return rowFailure(params)
        val request = model.readerRequestFor(selected)
        TestNavigationController.openReader(
            mangaId = request.mangaId,
            chapterId = request.chapterId,
            chapterTitle = request.chapterTitle,
            mangaTitle = request.mangaTitle,
            chapterUrl = request.chapterUrl,
            sourceId = request.sourceId,
            initialPage = request.initialPage,
        )
        return null
    }

    private fun item(params: Map<String, String>): UpdatesWithRelations? {
        val index = params["index"]?.toIntOrNull() ?: return null
        return model.state.value.items.getOrNull(index)
    }

    private fun rowFailure(params: Map<String, String>) =
        if ("index" in params) TimelineTestFailureCode.ROW_NOT_FOUND else TimelineTestFailureCode.MISSING_PARAMETER

    private fun failure(code: TimelineTestFailureCode) = UpdatesTestActionResult(false, snapshot(), code)
}

object UpdatesTestModeBridge {
    private val value = AtomicReference<UpdatesTestModeController?>()
    val controller: UpdatesTestModeController? get() = value.get()
    fun install(controller: UpdatesTestModeController) { value.set(controller) }
    fun clear(expected: UpdatesTestModeController): Boolean = value.compareAndSet(expected, null)
}

@Serializable
data class HistoryTestRow(
    val id: Long,
    val chapterId: Long,
    val mangaId: Long,
    val title: String,
    val readAt: Long,
)

@Serializable
data class HistoryTestSnapshot(
    val searchQuery: String,
    val rows: List<HistoryTestRow>,
)

@Serializable
data class HistoryTestActionResult(
    val success: Boolean,
    val snapshot: HistoryTestSnapshot,
    val failureCode: TimelineTestFailureCode? = null,
)

class HistoryTestModeController(
    private val model: HistoryScreenModel,
) {
    private val closed = AtomicBoolean(false)

    suspend fun hydrate() {
        if (!closed.get()) model.loadHistory()
    }

    fun snapshot(): HistoryTestSnapshot {
        val state = model.state.value
        return HistoryTestSnapshot(
            searchQuery = state.searchQuery,
            rows = state.items.map {
                HistoryTestRow(
                    id = it.id,
                    chapterId = it.chapterId,
                    mangaId = it.mangaId,
                    title = it.title,
                    readAt = it.readAt?.time ?: 0L,
                )
            },
        )
    }

    suspend fun execute(
        action: String,
        params: Map<String, String>,
    ): HistoryTestActionResult {
        if (closed.get()) return failure(TimelineTestFailureCode.OWNER_CLOSED)
        val before = snapshot()
        val failureCode = try {
            when (action) {
                "history_search" -> {
                    model.loadHistory(params["query"] ?: return failure(TimelineTestFailureCode.MISSING_PARAMETER))
                    null
                }
                "history_clear_all" -> {
                    model.clearAllHistory()
                    null
                }
                "history_remove" -> {
                    val history = item(params)
                    if (history == null) {
                        rowFailure(params)
                    } else {
                        model.removeHistory(history)
                        null
                    }
                }
                "history_select" -> select(params)
                else -> TimelineTestFailureCode.UNSUPPORTED_ACTION
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return failure(
                if (snapshot() != before) TimelineTestFailureCode.PARTIAL_FAILURE else TimelineTestFailureCode.OPERATION_REJECTED,
            )
        }
        return failureCode?.let(::failure) ?: HistoryTestActionResult(true, snapshot())
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        HistoryTestModeBridge.clear(this)
    }

    private suspend fun select(params: Map<String, String>): TimelineTestFailureCode? {
        val selected = item(params)
        if (selected == null) return rowFailure(params)
        val request = model.readerRequestFor(selected) ?: return TimelineTestFailureCode.OPERATION_REJECTED
        TestNavigationController.openReader(
            mangaId = request.mangaId,
            chapterId = request.chapterId,
            chapterTitle = request.chapterTitle,
            mangaTitle = request.mangaTitle,
            chapterUrl = request.chapterUrl,
            sourceId = request.sourceId,
            initialPage = request.initialPage,
        )
        return null
    }

    private fun item(params: Map<String, String>) =
        params["index"]?.toIntOrNull()?.let { model.state.value.items.getOrNull(it) }

    private fun rowFailure(params: Map<String, String>) =
        if ("index" in params) TimelineTestFailureCode.ROW_NOT_FOUND else TimelineTestFailureCode.MISSING_PARAMETER

    private fun failure(code: TimelineTestFailureCode) = HistoryTestActionResult(false, snapshot(), code)
}

object HistoryTestModeBridge {
    private val value = AtomicReference<HistoryTestModeController?>()
    val controller: HistoryTestModeController? get() = value.get()
    fun install(controller: HistoryTestModeController) { value.set(controller) }
    fun clear(expected: HistoryTestModeController): Boolean = value.compareAndSet(expected, null)
}
