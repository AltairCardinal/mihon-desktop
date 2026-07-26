package mihon.desktop.test.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import mihon.domain.task.TaskState
import mihon.desktop.domain.SortMode
import mihon.desktop.library.MangaDetailScreenModelFactory
import mihon.desktop.test.navigation.TestNavigationController
import mihon.desktop.ui.library.LibraryFilterField
import mihon.desktop.ui.library.LibraryScreenModel
import mihon.desktop.ui.library.MangaDetailScreenModel
import tachiyomi.domain.library.interactor.LibraryFilter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.BatchChapterResult
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.LibraryMembershipResult
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class LibraryTestRow(
    val mangaId: Long,
    val title: String,
)

@Serializable
data class LibraryTestSnapshot(
    val loadState: OwnerLoadState,
    val loadError: String?,
    val searchQuery: String,
    val sortMode: String,
    val sortAscending: Boolean,
    val selectedCategoryIndex: Int,
    val rows: List<LibraryTestRow>,
)

@Serializable
enum class OwnerLoadState { LOADING, READY, FAILED, CLOSED }

@Serializable
data class MangaDetailTestSnapshot(
    val loadState: OwnerLoadState,
    val loadError: String?,
    val mangaId: Long,
    val title: String,
    val favorite: Boolean,
    val chapters: List<Long>,
    val categoryIds: List<Long>,
    val lastSucceededChapterIds: List<Long>,
    val lastFailedChapterIds: List<Long>,
    val coverFeedback: String?,
)

@Serializable
enum class LibraryMangaActionFailureCode {
    MISSING_PARAMETER,
    INVALID_PARAMETER,
    ROW_NOT_FOUND,
    DETAIL_NOT_OPEN,
    ACTION_UNAVAILABLE,
    OPERATION_REJECTED,
    PARTIAL_FAILURE,
    LIBRARY_LOADING,
    LIBRARY_UNAVAILABLE,
    DETAIL_LOADING,
    DETAIL_NOT_FOUND,
    DETAIL_LOAD_FAILED,
    PORT_CLOSED,
    UNSUPPORTED_ACTION,
}

@Serializable
data class LibraryMangaActionResult(
    val success: Boolean,
    val snapshot: LibraryTestSnapshot,
    val failureCode: LibraryMangaActionFailureCode? = null,
)

