package tachiyomi.domain.history.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HistoryState(
    val searchQuery: String? = null,
    val list: List<HistoryUiModel>? = null,
    val dialog: HistoryDialog? = null,
    val queryRevision: Long = 0,
    val loadedQueryRevision: Long? = null,
    val dialogRevision: Long = 0,
) {
    val items: List<HistoryWithRelations> get() = list.orEmpty().filterIsInstance<HistoryUiModel.Item>().map { it.item }
    val showClearAllDialog: Boolean get() = dialog == HistoryDialog.DeleteAll
}

sealed interface HistoryUiModel {
    data class Header(val date: LocalDate) : HistoryUiModel
    data class Item(val item: HistoryWithRelations) : HistoryUiModel
}

sealed interface HistoryDialog {
    data class Delete(val history: HistoryWithRelations) : HistoryDialog
    data object DeleteAll : HistoryDialog
    data class Duplicate(val manga: Manga, val duplicates: List<MangaWithChapterCount>) : HistoryDialog
    data class ChangeCategory(
        val manga: Manga,
        val categories: List<Category>,
        val selectedIds: List<Long>,
    ) : HistoryDialog
    data class Migrate(val current: Manga, val target: Manga) : HistoryDialog
}

sealed interface HistoryEvent {
    data class OpenChapter(val chapter: Chapter?, val requestToken: Long) : HistoryEvent
    data object InternalError : HistoryEvent
    data object HistoryCleared : HistoryEvent
}

