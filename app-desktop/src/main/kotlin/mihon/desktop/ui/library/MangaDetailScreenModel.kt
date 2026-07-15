package mihon.desktop.ui.library

import cafe.adriel.voyager.core.model.ScreenModel
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import mihon.desktop.download.DownloadItem
import mihon.desktop.domain.GetAvailableScanlators
import mihon.desktop.domain.GetExcludedScanlators
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.domain.SetExcludedScanlators
import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.ReaderNavigator
import mihon.desktop.reader.withDuplicateChapterFlags
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.externalChapterUrlOrNull
import mihon.desktop.reader.viewerFlagsWithReadingMode
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.interactor.BatchChapterResult
import tachiyomi.domain.chapter.interactor.BatchUpdateChapters
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.creator.model.CreatorRole
import tachiyomi.domain.creator.repository.CreatorRepository
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.UpdateLibraryMembership
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import mihon.domain.task.TaskState

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
    private val categoryRepository: CategoryRepository? = null,
    private val chapterRepository: ChapterRepository? = null,
    private val mangaRepository: MangaRepository? = null,
    private val setMangaCategories: SetMangaCategories? = null,
    private val creatorRepository: CreatorRepository? = null,
    private val enqueueDownload: ((DownloadItem) -> Unit)? = null,
    private val downloadQueue: StateFlow<List<DownloadItem>>? = null,
    private val isDownloaded: ((sourceId: Long, mangaTitle: String, chapterName: String) -> Boolean)? = null,
    private val deleteDownload: ((sourceId: Long, mangaTitle: String, chapterName: String) -> Unit)? = null,
    private val cancelDownload: ((chapterId: Long) -> Unit)? = null,
    private val batchUpdateChapters: BatchUpdateChapters = BatchUpdateChapters(),
    private val updateLibraryMembership: UpdateLibraryMembership? = null,
    private val coverAdapter: MangaCoverAdapter? = null,
    private val deleteCover: (suspend (Long) -> TaskState<Unit>)? = null,
    private val resolveCoverModel: ((Long, String?) -> String?)? = null,
) : ScreenModel {

    private val _state = MutableStateFlow(MangaDetailState())
    val state: StateFlow<MangaDetailState> = _state.asStateFlow()

    // ── Data loading ──────────────────────────────────────────────────────────

    suspend fun mangaWithChaptersFlow(): Flow<Pair<Manga, List<Chapter>>> {
        return requireNotNull(getMangaWithChapters) { "GetMangaWithChapters is required" }
            .subscribe(mangaId, applyScanlatorFilter = true)
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

    // ── Filter toggles ────────────────────────────────────────────────────────

    fun setFilterShowRead(show: Boolean) {
        _state.update { it.copy(filterShowRead = show) }
    }

    fun setFilterShowUnread(show: Boolean) {
        _state.update { it.copy(filterShowUnread = show) }
    }

    fun setFilterShowBookmarked(show: Boolean) {
        _state.update { it.copy(filterShowBookmarked = show) }
    }

    fun setFilterShowDownloaded(show: Boolean) {
        _state.update { it.copy(filterShowDownloaded = show) }
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
        _state.update { it.copy(showFilterMenu = !it.showFilterMenu) }
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
        requireNotNull(chapterRepository) { "ChapterRepository is required" }
            .updateAll(chapters.map { ChapterUpdate(id = it.id, read = true) })
    }

    suspend fun markSelectedRead(chapters: List<Chapter>, read: Boolean) {
        val repository = requireNotNull(chapterRepository) { "ChapterRepository is required" }
        runChapterBatch(chapters) { repository.update(ChapterUpdate(id = it.id, read = read)) }
    }

    suspend fun runChapterBatch(
        chapters: List<Chapter>,
        action: suspend (Chapter) -> Unit,
    ): BatchChapterResult = batchUpdateChapters.await(chapters, action).also { result ->
        _state.update {
            it.copy(batchActionMessage = "${result.succeededIds.size} succeeded, ${result.failures.size} failed")
        }
    }

    suspend fun markSelectedBookmark(chapters: List<Chapter>) {
        val shouldBookmark = chapters.any { !it.bookmark }
        val repository = requireNotNull(chapterRepository) { "ChapterRepository is required" }
        runChapterBatch(chapters) { repository.update(ChapterUpdate(id = it.id, bookmark = shouldBookmark)) }
    }

    suspend fun markAtOrBelowRead(displayedChapters: List<Chapter>, selectedIds: Set<Long>) {
        val repository = requireNotNull(chapterRepository) { "ChapterRepository is required" }
        chaptersAtOrBelowSelection(displayedChapters, selectedIds).forEach { chapter ->
            repository.update(ChapterUpdate(id = chapter.id, read = true))
        }
    }

    suspend fun toggleChapterBookmark(chapter: Chapter) {
        requireNotNull(chapterRepository) { "ChapterRepository is required" }
            .update(ChapterUpdate(id = chapter.id, bookmark = !chapter.bookmark))
    }

    suspend fun toggleChapterRead(chapter: Chapter) {
        requireNotNull(chapterRepository) { "ChapterRepository is required" }
            .update(ChapterUpdate(id = chapter.id, read = !chapter.read))
    }

    suspend fun toggleLibrary(manga: Manga, nowMillis: Long = System.currentTimeMillis()) {
        val useCase = updateLibraryMembership
            ?: UpdateLibraryMembership(requireNotNull(mangaRepository) { "MangaRepository is required" })
        useCase.await(manga, favorite = !manga.favorite, nowMillis = nowMillis)
    }

    suspend fun chooseCustomCover() {
        _state.update { it.copy(coverTask = TaskState.Running(), coverFeedback = null) }
        val result = requireNotNull(coverAdapter) { "Cover adapter is required" }.chooseAndUpdate(mangaId)
        if (result == null) {
            _state.update { it.copy(coverTask = TaskState.Idle) }
            return
        }
        applyCoverResult(result, "Cover updated")
    }

    suspend fun deleteCustomCover() {
        _state.update { it.copy(coverTask = TaskState.Running(), coverFeedback = null) }
        val result = requireNotNull(deleteCover) { "Delete cover callback is required" }(mangaId)
        applyCoverResult(result, "Cover deleted")
    }

    private fun applyCoverResult(result: TaskState<Unit>, successFeedback: String) {
        val manga = _state.value.manga
        _state.update {
            it.copy(
                coverTask = result,
                coverFeedback = when (result) {
                    is TaskState.Success -> successFeedback
                    is TaskState.Failure -> result.error.cause?.message ?: "Unable to update cover"
                    else -> null
                },
                coverLastModified = if (result is TaskState.Success) System.currentTimeMillis() else it.coverLastModified,
                coverModel = if (result is TaskState.Success) {
                    resolveCoverModel?.invoke(mangaId, manga?.thumbnailUrl) ?: manga?.thumbnailUrl
                } else {
                    it.coverModel
                },
            )
        }
    }

    suspend fun setFetchInterval(mangaId: Long, interval: Int) {
        requireNotNull(mangaRepository) { "MangaRepository is required" }
            .update(MangaUpdate(id = mangaId, fetchInterval = if (interval == 0) 0 else -interval))
    }

    suspend fun setReadingMode(mangaId: Long, currentFlags: Long, mode: ReadingMode?) {
        requireNotNull(mangaRepository) { "MangaRepository is required" }
            .update(MangaUpdate(id = mangaId, viewerFlags = viewerFlagsWithReadingMode(currentFlags, mode)))
    }

    suspend fun setChapterSort(manga: Manga, requestedMode: ChapterSortMode) {
        val (nextMode, nextAscending) = nextChapterSort(
            currentMode = _state.value.chapterSortMode,
            currentAscending = _state.value.chapterSortAscending,
            requestedMode = requestedMode,
        )
        requireNotNull(mangaRepository) { "MangaRepository is required" }
            .update(
                MangaUpdate(
                    id = manga.id,
                    chapterFlags = chapterSortFlags(
                        mode = nextMode,
                        ascending = nextAscending,
                        currentFlags = manga.chapterFlags,
                    ),
                ),
            )
        setSortMode(nextMode)
        setSortAscending(nextAscending)
    }

    suspend fun setChapterDisplayMode(manga: Manga, displayMode: Long) {
        requireNotNull(mangaRepository) { "MangaRepository is required" }
            .update(
                MangaUpdate(
                    id = manga.id,
                    chapterFlags = chapterDisplayFlags(
                        displayMode = displayMode,
                        currentFlags = manga.chapterFlags,
                    ),
                ),
            )
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
        requireNotNull(deleteDownload) { "Delete download callback is required" }(manga.source, manga.title, chapter.name)
    }

    suspend fun deleteDownloadBatch(manga: Manga, chapters: List<Chapter>): BatchChapterResult {
        val delete = requireNotNull(deleteDownload) { "Delete download callback is required" }
        return runChapterBatch(chapters) { chapter -> delete(manga.source, manga.title, chapter.name) }
    }

    fun cancelChapterDownload(chapterId: Long) {
        requireNotNull(cancelDownload) { "Cancel download callback is required" }(chapterId)
    }

    fun isChapterDownloaded(manga: Manga, chapter: Chapter): Boolean {
        return isDownloaded?.invoke(manga.source, manga.title, chapter.name) ?: false
    }

    fun readerRequest(
        manga: Manga,
        chapters: List<Chapter>,
        chapter: Chapter,
        visibleChapterIds: Set<Long>? = null,
    ): MangaDetailReaderRequest? {
        if (chapter.url.externalChapterUrlOrNull() != null) return null
        val chapterRefs = chapters
            .filterNot { it.url.externalChapterUrlOrNull() != null }
            .sortedBy { it.sourceOrder }
            .map {
                ReaderChapterRef(
                    id = it.id,
                    url = it.url,
                    name = it.name,
                    isRead = it.read,
                    chapterNumber = it.chapterNumber,
                    scanlator = it.scanlator,
                    isFiltered = visibleChapterIds != null && it.id !in visibleChapterIds,
                )
            }
            .withDuplicateChapterFlags(chapter.id)
        return MangaDetailReaderRequest(
            chapterTitle = chapter.name,
            mangaId = manga.id,
            mangaTitle = manga.title,
            sourceId = manga.source,
            chapterUrl = chapter.url,
            chapterId = chapter.id,
            chapters = chapterRefs,
            currentChapterIndex = ReaderNavigator.indexForId(chapterRefs, chapter.id),
            initialPage = chapter.lastPageRead.toInt().coerceAtLeast(0),
            mangaViewerFlags = manga.viewerFlags,
        )
    }

    suspend fun setCategoriesForManga(mangaId: Long, categoryIds: List<Long>) {
        requireNotNull(setMangaCategories) { "SetMangaCategories is required" }.await(mangaId, categoryIds)
    }

    suspend fun categories(): List<Category> {
        return requireNotNull(categoryRepository) { "CategoryRepository is required" }.getAll().sortedBy { it.order }
    }

    suspend fun categoryIdsForManga(mangaId: Long): Set<Long> {
        return requireNotNull(categoryRepository) { "CategoryRepository is required" }
            .getCategoriesByMangaId(mangaId)
            .map { it.id }
            .toSet()
    }

    suspend fun updateExcludedScanlators(excluded: Set<String>) {
        requireNotNull(setExcludedScanlators) { "SetExcludedScanlators is required" }.await(mangaId, excluded)
    }

    fun sourceFor(manga: Manga): CatalogueSource? {
        return requireNotNull(sourceManager) { "SourceManager is required" }
            .getCatalogueSources()
            .find { it.id == manga.source }
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
        requireNotNull(mangaRepository) { "MangaRepository is required" }
            .update(
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
        val repository = requireNotNull(creatorRepository) { "CreatorRepository is required" }
        val creator = repository.upsertCreator(name)
        repository.linkMangaCreator(
            mangaId = mangaId,
            creatorId = creator.id,
            role = role,
            sourceText = name,
            confidence = 1.0,
            evidence = "manga detail ${role.name.lowercase()}",
        )
        return creator.id
    }
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
)
