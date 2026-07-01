package mihon.desktop.history

import cafe.adriel.voyager.core.model.ScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.interactor.GetManga

data class HistoryState(
    val searchQuery: String = "",
    val items: List<HistoryWithRelations> = emptyList(),
    val showClearAllDialog: Boolean = false,
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
)

class HistoryScreenModel(
    private val getHistory: GetHistory,
    private val removeHistory: RemoveHistory,
    private val getChapter: GetChapter,
    private val getManga: GetManga,
) : ScreenModel {

    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    suspend fun loadHistory(query: String = state.value.searchQuery) {
        val items = getHistory.subscribe(query).first()
        _state.update { it.copy(searchQuery = query, items = items) }
    }

    fun setShowClearAllDialog(show: Boolean) {
        _state.update { it.copy(showClearAllDialog = show) }
    }

    suspend fun removeHistory(item: HistoryWithRelations) {
        removeHistory.await(item)
        loadHistory()
    }

    suspend fun clearAllHistory() {
        removeHistory.awaitAll()
        _state.update { it.copy(items = emptyList(), showClearAllDialog = false) }
    }

    suspend fun readerRequestFor(item: HistoryWithRelations): HistoryReaderRequest? {
        val chapter = getChapter.await(item.chapterId) ?: return null
        val manga = getManga.await(item.mangaId) ?: return null
        return HistoryReaderRequest(
            chapterTitle = chapter.name,
            mangaTitle = manga.title,
            sourceId = manga.source,
            chapterUrl = chapter.url,
            chapterId = chapter.id,
            mangaId = manga.id,
            mangaViewerFlags = manga.viewerFlags,
            initialPage = chapter.lastPageRead.toInt().coerceAtLeast(0),
        )
    }
}
