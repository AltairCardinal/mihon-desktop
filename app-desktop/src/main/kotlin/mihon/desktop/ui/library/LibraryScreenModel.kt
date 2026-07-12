package mihon.desktop.ui.library

import cafe.adriel.voyager.core.model.ScreenModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import mihon.desktop.domain.DesktopCategoryManager
import mihon.desktop.domain.LibraryUpdateChecker
import mihon.desktop.download.DownloadItem
import mihon.desktop.domain.SortMode
import mihon.desktop.domain.batchSetCategories
import mihon.desktop.download.DesktopDownloadPreferences
import mihon.desktop.download.DesktopDownloadProvider
import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.ReaderNavigator
import mihon.desktop.settings.LibraryCategoryPrefs
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

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

class LibraryScreenModel(
    private val getLibraryManga: GetLibraryManga? = null,
    private val categoryManager: DesktopCategoryManager? = null,
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
    private val startBackgroundUpdate: (() -> Job)? = null,
    private val cancelBackgroundUpdate: (() -> Boolean)? = null,
) : ScreenModel {

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    // ── Data loading ──────────────────────────────────────────────────────────

    fun libraryMangaFlow(): Flow<List<LibraryManga>> =
        requireNotNull(getLibraryManga) { "GetLibraryManga is required" }.subscribe()

    suspend fun refreshCategories() {
        setCategories(requireNotNull(categoryManager) { "DesktopCategoryManager is required" }.getAll())
    }

    suspend fun createCategory(name: String) {
        requireNotNull(categoryManager) { "DesktopCategoryManager is required" }.create(name)
        refreshCategories()
    }

    suspend fun renameCategory(categoryId: Long, name: String) {
        requireNotNull(categoryManager) { "DesktopCategoryManager is required" }.rename(categoryId, name)
        refreshCategories()
    }

    suspend fun deleteCategory(categoryId: Long) {
        requireNotNull(categoryManager) { "DesktopCategoryManager is required" }.delete(categoryId)
        refreshCategories()
    }

    suspend fun reorderCategory(categoryId: Long, newIndex: Int) {
        requireNotNull(categoryManager) { "DesktopCategoryManager is required" }.reorder(categoryId, newIndex)
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

    fun setFilters(unread: Boolean, started: Boolean, completed: Boolean, downloaded: Boolean) {
        _state.update {
            it.copy(
                filterUnread = unread,
                filterStarted = started,
                filterCompleted = completed,
                filterDownloaded = downloaded,
            )
        }
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
                setUpdateStatusText("Library update finished")
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

    suspend fun continueReadingRequest(item: LibraryManga): LibraryReaderRequest? {
        val repository = requireNotNull(chapterRepository) { "ChapterRepository is required" }
        val chapters = repository.getChapterByMangaId(item.manga.id)
            .sortedBy { it.sourceOrder }
        val target = chapters.firstOrNull { !it.read }
            ?: chapters.maxByOrNull { it.sourceOrder }
            ?: return null
        val chapterRefs = chapters.map {
            ReaderChapterRef(id = it.id, url = it.url, name = it.name, isRead = it.read)
        }
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
        batchSetCategories(
            mangaIds = mangaIds,
            categoryIds = categoryIds,
            setMangaCategories = requireNotNull(setMangaCategories) { "SetMangaCategories is required" },
        )
    }

    suspend fun categoryIdsForManga(mangaId: Long): Set<Long> {
        val repository = requireNotNull(categoryRepository) { "CategoryRepository is required" }
        return repository.getCategoriesByMangaId(mangaId).map { it.id }.toSet()
    }
}
