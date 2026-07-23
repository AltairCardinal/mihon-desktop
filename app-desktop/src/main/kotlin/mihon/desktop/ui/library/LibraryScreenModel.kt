package mihon.desktop.ui.library

import cafe.adriel.voyager.core.model.ScreenModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.download.DownloadItem
import mihon.desktop.download.DownloadStatus
import mihon.desktop.domain.SortMode
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.ReaderNavigator
import mihon.desktop.settings.LibraryCategoryPrefs
import mihon.domain.task.TaskStatus
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.track.service.TrackerSessionProvider
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.library.interactor.LibraryFilter
import mihon.desktop.domain.LibrarySearchFilter

/**
 * Voyager ScreenModel for [LibraryRootScreen].
 *
 * Owns all library UI state and exposes it as [StateFlow<LibraryState>].
 * All state transitions go through explicit mutation methods, enabling
 * JVM unit tests without Compose or DI.
 */
data class LibraryReaderRequest(
    val chapterTitle: String,
    val mangaTitle: String,
    val sourceId: Long,
    val chapterUrl: String,
    val chapterId: Long,
    val mangaId: Long,
    val mangaViewerFlags: Long,
    val chapters: List<ReaderChapterRef>,
    val currentChapterIndex: Int,
    val initialPage: Int,
)

data class LibraryBatchDownloadResult(
    val queued: Int = 0,
    val skipped: Int = 0,
    val failures: Int = 0,
)

