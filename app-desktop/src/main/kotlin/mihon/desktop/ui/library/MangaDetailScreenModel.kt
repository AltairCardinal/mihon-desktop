package mihon.desktop.ui.library

import cafe.adriel.voyager.core.model.ScreenModel
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.desktop.domain.GetAvailableScanlators
import mihon.desktop.domain.GetExcludedScanlators
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.domain.SetExcludedScanlators
import mihon.desktop.download.DownloadItem
import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.ReaderNavigator
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.externalChapterUrlOrNull
import mihon.desktop.reader.viewerFlagsFollowingGlobal
import mihon.desktop.reader.viewerFlagsWithReadingMode
import mihon.domain.reader.progress.resolveReaderChapterEntryPage
import mihon.domain.task.TaskState
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.BatchChapterFailure
import tachiyomi.domain.chapter.interactor.BatchChapterResult
import tachiyomi.domain.chapter.interactor.BatchUpdateChapters
import tachiyomi.domain.chapter.interactor.SetChapterReadStatus
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.filterAndSortChapters
import tachiyomi.domain.creator.interactor.ExtractCreatorsFromManga
import tachiyomi.domain.creator.interactor.LinkMangaCreator
import tachiyomi.domain.creator.interactor.ManageCreatorIdentity
import tachiyomi.domain.creator.model.CreatorMention
import tachiyomi.domain.creator.model.CreatorMentionResolution
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.LibraryMembershipResult
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.interactor.UpdateLibraryMembership
import tachiyomi.domain.manga.interactor.UpdateManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR

/**
 * Voyager ScreenModel for [MangaDetailScreen].
 *
 * Owns all manga-detail state and exposes it as [StateFlow<MangaDetailState>].
 * All filter, sort, and dialog state transitions go through explicit methods,
 * enabling JVM unit tests without Compose or DI.
 */