class LibraryMangaTestModeController(
    internal val libraryModel: LibraryScreenModel,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val libraryLoadTimeoutMillis: Long = 1_000L,
    private val detailLoadTimeoutMillis: Long = 2_000L,
    private val detailFactory: (Long) -> MangaDetailScreenModel = MangaDetailScreenModelFactory::create,
) {
    @Volatile
    internal var detailModel: MangaDetailScreenModel? = null
        private set
    @Volatile
    private var libraryLoadState = OwnerLoadState.LOADING
    @Volatile
    private var libraryLoadError: String? = null
    @Volatile
    private var detailLoadState = OwnerLoadState.CLOSED
    @Volatile
    private var detailLoadError: String? = null
    @Volatile
    private var detailAttempted = false
    private var categoryIds = emptyList<Long>()
    private var lastSucceededChapterIds = emptyList<Long>()
    private var lastFailedChapterIds = emptyList<Long>()
    private val closed = AtomicBoolean(false)
    private val libraryReady = CompletableDeferred<LibraryMangaActionFailureCode?>()
    private var detailJob: Job? = null
    private val libraryJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            libraryModel.libraryMangaFlow().collect {
                libraryModel.refreshCategories()
                libraryLoadError = null
                libraryLoadState = OwnerLoadState.READY
                if (!libraryReady.isCompleted) libraryReady.complete(null)
            }
            if (!closed.get()) {
                libraryLoadError = "Library observation completed"
                libraryLoadState = OwnerLoadState.FAILED
                if (!libraryReady.isCompleted) {
                    libraryReady.complete(LibraryMangaActionFailureCode.LIBRARY_UNAVAILABLE)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            libraryLoadError = failure.message ?: failure::class.simpleName ?: "Library observation failed"
            libraryLoadState = OwnerLoadState.FAILED
            if (!libraryReady.isCompleted) {
                libraryReady.complete(LibraryMangaActionFailureCode.LIBRARY_UNAVAILABLE)
            }
        }
    }

    fun snapshot(): LibraryTestSnapshot = LibraryTestSnapshot(
        loadState = libraryLoadState,
        loadError = libraryLoadError,
        searchQuery = libraryModel.state.value.searchQuery,
        sortMode = libraryModel.state.value.sortMode.name,
        sortAscending = libraryModel.state.value.sortAscending,
        selectedCategoryIndex = libraryModel.state.value.selectedCategoryIndex,
        rows = visibleItems().map { LibraryTestRow(it.id, it.manga.title) },
    )

    fun detailSnapshot(): MangaDetailTestSnapshot? = detailModel?.let { model ->
        val state = model.state.value
        val manga = state.manga
        MangaDetailTestSnapshot(
            loadState = detailLoadState,
            loadError = detailLoadError,
            mangaId = model.mangaId,
            title = manga?.title.orEmpty(),
            favorite = manga?.favorite ?: false,
            chapters = state.chapters.map { it.id },
            categoryIds = categoryIds,
            lastSucceededChapterIds = lastSucceededChapterIds,
            lastFailedChapterIds = lastFailedChapterIds,
            coverFeedback = state.coverFeedback,
        )
    }

    suspend fun execute(action: String, params: Map<String, String>): LibraryMangaActionResult {
        if (closed.get()) return failure(LibraryMangaActionFailureCode.PORT_CLOSED)
        libraryFailure(action, params)?.let { return failure(it) }
        detailFailure(action, params)?.let { return failure(it) }
        val failure = when (action) {
            "search" -> {
                val query = params["query"] ?: return failure(LibraryMangaActionFailureCode.MISSING_PARAMETER)
                libraryModel.setSearchQuery(query)
                null
            }
            "filter" -> filter(params)
            "sort" -> sort(params)
            "select" -> select(params)
            "open_manga_detail" -> openById(params)
            "addToLibrary" -> updateMembership(favorite = true)
            "removeFromLibrary" -> updateMembership(favorite = false)
            "detail_categories" -> updateCategories(params)
            "detail_chapter" -> updateChapters(params)
            "detail_cover" -> updateCover(params)
            "download" -> download(params)
            else -> LibraryMangaActionFailureCode.UNSUPPORTED_ACTION
        }
        return if (failure == null) {
            LibraryMangaActionResult(success = true, snapshot())
        } else {
            failure(failure)
        }
    }

    private fun failure(code: LibraryMangaActionFailureCode) =
        LibraryMangaActionResult(success = false, snapshot(), code)

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        libraryLoadState = OwnerLoadState.CLOSED
        detailLoadState = OwnerLoadState.CLOSED
        if (!libraryReady.isCompleted) libraryReady.complete(LibraryMangaActionFailureCode.PORT_CLOSED)
        scope.cancel()
        LibraryMangaTestModeBridge.clear(this)
    }

    suspend fun closeAndJoin() {
        close()
        libraryJob.join()
        detailJob?.join()
    }

    private fun filter(params: Map<String, String>): LibraryMangaActionFailureCode? {
        when (params["type"]) {
            "clear" -> libraryModel.setFilter(LibraryFilter())
            "unread" -> libraryModel.toggleFilter(LibraryFilterField.UNREAD)
            "started" -> libraryModel.toggleFilter(LibraryFilterField.STARTED)
            "completed" -> libraryModel.toggleFilter(LibraryFilterField.COMPLETED)
            "downloaded" -> libraryModel.toggleFilter(LibraryFilterField.DOWNLOADED)
            "bookmarked" -> libraryModel.toggleFilter(LibraryFilterField.BOOKMARKED)
            null -> return LibraryMangaActionFailureCode.MISSING_PARAMETER
            else -> return LibraryMangaActionFailureCode.INVALID_PARAMETER
        }
        return null
    }

    private fun sort(params: Map<String, String>): LibraryMangaActionFailureCode? {
        val mode = when (params["mode"]) {
            "title" -> SortMode.TITLE
            "lastRead" -> SortMode.LAST_READ
            "dateAdded" -> SortMode.DATE_ADDED
            "unreadCount" -> SortMode.UNREAD_COUNT
            null -> return LibraryMangaActionFailureCode.MISSING_PARAMETER
            else -> return LibraryMangaActionFailureCode.INVALID_PARAMETER
        }
        val ascending = params["ascending"]?.toBooleanStrictOrNull() ?: libraryModel.state.value.sortAscending
        libraryModel.setSortModeAndDirection(mode, ascending)
        return null
    }

    private suspend fun select(params: Map<String, String>): LibraryMangaActionFailureCode? {
        val index = params["index"]?.toIntOrNull() ?: return LibraryMangaActionFailureCode.MISSING_PARAMETER
        when (params["type"]) {
            "category" -> {
                if (index !in libraryModel.state.value.categories.indices) return LibraryMangaActionFailureCode.ROW_NOT_FOUND
                libraryModel.setSelectedCategoryIndex(index)
                return null
            }
            "chapter" -> return selectChapter(index)
            null, "manga" -> Unit
            else -> return LibraryMangaActionFailureCode.INVALID_PARAMETER
        }
        val item = visibleItems().getOrNull(index) ?: return LibraryMangaActionFailureCode.ROW_NOT_FOUND
        return open(item)
    }

    private suspend fun openById(params: Map<String, String>): LibraryMangaActionFailureCode? {
        val mangaId = params["mangaId"]?.toLongOrNull() ?: return LibraryMangaActionFailureCode.MISSING_PARAMETER
        val item = libraryModel.state.value.allItems.firstOrNull { it.id == mangaId }
            ?: return LibraryMangaActionFailureCode.ROW_NOT_FOUND
        return open(item)
    }

    private suspend fun open(item: LibraryManga): LibraryMangaActionFailureCode? {
        detailJob?.cancelAndJoin()
        detailAttempted = true
        detailLoadState = OwnerLoadState.LOADING
        detailLoadError = null
        detailModel = null
        categoryIds = emptyList()
        lastSucceededChapterIds = emptyList()
        lastFailedChapterIds = emptyList()
        val model = try {
            detailFactory(item.id)
        } catch (failure: Throwable) {
            detailLoadState = OwnerLoadState.FAILED
            detailLoadError = failure.message ?: failure::class.simpleName
            return LibraryMangaActionFailureCode.DETAIL_LOAD_FAILED
        }
        detailModel = model
        categoryIds = item.categories
        val first = CompletableDeferred<LibraryMangaActionFailureCode?>()
        detailJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                model.mangaWithChaptersFlow().collect { (manga, chapters) ->
                    model.setManga(manga)
                    model.setChapters(chapters)
                    detailLoadError = null
                    detailLoadState = OwnerLoadState.READY
                    if (!first.isCompleted) first.complete(null)
                }
                if (!closed.get()) {
                    detailLoadError = "Manga detail observation completed"
                    detailLoadState = OwnerLoadState.FAILED
                    if (!first.isCompleted) first.complete(LibraryMangaActionFailureCode.DETAIL_NOT_FOUND)
                }
            } catch (cancelled: CancellationException) {
                if (!first.isCompleted) {
                    first.complete(
                        if (closed.get()) {
                            LibraryMangaActionFailureCode.PORT_CLOSED
                        } else {
                            LibraryMangaActionFailureCode.DETAIL_LOADING
                        },
                    )
                }
                throw cancelled
            } catch (failure: Throwable) {
                detailLoadError = failure.message ?: failure::class.simpleName ?: "Manga detail observation failed"
                detailLoadState = OwnerLoadState.FAILED
                if (!first.isCompleted) first.complete(LibraryMangaActionFailureCode.DETAIL_LOAD_FAILED)
            }
        }
        val outcome = withTimeoutOrNull(detailLoadTimeoutMillis) { true to first.await() }
            ?: return LibraryMangaActionFailureCode.DETAIL_LOADING
        val result = outcome.second
        if (result == null) TestNavigationController.navigateToMangaDetail(item.id)
        return result
    }

    private fun selectChapter(index: Int): LibraryMangaActionFailureCode? {
        val model = detailModel ?: return LibraryMangaActionFailureCode.DETAIL_NOT_OPEN
        val state = model.state.value
        val manga = state.manga ?: return LibraryMangaActionFailureCode.DETAIL_NOT_OPEN
        val chapter = state.chapters.getOrNull(index) ?: return LibraryMangaActionFailureCode.ROW_NOT_FOUND
        val request = model.readerRequest(manga, state.chapters, chapter)
            ?: return LibraryMangaActionFailureCode.ACTION_UNAVAILABLE
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

    private fun visibleItems(): List<LibraryManga> {
        val state = libraryModel.state.value
        val categoryId = state.categories.getOrNull(state.selectedCategoryIndex)?.id
        return libraryModel.visibleItems(categoryId)
    }

    private suspend fun updateMembership(favorite: Boolean): LibraryMangaActionFailureCode? {
        val model = detailModel ?: return LibraryMangaActionFailureCode.DETAIL_NOT_OPEN
        val manga = model.state.value.manga ?: return LibraryMangaActionFailureCode.DETAIL_NOT_OPEN
        if (manga.favorite == favorite) return LibraryMangaActionFailureCode.ACTION_UNAVAILABLE
        return when (model.toggleLibrary(manga)) {
            is LibraryMembershipResult.Failure -> LibraryMangaActionFailureCode.OPERATION_REJECTED
            is LibraryMembershipResult.Success -> {
                val observed = withTimeoutOrNull(detailLoadTimeoutMillis) {
                    model.state.first { it.manga?.favorite == favorite }
                }
                if (observed == null) LibraryMangaActionFailureCode.DETAIL_LOADING else null
            }
        }
    }

    private suspend fun updateCategories(params: Map<String, String>): LibraryMangaActionFailureCode? {
        val model = detailModel ?: return LibraryMangaActionFailureCode.DETAIL_NOT_OPEN
        val rawIds = params["categoryIds"] ?: return LibraryMangaActionFailureCode.MISSING_PARAMETER
        val idTokens = rawIds.split(',').filter(String::isNotBlank)
        val ids = idTokens.mapNotNull(String::toLongOrNull)
        if (ids.size != idTokens.size) return LibraryMangaActionFailureCode.INVALID_PARAMETER
        if (model.state.value.manga == null) return LibraryMangaActionFailureCode.DETAIL_NOT_OPEN
        return when (model.setCategoriesForManga(model.mangaId, ids)) {
            SetMangaCategories.Result.Success -> {
                categoryIds = ids.distinct()
                null
            }
            is SetMangaCategories.Result.InternalError -> LibraryMangaActionFailureCode.OPERATION_REJECTED
        }
    }

    private suspend fun updateChapters(params: Map<String, String>): LibraryMangaActionFailureCode? {
        val model = detailModel ?: return LibraryMangaActionFailureCode.DETAIL_NOT_OPEN
        val chapters = selectedChapters(model, params) ?: return LibraryMangaActionFailureCode.ROW_NOT_FOUND
        val result = when (params["operation"]) {
            "read" -> model.markSelectedRead(chapters, read = params["read"]?.toBooleanStrictOrNull() ?: true)
            "bookmark" -> model.markSelectedBookmark(chapters)
            null -> return LibraryMangaActionFailureCode.MISSING_PARAMETER
            else -> return LibraryMangaActionFailureCode.INVALID_PARAMETER
        }
        return recordBatch(result)
    }

    private suspend fun updateCover(params: Map<String, String>): LibraryMangaActionFailureCode? {
        val model = detailModel ?: return LibraryMangaActionFailureCode.DETAIL_NOT_OPEN
        when (params["operation"]) {
            "delete" -> model.deleteCustomCover()
            null -> return LibraryMangaActionFailureCode.MISSING_PARAMETER
            else -> return LibraryMangaActionFailureCode.INVALID_PARAMETER
        }
        return if (model.state.value.coverTask is TaskState.Success) {
            null
        } else {
            LibraryMangaActionFailureCode.OPERATION_REJECTED
        }
    }

    private suspend fun download(params: Map<String, String>): LibraryMangaActionFailureCode? {
        val model = detailModel ?: return LibraryMangaActionFailureCode.DETAIL_NOT_OPEN
        val manga = model.state.value.manga ?: return LibraryMangaActionFailureCode.DETAIL_NOT_OPEN
        val chapters = selectedChapters(model, params) ?: return LibraryMangaActionFailureCode.ROW_NOT_FOUND
        if (chapters.isEmpty()) return LibraryMangaActionFailureCode.ACTION_UNAVAILABLE
        return recordBatch(model.enqueueDownloadBatch(manga, chapters))
    }

    private fun selectedChapters(
        model: MangaDetailScreenModel,
        params: Map<String, String>,
    ): List<Chapter>? {
        val rawIds = params["chapterIds"] ?: return model.state.value.chapters
        val idTokens = rawIds.split(',').filter(String::isNotBlank)
        val requested = idTokens.mapNotNull(String::toLongOrNull).toSet()
        if (requested.size != idTokens.size) return null
        val selected = model.state.value.chapters.filter { it.id in requested }
        return selected.takeIf { it.size == requested.size }
    }

    private fun recordBatch(result: BatchChapterResult): LibraryMangaActionFailureCode? {
        lastSucceededChapterIds = result.succeededIds
        lastFailedChapterIds = result.failures.map { it.id }
        return when {
            result.failures.isEmpty() -> null
            result.succeededIds.isNotEmpty() -> LibraryMangaActionFailureCode.PARTIAL_FAILURE
            else -> LibraryMangaActionFailureCode.OPERATION_REJECTED
        }
    }

    private suspend fun libraryFailure(
        action: String,
        params: Map<String, String>,
    ): LibraryMangaActionFailureCode? {
        val requiresLibrary = action in setOf("search", "filter", "sort", "open_manga_detail") ||
            action == "select" && params["type"] != "chapter"
        if (!requiresLibrary) return null
        return when (libraryLoadState) {
            OwnerLoadState.LOADING -> {
                val outcome = withTimeoutOrNull(libraryLoadTimeoutMillis) { true to libraryReady.await() }
                    ?: return LibraryMangaActionFailureCode.LIBRARY_LOADING
                outcome.second
            }
            OwnerLoadState.FAILED -> LibraryMangaActionFailureCode.LIBRARY_UNAVAILABLE
            OwnerLoadState.CLOSED -> LibraryMangaActionFailureCode.PORT_CLOSED
            OwnerLoadState.READY -> null
        }
    }

    private fun detailFailure(
        action: String,
        params: Map<String, String>,
    ): LibraryMangaActionFailureCode? {
        val requiresDetail = action in setOf(
            "addToLibrary",
            "removeFromLibrary",
            "detail_categories",
            "detail_chapter",
            "detail_cover",
            "download",
        ) || action == "select" && params["type"] == "chapter"
        if (!requiresDetail) return null
        if (!detailAttempted) return LibraryMangaActionFailureCode.DETAIL_NOT_OPEN
        return when (detailLoadState) {
            OwnerLoadState.LOADING -> LibraryMangaActionFailureCode.DETAIL_LOADING
            OwnerLoadState.FAILED -> LibraryMangaActionFailureCode.DETAIL_LOAD_FAILED
            OwnerLoadState.CLOSED -> LibraryMangaActionFailureCode.PORT_CLOSED
            OwnerLoadState.READY -> {
                if (detailModel == null) LibraryMangaActionFailureCode.DETAIL_NOT_OPEN else null
            }
        }
    }

}

object LibraryMangaTestModeBridge {
    private val value = AtomicReference<LibraryMangaTestModeController?>()
    val controller: LibraryMangaTestModeController? get() = value.get()

    fun install(controller: LibraryMangaTestModeController) {
        value.set(controller)
    }

    fun clear(expected: LibraryMangaTestModeController): Boolean = value.compareAndSet(expected, null)
}