class LibraryScreenModel(
    private val getLibraryManga: GetLibraryManga? = null,
    private val getCategories: GetCategories? = null,
    private val createCategory: CreateCategoryWithName? = null,
    private val renameCategory: RenameCategory? = null,
    private val deleteCategory: DeleteCategory? = null,
    private val reorderCategory: ReorderCategory? = null,
    private val updateChecker: LibraryUpdateChecker? = null,
    private val sourceManager: SourceManager? = null,
    private val chapterRepository: ChapterRepository? = null,
    private val mangaRepository: MangaRepository? = null,
    private val setMangaCategories: SetMangaCategories? = null,
    private val enqueueDownload: ((DownloadItem) -> Unit)? = null,
    private val downloadProvider: DesktopDownloadProvider? = null,
    private val downloadPreferences: DesktopDownloadPreferences? = null,
    private val categoryPrefs: LibraryCategoryPrefs? = null,
    private val categoryRepository: CategoryRepository? = null,
    private val trackRepository: TrackRepository? = null,
    private val trackerSessionProvider: TrackerSessionProvider? = null,
    private val startBackgroundUpdate: (() -> Job)? = null,
    private val cancelBackgroundUpdate: (() -> Boolean)? = null,
    private val backgroundUpdateStatus: (() -> TaskStatus?)? = null,
) : ScreenModel {

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    // ── Data loading ──────────────────────────────────────────────────────────

    fun libraryMangaFlow(): Flow<List<LibraryManga>> = combine(
        requireNotNull(getLibraryManga) { "GetLibraryManga is required" }.subscribe(),
        trackRepository?.getTracksAsFlow() ?: flowOf(emptyList()),
        trackerSessionProvider?.loggedInTrackerIds() ?: flowOf(emptySet()),
    ) { items, tracks, loggedInTrackerIds ->
        updateLibrarySnapshot(items, tracks, loggedInTrackerIds)
        items
    }

    private fun updateLibrarySnapshot(
        items: List<LibraryManga>,
        tracks: List<Track>,
        loggedInTrackerIds: Set<Long>,
    ) {
        val activeTracks = tracks.filter { it.trackerId in loggedInTrackerIds }
        val tracksByManga = activeTracks.groupBy { it.mangaId }
        val localMangaIds = items.mapNotNullTo(mutableSetOf()) { item ->
            item.id.takeIf { item.manga.source == LOCAL_SOURCE_ID }
        }
        val trackerIdsByManga = tracksByManga.mapValues { (_, mangaTracks) ->
            mangaTracks.mapTo(mutableSetOf()) { track -> track.trackerId }
        }
        _state.update {
            it.copy(
                allItems = items,
                downloadedMangaIds = downloadedMangaIds(items),
                localMangaIds = localMangaIds,
                trackerIdsByManga = trackerIdsByManga,
                availableTrackerIds = activeTracks.mapTo(mutableSetOf()) { track -> track.trackerId },
                filter = it.filter.copy(
                    tracking = it.filter.tracking.filterKeys { trackerId -> trackerId in loggedInTrackerIds },
                ),
            )
        }
    }

    suspend fun refreshCategories() {
        setCategories(requireNotNull(getCategories) { "GetCategories is required" }.await())
    }

    suspend fun createCategory(name: String) {
        requireNotNull(createCategory) { "CreateCategoryWithName is required" }.await(name.trim())
        refreshCategories()
    }

    suspend fun renameCategory(categoryId: Long, name: String) {
        requireNotNull(renameCategory) { "RenameCategory is required" }.await(categoryId, name.trim())
        refreshCategories()
    }

    suspend fun deleteCategory(categoryId: Long) {
        requireNotNull(deleteCategory) { "DeleteCategory is required" }.await(categoryId)
        refreshCategories()
    }

    suspend fun reorderCategory(categoryId: Long, newIndex: Int) {
        val category = state.value.categories.firstOrNull { it.id == categoryId } ?: return
        requireNotNull(reorderCategory) { "ReorderCategory is required" }.await(category, newIndex)
        refreshCategories()
    }

    fun setAllItems(items: List<LibraryManga>) {
        _state.update { it.copy(allItems = items) }
    }

    fun setCategories(categories: List<Category>) {
        _state.update { it.copy(categories = categories) }
    }

    // ── Update status ─────────────────────────────────────────────────────────

    fun setIsUpdating(updating: Boolean) {
        _state.update { it.copy(isUpdating = updating) }
    }

    fun setUpdateStatusText(text: String?) {
        _state.update { it.copy(updateStatusText = text) }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    // ── Sort ──────────────────────────────────────────────────────────────────

    fun setSortMode(mode: SortMode) {
        _state.update { it.copy(sortMode = mode) }
    }

    fun setSortAscending(ascending: Boolean) {
        _state.update { it.copy(sortAscending = ascending) }
    }

    fun setSortModeAndDirection(mode: SortMode, ascending: Boolean) {
        _state.update { it.copy(sortMode = mode, sortAscending = ascending) }
    }

    fun applyCategoryPreferences(categoryId: Long?) {
        val prefs = categoryPrefs ?: return
        _state.update {
            it.copy(
                sortMode = prefs.getSortMode(categoryId),
                sortAscending = prefs.getSortAscending(categoryId),
                displayMode = prefs.getDisplayMode(categoryId),
            )
        }
    }

    fun setSortModeAndDirectionForCategory(categoryId: Long?, mode: SortMode, ascending: Boolean) {
        setSortModeAndDirection(mode, ascending)
        categoryPrefs?.setSortMode(categoryId, mode)
        categoryPrefs?.setSortAscending(categoryId, ascending)
    }

    // ── Filters ───────────────────────────────────────────────────────────────

    fun setFilter(filter: LibraryFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun setFilters(unread: Boolean, started: Boolean, completed: Boolean, downloaded: Boolean) {
        setFilter(
            state.value.filter.copy(
                unread = unread.asTriState(),
                started = started.asTriState(),
                completed = completed.asTriState(),
                downloaded = downloaded.asTriState(),
            ),
        )
    }

    fun toggleFilter(field: LibraryFilterField) {
        _state.update { current ->
            val filter = current.filter
            current.copy(filter = when (field) {
                LibraryFilterField.DOWNLOADED -> filter.copy(downloaded = filter.downloaded.next())
                LibraryFilterField.UNREAD -> filter.copy(unread = filter.unread.next())
                LibraryFilterField.STARTED -> filter.copy(started = filter.started.next())
                LibraryFilterField.BOOKMARKED -> filter.copy(bookmarked = filter.bookmarked.next())
                LibraryFilterField.COMPLETED -> filter.copy(completed = filter.completed.next())
                LibraryFilterField.INTERVAL_CUSTOM -> filter.copy(intervalCustom = filter.intervalCustom.next())
            })
        }
    }

    fun toggleTrackingFilter(trackerId: Long) {
        _state.update { current ->
            val next = current.filter.tracking[trackerId].orDisabled().next()
            current.copy(filter = current.filter.copy(tracking = current.filter.tracking + (trackerId to next)))
        }
    }

    fun toggleGlobalDownloadedOnly() {
        _state.update { it.copy(filter = it.filter.copy(globalDownloadedOnly = !it.filter.globalDownloadedOnly)) }
    }

    fun toggleSkipOutsideReleasePeriod() {
        _state.update {
            it.copy(filter = it.filter.copy(skipOutsideReleasePeriod = !it.filter.skipOutsideReleasePeriod))
        }
    }

    fun setEvaluationContext(
        downloadedMangaIds: Set<Long>,
        localMangaIds: Set<Long> = emptySet(),
        trackerIdsByManga: Map<Long, Set<Long>> = emptyMap(),
    ) {
        _state.update {
            it.copy(
                downloadedMangaIds = downloadedMangaIds,
                localMangaIds = localMangaIds,
                trackerIdsByManga = trackerIdsByManga,
                availableTrackerIds = trackerIdsByManga.values.flatten().toSet(),
            )
        }
    }

    fun visibleItems(categoryId: Long? = null): List<LibraryManga> = state.value.let {
        LibrarySearchFilter.apply(
            items = it.allItems,
            categoryId = categoryId,
            searchQuery = it.searchQuery,
            filter = it.filter,
            downloadedMangaIds = it.downloadedMangaIds,
            localMangaIds = it.localMangaIds,
            trackerIds = it.trackerIdsByManga,
            trackerMeans = emptyMap(),
            sort = LibrarySearchFilter.toSharedSort(it.sortMode, it.sortAscending),
        )
    }

    // ── Category selection ────────────────────────────────────────────────────

    fun setSelectedCategoryIndex(index: Int) {
        _state.update { it.copy(selectedCategoryIndex = index) }
    }

    // ── Display mode ──────────────────────────────────────────────────────────

    fun setDisplayMode(mode: LibraryDisplayMode) {
        _state.update { it.copy(displayMode = mode) }
    }

    fun setDisplayModeForCategory(categoryId: Long?, mode: LibraryDisplayMode) {
        setDisplayMode(mode)
        categoryPrefs?.setDisplayMode(categoryId, mode)
    }

    // ── Dialog / menu visibility ──────────────────────────────────────────────

    fun setShowCategoryDialog(show: Boolean) {
        _state.update { it.copy(showCategoryDialog = show) }
    }

    fun setContextMenuManga(manga: LibraryManga?) {
        _state.update { it.copy(contextMenuManga = manga) }
    }

    fun setShowBatchCategoryDialog(show: Boolean) {
        _state.update { it.copy(showBatchCategoryDialog = show) }
    }

    fun downloadedMangaIds(items: List<LibraryManga>): Set<Long> {
        val provider = downloadProvider ?: return emptySet()
        return items
            .filter { provider.hasMangaDownloads(it.manga.source, it.manga.title) }
            .map { it.id }
            .toSet()
    }

    suspend fun refreshLibrary(items: List<LibraryManga>) {
        if (_state.value.isUpdating && cancelBackgroundUpdate != null) {
            cancelLibraryUpdate()
            return
        }
        startBackgroundUpdate?.let { start ->
            setIsUpdating(true)
            setUpdateStatusText("Checking for updates...")
            try {
                start().join()
                setUpdateStatusText(
                    when (backgroundUpdateStatus?.invoke()) {
                        TaskStatus.Failed -> "Library update failed"
                        TaskStatus.Cancelled -> "Library update cancelled"
                        else -> "Library update finished"
                    },
                )
            } finally {
                setIsUpdating(false)
            }
            return
        }
        val sourceManager = requireNotNull(sourceManager) { "SourceManager is required" }
        val updateChecker = requireNotNull(updateChecker) { "LibraryUpdateChecker is required" }
        val autoDownload = downloadPreferences?.autoDownloadNewChapters?.get() == true

        setIsUpdating(true)
        setUpdateStatusText("Checking for updates...")
        var totalNew = 0
        try {
            for (item in items) {
                val source = sourceManager.getCatalogueSources()
                    .find { it.id == item.manga.source }
                    ?: continue
                val result = updateChecker.checkForUpdates(item.manga, source)
                totalNew += result.newChapterCount
                if (autoDownload) {
                    result.newChapters.forEach { chapter ->
                        enqueueDownload?.invoke(
                            DownloadItem(
                                sourceId = item.manga.source,
                                mangaTitle = item.manga.title,
                                chapterName = chapter.name,
                                chapterId = chapter.id,
                                chapterUrl = chapter.url,
                            ),
                        )
                    }
                }
            }
            setUpdateStatusText(
                if (totalNew > 0) "$totalNew new chapter(s) found" else "Library is up to date",
            )
        } finally {
            setIsUpdating(false)
        }
    }

    fun cancelLibraryUpdate(): Boolean = cancelBackgroundUpdate?.invoke() == true

    suspend fun markMangaRead(mangaId: Long, read: Boolean) {
        val repository = requireNotNull(chapterRepository) { "ChapterRepository is required" }
        val updates = repository.getChapterByMangaId(mangaId)
            .map { ChapterUpdate(id = it.id, read = read) }
        repository.updateAll(updates)
    }

    suspend fun markMangaRead(mangaIds: Iterable<Long>, read: Boolean) {
        mangaIds.forEach { markMangaRead(it, read) }
    }

    suspend fun removeFromLibrary(mangaIds: Iterable<Long>) {
        val repository = requireNotNull(mangaRepository) { "MangaRepository is required" }
        mangaIds.forEach { mangaId ->
            repository.update(MangaUpdate(id = mangaId, favorite = false))
        }
    }

    suspend fun enqueueNextUnreadDownload(item: LibraryManga): Boolean {
        val repository = requireNotNull(chapterRepository) { "ChapterRepository is required" }
        val enqueue = requireNotNull(enqueueDownload) { "Download enqueue callback is required" }
        val firstUnread = repository.getChapterByMangaId(item.manga.id)
            .sortedBy { it.sourceOrder }
            .firstOrNull { !it.read }
            ?: return false
        enqueue(
            DownloadItem(
                sourceId = item.manga.source,
                mangaTitle = item.manga.title,
                chapterName = firstUnread.name,
                chapterId = firstUnread.id,
                chapterUrl = firstUnread.url,
            ),
        )
        return true
    }

    internal suspend fun enqueueDownloads(
        items: List<LibraryManga>,
        action: MangaDetailDownloadAction,
        queue: List<DownloadItem> = emptyList(),
    ): LibraryBatchDownloadResult {
        if (items.isEmpty()) {
            _state.update { it.copy(batchCategoryResultMessage = "No manga selected") }
            return LibraryBatchDownloadResult()
        }
        val repository = requireNotNull(chapterRepository) { "ChapterRepository is required" }
        val enqueue = requireNotNull(enqueueDownload) { "Download enqueue callback is required" }
        val activeIds = queue
            .filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }
            .mapTo(mutableSetOf()) { it.chapterId }
        var result = LibraryBatchDownloadResult()
        items.forEach { item ->
            val chapters = runCatching {
                val all = repository.getChapterByMangaId(item.id)
                if (action == MangaDetailDownloadAction.BOOKMARKED_CHAPTERS) all.filter { it.bookmark } else chaptersForDownloadAction(all, action)
            }.getOrElse {
                result = result.copy(failures = result.failures + 1)
                return@forEach
            }
            chapters.forEach { chapter ->
                val unavailable = chapter.id in activeIds ||
                    downloadProvider?.isChapterDownloaded(item.manga.source, item.manga.title, chapter.name) == true
                result = when {
                    unavailable -> result.copy(skipped = result.skipped + 1)
                    runCatching {
                        enqueue(
                            DownloadItem(
                                sourceId = item.manga.source,
                                mangaTitle = item.manga.title,
                                chapterName = chapter.name,
                                chapterId = chapter.id,
                                mangaId = item.id,
                                chapterUrl = chapter.url,
                            ),
                        )
                    }.isSuccess -> result.copy(queued = result.queued + 1)
                    else -> result.copy(failures = result.failures + 1)
                }
            }
        }
        _state.update {
            it.copy(batchCategoryResultMessage = "${result.queued} queued, ${result.skipped} skipped, ${result.failures} failed")
        }
        return result
    }

    suspend fun continueReadingRequest(item: LibraryManga): LibraryReaderRequest? {
        val repository = requireNotNull(chapterRepository) { "ChapterRepository is required" }
        val chapters = repository.getChapterByMangaId(item.manga.id)
            .sortedBy { it.sourceOrder }
        val target = chapters.firstOrNull { !it.read }
            ?: chapters.maxByOrNull { it.sourceOrder }
            ?: return null
        val chapterRefs = chapters.toReaderChapterRefs(
            currentChapterId = target.id,
            manga = item.manga,
            isChapterDownloaded = { chapter ->
                downloadProvider?.isChapterDownloaded(
                    item.manga.source,
                    item.manga.title,
                    chapter.name,
                ) == true
            },
        )
        return LibraryReaderRequest(
            chapterTitle = target.name,
            mangaTitle = item.manga.title,
            sourceId = item.manga.source,
            chapterUrl = target.url,
            chapterId = target.id,
            mangaId = item.manga.id,
            mangaViewerFlags = item.manga.viewerFlags,
            chapters = chapterRefs,
            currentChapterIndex = ReaderNavigator.indexForId(chapterRefs, target.id),
            initialPage = target.lastPageRead.toInt().coerceAtLeast(0),
        )
    }

    suspend fun setCategoriesForManga(mangaIds: List<Long>, categoryIds: List<Long>) {
        val result = requireNotNull(setMangaCategories) { "SetMangaCategories is required" }
            .awaitBatch(mangaIds, categoryIds)
        _state.update {
            it.copy(
                batchCategoryResultMessage = if (result.failures.isEmpty()) {
                    "${result.succeededIds.size} updated"
                } else {
                    "${result.succeededIds.size} updated, ${result.failures.size} failed"
                },
            )
        }
    }

    suspend fun categoryIdsForManga(mangaId: Long): Set<Long> {
        val repository = requireNotNull(categoryRepository) { "CategoryRepository is required" }
        return repository.getCategoriesByMangaId(mangaId).map { it.id }.toSet()
    }
}

enum class LibraryFilterField { DOWNLOADED, UNREAD, STARTED, BOOKMARKED, COMPLETED, INTERVAL_CUSTOM }

private fun TriState?.orDisabled() = this ?: TriState.DISABLED
private fun Boolean.asTriState() = if (this) TriState.ENABLED_IS else TriState.DISABLED

private const val LOCAL_SOURCE_ID = 0L
