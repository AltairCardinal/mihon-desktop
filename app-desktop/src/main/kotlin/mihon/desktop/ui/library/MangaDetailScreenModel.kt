package mihon.desktop.ui.library

import cafe.adriel.voyager.core.model.ScreenModel
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
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
import tachiyomi.domain.chapter.interactor.BatchChapterResult
import tachiyomi.domain.chapter.interactor.BatchUpdateChapters
import tachiyomi.domain.chapter.interactor.SetChapterReadStatus
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
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
) : ScreenModel {

    private val _state = MutableStateFlow(MangaDetailState())
    val state: StateFlow<MangaDetailState> = _state.asStateFlow()
    private val chapterSettingsMutex = Mutex()

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

    fun availableScanlatorsFlow(): Flow<Set<String>> {
        return requireNotNull(getAvailableScanlators) { "GetAvailableScanlators is required" }.subscribe(mangaId)
    }

    fun excludedScanlatorsFlow(): Flow<Set<String>> {
        return requireNotNull(getExcludedScanlators) { "GetExcludedScanlators is required" }.subscribe(mangaId)
    }

    fun downloadQueueFlow(): StateFlow<List<DownloadItem>> {
        return requireNotNull(downloadQueue) { "Download queue is required" }
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
        return runChapterBatch(updater.filterToUpdate(chapters, read)) { updater.awaitOrThrow(it, read) }
    }

    suspend fun runChapterBatch(
        chapters: List<Chapter>,
        action: suspend (Chapter) -> Unit,
    ): BatchChapterResult = batchUpdateChapters.await(chapters, action).also { result ->
        _state.update {
            it.copy(batchActionMessage = "${result.succeededIds.size} succeeded, ${result.failures.size} failed")
        }
    }

    suspend fun markSelectedBookmark(chapters: List<Chapter>): BatchChapterResult {
        val shouldBookmark = chapters.any { !it.bookmark }
        val updater = requireNotNull(updateChapter) { "UpdateChapter is required" }
        return runChapterBatch(chapters) { updater.awaitOrThrow(ChapterUpdate(id = it.id, bookmark = shouldBookmark)) }
    }

    suspend fun markAtOrBelowRead(displayedChapters: List<Chapter>, selectedIds: Set<Long>) {
        requireNotNull(setChapterReadStatus) { "SetChapterReadStatus is required" }
            .awaitOrThrow(chaptersAtOrBelowSelection(displayedChapters, selectedIds), read = true)
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
        return requireNotNull(updateLibraryMembership) { "UpdateLibraryMembership is required" }
            .await(
                manga = manga,
                favorite = !manga.favorite,
                categoryIds = categoryIds,
                nowMillis = nowMillis,
            )
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

    private fun applyCoverResult(result: TaskState<Unit>, successFeedback: String) {
        val manga = _state.value.manga
        _state.update {
            it.copy(
                coverTask = result,
                coverFeedback = when (result) {
                    is TaskState.Success -> successFeedback
                    is TaskState.Failure ->
                        result.error.cause?.message
                            ?: MR.strings.desktop_ui_unable_to_update_cover.localized()
                    else -> null
                },
                coverLastModified = if (result is TaskState.Success) {
                    System.currentTimeMillis()
                } else {
                    it.coverLastModified
                },
                coverModel = if (result is TaskState.Success) {
                    resolveCoverModel?.invoke(mangaId, manga?.thumbnailUrl) ?: manga?.thumbnailUrl
                } else {
                    it.coverModel
                },
            )
        }
    }

    suspend fun setFetchInterval(mangaId: Long, interval: Int) {
        requireNotNull(updateManga) { "UpdateManga is required" }
            .await(MangaUpdate(id = mangaId, fetchInterval = if (interval == 0) 0 else -interval))
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

    fun enqueueDownloads(manga: Manga, chapters: List<Chapter>) {
        val enqueue = requireNotNull(enqueueDownload) { "Download enqueue callback is required" }
        chapters
            .filterNot { it.url.externalChapterUrlOrNull() != null }
            .filterNot { chapter -> isChapterDownloaded(manga, chapter) }
            .forEach { chapter ->
                enqueue(
                    DownloadItem(
                        sourceId = manga.source,
                        mangaTitle = manga.title,
                        chapterName = chapter.name,
                        chapterId = chapter.id,
                        chapterUrl = chapter.url,
                    ),
                )
            }
    }

    suspend fun enqueueDownloadBatch(manga: Manga, chapters: List<Chapter>): BatchChapterResult {
        val enqueue = requireNotNull(enqueueDownload) { "Download enqueue callback is required" }
        val eligible = chapters
            .filterNot { it.url.externalChapterUrlOrNull() != null }
            .filterNot { chapter -> isChapterDownloaded(manga, chapter) }
        return runChapterBatch(eligible) { chapter ->
            enqueue(
                DownloadItem(
                    sourceId = manga.source,
                    mangaTitle = manga.title,
                    chapterName = chapter.name,
                    chapterId = chapter.id,
                    chapterUrl = chapter.url,
                ),
            )
        }
    }

    fun deleteChapterDownload(manga: Manga, chapter: Chapter) {
        requireNotNull(deleteDownload) { "Delete download callback is required" }(manga, chapter)
    }

    suspend fun deleteDownloadBatch(manga: Manga, chapters: List<Chapter>): BatchChapterResult {
        val delete = requireNotNull(deleteDownload) { "Delete download callback is required" }
        return runChapterBatch(chapters) { chapter -> delete(manga, chapter) }
    }

    fun cancelChapterDownload(chapterId: Long) {
        requireNotNull(cancelDownload) { "Cancel download callback is required" }(chapterId)
    }

    fun retryChapterDownload(chapterId: Long) {
        requireNotNull(retryDownload) { "Retry download callback is required" }(chapterId)
    }

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
        val source = sourceFor(manga) ?: return
        requireNotNull(updateChecker) { "LibraryUpdateChecker is required" }.checkForUpdates(manga, source)
    }

    suspend fun migrateTo(targetSourceId: Long, item: SManga, fallbackTitle: String?) {
        requireNotNull(updateManga) { "UpdateManga is required" }
            .await(
                MangaUpdate(
                    id = mangaId,
                    source = targetSourceId,
                    url = item.url,
                    title = item.title.takeIf { it.isNotBlank() } ?: fallbackTitle,
                    thumbnailUrl = item.thumbnail_url,
                ),
            )
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
