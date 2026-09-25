package mihon.desktop.updates

import cafe.adriel.voyager.core.model.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import mihon.domain.download.DownloadQueueEntry
import mihon.domain.download.DownloadQueueStatus
import mihon.domain.download.EnqueueDownload
import mihon.domain.download.IsChapterDownloaded
import mihon.domain.reader.content.DownloadChapterIdentity
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.creator.model.ArchiveDiscovery
import tachiyomi.domain.creator.model.ArchiveUnreadWork
import tachiyomi.domain.creator.model.ArchiveLanguageSubject
import tachiyomi.domain.creator.model.DiscoveryReadState
import tachiyomi.domain.creator.model.ReviewDisposition
import tachiyomi.domain.creator.model.LanguageDimension
import mihon.desktop.ui.authors.LanguageArchiveFilter
import tachiyomi.domain.creator.repository.CreatorArchiveRepository
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
    val markAllReadFailed: Boolean = false,
    val markAllReadRunning: Boolean = false,
    val showFilterDialog: Boolean = false,
    val filterUnread: TriState = TriState.DISABLED,
    val filterDownloaded: TriState = TriState.DISABLED,
    val filterStarted: TriState = TriState.DISABLED,
    val filterBookmarked: TriState = TriState.DISABLED,
    val filterExcludedScanlators: Boolean = false,
    val creatorDiscoveries: List<ArchiveDiscovery> = emptyList(),
    val unreadWorks: List<ArchiveUnreadWork> = emptyList(),
    val showCreatorDiscoveries: Boolean = true,
    val creatorLanguageFilter: LanguageArchiveFilter = LanguageArchiveFilter.ALL,
) {
    val unreadDiscoveryCount: Int
        get() = if (unreadWorks.isNotEmpty()) unreadWorks.size else {
            creatorDiscoveries.count { it.state.readState == DiscoveryReadState.UNSEEN }
        }

    val hasActiveFilters: Boolean
        get() = listOf(filterUnread, filterDownloaded, filterStarted, filterBookmarked)
            .any { it != TriState.DISABLED } || filterExcludedScanlators || !showCreatorDiscoveries ||
            creatorLanguageFilter != LanguageArchiveFilter.ALL

    val visibleCreatorDiscoveries: List<ArchiveDiscovery>
        get() = if (!showCreatorDiscoveries) {
            emptyList()
        } else {
            creatorDiscoveries.filter { creatorLanguageFilter.accepts(it.readingLanguage.certainty) }
        }
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
    private val isChapterDownloaded: IsChapterDownloaded,
    private val enqueueDownload: EnqueueDownload,
    private val downloadIdentity: (UpdatesWithRelations) -> DownloadChapterIdentity = { item ->
        DownloadChapterIdentity(
            sourceDisplayName = item.sourceId.toString(),
            mangaTitle = item.mangaTitle,
            chapterName = item.chapterName,
            scanlator = item.scanlator,
            chapterUrl = item.chapterUrl,
            disallowNonAsciiFilenames = false,
        )
    },
    private val creatorArchiveRepository: CreatorArchiveRepository? = null,
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
        val discoveries = creatorArchiveRepository?.let { repository ->
            repository.getDiscoveries(DISCOVERY_LIMIT).map { discovery ->
                discovery.copy(
                    readingLanguage = repository.getLanguageProjection(
                        ArchiveLanguageSubject.SourceWork(discovery.sourceWork),
                        LanguageDimension.READING,
                    ),
                )
            }
        }.orEmpty()
        _state.update { it.copy(creatorDiscoveries = discoveries) }
        val unreadWorks = creatorArchiveRepository?.getUnreadWorkDiscoveries(DISCOVERY_LIMIT).orEmpty()
        _state.update { it.copy(unreadWorks = unreadWorks) }
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
        _state.update { it.copy(showMarkAllReadDialog = show, markAllReadFailed = false) }
    }

    fun setShowFilterDialog(show: Boolean) {
        _state.update { it.copy(showFilterDialog = show) }
    }

    suspend fun markAllRead() {
        if (state.value.markAllReadRunning) return
        _state.update { it.copy(markAllReadRunning = true, markAllReadFailed = false) }
        try {
            val unreadItems = state.value.items.filter { !it.read }
            val unreadDiscoveries = state.value.creatorDiscoveries.filter {
                it.state.readState == DiscoveryReadState.UNSEEN
            }
            updateChapter.awaitAll(unreadItems.map { ChapterUpdate(id = it.chapterId, read = true) })
            if (state.value.unreadWorks.isNotEmpty()) {
                state.value.unreadWorks.forEach { work ->
                    work.creatorIds.forEach { creatorId ->
                        val authorSourceWork = requireNotNull(work.sourceWorksByCreator[creatorId]) {
                            "Unread reminder no longer has a source for author $creatorId"
                        }
                        creatorArchiveRepository?.markPresentationGroupSeen(creatorId, authorSourceWork, now())
                    }
                    creatorArchiveRepository?.markWorkSeen(work.sourceWork, now())
                }
            } else {
                creatorArchiveRepository?.markDiscoveriesSeen(unreadDiscoveries.mapTo(mutableSetOf(), ArchiveDiscovery::id), now())
            }
            val unreadIds = unreadItems.map { it.chapterId }.toSet()
            val unreadDiscoveryIds = unreadDiscoveries.mapTo(mutableSetOf(), ArchiveDiscovery::id)
            rawItems = rawItems.map { if (it.chapterId in unreadIds) it.copy(read = true) else it }
            _state.update {
                it.copy(
                    items = it.items.map { item -> if (item.chapterId in unreadIds) item.copy(read = true) else item },
                    creatorDiscoveries = it.creatorDiscoveries.map { discovery ->
                        if (discovery.id in unreadDiscoveryIds) {
                            discovery.copy(state = discovery.state.copy(readState = DiscoveryReadState.SEEN))
                        } else {
                            discovery
                        }
                    },
                    unreadWorks = emptyList(),
                    showMarkAllReadDialog = false,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            _state.update { it.copy(markAllReadFailed = true) }
        } finally {
            _state.update { it.copy(markAllReadRunning = false) }
        }
    }

    suspend fun markDiscoverySeen(discovery: ArchiveDiscovery) {
        if (discovery.state.readState == DiscoveryReadState.SEEN) return
        creatorArchiveRepository?.markDiscoverySeen(discovery.id, now())
        updateDiscovery(discovery.id) {
            it.copy(state = it.state.copy(readState = DiscoveryReadState.SEEN))
        }
    }

    suspend fun ignoreDiscovery(discovery: ArchiveDiscovery) {
        creatorArchiveRepository?.setDiscoveryReview(discovery.id, ReviewDisposition.IGNORED, now())
        updateDiscovery(discovery.id) {
            it.copy(state = it.state.copy(reviewDisposition = ReviewDisposition.IGNORED))
        }
    }

    suspend fun undoDiscoveryReview(discovery: ArchiveDiscovery) {
        creatorArchiveRepository?.setDiscoveryReview(discovery.id, ReviewDisposition.PENDING, now())
        updateDiscovery(discovery.id) {
            it.copy(state = it.state.copy(reviewDisposition = ReviewDisposition.PENDING))
        }
    }

    fun toggleCreatorDiscoveries() {
        _state.update { it.copy(showCreatorDiscoveries = !it.showCreatorDiscoveries) }
    }

    fun setCreatorLanguageFilter(filter: LanguageArchiveFilter) {
        _state.update { it.copy(creatorLanguageFilter = filter) }
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
            DownloadQueueEntry(
                sourceId = item.sourceId,
                mangaTitle = item.mangaTitle,
                chapterName = item.chapterName,
                chapterId = item.chapterId,
                mangaId = item.mangaId,
                chapterUrl = item.chapterUrl,
                pageUrls = emptyList(),
                status = DownloadQueueStatus.QUEUED,
                position = Long.MAX_VALUE,
            ),
        )
    }

    private fun applyVisibleItems() {
        val downloadedFilter = state.value.filterDownloaded
        _state.update {
            it.copy(
                items = when (downloadedFilter) {
                    TriState.DISABLED -> rawItems
                    TriState.ENABLED_IS -> rawItems.filter { item ->
                        isChapterDownloaded(item.sourceId, downloadIdentity(item))
                    }
                    TriState.ENABLED_NOT -> rawItems.filterNot { item ->
                        isChapterDownloaded(item.sourceId, downloadIdentity(item))
                    }
                },
            )
        }
    }

    private fun updateDiscovery(id: Long, transform: (ArchiveDiscovery) -> ArchiveDiscovery) {
        _state.update { state ->
            state.copy(creatorDiscoveries = state.creatorDiscoveries.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val DISCOVERY_LIMIT = 200L
    }
}

private fun TriState.toBooleanOrNull(): Boolean? = when (this) {
    TriState.DISABLED -> null
    TriState.ENABLED_IS -> true
    TriState.ENABLED_NOT -> false
}
