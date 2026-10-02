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
import kotlinx.coroutines.flow.map
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
import mihon.desktop.reader.externalChapterUrlOrNull
import mihon.desktop.settings.LibraryCategoryPrefs
import mihon.desktop.settings.saveDesktopPreference
import mihon.domain.sync.SyncMutationContext
import mihon.domain.task.TaskStatus
import tachiyomi.core.common.preference.Preference
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
import tachiyomi.domain.chapter.service.filterAndSortChapters
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
    val resumeSnapshot: tachiyomi.domain.reader.model.ReadingSyncSnapshot? = null,
)

data class LibraryBatchDownloadResult(
    val queued: Int = 0,
    val skipped: Int = 0,
    val failures: Int = 0,
)

/** Session-only position: an entity anchor plus a bounded fallback when that entity disappears. */
internal data class LibraryBrowsePosition(val mangaId: Long, val index: Int, val offset: Int) {
    fun indexIn(items: List<LibraryManga>): Int = items.indexOfFirst { it.id == mangaId }
        .takeIf { it >= 0 } ?: index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
}

class LibraryScreenModel(
    private val getLibraryManga: GetLibraryManga? = null,
    private val getCategories: GetCategories? = null,
    private val categoryRepository: tachiyomi.domain.category.repository.CategoryRepository? = null,
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
    private val trackerServiceRegistry: tachiyomi.domain.track.service.TrackerServiceRegistry? = null,
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
    private val readingProgress: tachiyomi.domain.reader.interactor.RecordReadingProgress? = null,
    private val readerPreferences: mihon.desktop.reader.ReaderPreferences? = null,
    val manualTracking: mihon.desktop.tracking.DesktopManualTracking? = null,
    private val enqueueAccepted: ((DownloadItem) -> Boolean)? = null,
    private val captureRemovalFiles: (suspend (LibraryManga) -> LibraryRemovalFiles)? = null,
    private val deleteRemovalFiles: (
        suspend (
            LibraryManga,
            LibraryRemovalFiles,
        ) -> LibraryRemovalDeletionResult
    )? = null,
    private val backgroundUpdateObservations: Flow<mihon.desktop.domain.LibraryUpdateObservation>? = null,
    private val backgroundUpdateSnapshot: (() -> mihon.desktop.task.StoredTask?)? = null,
    private val backgroundUpdateLaunchFailure: (() -> String?)? = null,
    private val retryFailedBackgroundUpdate: (() -> Job)? = null,
    private val resumeBackgroundUpdate: (() -> Job)? = null,
) : ScreenModel {

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    private var categoryProjectionInitialized = false
    private var pendingInitialCategoryIndex: Int? = null
    private var observedBackgroundUpdate: Job? = null
    private var observesBackgroundUpdate = false
    private val browsePositions = mutableMapOf<Long?, LibraryBrowsePosition>()

    internal fun browsePosition(categoryId: Long?) = browsePositions[categoryId]

    internal fun rememberBrowsePosition(categoryId: Long?, position: LibraryBrowsePosition) {
        browsePositions[categoryId] = position
    }

    private fun categoryIndex(state: LibraryState, categories: List<Category>, initial: Int? = null): Int {
        // Initial emissions retain the persisted index protocol until the first library projection.
        val selectedId = state.categories.getOrNull(state.selectedCategoryIndex)?.id.takeUnless { state.isLoading }
        val rememberedIndex = categories.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }
        val index = initial ?: rememberedIndex ?: state.selectedCategoryIndex
        return index.coerceIn(0, (categories.size - 1).coerceAtLeast(0))
    }

    init {
        applySharedPreferences(categoryId = null)
        if (backgroundUpdateObservations == null) syncBackgroundUpdate()
    }

    fun syncBackgroundUpdate() {
        if (!observesBackgroundUpdate) {
            backgroundUpdateObservations?.let { observations ->
                observesBackgroundUpdate = true
                screenModelScope.launch {
                    observations.collect { observed ->
                        _state.update { current ->
                            current.copy(
                                updateTask = observed.task,
                                updateLaunchFailed = observed.launchFailure != null,
                                isUpdating = observed.task?.status == TaskStatus.Running &&
                                    observed.task.libraryUpdate?.waitingForDevice.isNullOrEmpty(),
                                updateStatusText = if (observed.launchFailure != null) {
                                    MR.strings.desktop_ui_library_update_failed.localized()
                                } else {
                                    observed.task?.let(::libraryUpdateSummary)
                                },
                            )
                        }
                    }
                }
            }
        }
        val runningJob = backgroundUpdateJob?.invoke()?.takeIf(Job::isActive) ?: return
        val waiting = backgroundUpdateSnapshot?.invoke()?.libraryUpdate?.waitingForDevice?.isNotEmpty() == true
        setIsUpdating(!waiting)
        setUpdateStatusText(backgroundUpdateResultText())
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
        activeTrackerProfiles(),
        downloadQueueChanges,
        combine(
            libraryPreferences?.showContinueReadingButton()?.changes() ?: flowOf(false),
            libraryPreferences?.downloadedOnly()?.changes() ?: flowOf(false),
        ) { show, _ -> show },
    ) { items, tracksByManga, trackerProfiles, _, showContinue ->
        val (loggedInTrackerIds, trackerNames) = trackerProfiles
        _state.update { it.copy(trackerNamesById = trackerNames) }
        val eligible = if (showContinue) {
            items.distinctBy { it.id }.filter { item ->
                getChaptersByMangaId?.awaitOrThrow(item.id, applyScanlatorFilter = true)
                    ?.let { nextUnreadChapter(eligibleReaderChapters(item, it), item.manga) != null }
                    ?: (item.unreadCount > 0)
            }.mapTo(mutableSetOf()) { it.id }
        } else {
            emptySet()
        }
        val resumable = if (showContinue && readingProgress != null) {
            items.distinctBy { it.id }
                .filter { item ->
                    val resume = readingProgress.resumePosition(item.id)
                    resume != null && getChaptersByMangaId?.awaitOrThrow(item.id, applyScanlatorFilter = true)
                        ?.any { it.id == resume.chapterId && it.url.externalChapterUrlOrNull() == null } == true
                }.mapTo(mutableSetOf()) { it.id }
        } else {
            emptySet()
        }
        _state.update { it.copy(syncedResumeMangaIds = resumable, continueReadingMangaIds = eligible) }
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

    private fun activeTrackerProfiles(): Flow<Pair<Set<Long>, Map<Long, String>>> {
        val sessions = trackerSessionProvider?.loggedInTrackerIds() ?: flowOf(emptySet())
        val registry = trackerServiceRegistry ?: return sessions.map { it to emptyMap() }
        val profiles = if (registry.services.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(registry.services.map { it.profile }) { it.toList() }
        }
        return combine(sessions, profiles) { ids, current ->
            val active = current.filter { it.id in ids && it.loggedIn && it.unavailableReason == null }
            active.mapTo(mutableSetOf()) { it.id } to active.associate { it.id to it.name }
        }
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
            mangaTracks.map {
                tachiyomi.domain.track.service.TrackerProviderContracts.tenPointScore(it.trackerId, it.score)
            }.average()
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
                selectedCategoryIndex = categoryIndex(it, projectedCategories, pendingCategoryIndex),
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

    suspend fun createCategory(name: String): Boolean = categoryOperation {
        requireNotNull(createCategory) { "CreateCategoryWithName is required" }.await(name.trim()) ==
            CreateCategoryWithName.Result.Success
    }

    suspend fun renameCategory(categoryId: Long, name: String): Boolean = categoryOperation {
        requireNotNull(renameCategory) { "RenameCategory is required" }.await(categoryId, name.trim()) ==
            RenameCategory.Result.Success
    }

    suspend fun deleteCategory(categoryId: Long): Boolean = categoryOperation {
        requireNotNull(deleteCategory) { "DeleteCategory is required" }.await(categoryId) ==
            DeleteCategory.Result.Success
    }

    suspend fun reorderCategory(categoryId: Long, newIndex: Int): Boolean = categoryOperation {
        val category =
            state.value.allCategories.firstOrNull { it.id == categoryId && !it.isSystemCategory }
                ?: return@categoryOperation false
        when (requireNotNull(reorderCategory) { "ReorderCategory is required" }.await(category, newIndex)) {
            ReorderCategory.Result.Success, ReorderCategory.Result.Unchanged -> true
            is ReorderCategory.Result.InternalError -> false
        }
    }

    /** Reports the existing use case's write outcome; the observed repository remains authoritative. */
    private suspend fun categoryOperation(operation: suspend () -> Boolean): Boolean {
        val saved = try {
            operation()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            false
        }
        setOperationFeedback(if (saved) null else MR.strings.internal_error.localized())
        try {
            refreshCategories()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            setOperationFeedback(MR.strings.internal_error.localized())
        }
        return saved
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
                selectedCategoryIndex = categoryIndex(it, projectedCategories),
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
            it.copy(
                allCategories = categories,
                categories = projected,
                selectedCategoryIndex = categoryIndex(it, projected, pendingInitialCategoryIndex),
            )
        }
        browsePositions.keys.retainAll(categories.map { it.id }.toSet() + setOf(0L, null))
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
        val preferences = libraryPreferences
        if (preferences == null) {
            _state.update { it.copy(sortMode = mode, sortAscending = ascending) }
            return
        }
        val sort = LibrarySearchFilter.toSharedSort(mode, ascending)
        if (!saveDesktopPreference(preferences.sortingMode(), sort)) reportPreferenceFailure()
        if (sort.type == LibrarySort.Type.Random &&
            !saveDesktopPreference(preferences.randomSortSeed(), Random.nextInt())
        ) {
            reportPreferenceFailure()
        }
        applySharedPreferences(state.value.categories.getOrNull(state.value.selectedCategoryIndex)?.id)
    }

    internal fun <T> writeLibraryPreference(preference: Preference<T>, value: T): Boolean {
        val saved = saveDesktopPreference(preference, value)
        if (!saved) reportPreferenceFailure()
        applySharedPreferences(state.value.categories.getOrNull(state.value.selectedCategoryIndex)?.id)
        return saved
    }

    private fun reportPreferenceFailure() {
        setOperationFeedback(MR.strings.desktop_appearance_save_failed.localized())
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
                randomSortSeed = preferences.randomSortSeed().get(),
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
        val sharedSort = LibrarySearchFilter.toSharedSort(mode, ascending)
        val preferences = libraryPreferences
        if (setSortModeForCategory != null && preferences != null) {
            screenModelScope.launch {
                val oldSort = preferences.sortingMode().get() to preferences.sortingMode().isSet()
                val oldSeed = preferences.randomSortSeed().get() to preferences.randomSortSeed().isSet()
                val previousFlags = try {
                    categoryRepository?.getAll()?.associate { it.id to it.flags }.orEmpty()
                } catch (_: Exception) {
                    reportPreferenceFailure()
                    return@launch
                }
                val local = categoryId?.takeIf { preferences.categorizedDisplaySettings().get() && it in previousFlags }
                val affected = if (local != null) previousFlags.filterKeys { it == local } else previousFlags
                try {
                    setSortModeForCategory.await(categoryId, sharedSort.type, sharedSort.direction)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    runCatching {
                        val repository = categoryRepository ?: return@runCatching
                        val current = repository.getAll().associateBy { it.id }
                        val mask = sharedSort.mask
                        val updates = affected.mapNotNull { (id, previous) ->
                            val actual = current[id] ?: return@mapNotNull null
                            if (actual.flags and mask != sharedSort.flag) return@mapNotNull null
                            tachiyomi.domain.category.model.CategoryUpdate(
                                id = id,
                                flags = (actual.flags and mask.inv()) or (previous and mask),
                            )
                        }
                        repository.updatePartial(updates)
                        setCategories(repository.getAll())
                    }
                    runCatching {
                        if (oldSort.second) {
                            preferences.sortingMode().set(oldSort.first)
                        } else {
                            preferences.sortingMode().delete()
                        }
                    }
                    runCatching {
                        if (oldSeed.second) {
                            preferences.randomSortSeed().set(oldSeed.first)
                        } else {
                            preferences.randomSortSeed().delete()
                        }
                    }
                    reportPreferenceFailure()
                }
                applySharedPreferences(categoryId)
            }
        } else if (preferences != null) {
            setSortModeAndDirection(mode, ascending)
        } else {
            _state.update { it.copy(sortMode = mode, sortAscending = ascending) }
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
        val preferences = libraryPreferences
        if (preferences == null) {
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
            return
        }
        val preference = when (field) {
            LibraryFilterField.DOWNLOADED -> preferences.filterDownloaded()
            LibraryFilterField.UNREAD -> preferences.filterUnread()
            LibraryFilterField.STARTED -> preferences.filterStarted()
            LibraryFilterField.BOOKMARKED -> preferences.filterBookmarked()
            LibraryFilterField.COMPLETED -> preferences.filterCompleted()
            LibraryFilterField.INTERVAL_CUSTOM -> preferences.filterIntervalCustom()
        }
        if (field == LibraryFilterField.DOWNLOADED && state.value.filter.globalDownloadedOnly) return
        if (field == LibraryFilterField.INTERVAL_CUSTOM && !state.value.filter.skipOutsideReleasePeriod) return
        writeLibraryPreference(preference, preference.get().next())
    }

    fun toggleTrackingFilter(trackerId: Long) {
        val next = state.value.filter.tracking[trackerId].orDisabled().next()
        val preferences = libraryPreferences
        if (preferences == null) {
            setFilter(state.value.filter.copy(tracking = state.value.filter.tracking + (trackerId to next)))
        } else {
            writeLibraryPreference(preferences.filterTracking(trackerId.toInt()), next)
        }
    }

    fun toggleGlobalDownloadedOnly() {
        val preferences = libraryPreferences
        if (preferences ==
            null
        ) {
            setFilter(state.value.filter.copy(globalDownloadedOnly = !state.value.filter.globalDownloadedOnly))
        } else {
            writeLibraryPreference(preferences.downloadedOnly(), !preferences.downloadedOnly().get())
        }
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
            preferences.randomSortSeed().changes(),
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
            preferences.autoUpdateMangaRestrictions().changes(),
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
        val preferences = libraryPreferences
        if (preferences == null) {
            _state.update { it.copy(displayMode = mode) }
            return
        }
        val saved = saveDesktopPreference(preferences.displayMode(), mode.toShared()) {
            setDisplayModeInteractor?.await(it) ?: preferences.displayMode().set(it)
        }
        if (!saved) reportPreferenceFailure()
        applySharedPreferences(state.value.categories.getOrNull(state.value.selectedCategoryIndex)?.id)
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
            !backgroundIsWaitingForDevice() && (
                _state.value.isUpdating || backgroundUpdateJob?.invoke()?.isActive == true
                )
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

    private fun backgroundUpdateResultText(): String {
        if (backgroundUpdateLaunchFailure?.invoke() !=
            null
        ) {
            return MR.strings.desktop_ui_library_update_failed.localized()
        }
        backgroundUpdateSnapshot?.invoke()?.let { task -> libraryUpdateSummary(task)?.let { return it } }
        return when (backgroundUpdateStatus?.invoke()) {
            TaskStatus.Failed -> MR.strings.desktop_ui_library_update_failed.localized()
            TaskStatus.Cancelled -> MR.strings.desktop_ui_library_update_cancelled.localized()
            else -> MR.strings.desktop_ui_library_update_finished.localized()
        }
    }

    fun retryFailedLibraryUpdate() {
        runRecoveryUpdate(retryFailedBackgroundUpdate)
    }

    fun resumeLibraryUpdate() {
        runRecoveryUpdate(resumeBackgroundUpdate)
    }

    private fun backgroundIsWaitingForDevice(): Boolean =
        backgroundUpdateSnapshot?.invoke()?.let {
            it.status == TaskStatus.Running && it.libraryUpdate?.waitingForDevice?.isNotEmpty() == true
        } == true

    private fun runRecoveryUpdate(start: (() -> Job)?) {
        if ((_state.value.isUpdating && !backgroundIsWaitingForDevice()) || start == null) return
        screenModelScope.launch {
            setIsUpdating(!backgroundIsWaitingForDevice())
            try {
                start().join()
                setUpdateStatusText(backgroundUpdateResultText())
            } finally {
                setIsUpdating(false)
            }
        }
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

        val newlyReadChapters = chapters.filterNot { it.read }.distinctBy { it.id }
        val item = state.value.allItems.firstOrNull { it.id == mangaId }
        statusUpdater.awaitOrThrow(chapters, read)
        if (read) manualTracking?.afterRead(mangaId, chapters)
        if (read && sharedDownloadPreferences?.removeAfterMarkedAsRead()?.get() == true) {
            if (item != null) {
                val delete = requireNotNull(deleteChapterDownload) { "Delete chapter download is required" }
                try {
                    newlyReadChapters.forEach { chapter -> delete(item, chapter) }
                } finally {
                    refreshDownloadState()
                }
            }
        }
    }

    suspend fun markMangaRead(mangaIds: Iterable<Long>, read: Boolean): Boolean {
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
        return failures == 0
    }

    internal suspend fun captureRemovalSnapshot(items: List<LibraryManga>): List<LibraryRemovalTarget> =
        items.distinctBy { it.id }.map { item ->
            LibraryRemovalTarget(
                item,
                if (item.manga.source !=
                    LOCAL_SOURCE_ID
                ) {
                    captureRemovalFiles?.invoke(item)
                } else {
                    null
                },
            )
        }

    suspend fun removeFromLibrary(
        mangaIds: Iterable<Long>,
        deleteDownloads: Boolean = false,
        removeFromLibrary: Boolean = true,
    ): Boolean {
        val items = state.value.allItems.associateBy { it.id }
        val ids = mangaIds.toList().distinct()
        val targets = captureRemovalSnapshot(ids.mapNotNull(items::get))
        if (targets.size != ids.size) return false
        return removeSnapshot(targets, deleteDownloads, removeFromLibrary)
    }

    internal suspend fun removeSnapshot(
        targets: List<LibraryRemovalTarget>,
        deleteDownloads: Boolean,
        removeFromLibrary: Boolean,
    ): Boolean {
        var updated = 0
        var failures = 0
        var partialDownloads = false
        for (target in targets) {
            val item = target.item
            try {
                if (removeFromLibrary && !target.membershipCompleted) {
                    check(
                        requireNotNull(updateManga).await(
                            MangaUpdate(
                                id = item.id,
                                favorite = false,
                                syncContext = SyncMutationContext.User,
                            ),
                        ),
                    ) { "Unable to remove library membership" }
                    target.membershipCompleted = true
                }
                if (removeFromLibrary && !target.coverDeletionCompleted) {
                    check(deleteCustomCover?.invoke(item.id) != false) { "Unable to delete custom cover" }
                    target.coverDeletionCompleted = true
                }
                if (deleteDownloads && item.manga.source != LOCAL_SOURCE_ID) {
                    try {
                        val files = target.files
                        if (files != null && deleteRemovalFiles != null) {
                            val result = deleteRemovalFiles.invoke(item, files)
                            files.succeeded += result.succeeded
                            files.skipped += result.skipped
                            files.pendingArtifacts.retainAll(result.failedArtifacts.toSet())
                            check(result.failedArtifacts.isEmpty() && result.refusedAttempts.isEmpty()) {
                                "Unable to delete original downloads"
                            }
                        } else {
                            requireNotNull(deleteMangaDownloads) { "Delete manga downloads is required" }.invoke(item)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        partialDownloads = partialDownloads || target.membershipCompleted
                        throw error
                    } finally {
                        refreshDownloadState()
                    }
                }
                updated++
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failures++
            }
        }
        val outcome = when {
            partialDownloads -> MR.strings.desktop_detail_removal_partial.localized()
            failures > 0 -> MR.strings.desktop_ui_items_updated_failed.localized(Locale.getDefault(), updated, failures)
            removeFromLibrary -> MR.strings.manga_removed_library.localized()
            else -> MR.strings.desktop_ui_delete_download.localized()
        }
        val counts = if (deleteDownloads) {
            MR.strings.desktop_chapter_batch_result.localized(
                Locale.getDefault(),
                targets.sumOf { it.files?.succeeded ?: 0 },
                targets.sumOf { it.files?.skipped ?: 0 },
                targets.sumOf { target ->
                    target.files?.let { files ->
                        files.pendingArtifacts.size + files.pendingAttempts.count { attempt ->
                            files.queuedArtifacts[attempt.item.chapterId].orEmpty().none {
                                it in files.pendingArtifacts
                            }
                        }
                    } ?: 0
                },
            )
        } else {
            null
        }
        _state.update { it.copy(operationFeedback = listOfNotNull(outcome, counts).joinToString("\n")) }
        return failures == 0
    }

    suspend fun enqueueNextUnreadDownload(item: LibraryManga): Boolean =
        enqueueDownloads(listOf(item), MangaDetailDownloadAction.NEXT_1_CHAPTER).queued > 0

    internal suspend fun enqueueDownloads(
        items: List<LibraryManga>,
        action: MangaDetailDownloadAction,
        queue: List<DownloadItem> = emptyList(),
    ): LibraryBatchDownloadResult {
        if (items.isEmpty()) {
            _state.update { it.copy(batchCategoryResultMessage = MR.strings.desktop_ui_no_manga_selected.localized()) }
            return LibraryBatchDownloadResult()
        }
        val chaptersByManga = requireNotNull(getChaptersByMangaId) { "GetChaptersByMangaId is required" }
        val enqueue =
            enqueueAccepted
                ?: requireNotNull(enqueueDownload) { "Download enqueue callback is required" }.let { legacy ->
                    { item: DownloadItem ->
                        legacy(item)
                        true
                    }
                }
        val skipFiltered = readerPreferences?.skipFilteredChapters == true
        val downloadedOnly = libraryPreferences?.downloadedOnly()?.get() == true
        val activeIds = queue.mapTo(mutableSetOf()) { it.chapterId }
        val limit = when (action) {
            MangaDetailDownloadAction.NEXT_1_CHAPTER -> 1
            MangaDetailDownloadAction.NEXT_5_CHAPTERS -> 5
            MangaDetailDownloadAction.NEXT_10_CHAPTERS -> 10
            MangaDetailDownloadAction.NEXT_25_CHAPTERS -> 25
            else -> null
        }
        var result = LibraryBatchDownloadResult()
        val unavailable = mutableListOf<String>()
        for (item in items) {
            if (item.manga.source == 0L || sourceManager?.get(item.manga.source) == null) {
                unavailable += if (item.manga.source == 0L) {
                    MR.strings.desktop_manual_download_local.localized()
                } else {
                    MR.strings.desktop_manual_download_missing_source.localized(Locale.getDefault(), item.manga.source)
                }
                result = result.copy(skipped = result.skipped + 1)
                continue
            }
            val candidates = try {
                val raw = chaptersByManga.awaitOrThrow(item.id, applyScanlatorFilter = false)
                if (skipFiltered) {
                    val visible = chaptersByManga.awaitOrThrow(item.id, applyScanlatorFilter = true)
                        .filterAndSortChapters(item.manga, downloadedOnly, false) { chapterIsDownloaded(item, it) }
                        .mapTo(mutableSetOf()) { it.id }
                    raw.filter { it.id in visible }
                } else {
                    raw
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                result = result.copy(failures = result.failures + 1)
                continue
            }
            val eligible = candidates.filter {
                (if (action == MangaDetailDownloadAction.BOOKMARKED_CHAPTERS) it.bookmark else !it.read) &&
                    it.url.externalChapterUrlOrNull() == null
            }
            val available = tachiyomi.domain.library.selectManualDownloadChapters(
                candidates,
                item.manga,
                bookmarkedOnly = action == MangaDetailDownloadAction.BOOKMARKED_CHAPTERS,
                isQueued = { it.id in activeIds || isChapterQueued?.invoke(it) == true },
                isDownloaded = { chapterIsDownloaded(item, it) },
                isDownloadable = { it.url.externalChapterUrlOrNull() == null },
            )
            result = result.copy(skipped = result.skipped + eligible.size - available.size)
            for (chapter in limit?.let { available.take(it) } ?: available) {
                result = try {
                    val accepted = enqueue(
                        DownloadItem(
                            sourceId = item.manga.source,
                            mangaTitle = item.manga.title,
                            chapterName = chapter.name,
                            chapterId = chapter.id,
                            mangaId = item.id,
                            chapterUrl = chapter.url,
                        ),
                    )
                    if (accepted) {
                        activeIds += chapter.id
                        result.copy(queued = result.queued + 1)
                    } else {
                        result.copy(skipped = result.skipped + 1)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    result.copy(failures = result.failures + 1)
                }
            }
        }
        val message = MR.strings.desktop_ui_download_batch_result.localized(
            Locale.getDefault(),
            result.queued,
            result.skipped,
            result.failures,
        )
        _state.update {
            it.copy(
                batchCategoryResultMessage =
                (listOf(message) + unavailable.distinct()).joinToString("\n"),
            )
        }
        return result
    }

    private fun eligibleReaderChapters(item: LibraryManga, chapters: List<Chapter>): List<Chapter> =
        chapters.filterAndSortChapters(
            item.manga,
            libraryPreferences?.downloadedOnly()?.get() == true,
            item.manga.source == 0L,
        ) { chapterIsDownloaded(item, it) }

    suspend fun continueReadingRequest(item: LibraryManga): LibraryReaderRequest? {
        val chapters = requireNotNull(getChaptersByMangaId) { "GetChaptersByMangaId is required" }
            .awaitOrThrow(item.manga.id, applyScanlatorFilter = true)
            .sortedBy { it.sourceOrder }
        val target = nextUnreadChapter(eligibleReaderChapters(item, chapters), item.manga) ?: run {
            setOperationFeedback(MR.strings.no_next_chapter.localized())
            return null
        }
        val resume = readingProgress?.resumePosition(item.manga.id)?.takeIf { it.chapterId == target.id }
        setOperationFeedback(null)
        val chapterRefs = chapters
            .filterNot { it.url.externalChapterUrlOrNull() != null }
            .toReaderChapterRefs(
                currentChapterId = target.id,
                manga = item.manga,
                downloadedOnly = libraryPreferences?.downloadedOnly()?.get() == true,
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
            initialPage = resume?.pageIndex ?: target.lastPageRead.toInt().coerceAtLeast(0),
            resumeSnapshot = resume?.snapshot,
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
    ): SetMangaCategories.BatchResult {
        if (mangaIds.isEmpty()) {
            publishCategoryBatchResult(SetMangaCategories.BatchResult.Empty)
            return SetMangaCategories.BatchResult.Empty
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
        return SetMangaCategories.BatchResult(succeeded, failures).also(::publishCategoryBatchResult)
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

internal fun libraryUpdateSummary(task: mihon.desktop.task.StoredTask): String? {
    val units = task.libraryUpdate?.units ?: return null
    val counts = MR.strings.desktop_library_update_results_count.localized(
        Locale.getDefault(),
        units.count { it.status == mihon.desktop.task.LibraryUnitStatus.SUCCESS },
        units.count { it.status == mihon.desktop.task.LibraryUnitStatus.SKIPPED },
        units.count { it.status == mihon.desktop.task.LibraryUnitStatus.FAILED },
        units.count { it.status == mihon.desktop.task.LibraryUnitStatus.UNPROCESSED },
    )
    return libraryDeviceWaitingSummary(task)?.let { "$counts\n$it" } ?: counts
}

internal fun libraryDeviceWaitingSummary(task: mihon.desktop.task.StoredTask): String? {
    if (task.status != TaskStatus.Running) return null
    val waiting = task.libraryUpdate?.waitingForDevice.orEmpty()
    if (waiting.isEmpty()) return null
    val conditions = waiting.mapNotNull { (key, state) ->
        val label = when (key) {
            "wifi" -> MR.strings.connected_to_wifi
            "network_not_metered" -> MR.strings.network_not_metered
            "ac" -> MR.strings.desktop_device_external_power
            else -> return@mapNotNull null
        }.localized()
        val reason = if (state == "UNKNOWN") {
            MR.strings.desktop_device_condition_unknown
        } else {
            MR.strings.desktop_device_condition_unmet
        }
        "$label: ${reason.localized()}"
    }
    return (listOf(MR.strings.desktop_library_update_waiting.localized()) + conditions).joinToString("\n")
}
