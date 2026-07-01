package mihon.desktop.updates

import cafe.adriel.voyager.core.model.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import mihon.desktop.download.DownloadItem
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.service.UpdatesPreferences
import java.time.Instant
import java.time.temporal.ChronoUnit

data class UpdatesState(
    val items: List<UpdatesWithRelations> = emptyList(),
    val isRefreshing: Boolean = false,
    val showMarkAllReadDialog: Boolean = false,
    val showFilterDialog: Boolean = false,
    val filterUnread: TriState = TriState.DISABLED,
    val filterDownloaded: TriState = TriState.DISABLED,
    val filterStarted: TriState = TriState.DISABLED,
    val filterBookmarked: TriState = TriState.DISABLED,
    val filterExcludedScanlators: Boolean = false,
) {
    val hasActiveFilters: Boolean
        get() = listOf(filterUnread, filterDownloaded, filterStarted, filterBookmarked)
            .any { it != TriState.DISABLED } || filterExcludedScanlators
}

data class UpdatesReaderRequest(
    val chapterTitle: String,
    val mangaTitle: String,
    val sourceId: Long,
    val chapterUrl: String,
    val chapterId: Long,
    val mangaId: Long,
    val mangaViewerFlags: Long,
    val initialPage: Int,
)

class UpdatesScreenModel(
    private val getUpdates: GetUpdates,
    private val updateChapter: UpdateChapter,
    private val getManga: GetManga,
    private val updatesPreferences: UpdatesPreferences,
    private val isDownloaded: (UpdatesWithRelations) -> Boolean,
    private val enqueueDownload: (DownloadItem) -> Unit,
) : ScreenModel {

    private var rawItems: List<UpdatesWithRelations> = emptyList()

    private val _state = MutableStateFlow(
        UpdatesState(
            filterUnread = updatesPreferences.filterUnread().get(),
            filterDownloaded = updatesPreferences.filterDownloaded().get(),
            filterStarted = updatesPreferences.filterStarted().get(),
            filterBookmarked = updatesPreferences.filterBookmarked().get(),
            filterExcludedScanlators = updatesPreferences.filterExcludedScanlators().get(),
        ),
    )
    val state: StateFlow<UpdatesState> = _state.asStateFlow()

    suspend fun loadUpdates(since: Instant = Instant.now().minus(14, ChronoUnit.DAYS)) {
        val filters = state.value
        rawItems = getUpdates.subscribe(
            instant = since,
            unread = filters.filterUnread.toBooleanOrNull(),
            started = filters.filterStarted.toBooleanOrNull(),
            bookmarked = filters.filterBookmarked.toBooleanOrNull(),
            hideExcludedScanlators = filters.filterExcludedScanlators,
        ).first()
        applyVisibleItems()
    }

    suspend fun refreshUpdates() {
        _state.update { it.copy(isRefreshing = true) }
        try {
            loadUpdates()
        } finally {
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    fun setShowMarkAllReadDialog(show: Boolean) {
        _state.update { it.copy(showMarkAllReadDialog = show) }
    }

    fun setShowFilterDialog(show: Boolean) {
        _state.update { it.copy(showFilterDialog = show) }
    }

    suspend fun markAllRead() {
        val unreadItems = state.value.items.filter { !it.read }
        updateChapter.awaitAll(unreadItems.map { ChapterUpdate(id = it.chapterId, read = true) })
        val unreadIds = unreadItems.map { it.chapterId }.toSet()
        rawItems = rawItems.map { if (it.chapterId in unreadIds) it.copy(read = true) else it }
        _state.update {
            it.copy(
                items = it.items.map { item -> if (item.chapterId in unreadIds) item.copy(read = true) else item },
                showMarkAllReadDialog = false,
            )
        }
    }

    suspend fun markRead(item: UpdatesWithRelations) {
        updateChapter.await(ChapterUpdate(id = item.chapterId, read = true))
        rawItems = rawItems.map { if (it.chapterId == item.chapterId) it.copy(read = true) else it }
        _state.update {
            it.copy(items = it.items.map { visible -> if (visible.chapterId == item.chapterId) visible.copy(read = true) else visible })
        }
    }

    suspend fun toggleUnreadFilter() {
        val next = state.value.filterUnread.next()
        updatesPreferences.filterUnread().set(next)
        _state.update { it.copy(filterUnread = next) }
        loadUpdates()
    }

    fun toggleDownloadedFilter() {
        val next = state.value.filterDownloaded.next()
        updatesPreferences.filterDownloaded().set(next)
        _state.update { it.copy(filterDownloaded = next) }
        applyVisibleItems()
    }

    suspend fun toggleStartedFilter() {
        val next = state.value.filterStarted.next()
        updatesPreferences.filterStarted().set(next)
        _state.update { it.copy(filterStarted = next) }
        loadUpdates()
    }

    suspend fun toggleBookmarkedFilter() {
        val next = state.value.filterBookmarked.next()
        updatesPreferences.filterBookmarked().set(next)
        _state.update { it.copy(filterBookmarked = next) }
        loadUpdates()
    }

    suspend fun toggleExcludedScanlatorsFilter() {
        val next = !state.value.filterExcludedScanlators
        updatesPreferences.filterExcludedScanlators().set(next)
        _state.update { it.copy(filterExcludedScanlators = next) }
        loadUpdates()
    }

    suspend fun readerRequestFor(item: UpdatesWithRelations): UpdatesReaderRequest {
        val mangaViewerFlags = getManga.await(item.mangaId)?.viewerFlags ?: 0L
        return UpdatesReaderRequest(
            chapterTitle = item.chapterName,
            mangaTitle = item.mangaTitle,
            sourceId = item.sourceId,
            chapterUrl = item.chapterUrl,
            chapterId = item.chapterId,
            mangaId = item.mangaId,
            mangaViewerFlags = mangaViewerFlags,
            initialPage = item.lastPageRead.toInt().coerceAtLeast(0),
        )
    }

    fun enqueueDownload(item: UpdatesWithRelations) {
        enqueueDownload(
            DownloadItem(
                sourceId = item.sourceId,
                mangaTitle = item.mangaTitle,
                chapterName = item.chapterName,
                chapterId = item.chapterId,
                chapterUrl = item.chapterUrl,
            ),
        )
    }

    private fun applyVisibleItems() {
        val downloadedFilter = state.value.filterDownloaded
        _state.update {
            it.copy(
                items = when (downloadedFilter) {
                    TriState.DISABLED -> rawItems
                    TriState.ENABLED_IS -> rawItems.filter { item -> isDownloaded(item) }
                    TriState.ENABLED_NOT -> rawItems.filterNot { item -> isDownloaded(item) }
                },
            )
        }
    }
}

private fun TriState.toBooleanOrNull(): Boolean? = when (this) {
    TriState.DISABLED -> null
    TriState.ENABLED_IS -> true
    TriState.ENABLED_NOT -> false
}