/** The sole owner of history queries. Input never waits for a repository result. */
class HistoryController(
    scope: CoroutineScope,
    private val getHistory: GetHistory,
    private val removeHistory: RemoveHistory,
    private val getNextChapters: GetNextChapters? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val favoriteActions: HistoryFavoriteActions? = null,
) {
    private val mutableState = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = mutableState.asStateFlow()
    private val readerGate = Any()
    private var readerEpoch = 0L
    private var readerActive = true
    private var readerReadyToken: Long? = null
    private var readerReadyChapter: Long? = null
    private var readerDelivered = false

    fun activateReaderRequests() = synchronized(readerGate) {
        readerEpoch++
        readerActive = true
        readerReadyToken = null
        readerDelivered = false
    }

    fun cancelReaderRequests() = synchronized(readerGate) {
        readerEpoch++
        readerActive = false
        readerReadyToken = null
        readerDelivered = false
    }

    fun invalidateReaderRequests() = synchronized(readerGate) {
        readerEpoch++
        readerReadyToken = null
    }

    fun beginReaderRequest(): Long? = synchronized(readerGate) {
        if (!readerActive || readerDelivered || readerReadyToken != null) null else ++readerEpoch
    }

    fun finishReaderRequest(token: Long, chapterId: Long?): Boolean = synchronized(readerGate) {
        if (!readerActive || readerEpoch != token || readerDelivered || readerReadyToken != null) {
            false
        } else {
            readerReadyToken = token
            readerReadyChapter = chapterId
            true
        }
    }

    fun consumeReaderRequest(token: Long): Boolean = synchronized(readerGate) {
        if (!readerActive || readerEpoch != token || readerReadyToken != token) {
            false
        } else {
            readerReadyToken = null
            readerDelivered = readerReadyChapter != null
            true
        }
    }

    private val eventChannel = Channel<HistoryEvent>(Channel.UNLIMITED)
    val events = eventChannel.receiveAsFlow()
    private val observation: Job = scope.launch {
        state.map { it.searchQuery to it.queryRevision }.distinctUntilChanged()
            .flatMapLatest { (query, revision) ->
                getHistory.subscribe(query.orEmpty())
                    .distinctUntilChanged()
                    .map { revision to it }
                    .catch { error ->
                        if (error is CancellationException) throw error
                        eventChannel.send(HistoryEvent.InternalError)
                    }
            }
            .collect { (revision, items) ->
                mutableState.update {
                    if (it.queryRevision == revision) {
                        it.copy(list = group(items), loadedQueryRevision = revision)
                    } else {
                        it
                    }
                }
            }
    }

    fun updateSearchQuery(query: String?) {
        mutableState.update {
            if (it.searchQuery == query) {
                it
            } else {
                it.copy(searchQuery = query, queryRevision = it.queryRevision + 1)
            }
        }
    }

    fun refresh(query: String? = state.value.searchQuery) {
        mutableState.update { it.copy(searchQuery = query, queryRevision = it.queryRevision + 1) }
    }

    fun setDialog(dialog: HistoryDialog?) {
        if (dialog != null) invalidateReaderRequests()
        mutableState.update { it.copy(dialog = dialog, dialogRevision = it.dialogRevision + 1) }
    }

    suspend fun internalError() {
        eventChannel.send(HistoryEvent.InternalError)
    }

    suspend fun addFavorite(mangaId: Long, allowDuplicate: Boolean = false) = favoriteOperation {
        requireNotNull(favoriteActions).add(mangaId, allowDuplicate, ::setDialog)
    }

    suspend fun confirmCategory(manga: Manga, categories: List<Long>) = favoriteOperation {
        requireNotNull(favoriteActions).confirm(manga, categories)
        setDialog(null)
    }

    suspend fun confirmCategory() {
        val dialog = state.value.dialog as? HistoryDialog.ChangeCategory ?: return
        confirmCategory(dialog.manga, dialog.selectedIds)
    }

    fun selectCategory(categoryId: Long, selected: Boolean) {
        mutableState.update { current ->
            val dialog = current.dialog as? HistoryDialog.ChangeCategory ?: return@update current
            current.copy(
                dialog = dialog.copy(
                    selectedIds = if (selected) {
                        (dialog.selectedIds + categoryId).distinct()
                    } else {
                        dialog.selectedIds - categoryId
                    },
                ),
            )
        }
    }

    fun showMigration(current: Manga, target: Manga) = setDialog(HistoryDialog.Migrate(current, target))

    suspend fun refreshCategoryChoices() = favoriteOperation {
        val dialog = state.value.dialog as? HistoryDialog.ChangeCategory ?: return@favoriteOperation
        val revision = state.value.dialogRevision
        val categories = requireNotNull(favoriteActions).categories()
        mutableState.update { current ->
            val live = current.dialog as? HistoryDialog.ChangeCategory
            if (current.dialogRevision != revision || live?.manga?.id != dialog.manga.id) {
                current
            } else {
                current.copy(
                    dialog = live.copy(
                        categories = categories,
                        selectedIds = live.selectedIds.filter { id -> categories.any { it.id == id } },
                    ),
                )
            }
        }
    }

    private suspend fun favoriteOperation(action: suspend () -> Unit) {
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            internalError()
        }
    }

    suspend fun nextChapter(mangaId: Long, chapterId: Long): Chapter? =
        requireNotNull(getNextChapters).await(mangaId, chapterId, onlyUnread = false).firstOrNull()

    suspend fun latestChapter(): Chapter? = requireNotNull(getNextChapters).await(onlyUnread = false).firstOrNull()

    suspend fun resume(mangaId: Long, chapterId: Long) = deliverReader { nextChapter(mangaId, chapterId) }

    suspend fun resume(token: Long, mangaId: Long, chapterId: Long) = deliverReader(token) {
        nextChapter(mangaId, chapterId)
    }

    suspend fun resumeLatest(token: Long) = deliverReader(token) { latestChapter() }

    private suspend fun deliverReader(select: suspend () -> Chapter?) {
        val token = beginReaderRequest() ?: return
        deliverReader(token, select)
    }

    private suspend fun deliverReader(token: Long, select: suspend () -> Chapter?) {
        val chapter = select()
        if (finishReaderRequest(token, chapter?.id)) eventChannel.trySend(HistoryEvent.OpenChapter(chapter, token))
    }

    suspend fun remove(item: HistoryWithRelations, all: Boolean = false) {
        if (all) removeHistory.await(item.mangaId) else removeHistory.await(item)
    }

    suspend fun clear(): Boolean {
        val success = removeHistory.awaitAll()
        if (success) eventChannel.send(HistoryEvent.HistoryCleared)
        return success
    }

    fun close() {
        cancelReaderRequests()
        observation.cancel()
        eventChannel.close()
    }

    private fun group(items: List<HistoryWithRelations>): List<HistoryUiModel> = buildList {
        var previous: LocalDate? = null
        items.forEach { item ->
            val date = item.readAt?.let { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }
            if (date != null && date != previous) add(HistoryUiModel.Header(date))
            add(HistoryUiModel.Item(item))
            previous = date
        }
    }
}
