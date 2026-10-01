package mihon.desktop.history

import cafe.adriel.voyager.core.model.ScreenModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import mihon.desktop.reader.externalChapterUrlOrNull
import mihon.desktop.ui.library.toReaderChapterRefs
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.interactor.GetManga

data class HistoryState(
    val searchQuery: String = "",
    val items: List<HistoryWithRelations> = emptyList(),
    val showClearAllDialog: Boolean = false,
    val readStatus: HistoryReadStatus? = null,
    val lastReadHistoryId: Long? = null,
)

data class HistoryReaderRequest(
    val chapterTitle: String,
    val mangaTitle: String,
    val sourceId: Long,
    val chapterUrl: String,
    val chapterId: Long,
    val mangaId: Long,
    val mangaViewerFlags: Long,
    val initialPage: Int,
    val resumeSnapshot: tachiyomi.domain.reader.model.ReadingSyncSnapshot? = null,
    val chapters: List<mihon.desktop.reader.ReaderChapterRef> = emptyList(),
    val currentChapterIndex: Int = 0,
    val chapterNumber: Double = 0.0,
)

class HistoryScreenModel(
    private val getHistory: GetHistory,
    private val removeHistory: RemoveHistory,
    private val getChapter: GetChapter,
    private val getManga: GetManga,
    private val readingProgress: tachiyomi.domain.reader.interactor.RecordReadingProgress? = null,
    private val getChapters: tachiyomi.domain.chapter.interactor.GetChaptersByMangaId? = null,
    private val prepareDirectory: (suspend (tachiyomi.domain.manga.model.Manga) -> mihon.desktop.extension.SourceCallResult<mihon.desktop.domain.PreparedChapterCatalog>)? = null,
    private val isDownloaded: (tachiyomi.domain.manga.model.Manga, tachiyomi.domain.chapter.model.Chapter) -> Boolean = { _, _ -> false },
) : ScreenModel {

    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()
    private var readGeneration = 0L
    private var deliveredHistoryId: Long? = null
    private var historyLoaded = false

    fun cancelRead() {
        readGeneration++
        deliveredHistoryId = null
        _state.update { it.copy(readStatus = null) }
    }

    override fun onDispose() {
        cancelRead()
    }

    suspend fun loadHistory(query: String = state.value.searchQuery) {
        val items = getHistory.subscribe(query).first()
        historyLoaded = true
        _state.update { it.copy(searchQuery = query, items = items) }
    }

    fun setShowClearAllDialog(show: Boolean) {
        _state.update { it.copy(showClearAllDialog = show) }
    }

    suspend fun removeHistory(item: HistoryWithRelations) {
        if (state.value.readStatus?.historyId == item.id) cancelRead()
        removeHistory.await(item)
        loadHistory()
    }

    suspend fun clearAllHistory() {
        cancelRead()
        removeHistory.awaitAll()
        _state.update { it.copy(items = emptyList(), showClearAllDialog = false) }
    }

    suspend fun readerRequestFor(item: HistoryWithRelations, useExisting: Boolean = false): HistoryReaderRequest? {
        if (deliveredHistoryId == item.id) return null
        if (state.value.readStatus?.let { it.historyId == item.id && it.loading } == true) return null
        val prior = state.value.readStatus?.takeIf { it.historyId == item.id }
        val generation = ++readGeneration
        _state.update { it.copy(readStatus = HistoryReadStatus(item.id, loading = true)) }
        var known = emptyList<tachiyomi.domain.chapter.model.Chapter>()
        try {
            val manga = getManga.await(item.mangaId) ?: return fail(generation, item, HistoryReadFailure.TARGET_MISSING, false)
            known = getChapters?.awaitOrThrow(manga.id) ?: listOfNotNull(getChapter.await(item.chapterId))
            var directory = known
            if (!useExisting && prepareDirectory != null) {
                when (val result = prepareDirectory.invoke(manga)) {
                    is mihon.desktop.extension.SourceCallResult.Success -> directory = result.value.chapters
                    is mihon.desktop.extension.SourceCallResult.Error -> return fail(generation, item, failureFor(result.error), known.isNotEmpty(), result.error)
                    is mihon.desktop.extension.SourceCallResult.Timeout -> return fail(generation, item, HistoryReadFailure.SOURCE, known.isNotEmpty(), result.error)
                }
            }
            if (generation != readGeneration) return null
            // Resolve only after preparation: a synchronization arriving during fetch wins on next entry.
            val currentManga = getManga.await(manga.id) ?: return fail(generation, item, HistoryReadFailure.TARGET_MISSING, known.isNotEmpty())
            check(currentManga.source == manga.source && currentManga.url == manga.url) { "Source manga identity conflict" }
            val resume = readingProgress?.resumePosition(manga.id)
            val resumed = resume?.let { getChapter.await(it.chapterId) }?.takeIf { it.mangaId == manga.id && it.url.externalChapterUrlOrNull() == null }
            val chapter = resumed ?: getChapter.await(item.chapterId)?.takeIf { it.mangaId == manga.id && it.url.externalChapterUrlOrNull() == null }
                ?: return fail(generation, item, HistoryReadFailure.TARGET_MISSING, known.isNotEmpty())
            if (useExisting && prior?.failure == HistoryReadFailure.TARGET_MISSING) directory = listOf(chapter)
            val verifiedIds = directory.map { it.id }.toSet()
            val latest = getChapters?.awaitOrThrow(manga.id, applyScanlatorFilter = true) ?: directory
            val filtered = latest.filter { it.id in verifiedIds && it.url.externalChapterUrlOrNull() == null }
            val readerChapters = (filtered + listOf(chapter).filter { it.id in verifiedIds && filtered.none { found -> found.id == it.id } }).sortedBy { it.sourceOrder }
            check(readerChapters.map { it.url }.distinct().size == readerChapters.size) { "Duplicate local chapter identity" }
            val refs = readerChapters.toReaderChapterRefs(chapter.id, currentManga) { isDownloaded(currentManga, it) }
            val index = refs.indexOfFirst { it.id == chapter.id }
            if (index < 0) return fail(generation, item, HistoryReadFailure.TARGET_MISSING, known.isNotEmpty())
            if (generation != readGeneration) return null
            if (historyLoaded && getHistory.await(item.mangaId).none { it.id == item.id && it.chapterId == item.chapterId && (it.readAt?.time ?: 0) > 0 }) {
                cancelRead()
                return null
            }
            if (generation != readGeneration) return null
            deliveredHistoryId = item.id
            _state.update { it.copy(readStatus = null, lastReadHistoryId = item.id) }
            return HistoryReaderRequest(
                chapterTitle = chapter.name, mangaTitle = currentManga.title, sourceId = currentManga.source,
                chapterUrl = chapter.url, chapterId = chapter.id, mangaId = currentManga.id,
                mangaViewerFlags = currentManga.viewerFlags,
                initialPage = if (resumed != null) requireNotNull(resume).pageIndex else chapter.lastPageRead.toInt().coerceAtLeast(0),
                resumeSnapshot = resume?.snapshot.takeIf { resumed != null },
                chapters = refs.toList(), currentChapterIndex = index, chapterNumber = chapter.chapterNumber,
            )
        } catch (error: CancellationException) {
            if (generation == readGeneration) cancelRead()
            throw error
        } catch (error: Exception) {
            return fail(generation, item, if (error.message.orEmpty().contains("identity", true)) HistoryReadFailure.IDENTITY else HistoryReadFailure.STORAGE, known.isNotEmpty(), mihon.domain.error.AppError.Storage(error))
        }
    }

    private fun failureFor(error: mihon.domain.error.AppError) = when {
        error.cause is mihon.desktop.domain.SourceCatalogUnavailableException -> HistoryReadFailure.SOURCE_UNAVAILABLE
        error.cause?.message.orEmpty().contains("identity", true) -> HistoryReadFailure.IDENTITY
        error is mihon.domain.error.AppError.Storage -> HistoryReadFailure.STORAGE
        else -> HistoryReadFailure.SOURCE
    }

    private fun fail(generation: Long, item: HistoryWithRelations, failure: HistoryReadFailure, hasKnown: Boolean, error: mihon.domain.error.AppError? = null): HistoryReaderRequest? {
        if (generation == readGeneration) _state.update { it.copy(readStatus = HistoryReadStatus(item.id, false, failure, error, hasKnown)) }
        return null
    }
}

enum class HistoryReadFailure { SOURCE_UNAVAILABLE, TARGET_MISSING, IDENTITY, STORAGE, SOURCE }
data class HistoryReadStatus(val historyId: Long, val loading: Boolean, val failure: HistoryReadFailure? = null, val error: mihon.domain.error.AppError? = null, val canUseExisting: Boolean = false)
