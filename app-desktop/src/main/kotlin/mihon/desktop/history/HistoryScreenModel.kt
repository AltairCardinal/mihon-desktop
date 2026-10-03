package mihon.desktop.history

import cafe.adriel.voyager.core.model.ScreenModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.service.HistoryController
import tachiyomi.domain.manga.interactor.GetManga

typealias HistoryState = tachiyomi.domain.history.service.HistoryState

typealias HistoryReaderRequest = mihon.desktop.reader.DesktopReaderOpenContext

class HistoryScreenModel(
    private val getHistory: GetHistory,
    private val removeHistory: RemoveHistory,
    private val getManga: GetManga,
    private val readingProgress: tachiyomi.domain.reader.interactor.RecordReadingProgress? = null,
    private val getChapters: tachiyomi.domain.chapter.interactor.GetChaptersByMangaId? = null,
    private val observationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val getNextChapters: tachiyomi.domain.history.interactor.GetNextChapters,
    private val isDownloaded: (tachiyomi.domain.manga.model.Manga, tachiyomi.domain.chapter.model.Chapter) -> Boolean = { _, _ -> false },
    private val favoriteActions: tachiyomi.domain.history.service.HistoryFavoriteActions? = null,
) : ScreenModel {

    val controller = HistoryController(observationScope, getHistory, removeHistory, getNextChapters, favoriteActions = favoriteActions)
    val state = controller.state

    fun updateSearchQuery(query: String?) {
        controller.updateSearchQuery(query)
    }

    fun cancelRead() {
        controller.activateReaderRequests()
    }

    override fun onDispose() {
        cancelRead()
        controller.close()
        observationScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    suspend fun loadHistory(query: String? = state.value.searchQuery) {
        controller.refresh(query)
        val revision = controller.state.value.queryRevision
        controller.state.first { it.searchQuery == query && it.loadedQueryRevision == revision && it.list != null }
    }

    fun setShowClearAllDialog(show: Boolean) {
        controller.setDialog(if (show) tachiyomi.domain.history.service.HistoryDialog.DeleteAll else null)
    }

    suspend fun removeHistory(item: HistoryWithRelations) {
        controller.invalidateReaderRequests()
        removeHistory.await(item)
        loadHistory()
    }

    suspend fun clearAllHistory() {
        controller.invalidateReaderRequests()
        val result = controller.clear()
        controller.setDialog(null)
        if (result) loadHistory()
    }

    suspend fun readerRequestFor(item: HistoryWithRelations): HistoryReaderRequest? = consumeDelivery(item)

    suspend fun latestReaderRequest(): HistoryReaderRequest? = consumeDelivery(null)

    private suspend fun consumeDelivery(item: HistoryWithRelations?): HistoryReaderRequest? {
        val token = controller.beginReaderRequest() ?: return null
        val delivery = readerDeliveryFor(item, token) ?: return null
        if (!controller.consumeReaderRequest(token)) return null
        if (delivery.internalError) controller.internalError()
        return delivery.request
    }

    internal suspend fun readerDeliveryFor(item: HistoryWithRelations?, token: Long): HistoryReaderDelivery? {
        try {
            val chapter = if (item == null) controller.latestChapter() else controller.nextChapter(item.mangaId, item.chapterId)
            if (chapter == null) return finishDelivery(token, null)
            val manga = getManga.awaitOrThrow(chapter.mangaId) ?: return finishDelivery(token, null)
            val chapters = getChapters?.awaitOrThrow(manga.id, applyScanlatorFilter = true) ?: listOf(chapter)
            val request = mihon.desktop.reader.selectedDesktopReaderOpenContext(manga, chapters, chapter, readingProgress, isDownloaded)
            if (controller.state.value.list != null) {
                val rows = getHistory.await(chapter.mangaId)
                if (rows.none { (it.readAt?.time ?: 0) > 0 && (item == null || (it.id == item.id && it.chapterId == item.chapterId)) }) return null
            }
            return finishDelivery(token, request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return finishDelivery(token, null, internalError = true)
        }
    }

    private fun finishDelivery(token: Long, request: HistoryReaderRequest?, internalError: Boolean = false): HistoryReaderDelivery? =
        if (controller.finishReaderRequest(token, request?.chapterId)) HistoryReaderDelivery(request, internalError) else null
}

internal data class HistoryReaderDelivery(val request: HistoryReaderRequest?, val internalError: Boolean = false)