class MangaDetailScreenModel(
    val mangaId: Long,
    private val migrateManga: (suspend (Long, SManga) -> Manga)? = null,
    private val getMangaWithChapters: GetMangaWithChapters? = null,
    private val sourceManager: SourceManager? = null,
    private val updateChecker: LibraryUpdateChecker? = null,
    private val getAvailableScanlators: GetAvailableScanlators? = null,
    private val getExcludedScanlators: GetExcludedScanlators? = null,
    private val setExcludedScanlators: SetExcludedScanlators? = null,
    private val getCategories: GetCategories? = null,
    private val libraryPreferences: LibraryPreferences? = null,
    private val updateChapter: UpdateChapter? = null,
    private val setChapterReadStatus: SetChapterReadStatus? = null,
    private val updateManga: UpdateManga? = null,
    private val setMangaChapterFlags: SetMangaChapterFlags? = null,
    private val setMangaDefaultChapterFlags: tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags? = null,
    private val setMangaCategories: SetMangaCategories? = null,
    private val linkMangaCreator: LinkMangaCreator? = null,
    private val manageCreatorIdentity: ManageCreatorIdentity? = null,
    private val extractCreatorsFromManga: ExtractCreatorsFromManga = ExtractCreatorsFromManga(),
    private val enqueueDownload: ((DownloadItem) -> Unit)? = null,
    private val downloadQueue: StateFlow<List<DownloadItem>>? = null,
    private val downloadAvailability: StateFlow<Long>? = null,
    private val isDownloaded: ((manga: Manga, chapter: Chapter) -> Boolean)? = null,
    private val deleteDownload: ((manga: Manga, chapter: Chapter) -> Unit)? = null,
    private val cancelDownload: ((chapterId: Long) -> Unit)? = null,
    private val retryDownload: ((chapterId: Long) -> Unit)? = null,
    private val batchUpdateChapters: BatchUpdateChapters = BatchUpdateChapters(),
    private val updateLibraryMembership: UpdateLibraryMembership? = null,
    private val coverAdapter: MangaCoverAdapter? = null,
    private val deleteCover: (suspend (Long) -> TaskState<Unit>)? = null,
    private val resolveCoverModel: ((Long, String?) -> String?)? = null,
    private val readingProgress: tachiyomi.domain.reader.interactor.RecordReadingProgress? = null,
    private val getDuplicateLibraryManga: tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga? = null,
    private val hasCustomCover: ((Long) -> Boolean)? = null,
    private val deleteRemovedDownloads: (suspend (Manga, List<Chapter>) -> Unit)? = null,
    private val deleteSelectedDownloads: (suspend (Manga, List<Chapter>) -> BatchChapterResult)? = null,
    private val readerPreferences: mihon.desktop.reader.ReaderPreferences? = null,
    private val enqueueAccepted: ((DownloadItem) -> Boolean)? = null,
    private val startDownloadNow: ((Long) -> Boolean)? = null,
    private val cancelAccepted: ((Long) -> Boolean)? = null,
    private val retryAccepted: ((Long) -> Boolean)? = null,
    private val captureDownloadDeletion: ((Manga, List<Chapter>) -> (suspend () -> BatchChapterResult))? = null,
    val manualTracking: mihon.desktop.tracking.DesktopManualTracking? = null,
    private val captureMangaDownloadDeletion: (suspend (Manga) -> (suspend () -> Boolean))? = null,
) : ScreenModel {

    private val _state = MutableStateFlow(MangaDetailState())
    val state: StateFlow<MangaDetailState> = _state.asStateFlow()
    private val chapterSettingsMutex = Mutex()
    internal var chapterPosition: MangaDetailChapterPosition? = null

    // ── Data loading ──────────────────────────────────────────────────────────

    suspend fun mangaWithChaptersFlow(): Flow<Pair<Manga, List<Chapter>>> {
        return requireNotNull(getMangaWithChapters) { "GetMangaWithChapters is required" }
            .subscribe(mangaId, applyScanlatorFilter = true).map { (manga, chapters) ->
                val candidate = readingProgress?.resumePosition(manga.id)
                val chapter = chapters.find {
                    it.id == candidate?.chapterId && it.url.externalChapterUrlOrNull() == null
                }
                _state.update { it.copy(syncedResumeChapterId = chapter?.id) }
                manga to chapters
            }
    }

    fun trackingBindingCount(manga: Manga): Flow<Int> = manualTracking?.bindingCount(manga) ?: flowOf(0)

    fun availableScanlatorsFlow(): Flow<Set<String>> {
        return requireNotNull(getAvailableScanlators) { "GetAvailableScanlators is required" }.subscribe(mangaId)
    }

    fun excludedScanlatorsFlow(): Flow<Set<String>> {
        return requireNotNull(getExcludedScanlators) { "GetExcludedScanlators is required" }.subscribe(mangaId)
    }

    fun downloadQueueFlow(): StateFlow<List<DownloadItem>> {
        return requireNotNull(downloadQueue) { "Download queue is required" }
    }

    fun visibleChapters(): List<Chapter> {
        val current = state.value.manga ?: return emptyList()
        return state.value.chapters.filterAndSortChapters(
            current,
            libraryPreferences?.downloadedOnly()?.get() == true,
            current.source == 0L,
        ) { isChapterDownloaded(current, it) }
    }

    fun downloadedOnlyFlow(): Flow<Boolean> = libraryPreferences?.downloadedOnly()?.changes() ?: flowOf(false)

    fun downloadAvailabilityFlow(): Flow<Long> = downloadAvailability ?: flowOf(0L)

    suspend fun saveChapterDefaults(applyToExisting: Boolean): Boolean {
        return chapterSettingsMutex.withLock {
            try {
                saveChapterDefaultsLocked(applyToExisting)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                chapterSettingsFailed()
                false
            }
        }
    }

    private suspend fun saveChapterDefaultsLocked(applyToExisting: Boolean): Boolean {
        val manga = requireNotNull(getMangaWithChapters).awaitManga(mangaId)
        val preferences = requireNotNull(libraryPreferences)
        val entries = listOf(
            preferences.filterChapterByRead() to manga.unreadFilterRaw,
            preferences.filterChapterByDownloaded() to manga.downloadedFilterRaw,
            preferences.filterChapterByBookmarked() to manga.bookmarkedFilterRaw,
            preferences.sortChapterBySourceOrNumber() to manga.sorting,
            preferences.displayChapterByNameOrNumber() to manga.displayMode,
            preferences.sortChapterByAscendingOrDescending() to
                if (manga.sortDescending()) Manga.CHAPTER_SORT_DESC else Manga.CHAPTER_SORT_ASC,
        )
        val previous = entries.map { (preference, _) -> preference.get() to preference.isSet() }
        try {
            preferences.setChapterSettingsDefault(manga)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            entries.forEachIndexed { index, (preference, target) ->
                try {
                    if (preference.get() == target) {
                        val (value, wasSet) = previous[index]
                        if (wasSet) preference.set(value) else preference.delete()
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // A second storage failure is reported; there is no multi-key transaction.
                }
            }
            chapterSettingsFailed()
            return false
        }
        if (applyToExisting) {
            try {
                requireNotNull(setMangaDefaultChapterFlags).awaitAll()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        chapterSettingsFeedback = MR.strings.desktop_chapter_defaults_apply_failed.localized(),
                        chapterSettingsFeedbackIsError = true,
                    )
                }
                return false
            }
        }
        _state.update {
            it.copy(
                chapterSettingsFeedback = MR.strings.chapter_settings_updated.localized(),
                chapterSettingsFeedbackIsError = false,
            )
        }
        return true
    }

    suspend fun resetChapterDefaults(): Boolean = editChapterSettings(
        Manga.CHAPTER_UNREAD_MASK or Manga.CHAPTER_DOWNLOADED_MASK or Manga.CHAPTER_BOOKMARKED_MASK or
            Manga.CHAPTER_SORTING_MASK or Manga.CHAPTER_SORT_DIR_MASK or Manga.CHAPTER_DISPLAY_MASK,
    ) { requireNotNull(setMangaDefaultChapterFlags).await(it) }

    suspend fun setChapterBookmarkFilter(value: TriState): Boolean = editChapterSettings(
        Manga.CHAPTER_BOOKMARKED_MASK,
    ) {
        requireNotNull(setMangaChapterFlags).awaitSetBookmarkFilter(
            it,
            when (value) {
                TriState.DISABLED -> Manga.SHOW_ALL
                TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_BOOKMARKED
                TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
            },
        )
    }

    suspend fun setChapterReadFilter(value: TriState): Boolean = editChapterSettings(Manga.CHAPTER_UNREAD_MASK) {
        requireNotNull(setMangaChapterFlags).awaitSetUnreadFilter(
            it,
            when (value) {
                TriState.DISABLED -> Manga.SHOW_ALL
                TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_READ
                TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_UNREAD
            },
        )
    }

    suspend fun setChapterDownloadFilter(value: TriState): Boolean {
        if (libraryPreferences?.downloadedOnly()?.get() == true) return false
        return editChapterSettings(Manga.CHAPTER_DOWNLOADED_MASK) {
            requireNotNull(setMangaChapterFlags).awaitSetDownloadedFilter(
                it,
                when (value) {
                    TriState.DISABLED -> Manga.SHOW_ALL
                    TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_DOWNLOADED
                    TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
                },
            )
        }
    }

    private suspend fun editChapterSettings(mask: Long, write: suspend (Manga) -> Boolean): Boolean =
        chapterSettingsMutex.withLock {
            var before: Manga? = null
            try {
                val target = getMangaWithChapters?.awaitManga(mangaId) ?: state.value.manga ?: return@withLock false
                before = target
                check(write(target)) { "Chapter settings write rejected" }
                setManga(getMangaWithChapters?.awaitManga(mangaId) ?: target)
                clearChapterSettingsFeedback()
                true
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                try {
                    before?.let { previous ->
                        val current = requireNotNull(getMangaWithChapters).awaitManga(mangaId)
                        val restored = current.chapterFlags and mask.inv() or (previous.chapterFlags and mask)
                        if (restored != current.chapterFlags) {
                            check(requireNotNull(updateManga).await(MangaUpdate(id = mangaId, chapterFlags = restored)))
                        }
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Reconciliation can fail too; the next read remains authoritative.
                }
                try {
                    getMangaWithChapters?.let { setManga(it.awaitManga(mangaId)) }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Keep the previous visible state and report that saving could not be reconciled.
                }
                chapterSettingsFailed()
                false
            }
        }

    fun clearChapterSettingsFeedback() {
        _state.update { it.copy(chapterSettingsFeedback = null, chapterSettingsFeedbackIsError = false) }
    }

    private fun chapterSettingsFailed() {
        _state.update {
            it.copy(
                chapterSettingsFeedback = MR.strings.desktop_appearance_save_failed.localized(),
                chapterSettingsFeedbackIsError = true,
            )
        }
    }

    fun setManga(manga: Manga?) {
        _state.update { state ->
            if (manga == null) {
                state.copy(manga = null)
            } else {
                state.copy(
                    manga = manga,
                    coverLastModified = manga.coverLastModified,
                    hasCustomCover = if (
                        state.manga?.id != manga.id || state.manga?.coverLastModified != manga.coverLastModified ||
                        state.manga?.thumbnailUrl != manga.thumbnailUrl
                    ) {
                        hasCustomCover?.invoke(manga.id) ?: false
                    } else {
                        state.hasCustomCover
                    },
                    coverModel = resolveCoverModel?.invoke(manga.id, manga.thumbnailUrl) ?: manga.thumbnailUrl,
                    chapterSortMode = chapterSortModeFromManga(manga),
                    chapterSortAscending = !manga.sortDescending(),
                )
            }
        }
    }

    fun setChapters(chapters: List<Chapter>) {
        _state.update { it.copy(chapters = chapters) }
    }

    fun setIsUpdating(updating: Boolean) {
        _state.update { it.copy(isUpdating = updating) }
    }

    fun setAvailableScanlators(scanlators: Set<String>) {
        _state.update { it.copy(availableScanlators = scanlators) }
    }

    fun setExcludedScanlators(scanlators: Set<String>) {
        _state.update { it.copy(excludedScanlators = scanlators) }
    }

    // ── Sort ──────────────────────────────────────────────────────────────────

    fun setSortMode(mode: ChapterSortMode) {
        _state.update { it.copy(chapterSortMode = mode) }
    }

    fun setSortAscending(ascending: Boolean) {
        _state.update { it.copy(chapterSortAscending = ascending) }
    }

    /**
     * Taps a sort mode button:
     * - Same mode → flip ascending/descending
     * - Different mode → switch to new mode, reset to descending
     */
    fun toggleSort(mode: ChapterSortMode) {
        _state.update { s ->
            val (nextMode, nextAscending) = nextChapterSort(s.chapterSortMode, s.chapterSortAscending, mode)
            s.copy(chapterSortMode = nextMode, chapterSortAscending = nextAscending)
        }
    }

    // ── Dialog / sheet visibility ─────────────────────────────────────────────

    fun toggleFilterMenu() {
        _state.update {
            it.copy(
                showFilterMenu = !it.showFilterMenu,
                chapterSettingsFeedback = null,
                chapterSettingsFeedbackIsError = false,
            )
        }
    }

    fun setShowNotesDialog(show: Boolean) {
        _state.update { it.copy(showNotesDialog = show) }
    }

    fun setShowMigrateSourcePicker(show: Boolean) {
        _state.update { it.copy(showMigrateSourcePicker = show) }
    }

    fun setDeleteConfirmChapter(chapter: Chapter?) {
        _state.update { it.copy(deleteConfirmChapter = chapter) }
    }

    fun setMarkAllReadConfirm(show: Boolean) {
        _state.update { it.copy(markAllReadConfirm = show) }
    }

    // ── Migration state ───────────────────────────────────────────────────────

    fun setMigrateSearchResults(results: List<SManga>?) {
        _state.update { it.copy(migrateSearchResults = results) }
    }

    fun setMigrateTargetSourceId(sourceId: Long?) {
        _state.update { it.copy(migrateTargetSourceId = sourceId) }
    }

    fun setMigrateSearching(searching: Boolean) {
        _state.update { it.copy(migrateSearching = searching) }
    }

    fun setMigrateConfirmItem(item: SManga?) {
        _state.update { it.copy(migrateConfirmItem = item) }
    }

    // ── Business actions ─────────────────────────────────────────────────────

    suspend fun markAllRead(chapters: List<Chapter>) {
        requireNotNull(setChapterReadStatus) { "SetChapterReadStatus is required" }
            .awaitOrThrow(chapters, read = true)
    }

    suspend fun markSelectedRead(chapters: List<Chapter>, read: Boolean): BatchChapterResult {
        val updater = requireNotNull(setChapterReadStatus) { "SetChapterReadStatus is required" }
        val result = runChapterBatch(updater.filterToUpdate(chapters, read)) { updater.awaitOrThrow(it, read) }
        if (read) manualTracking?.afterRead(mangaId, chapters.filter { it.id in result.succeededIds })
        return result
    }

    suspend fun runChapterBatch(
        chapters: List<Chapter>,
        skippedIds: List<Long> = emptyList(),
        action: suspend (Chapter) -> Unit,
    ): BatchChapterResult = publishChapterBatchResult(
        batchUpdateChapters.await(chapters.distinctBy { it.id }, action).copy(skippedIds = skippedIds.distinct()),
    )

    private fun publishChapterBatchResult(result: BatchChapterResult): BatchChapterResult {
        _state.update {
            it.copy(
                batchActionMessage = MR.strings.desktop_chapter_batch_result.localized(
                    java.util.Locale.getDefault(),
                    result.succeededIds.size,
                    result.skippedIds.size,
                    result.failures.size,
                ),
            )
        }
        return result
    }

    fun consumeChapterBatchFeedback() {
        _state.update { it.copy(batchActionMessage = null) }
    }

    suspend fun markSelectedBookmark(chapters: List<Chapter>): BatchChapterResult {
        val shouldBookmark = chapters.any { !it.bookmark }
        val applicable = chapters.filter { it.bookmark != shouldBookmark }
        val updater = requireNotNull(updateChapter) { "UpdateChapter is required" }
        return runChapterBatch(applicable, skippedIds = (chapters - applicable.toSet()).map { it.id }) {
            updater.awaitOrThrow(ChapterUpdate(id = it.id, bookmark = shouldBookmark))
        }
    }

    suspend fun markAtOrBelowRead(displayedChapters: List<Chapter>, selectedIds: Set<Long>): BatchChapterResult {
        val manga = state.value.manga ?: return BatchChapterResult.Empty
        val pointer = selectedIds.singleOrNull() ?: return BatchChapterResult.Empty
        val previous = tachiyomi.domain.chapter.service.chaptersBeforePointer(displayedChapters, manga, pointer)
        return markSelectedRead(previous, read = true)
    }

    suspend fun toggleChapterBookmark(chapter: Chapter) {
        requireNotNull(updateChapter) { "UpdateChapter is required" }
            .awaitOrThrow(ChapterUpdate(id = chapter.id, bookmark = !chapter.bookmark))
    }

    suspend fun toggleChapterRead(chapter: Chapter) {
        requireNotNull(setChapterReadStatus) { "SetChapterReadStatus is required" }
            .awaitOrThrow(chapter, read = !chapter.read)
    }

    suspend fun toggleLibrary(
        manga: Manga,
        categoryIds: List<Long> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
    ): LibraryMembershipResult {
        val result = requireNotNull(updateLibraryMembership) { "UpdateLibraryMembership is required" }
            .await(
                manga = manga,
                favorite = !manga.favorite,
                categoryIds = categoryIds,
                nowMillis = nowMillis,
            )
        if (!manga.favorite && result is LibraryMembershipResult.Success) manualTracking?.afterAdded(manga)
        return result
    }

    internal suspend fun addToLibraryUsingDefault(
        manga: Manga,
        nowMillis: Long = System.currentTimeMillis(),
    ): MangaDetailAddToLibraryResult {
        require(!manga.favorite) { "Manga is already in the library" }
        val categories = categories()
        val defaultCategoryId = libraryPreferences?.defaultCategory()?.get()?.toLong() ?: -1L
        val defaultCategory = categories.find { it.id == defaultCategoryId }
        val categoryIds = when {
            defaultCategory != null -> listOf(defaultCategory.id)
            defaultCategoryId == Category.UNCATEGORIZED_ID || categories.isEmpty() -> emptyList()
            else -> return MangaDetailAddToLibraryResult.CHOOSE_CATEGORY
        }
        return when (toggleLibrary(manga, categoryIds, nowMillis)) {
            is LibraryMembershipResult.Success -> MangaDetailAddToLibraryResult.ADDED
            is LibraryMembershipResult.Failure -> MangaDetailAddToLibraryResult.FAILED
        }
    }

    suspend fun chooseCustomCover() {
        _state.update { it.copy(coverTask = TaskState.Running(), coverFeedback = null) }
        val result = requireNotNull(coverAdapter) { "Cover adapter is required" }.chooseAndUpdate(mangaId)
        if (result == null) {
            _state.update { it.copy(coverTask = TaskState.Idle) }
            return
        }
        applyCoverResult(result, MR.strings.cover_updated.localized())
    }

    suspend fun deleteCustomCover() {
        _state.update { it.copy(coverTask = TaskState.Running(), coverFeedback = null) }
        val result = requireNotNull(deleteCover) { "Delete cover callback is required" }(mangaId)
        applyCoverResult(result, MR.strings.desktop_ui_cover_deleted.localized())
    }

    private suspend fun applyCoverResult(result: TaskState<Unit>, successFeedback: String) {
        var settled = result
        if (result is TaskState.Success && getMangaWithChapters != null) {
            try {
                setManga(getMangaWithChapters.awaitManga(mangaId))
            } catch (canceled: kotlinx.coroutines.CancellationException) {
                throw canceled
            } catch (error: Exception) {
                settled = TaskState.Failure(mihon.domain.error.AppError.Storage(error))
            }
        }
        val manga = _state.value.manga
        _state.update {
            it.copy(
                coverTask = settled,
                hasCustomCover = hasCustomCover?.invoke(mangaId) ?: it.hasCustomCover,
                coverFeedback = when (settled) {
                    is TaskState.Success -> successFeedback
                    is TaskState.Failure ->
                        settled.error.cause?.message
                            ?: MR.strings.desktop_ui_unable_to_update_cover.localized()
                    else -> null
                },
                coverModel = if (settled is TaskState.Success) {
                    resolveCoverModel?.invoke(mangaId, manga?.thumbnailUrl) ?: manga?.thumbnailUrl
                } else {
                    it.coverModel
                },
            )
        }
    }

    suspend fun setFetchInterval(mangaId: Long, interval: Int): Boolean {
        return requireNotNull(updateManga) { "UpdateManga is required" }
            .await(MangaUpdate(id = mangaId, fetchInterval = if (interval == 0) 0 else -interval))
    }

    internal suspend fun duplicates(manga: Manga): List<tachiyomi.domain.manga.model.MangaWithChapterCount> =
        requireNotNull(getDuplicateLibraryManga) { "GetDuplicateLibraryManga is required" }(manga)

    internal suspend fun captureFavoriteDownloadDeletion(manga: Manga, chapters: List<Chapter>): suspend () -> Boolean {
        captureMangaDownloadDeletion?.let { return it(manga) }
        val delete = captureChapterDownloadDeletion(manga, chapters)
        return { delete().failures.isEmpty() }
    }

    internal suspend fun removeFavorite(
        manga: Manga,
        downloadedChapters: List<Chapter>,
        deleteFiles: Boolean,
        membershipCompleted: Boolean = false,
        executeDownloads: (suspend () -> Boolean)? = null,
    ): MangaRemovalResult {
        if (!membershipCompleted && toggleLibrary(manga) !is LibraryMembershipResult.Success) {
            return MangaRemovalResult.MEMBERSHIP_FAILED
        }
        try {
            if (deleteFiles) {
                if (executeDownloads != null) {
                    check(executeDownloads()) { "Some captured downloads could not be deleted" }
                } else if (deleteRemovedDownloads != null) {
                    deleteRemovedDownloads.invoke(manga, downloadedChapters)
                } else {
                    val delete = requireNotNull(deleteDownload) { "Delete download callback is required" }
                    downloadedChapters.forEach { delete(manga, it) }
                }
            }
        } catch (canceled: kotlinx.coroutines.CancellationException) {
            throw canceled
        } catch (_: Exception) {
            return MangaRemovalResult.DOWNLOADS_FAILED
        }
        return MangaRemovalResult.SUCCESS
    }

    suspend fun setReadingMode(mangaId: Long, currentFlags: Long, mode: ReadingMode?) {
        requireNotNull(updateManga) { "UpdateManga is required" }
            .await(
                MangaUpdate(
                    id = mangaId,
                    viewerFlags = if (mode ==
                        null
                    ) {
                        viewerFlagsFollowingGlobal(currentFlags)
                    } else {
                        viewerFlagsWithReadingMode(currentFlags, mode)
                    },
                ),
            )
    }

    suspend fun setChapterSort(manga: Manga, requestedMode: ChapterSortMode): Boolean =
        editChapterSettings(Manga.CHAPTER_SORTING_MASK or Manga.CHAPTER_SORT_DIR_MASK) {
            requireNotNull(setMangaChapterFlags).awaitSetSortingModeOrFlipOrder(it, requestedMode.toMangaFlag())
        }

    suspend fun setChapterDisplayMode(manga: Manga, displayMode: Long): Boolean =
        editChapterSettings(Manga.CHAPTER_DISPLAY_MASK) {
            requireNotNull(setMangaChapterFlags).awaitSetDisplayMode(it, displayMode)
        }

    internal fun manualDownloadScope(): String = if (readerPreferences?.skipFilteredChapters == true) {
        MR.strings.desktop_manual_download_scope_filtered.localized()
    } else {
        MR.strings.desktop_manual_download_scope_all.localized()
    }

    internal suspend fun downloadManualAction(action: MangaDetailDownloadAction): BatchChapterResult {
        val current = state.value.manga ?: return BatchChapterResult.Empty
        val unavailable = when {
            current.source == 0L -> MR.strings.desktop_manual_download_local.localized()
            sourceManager?.get(current.source) == null ->
                MR.strings.desktop_manual_download_missing_source.localized(
                    java.util.Locale.getDefault(),
                    current.source,
                )
            else -> null
        }
        if (unavailable != null) {
            _state.update { it.copy(batchActionMessage = unavailable) }
            return BatchChapterResult(emptyList(), emptyList(), skippedIds = state.value.chapters.map { it.id })
        }
        val skipFiltered = readerPreferences?.skipFilteredChapters == true
        val visibleIds = visibleChapters().mapTo(mutableSetOf()) { it.id }
        val candidates = try {
            requireNotNull(getMangaWithChapters).awaitChapters(current.id, applyScanlatorFilter = false)
                .filter { !skipFiltered || it.id in visibleIds }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { it.copy(batchActionMessage = MR.strings.desktop_ui_download_failed.localized()) }
            return BatchChapterResult(
                emptyList(),
                listOf(
                    tachiyomi.domain.chapter.interactor.BatchChapterFailure(
                        current.id,
                        error.message ?: "Directory read failed",
                    ),
                ),
            )
        }
        val limit = when (action) {
            MangaDetailDownloadAction.NEXT_1_CHAPTER -> 1
            MangaDetailDownloadAction.NEXT_5_CHAPTERS -> 5
            MangaDetailDownloadAction.NEXT_10_CHAPTERS -> 10
            MangaDetailDownloadAction.NEXT_25_CHAPTERS -> 25
            else -> null
        }
        val chapters = tachiyomi.domain.library.selectManualDownloadChapters(
            candidates,
            current,
            bookmarkedOnly = action == MangaDetailDownloadAction.BOOKMARKED_CHAPTERS,
            limit = limit,
            isQueued = { chapter -> downloadQueue?.value.orEmpty().any { it.chapterId == chapter.id } },
            isDownloaded = { isChapterDownloaded(current, it) },
            isDownloadable = { it.url.externalChapterUrlOrNull() == null },
        )
        return enqueueDownloadBatch(current, chapters)
    }

    fun enqueueDownloads(manga: Manga, chapters: List<Chapter>) {
        val enqueue =
            enqueueAccepted
                ?: requireNotNull(enqueueDownload) { "Download enqueue callback is required" }.let { legacy ->
                    { item: DownloadItem ->
                        legacy(item)
                        true
                    }
                }
        chapters
            .filterNot { it.url.externalChapterUrlOrNull() != null }
            .filterNot { chapter -> isChapterDownloaded(manga, chapter) }
            .forEach { chapter ->
                enqueue(
                    DownloadItem(
                        sourceId = manga.source,
                        mangaId = manga.id,
                        mangaTitle = manga.title,
                        chapterName = chapter.name,
                        chapterId = chapter.id,
                        chapterUrl = chapter.url,
                    ),
                )
            }
    }

    internal fun downloadableChapters(manga: Manga, chapters: List<Chapter>): List<Chapter> {
        if (manga.source == 0L) return emptyList()
        val queued = downloadQueue?.value.orEmpty().filter {
            it.status != mihon.desktop.download.DownloadStatus.DONE &&
                it.status != mihon.desktop.download.DownloadStatus.CANCELLED
        }.mapTo(mutableSetOf()) { it.chapterId }
        return chapters.filter {
            it.url.externalChapterUrlOrNull() == null && it.id !in queued &&
                !isChapterDownloaded(manga, it)
        }
    }

    suspend fun enqueueDownloadBatch(manga: Manga, chapters: List<Chapter>): BatchChapterResult {
        val unavailable = when {
            manga.source == 0L -> MR.strings.desktop_manual_download_local.localized()
            sourceManager != null && sourceManager.get(manga.source) == null ->
                MR.strings.desktop_manual_download_missing_source.localized(java.util.Locale.getDefault(), manga.source)
            else -> null
        }
        if (unavailable != null) {
            _state.update { it.copy(batchActionMessage = unavailable) }
            return BatchChapterResult(emptyList(), emptyList(), chapters.map { it.id })
        }
        val enqueue =
            enqueueAccepted
                ?: requireNotNull(enqueueDownload) { "Download enqueue callback is required" }.let { legacy ->
                    { item: DownloadItem ->
                        legacy(item)
                        true
                    }
                }
        val eligible = downloadableChapters(manga, chapters)
        val declined = mutableSetOf<Long>()
        val result = batchUpdateChapters.await(eligible) { chapter ->
            if (!enqueue(
                    DownloadItem(
                        sourceId = manga.source,
                        mangaId = manga.id,
                        mangaTitle = manga.title,
                        chapterName = chapter.name,
                        chapterId = chapter.id,
                        chapterUrl = chapter.url,
                    ),
                )
            ) {
                declined += chapter.id
            }
        }
        return publishChapterBatchResult(
            result.copy(
                succeededIds = result.succeededIds.filterNot { it in declined },
                skippedIds = ((chapters - eligible.toSet()).map { it.id } + declined).distinct(),
            ),
        )
    }

    fun downloadChapterNow(chapterId: Long): BatchChapterResult = queueCommand(
        chapterId,
        requireNotNull(startDownloadNow) { "Start download callback is required" },
    )

    private fun queueCommand(chapterId: Long, command: (Long) -> Boolean): BatchChapterResult {
        if (downloadQueue?.value.orEmpty().none { it.chapterId == chapterId }) {
            return publishChapterBatchResult(BatchChapterResult(emptyList(), emptyList(), listOf(chapterId)))
        }
        val result = try {
            check(command(chapterId)) { "Download queue did not accept the command" }
            BatchChapterResult(listOf(chapterId), emptyList())
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (error: Exception) {
            BatchChapterResult(emptyList(), listOf(BatchChapterFailure(chapterId, error.message ?: "Download failed")))
        }
        return publishChapterBatchResult(result)
    }

    fun deleteChapterDownload(manga: Manga, chapter: Chapter) {
        requireNotNull(deleteDownload) { "Delete download callback is required" }(manga, chapter)
    }

    fun captureChapterDownloadDeletion(manga: Manga, chapters: List<Chapter>): suspend () -> BatchChapterResult {
        val fixed = chapters.distinctBy { it.id }.toList()
        val execute = captureDownloadDeletion?.invoke(manga, fixed)
        return if (execute != null) {
            { publishChapterBatchResult(execute()) }
        } else {
            { deleteDownloadBatch(manga, fixed) }
        }
    }

    suspend fun deleteDownloadBatch(manga: Manga, chapters: List<Chapter>): BatchChapterResult {
        val eligible = chapters.filter { isChapterDownloaded(manga, it) }
        val skipped = (chapters - eligible.toSet()).map { it.id }
        val result = if (deleteSelectedDownloads != null) {
            deleteSelectedDownloads.invoke(manga, eligible)
        } else {
            val delete = requireNotNull(deleteDownload) { "Delete download callback is required" }
            batchUpdateChapters.await(eligible) { chapter -> delete(manga, chapter) }
        }
        return publishChapterBatchResult(result.copy(skippedIds = skipped))
    }

    fun cancelChapterDownload(chapterId: Long): BatchChapterResult = queueCommand(
        chapterId,
        cancelAccepted ?: requireNotNull(cancelDownload) { "Cancel download callback is required" }.let { legacy ->
            { id ->
                legacy(id)
                true
            }
        },
    )

    fun retryChapterDownload(chapterId: Long): BatchChapterResult = queueCommand(
        chapterId,
        retryAccepted ?: requireNotNull(retryDownload) { "Retry download callback is required" }.let { legacy ->
            { id ->
                legacy(id)
                true
            }
        },
    )

    fun isChapterDownloaded(manga: Manga, chapter: Chapter): Boolean {
        return isDownloaded?.invoke(manga, chapter) ?: false
    }

    suspend fun continueReadingRequest(manga: Manga, chapters: List<Chapter>): MangaDetailReaderRequest? {
        val target = nextUnreadChapter(chapters, manga) ?: return null
        val request = readerRequest(manga, chapters, target) ?: return null
        val resume = readingProgress?.resumePosition(manga.id)?.takeIf { it.chapterId == target.id }
        return if (resume != null) {
            request.copy(
                initialPage = resume.pageIndex,
                resumeSnapshot = resume.snapshot,
            )
        } else {
            request
        }
    }

    fun readerRequest(
        manga: Manga,
        chapters: List<Chapter>,
        chapter: Chapter,
    ): MangaDetailReaderRequest? {
        if (chapter.url.externalChapterUrlOrNull() != null) return null
        val readerChapters = chapters
            .filterNot { it.url.externalChapterUrlOrNull() != null }
            .sortedBy { it.sourceOrder }
        val chapterRefs = readerChapters.toReaderChapterRefs(
            currentChapterId = chapter.id,
            manga = manga,
            downloadedOnly = libraryPreferences?.downloadedOnly()?.get() == true,
            isChapterDownloaded = { readerChapter -> isChapterDownloaded(manga, readerChapter) },
        )
        return MangaDetailReaderRequest(
            chapterTitle = chapter.name,
            mangaId = manga.id,
            mangaTitle = manga.title,
            sourceId = manga.source,
            chapterUrl = chapter.url,
            chapterId = chapter.id,
            chapters = chapterRefs,
            currentChapterIndex = ReaderNavigator.indexForId(chapterRefs, chapter.id),
            initialPage = resolveReaderChapterEntryPage(chapter.read, chapter.lastPageRead),
            mangaViewerFlags = manga.viewerFlags,
        )
    }

    suspend fun setCategoriesForManga(mangaId: Long, categoryIds: List<Long>): SetMangaCategories.Result {
        return requireNotNull(setMangaCategories) { "SetMangaCategories is required" }.awaitResult(mangaId, categoryIds)
    }

    suspend fun categories(): List<Category> {
        return requireNotNull(getCategories) { "GetCategories is required" }
            .await()
            .filterNot(Category::isSystemCategory)
            .sortedBy { it.order }
    }

    suspend fun categoryIdsForManga(mangaId: Long): Set<Long> {
        return requireNotNull(getCategories) { "GetCategories is required" }
            .await(mangaId)
            .map { it.id }
            .toSet()
    }

    suspend fun updateExcludedScanlators(excluded: Set<String>): Boolean {
        return try {
            requireNotNull(setExcludedScanlators).await(mangaId, excluded)
            setExcludedScanlators(requireNotNull(getExcludedScanlators).await(mangaId))
            clearChapterSettingsFeedback()
            true
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            chapterSettingsFailed()
            false
        }
    }

    fun sourceFor(manga: Manga): eu.kanade.tachiyomi.source.Source? {
        return requireNotNull(sourceManager) { "SourceManager is required" }
            .get(manga.source)
    }

    fun migrationSources(currentSourceId: Long?): List<CatalogueSource> {
        return requireNotNull(sourceManager) { "SourceManager is required" }
            .getCatalogueSources()
            .filter { it.id != currentSourceId }
    }

    suspend fun searchMigration(source: CatalogueSource, query: String): List<SManga> {
        return source.getSearchManga(1, query, FilterList()).mangas
    }

    suspend fun refreshManga(manga: Manga) {
        _state.update { it.copy(directoryRefreshFeedback = null) }
        val source = sourceFor(manga)
        if (source == null) {
            _state.update {
                it.copy(directoryRefreshFeedback = MR.strings.desktop_source_preferences_missing.localized())
            }
            return
        }
        try {
            val result = requireNotNull(updateChecker).checkForUpdates(manga, source, origin = "DETAIL_REFRESH")
            if (result.error != null || result.sourceError != null) {
                _state.update {
                    it.copy(directoryRefreshFeedback = MR.strings.desktop_ui_library_update_failed.localized())
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            _state.update {
                it.copy(directoryRefreshFeedback = MR.strings.desktop_ui_library_update_failed.localized())
            }
        }
    }

    suspend fun migrateTo(targetSourceId: Long, item: SManga, fallbackTitle: String?) {
        val target = item
        if (target.title.isBlank()) target.title = fallbackTitle.orEmpty()
        requireNotNull(migrateManga) { "Independent migration is required" }.invoke(targetSourceId, target)
    }

    suspend fun linkCreator(name: String, role: CreatorRole): Long {
        return requireNotNull(linkMangaCreator) { "LinkMangaCreator is required" }
            .await(mangaId, name, role)
    }

    fun creatorMentions(manga: Manga): List<CreatorMention> = extractCreatorsFromManga.await(manga)

    suspend fun resolveCreatorMention(
        manga: Manga,
        mention: CreatorMention,
    ): CreatorMentionResolution {
        return requireNotNull(manageCreatorIdentity) { "ManageCreatorIdentity is required" }.resolve(manga, mention)
    }

    suspend fun selectCreatorIdentity(
        manga: Manga,
        mention: CreatorMention,
        creatorId: Long,
    ) {
        requireNotNull(manageCreatorIdentity) { "ManageCreatorIdentity is required" }.select(manga, mention, creatorId)
    }

    suspend fun createDistinctCreatorIdentity(manga: Manga, mention: CreatorMention): Long {
        return requireNotNull(manageCreatorIdentity) { "ManageCreatorIdentity is required" }
            .createDistinct(manga, mention)
    }
}

internal enum class MangaDetailAddToLibraryResult {
    ADDED,
    CHOOSE_CATEGORY,
    FAILED,
}

data class MangaDetailReaderRequest(
    val chapterTitle: String,
    val mangaId: Long,
    val mangaTitle: String,
    val sourceId: Long,
    val chapterUrl: String,
    val chapterId: Long,
    val chapters: List<ReaderChapterRef>,
    val currentChapterIndex: Int,
    val initialPage: Int,
    val mangaViewerFlags: Long,
    val resumeSnapshot: tachiyomi.domain.reader.model.ReadingSyncSnapshot? = null,
)
