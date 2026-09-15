package mihon.desktop.ui.library

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.desktop.domain.LibrarySearchFilter
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.domain.SortMode
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.download.DownloadItem
import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.ReaderNavigator
import mihon.desktop.settings.LibraryCategoryPrefs
import mihon.domain.sync.SyncMutationContext
import mihon.domain.task.TaskStatus
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.interactor.SetDisplayMode
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.interactor.SetSortModeForCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetBookmarkedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.SetChapterReadStatus
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.library.applyLibraryCategoryDelta
import tachiyomi.domain.library.interactor.LibraryFilter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.selectLibraryDownloadChapters
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.UpdateManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracksPerManga
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.service.TrackerSessionProvider
import tachiyomi.i18n.MR
import java.util.Locale
import kotlin.random.Random
import tachiyomi.domain.library.model.LibraryDisplayMode as SharedLibraryDisplayMode

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
    private val getChaptersByMangaId: GetChaptersByMangaId? = null,
    private val getBookmarkedChaptersByMangaId: GetBookmarkedChaptersByMangaId? = null,
    private val getNextChapters: GetNextChapters? = null,
    private val setChapterReadStatus: SetChapterReadStatus? = null,
    private val updateManga: UpdateManga? = null,
    private val setMangaCategories: SetMangaCategories? = null,
    private val enqueueDownload: ((DownloadItem) -> Unit)? = null,
    private val downloadProvider: DesktopDownloadProvider? = null,
    private val isMangaDownloaded: ((LibraryManga) -> Boolean)? = null,
    private val downloadPreferences: DesktopDownloadPreferences? = null,
    private val categoryPrefs: LibraryCategoryPrefs? = null,
    private val getTracksPerManga: GetTracksPerManga? = null,
    private val trackerSessionProvider: TrackerSessionProvider? = null,
    private val startBackgroundUpdate: (() -> Job)? = null,
    private val startScopedBackgroundUpdate: ((Long?) -> Job)? = null,
    private val cancelBackgroundUpdate: (() -> Boolean)? = null,
    private val backgroundUpdateStatus: (() -> TaskStatus?)? = null,
    private val backgroundUpdateJob: (() -> Job?)? = null,
    private val libraryPreferences: LibraryPreferences? = null,
    private val setDisplayModeInteractor: SetDisplayMode? = null,
    private val setSortModeForCategory: SetSortModeForCategory? = null,
    private val downloadedChapterCount: ((LibraryManga) -> Long)? = null,
    private val deleteMangaDownloads: (suspend (LibraryManga) -> Unit)? = null,
    private val deleteCustomCover: ((Long) -> Boolean)? = null,
    private val getCategoryIdsForManga: (suspend (Long) -> Set<Long>)? = null,
    private val sharedDownloadPreferences: DownloadPreferences? = null,
    private val deleteChapterDownload: (suspend (LibraryManga, Chapter) -> Unit)? = null,
    private val isChapterDownloaded: ((LibraryManga, Chapter) -> Boolean)? = null,
    private val isChapterQueued: ((Chapter) -> Boolean)? = null,
    private val downloadQueueChanges: Flow<Unit> = flowOf(Unit),
) : ScreenModel {

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    private var categoryProjectionInitialized = false
    private var pendingInitialCategoryIndex: Int? = null
    private var observedBackgroundUpdate: Job? = null

    init {
        applySharedPreferences(categoryId = null)
        syncBackgroundUpdate()
    }

    fun syncBackgroundUpdate() {
        val runningJob = backgroundUpdateJob?.invoke()?.takeIf(Job::isActive) ?: return
        setIsUpdating(true)
        setUpdateStatusText(MR.strings.desktop_ui_checking_for_updates.localized())
        if (observedBackgroundUpdate === runningJob) return
        observedBackgroundUpdate = runningJob
        screenModelScope.launch {
            runningJob.join()
            if (observedBackgroundUpdate === runningJob) {
                observedBackgroundUpdate = null
                setIsUpdating(false)
                setUpdateStatusText(backgroundUpdateResultText())
            }
        }
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    fun libraryMangaFlow(propagateErrors: Boolean = false): Flow<List<LibraryManga>> = combine(
        requireNotNull(getLibraryManga) { "GetLibraryManga is required" }.subscribe(),
        getTracksPerManga?.subscribe() ?: flowOf(emptyMap()),
        trackerSessionProvider?.loggedInTrackerIds() ?: flowOf(emptySet()),
        downloadQueueChanges,
    ) { items, tracksByManga, loggedInTrackerIds, _ ->
        updateLibrarySnapshot(items, tracksByManga, loggedInTrackerIds)
        items
    }.catch { error ->
        if (propagateErrors || error is CancellationException) throw error
        _state.update {
            it.copy(
                isLoading = false,
                loadError = MR.strings.internal_error.localized(),
            )
        }
        emit(emptyList())
    }

    private fun updateLibrarySnapshot(
        items: List<LibraryManga>,
        tracksByManga: Map<Long, List<Track>>,
        loggedInTrackerIds: Set<Long>,
    ) {
        val activeTracksByManga = tracksByManga
            .mapValues { (_, tracks) -> tracks.filter { it.trackerId in loggedInTrackerIds } }
            .filterValues { it.isNotEmpty() }
        val activeTracks = activeTracksByManga.values.flatten()
        val localMangaIds = items.mapNotNullTo(mutableSetOf()) { item ->
            item.id.takeIf { item.manga.source == LOCAL_SOURCE_ID }
        }
        val trackerIdsByManga = activeTracksByManga.mapValues { (_, mangaTracks) ->
            mangaTracks.mapTo(mutableSetOf()) { track -> track.trackerId }
        }
        val trackerMeansByManga = activeTracksByManga.mapValues { (_, mangaTracks) ->
            mangaTracks.map { it.score }.average()
        }
        val pendingCategoryIndex = pendingInitialCategoryIndex
        _state.update {
            val downloadedIds = downloadedMangaIds(items)
            val projectedCategories = libraryCategoryTabs(it.allCategories, items)
            it.copy(
                allItems = items,
                downloadedMangaIds = downloadedIds,
                downloadCountsByManga = items.associate { item ->
                    item.id to (
                        downloadedChapterCount?.invoke(item)
                            ?: if (item.id in downloadedIds) 1L else 0L
                        )
                },
                localMangaIds = localMangaIds,
                sourceLanguagesByManga = sourceLanguagesByManga(items),
                trackerIdsByManga = trackerIdsByManga,
                trackerMeansByManga = trackerMeansByManga,
                availableTrackerIds = loggedInTrackerIds,
                categories = projectedCategories,
                selectedCategoryIndex = (pendingCategoryIndex ?: it.selectedCategoryIndex).coerceIn(
                    0,
                    (projectedCategories.size - 1).coerceAtLeast(0),
                ),
                filter = it.filter.copy(
                    tracking = loggedInTrackerIds.associateWith { trackerId ->
                        libraryPreferences?.filterTracking(trackerId.toInt())?.get()
                            ?: it.filter.tracking[trackerId]
                            ?: TriState.DISABLED
                    },
                ),
                isLoading = false,
                loadError = null,
            )
        }
        pendingInitialCategoryIndex = null
    }

    suspend fun refreshCategories() {
        setCategories(requireNotNull(getCategories) { "GetCategories is required" }.await())
    }

    suspend fun observeCategories() {
        requireNotNull(getCategories) { "GetCategories is required" }
            .subscribe()
            .collect(::setCategories)
    }

    suspend fun createCategory(name: String) {
        when (val result = requireNotNull(createCategory) { "CreateCategoryWithName is required" }.await(name.trim())) {
            CreateCategoryWithName.Result.Success -> {
                setOperationFeedback(null)
                refreshCategoriesAfterCategoryOperation()
            }
            is CreateCategoryWithName.Result.InternalError -> setCategoryOperationFailure()
        }
    }

    suspend fun renameCategory(categoryId: Long, name: String) {
        when (
            val result = requireNotNull(renameCategory) {
                "RenameCategory is required"
            }.await(categoryId, name.trim())
        ) {
            RenameCategory.Result.Success -> {
                setOperationFeedback(null)
                refreshCategoriesAfterCategoryOperation()
            }
            is RenameCategory.Result.InternalError -> setCategoryOperationFailure()
        }
    }

    suspend fun deleteCategory(categoryId: Long) {
        when (val result = requireNotNull(deleteCategory) { "DeleteCategory is required" }.await(categoryId)) {
            DeleteCategory.Result.Success -> {
                setOperationFeedback(null)
                refreshCategoriesAfterCategoryOperation()
            }
            is DeleteCategory.Result.InternalError -> setCategoryOperationFailure()
        }
    }

    suspend fun reorderCategory(categoryId: Long, newIndex: Int) {
        val category = state.value.categories.firstOrNull { it.id == categoryId } ?: return
        when (
            val result = requireNotNull(reorderCategory) {
                "ReorderCategory is required"
            }.await(category, newIndex)
        ) {
            ReorderCategory.Result.Success -> {
                setOperationFeedback(null)
                refreshCategoriesAfterCategoryOperation()
            }
            ReorderCategory.Result.Unchanged -> Unit
            is ReorderCategory.Result.InternalError -> setCategoryOperationFailure()
        }
    }

    private suspend fun refreshCategoriesAfterCategoryOperation() {
        runCatching { refreshCategories() }
            .onFailure { setCategoryOperationFailure() }
    }

    private fun setCategoryOperationFailure() {
        setOperationFeedback(MR.strings.internal_error.localized())
    }

    fun setAllItems(items: List<LibraryManga>) {
        _state.update {
            val downloadedIds = downloadedMangaIds(items)
            val projectedCategories = libraryCategoryTabs(it.allCategories, items)
            it.copy(
                allItems = items,
                downloadedMangaIds = downloadedIds,
                downloadCountsByManga = items.associate { item ->
                    item.id to (
                        downloadedChapterCount?.invoke(item)
                            ?: if (item.id in downloadedIds) 1L else 0L
                        )
                },
                sourceLanguagesByManga = sourceLanguagesByManga(items),
                categories = projectedCategories,
                selectedCategoryIndex = it.selectedCategoryIndex.coerceIn(
                    0,
                    (projectedCategories.size - 1).coerceAtLeast(0),
                ),
                isLoading = false,
                loadError = null,
            )
        }
    }

    private fun sourceLanguagesByManga(items: List<LibraryManga>): Map<Long, String> =
        items.mapNotNull { item ->
            sourceManager?.getOrStub(item.manga.source)?.lang
                ?.takeIf { it.isNotBlank() }
                ?.let { language -> item.id to language }
        }.toMap()

    fun setCategories(categories: List<Category>) {
        if (!categoryProjectionInitialized) {
            categoryProjectionInitialized = true
            pendingInitialCategoryIndex = libraryPreferences?.lastUsedCategory()?.get()
        }
        _state.update {
            val projected = libraryCategoryTabs(categories, it.allItems)
            val selectedIndex = pendingInitialCategoryIndex ?: it.selectedCategoryIndex
            it.copy(
                allCategories = categories,
                categories = projected,
                selectedCategoryIndex = selectedIndex.coerceIn(0, (projected.size - 1).coerceAtLeast(0)),
            )
        }
        if (!state.value.isLoading) pendingInitialCategoryIndex = null
    }

    // ── Update status ─────────────────────────────────────────────────────────

    fun setIsUpdating(updating: Boolean) {
        _state.update { it.copy(isUpdating = updating) }
    }

    fun setUpdateStatusText(text: String?) {
        _state.update { it.copy(updateStatusText = text) }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    fun setSearchQuery(query: String?) {
        _state.update { it.copy(searchQuery = query) }
    }

    // ── Sort ──────────────────────────────────────────────────────────────────

    fun setSortMode(mode: SortMode) {
        setSortModeAndDirection(mode, state.value.sortAscending)
    }

    fun setSortAscending(ascending: Boolean) {
        setSortModeAndDirection(state.value.sortMode, ascending)
    }

    fun setSortModeAndDirection(mode: SortMode, ascending: Boolean) {
        _state.update { it.copy(sortMode = mode, sortAscending = ascending) }
        libraryPreferences?.let { preferences ->
            val sort = LibrarySearchFilter.toSharedSort(mode, ascending)
            preferences.sortingMode().set(sort)
            if (sort.type == LibrarySort.Type.Random) preferences.randomSortSeed().set(Random.nextInt())
        }
    }

    private fun applySharedPreferences(categoryId: Long?) {
        val preferences = libraryPreferences ?: return
        val category = categoryId?.let { id -> state.value.categories.firstOrNull { it.id == id } }
        val sharedSort = if (category != null && preferences.categorizedDisplaySettings().get()) {
            LibrarySort.valueOf(category.flags)
        } else {
            preferences.sortingMode().get()
        }
        val filter = LibraryFilter(
            downloaded = preferences.filterDownloaded().get(),
            unread = preferences.filterUnread().get(),
            started = preferences.filterStarted().get(),
            bookmarked = preferences.filterBookmarked().get(),
            completed = preferences.filterCompleted().get(),
            intervalCustom = preferences.filterIntervalCustom().get(),
            globalDownloadedOnly = preferences.downloadedOnly().get(),
            skipOutsideReleasePeriod = runCatching {
                LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in preferences.autoUpdateMangaRestrictions().get()
            }.getOrDefault(false),
            tracking = state.value.availableTrackerIds.associateWith { trackerId ->
                preferences.filterTracking(trackerId.toInt()).get()
            },
        )
        _state.update {
            it.copy(
                sortMode = sharedSort.toDesktopSortMode(),
                sortAscending = sharedSort.isAscending,
                filter = filter,
                displayMode = preferences.displayMode().get().toDesktopDisplayMode(),
                portraitColumns = preferences.portraitColumns().get().coerceIn(0, 10),
                landscapeColumns = preferences.landscapeColumns().get().coerceIn(0, 10),
                showContinueReadingButton = preferences.showContinueReadingButton().get(),
                showDownloadBadge = preferences.downloadBadge().get(),
                showUnreadBadge = preferences.unreadBadge().get(),
                showLocalBadge = preferences.localBadge().get(),
                showLanguageBadge = preferences.languageBadge().get(),
                showCategoryTabs = preferences.categoryTabs().get(),
                showCategoryItemCounts = preferences.categoryNumberOfItems().get(),
                categorizedDisplaySettings = preferences.categorizedDisplaySettings().get(),
            )
        }
    }

    fun applyCategoryPreferences(categoryId: Long?) {
        if (libraryPreferences != null) {
            applySharedPreferences(categoryId)
            return
        }
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
        _state.update { it.copy(sortMode = mode, sortAscending = ascending) }
        val sharedSort = LibrarySearchFilter.toSharedSort(mode, ascending)
        if (setSortModeForCategory != null) {
            screenModelScope.launch {
                setSortModeForCategory.await(
                    categoryId = categoryId,
                    type = sharedSort.type,
                    direction = sharedSort.direction,
                )
            }
        } else if (libraryPreferences == null) {
            categoryPrefs?.setSortMode(categoryId, mode)
            categoryPrefs?.setSortAscending(categoryId, ascending)
        }
    }

    // ── Filters ───────────────────────────────────────────────────────────────

    fun setFilter(filter: LibraryFilter) {
        _state.update { it.copy(filter = filter) }
        libraryPreferences?.let { preferences ->
            preferences.filterDownloaded().set(filter.downloaded)
            preferences.filterUnread().set(filter.unread)
            preferences.filterStarted().set(filter.started)
            preferences.filterBookmarked().set(filter.bookmarked)
            preferences.filterCompleted().set(filter.completed)
            preferences.filterIntervalCustom().set(filter.intervalCustom)
            preferences.downloadedOnly().set(filter.globalDownloadedOnly)
            filter.tracking.forEach { (trackerId, value) -> preferences.filterTracking(trackerId.toInt()).set(value) }
        }
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
        val filter = state.value.filter
        setFilter(
            when (field) {
                LibraryFilterField.DOWNLOADED -> filter.copy(downloaded = filter.downloaded.next())
                LibraryFilterField.UNREAD -> filter.copy(unread = filter.unread.next())
                LibraryFilterField.STARTED -> filter.copy(started = filter.started.next())
                LibraryFilterField.BOOKMARKED -> filter.copy(bookmarked = filter.bookmarked.next())
                LibraryFilterField.COMPLETED -> filter.copy(completed = filter.completed.next())
                LibraryFilterField.INTERVAL_CUSTOM -> filter.copy(intervalCustom = filter.intervalCustom.next())
            },
        )
    }

    fun toggleTrackingFilter(trackerId: Long) {
        val next = state.value.filter.tracking[trackerId].orDisabled().next()
        setFilter(state.value.filter.copy(tracking = state.value.filter.tracking + (trackerId to next)))
    }

    fun toggleGlobalDownloadedOnly() {
        setFilter(state.value.filter.copy(globalDownloadedOnly = !state.value.filter.globalDownloadedOnly))
    }

    fun toggleSkipOutsideReleasePeriod() {
        val preferences = libraryPreferences
        if (preferences == null) {
            _state.update {
                it.copy(filter = it.filter.copy(skipOutsideReleasePeriod = !it.filter.skipOutsideReleasePeriod))
            }
            return
        }
        runCatching {
            val current = preferences.autoUpdateMangaRestrictions().get()
            val next = if (LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in current) {
                current - LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD
            } else {
                current + LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD
            }
            preferences.autoUpdateMangaRestrictions().set(next)
            applySharedPreferences(null)
        }.onFailure {
            _state.update { state ->
                state.copy(
                    filter = state.filter.copy(skipOutsideReleasePeriod = !state.filter.skipOutsideReleasePeriod),
                )
            }
        }
    }

    /** Keeps a library page in sync while its settings screen is open. */
    suspend fun observeLibraryPreferences() {
        val preferences = libraryPreferences ?: return
        merge(
            preferences.displayMode().changes(),
            preferences.portraitColumns().changes(),
            preferences.landscapeColumns().changes(),
            preferences.sortingMode().changes(),
            preferences.filterDownloaded().changes(),
            preferences.filterUnread().changes(),
            preferences.filterStarted().changes(),
            preferences.filterBookmarked().changes(),
            preferences.filterCompleted().changes(),
            preferences.filterIntervalCustom().changes(),
            preferences.downloadedOnly().changes(),
            preferences.showContinueReadingButton().changes(),
            preferences.downloadBadge().changes(),
            preferences.unreadBadge().changes(),
            preferences.localBadge().changes(),
            preferences.languageBadge().changes(),
            preferences.categoryTabs().changes(),
            preferences.categoryNumberOfItems().changes(),
            preferences.categorizedDisplaySettings().changes(),
        ).collect {
            applySharedPreferences(state.value.categories.getOrNull(state.value.selectedCategoryIndex)?.id)
        }
    }

    fun setEvaluationContext(
        downloadedMangaIds: Set<Long>,
        downloadCountsByManga: Map<Long, Long> = emptyMap(),
        localMangaIds: Set<Long> = emptySet(),
        trackerIdsByManga: Map<Long, Set<Long>> = emptyMap(),
        trackerMeansByManga: Map<Long, Double> = emptyMap(),
    ) {
        _state.update {
            it.copy(
                downloadedMangaIds = downloadedMangaIds,
                downloadCountsByManga = downloadCountsByManga,
                localMangaIds = localMangaIds,
                trackerIdsByManga = trackerIdsByManga,
                trackerMeansByManga = trackerMeansByManga,
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
            downloadCountsByManga = it.downloadCountsByManga,
            localMangaIds = it.localMangaIds,
            trackerIds = it.trackerIdsByManga,
            trackerMeans = it.trackerMeansByManga,
            sort = LibrarySearchFilter.toSharedSort(it.sortMode, it.sortAscending),
            randomSeed = libraryPreferences?.randomSortSeed()?.get() ?: 0,
            sourceNames = it.allItems.associate { item ->
                item.manga.source to (sourceManager?.getOrStub(item.manga.source)?.name.orEmpty())
            },
        )
    }

    // ── Category selection ────────────────────────────────────────────────────

    fun setSelectedCategoryIndex(index: Int) {
        pendingInitialCategoryIndex = null
        _state.update {
            it.copy(
                selectedCategoryIndex = if (it.categories.isEmpty()) {
                    index.coerceAtLeast(0)
                } else {
                    index.coerceIn(0, it.categories.lastIndex)
                },
            )
        }
        libraryPreferences?.lastUsedCategory()?.set(state.value.selectedCategoryIndex)
    }

    // ── Display mode ──────────────────────────────────────────────────────────

    fun setDisplayMode(mode: LibraryDisplayMode) {
        _state.update { it.copy(displayMode = mode) }
        setDisplayModeInteractor?.await(mode.toShared())
            ?: libraryPreferences?.displayMode()?.set(mode.toShared())
    }

    fun setDisplayModeForCategory(categoryId: Long?, mode: LibraryDisplayMode) {
        setDisplayMode(mode)
        if (libraryPreferences == null) categoryPrefs?.setDisplayMode(categoryId, mode)
    }

    // ── Dialog / menu visibility ──────────────────────────────────────────────

    fun setShowCategoryDialog(show: Boolean) {
        _state.update { it.copy(showCategoryDialog = show) }
    }

    fun setContextMenuManga(manga: LibraryManga?) {
        _state.update { it.copy(contextMenuManga = manga) }
    }

    fun setOperationFeedback(feedback: String?) {
        _state.update { it.copy(operationFeedback = feedback) }
    }

    fun clearOperationResults() {
        _state.update { it.copy(operationFeedback = null, batchCategoryResultMessage = null) }
    }

    fun setShowBatchCategoryDialog(show: Boolean) {
        _state.update { it.copy(showBatchCategoryDialog = show) }
    }

    fun downloadedMangaIds(items: List<LibraryManga>): Set<Long> {
        if (downloadProvider == null && isMangaDownloaded == null) return emptySet()
        return items
            .filter { item ->
                isMangaDownloaded?.invoke(item)
                    ?: downloadProvider?.hasMangaDownloads(item.manga.source, item.manga.title)
                    ?: false
            }
            .map { it.id }
            .toSet()
    }

    private fun refreshDownloadState() {
        val items = state.value.allItems
        _state.update {
            val downloadedIds = downloadedMangaIds(items)
            it.copy(
                downloadedMangaIds = downloadedIds,
                downloadCountsByManga = items.associate { item ->
                    item.id to (
                        downloadedChapterCount?.invoke(item)
                            ?: if (item.id in downloadedIds) 1L else 0L
                        )
                },
            )
        }
    }

    suspend fun refreshLibrary(items: List<LibraryManga>, categoryId: Long? = null) {
        if (
            _state.value.isUpdating ||
            backgroundUpdateJob?.invoke()?.isActive == true
        ) {
            syncBackgroundUpdate()
            setUpdateStatusText(MR.strings.update_already_running.localized())
            return
        }
        val startUpdate = startScopedBackgroundUpdate?.let { scoped ->
            { scoped(categoryId) }
        } ?: startBackgroundUpdate?.let { start ->
            { start() }
        }
        startUpdate?.let { start ->
            setIsUpdating(true)
            setUpdateStatusText(MR.strings.desktop_ui_checking_for_updates.localized())
            try {
                start().join()
                setUpdateStatusText(backgroundUpdateResultText())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                setUpdateStatusText(MR.strings.desktop_ui_library_update_failed.localized())
            } finally {
                setIsUpdating(false)
            }
            return
        }
        val sourceManager = requireNotNull(sourceManager) { "SourceManager is required" }
        val updateChecker = requireNotNull(updateChecker) { "LibraryUpdateChecker is required" }
        val autoDownload = downloadPreferences?.autoDownloadNewChapters?.get() == true

        setIsUpdating(true)
        setUpdateStatusText(MR.strings.desktop_ui_checking_for_updates.localized())
        var totalNew = 0
        val scopedItems = categoryId?.let { id -> items.filter { id in it.categories } } ?: items
        try {
            for (item in scopedItems) {
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
                if (totalNew > 0) {
                    MR.strings.desktop_ui_new_chapters_found.localized(Locale.getDefault(), totalNew)
                } else {
                    MR.strings.desktop_ui_library_up_to_date.localized()
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            setUpdateStatusText(MR.strings.desktop_ui_library_update_failed.localized())
        } finally {
            setIsUpdating(false)
        }
    }

    private fun backgroundUpdateResultText(): String = when (backgroundUpdateStatus?.invoke()) {
        TaskStatus.Failed -> MR.strings.desktop_ui_library_update_failed.localized()
        TaskStatus.Cancelled -> MR.strings.desktop_ui_library_update_cancelled.localized()
        else -> MR.strings.desktop_ui_library_update_finished.localized()
    }

    fun cancelLibraryUpdate(): Boolean = cancelBackgroundUpdate?.invoke() == true

    private fun chapterIsDownloaded(item: LibraryManga, chapter: Chapter): Boolean =
        isChapterDownloaded?.invoke(item, chapter)
            ?: (
                downloadProvider?.isChapterDownloaded(
                    item.manga.source,
                    item.manga.title,
                    chapter.name,
                ) == true
                )

    suspend fun markMangaRead(mangaId: Long, read: Boolean): Boolean {
        return try {
            applyMangaReadStatus(mangaId, read)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            setOperationFeedback(
                MR.strings.desktop_ui_items_updated_failed.localized(Locale.getDefault(), 0, 1),
            )
            false
        }
    }

    private suspend fun applyMangaReadStatus(mangaId: Long, read: Boolean) {
        val statusUpdater = requireNotNull(setChapterReadStatus) { "SetChapterReadStatus is required" }
        val chapters = getChaptersByMangaId?.awaitOrThrow(mangaId)
        if (chapters == null) {
            statusUpdater.awaitOrThrow(mangaId, read)
            return
        }

        val changedChapters = statusUpdater.filterToUpdate(chapters, read)
        val item = state.value.allItems.firstOrNull { it.id == mangaId }
        statusUpdater.awaitOrThrow(chapters, read)
        if (read && sharedDownloadPreferences?.removeAfterMarkedAsRead()?.get() == true) {
            if (item != null) {
                val delete = requireNotNull(deleteChapterDownload) { "Delete chapter download is required" }
                try {
                    changedChapters.forEach { chapter -> delete(item, chapter) }
                } finally {
                    refreshDownloadState()
                }
            }
        }
    }

    suspend fun markMangaRead(mangaIds: Iterable<Long>, read: Boolean) {
        val targets = mangaIds.toList().distinct()
        var updated = 0
        var failures = 0
        targets.forEach { mangaId ->
            try {
                applyMangaReadStatus(mangaId, read)
                updated++
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failures++
            }
        }
        if (failures > 0) {
            setOperationFeedback(
                MR.strings.desktop_ui_items_updated_failed.localized(Locale.getDefault(), updated, failures),
            )
        }
    }

    suspend fun removeFromLibrary(
        mangaIds: Iterable<Long>,
        deleteDownloads: Boolean = false,
        removeFromLibrary: Boolean = true,
    ) {
        val updater = updateManga
        if (removeFromLibrary) requireNotNull(updater) { "UpdateManga is required" }
        val targets = mangaIds.toList().distinct()
        val itemsById = state.value.allItems.associateBy { it.id }
        var updated = 0
        var failures = 0
        targets.forEach { mangaId ->
            var itemFailed = false
            var membershipUpdated = true
            try {
                if (removeFromLibrary) {
                    if (!requireNotNull(updater).await(
                            MangaUpdate(
                                id = mangaId,
                                favorite = false,
                                syncContext = SyncMutationContext.User,
                            ),
                        )
                    ) {
                        itemFailed = true
                        membershipUpdated = false
                    } else {
                        deleteCustomCover?.invoke(mangaId)?.let { deleted ->
                            if (!deleted) itemFailed = true
                        }
                    }
                }
                if (membershipUpdated) {
                    val item = itemsById[mangaId]
                    if (deleteDownloads && item != null && item.manga.source != LOCAL_SOURCE_ID) {
                        try {
                            requireNotNull(deleteMangaDownloads) { "Delete manga downloads is required" }.invoke(item)
                        } finally {
                            refreshDownloadState()
                        }
                    } else if (deleteDownloads && item == null) {
                        itemFailed = true
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                itemFailed = true
            }
            if (itemFailed) failures++ else updated++
        }
        _state.update {
            it.copy(
                operationFeedback = if (failures == 0) {
                    if (removeFromLibrary) {
                        MR.strings.manga_removed_library.localized()
                    } else {
                        MR.strings.desktop_ui_delete_download.localized()
                    }
                } else {
                    MR.strings.desktop_ui_items_updated_failed.localized(
                        Locale.getDefault(),
                        updated,
                        failures,
                    )
                },
            )
        }
    }

    suspend fun enqueueNextUnreadDownload(item: LibraryManga): Boolean {
        val chapters = requireNotNull(getNextChapters) { "GetNextChapters is required" }
        val enqueue = requireNotNull(enqueueDownload) { "Download enqueue callback is required" }
        val firstUnread = selectLibraryDownloadChapters(
            candidates = chapters.awaitOrThrow(item.manga.id),
            limit = 1,
            isQueued = { chapter -> isChapterQueued?.invoke(chapter) == true },
            isDownloaded = { chapter -> chapterIsDownloaded(item, chapter) },
        ).firstOrNull()
            ?: return false
        enqueue(
            DownloadItem(
                sourceId = item.manga.source,
                mangaTitle = item.manga.title,
                chapterName = firstUnread.name,
                chapterId = firstUnread.id,
                mangaId = item.id,
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
            _state.update { it.copy(batchCategoryResultMessage = MR.strings.desktop_ui_no_manga_selected.localized()) }
            return LibraryBatchDownloadResult()
        }
        val bookmarkedByManga = requireNotNull(getBookmarkedChaptersByMangaId) {
            "GetBookmarkedChaptersByMangaId is required"
        }
        val nextChaptersByManga = requireNotNull(getNextChapters) { "GetNextChapters is required" }
        val enqueue = requireNotNull(enqueueDownload) { "Download enqueue callback is required" }
        val activeIds = queue.mapTo(mutableSetOf()) { it.chapterId }
        var result = LibraryBatchDownloadResult()
        items.forEach { item ->
            val candidates = try {
                if (action == MangaDetailDownloadAction.BOOKMARKED_CHAPTERS) {
                    bookmarkedByManga.awaitOrThrow(item.id)
                } else {
                    nextChaptersByManga.awaitOrThrow(item.id)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                result = result.copy(failures = result.failures + 1)
                return@forEach
            }
            val available = selectLibraryDownloadChapters(
                candidates = candidates,
                isQueued = { chapter -> chapter.id in activeIds },
                isDownloaded = { chapter -> chapterIsDownloaded(item, chapter) },
            )
            result = result.copy(skipped = result.skipped + (candidates.size - available.size))
            val chapters = when (action) {
                MangaDetailDownloadAction.NEXT_1_CHAPTER -> available.take(1)
                MangaDetailDownloadAction.NEXT_5_CHAPTERS -> available.take(5)
                MangaDetailDownloadAction.NEXT_10_CHAPTERS -> available.take(10)
                MangaDetailDownloadAction.NEXT_25_CHAPTERS -> available.take(25)
                MangaDetailDownloadAction.UNREAD_CHAPTERS,
                MangaDetailDownloadAction.BOOKMARKED_CHAPTERS,
                -> available
            }
            chapters.forEach { chapter ->
                result = try {
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
                    result.copy(queued = result.queued + 1)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    result.copy(failures = result.failures + 1)
                }
            }
        }
        _state.update {
            it.copy(
                batchCategoryResultMessage = MR.strings.desktop_ui_download_batch_result.localized(
                    Locale.getDefault(),
                    result.queued,
                    result.skipped,
                    result.failures,
                ),
            )
        }
        return result
    }

    suspend fun continueReadingRequest(item: LibraryManga): LibraryReaderRequest? {
        val chapters = requireNotNull(getChaptersByMangaId) { "GetChaptersByMangaId is required" }
            .awaitOrThrow(item.manga.id)
            .sortedBy { it.sourceOrder }
        val target = nextUnreadChapter(chapters, item.manga) ?: run {
            setOperationFeedback(MR.strings.no_next_chapter.localized())
            return null
        }
        setOperationFeedback(null)
        val chapterRefs = chapters.toReaderChapterRefs(
            currentChapterId = target.id,
            manga = item.manga,
            isChapterDownloaded = { chapter -> chapterIsDownloaded(item, chapter) },
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
        publishCategoryBatchResult(result)
    }

    suspend fun updateCategoriesForManga(
        mangaIds: List<Long>,
        addCategoryIds: Set<Long>,
        removeCategoryIds: Set<Long>,
    ) {
        if (mangaIds.isEmpty()) {
            publishCategoryBatchResult(SetMangaCategories.BatchResult.Empty)
            return
        }
        val getCategories = getCategoryIdsForManga
        val setter = requireNotNull(setMangaCategories) { "SetMangaCategories is required" }
        val succeeded = mutableListOf<Long>()
        val failures = mutableListOf<SetMangaCategories.BatchFailure>()
        mangaIds.forEach { mangaId ->
            try {
                val current = getCategories?.invoke(mangaId)
                    ?: requireNotNull(this.getCategories) { "GetCategories is required" }
                        .await(mangaId)
                        .map { it.id }
                        .toSet()
                val target = applyLibraryCategoryDelta(
                    currentCategoryIds = current,
                    addCategoryIds = addCategoryIds,
                    removeCategoryIds = removeCategoryIds,
                )
                when (val result = setter.awaitResult(mangaId, target)) {
                    SetMangaCategories.Result.Success -> succeeded += mangaId
                    is SetMangaCategories.Result.InternalError ->
                        failures +=
                            SetMangaCategories.BatchFailure(mangaId, result.error)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failures += SetMangaCategories.BatchFailure(mangaId, error)
            }
        }
        publishCategoryBatchResult(SetMangaCategories.BatchResult(succeeded, failures))
    }

    private fun publishCategoryBatchResult(result: SetMangaCategories.BatchResult) {
        _state.update {
            it.copy(
                batchCategoryResultMessage = if (result.failures.isEmpty()) {
                    MR.strings.desktop_ui_items_updated.localized(Locale.getDefault(), result.succeededIds.size)
                } else {
                    MR.strings.desktop_ui_items_updated_failed.localized(
                        Locale.getDefault(),
                        result.succeededIds.size,
                        result.failures.size,
                    )
                },
            )
        }
    }

    suspend fun categoryIdsForManga(mangaId: Long): Set<Long> {
        return requireNotNull(getCategories) { "GetCategories is required" }
            .await(mangaId)
            .map { it.id }
            .toSet()
    }
}

enum class LibraryFilterField { DOWNLOADED, UNREAD, STARTED, BOOKMARKED, COMPLETED, INTERVAL_CUSTOM }

private fun TriState?.orDisabled() = this ?: TriState.DISABLED
private fun Boolean.asTriState() = if (this) TriState.ENABLED_IS else TriState.DISABLED

private const val LOCAL_SOURCE_ID = 0L

private fun LibrarySort.toDesktopSortMode() = when (type) {
    LibrarySort.Type.Alphabetical -> SortMode.TITLE
    LibrarySort.Type.LastRead -> SortMode.LAST_READ
    LibrarySort.Type.LastUpdate -> SortMode.LAST_UPDATE
    LibrarySort.Type.UnreadCount -> SortMode.UNREAD_COUNT
    LibrarySort.Type.TotalChapters -> SortMode.TOTAL_CHAPTERS
    LibrarySort.Type.LatestChapter -> SortMode.LATEST_CHAPTER
    LibrarySort.Type.ChapterFetchDate -> SortMode.CHAPTER_FETCH_DATE
    LibrarySort.Type.DateAdded -> SortMode.DATE_ADDED
    LibrarySort.Type.TrackerMean -> SortMode.TRACKER_MEAN
    LibrarySort.Type.Random -> SortMode.RANDOM
}

private fun LibraryDisplayMode.toShared() = when (this) {
    LibraryDisplayMode.COMPACT_GRID -> SharedLibraryDisplayMode.CompactGrid
    LibraryDisplayMode.COMFORTABLE_GRID -> SharedLibraryDisplayMode.ComfortableGrid
    LibraryDisplayMode.LIST -> SharedLibraryDisplayMode.List
    LibraryDisplayMode.COVER_ONLY_GRID -> SharedLibraryDisplayMode.CoverOnlyGrid
}

private fun SharedLibraryDisplayMode.toDesktopDisplayMode() = when (this) {
    SharedLibraryDisplayMode.CompactGrid -> LibraryDisplayMode.COMPACT_GRID
    SharedLibraryDisplayMode.ComfortableGrid -> LibraryDisplayMode.COMFORTABLE_GRID
    SharedLibraryDisplayMode.List -> LibraryDisplayMode.LIST
    SharedLibraryDisplayMode.CoverOnlyGrid -> LibraryDisplayMode.COVER_ONLY_GRID
}
