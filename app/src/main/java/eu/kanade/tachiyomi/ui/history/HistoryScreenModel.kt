package eu.kanade.tachiyomi.ui.history

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.track.interactor.AddTracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.service.HistoryController
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class HistoryScreenModel(
    private val addTracks: AddTracks = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getHistory: GetHistory = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val removeHistory: RemoveHistory = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val updateMembership: tachiyomi.domain.manga.interactor.UpdateLibraryMembership = Injekt.get(),
    private val readerActionDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : ScreenModel {

    private val _events: Channel<Event> = Channel(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    val controller = HistoryController(
        screenModelScope,
        getHistory,
        removeHistory,
        getNextChapters,
        favoriteActions = tachiyomi.domain.history.service.HistoryFavoriteActions(
            getManga,
            getCategories,
            getDuplicateLibraryManga,
            libraryPreferences,
            updateMembership,
            bindEnhanced = { addTracks.bindEnhancedTrackers(it, sourceManager.getOrStub(it.source)) },
        ),
    )

    val state = controller.state

    init {
        screenModelScope.launch {
            controller.events.collect { event ->
                _events.send(
                    when (event) {
                        tachiyomi.domain.history.service.HistoryEvent.InternalError -> Event.InternalError
                        tachiyomi.domain.history.service.HistoryEvent.HistoryCleared -> Event.HistoryCleared
                        is tachiyomi.domain.history.service.HistoryEvent.OpenChapter -> Event.OpenChapter(
                            event.chapter,
                            event.requestToken,
                        )
                    },
                )
            }
        }
    }

    suspend fun getNextChapter(): Chapter? = controller.latestChapter()

    fun resumeLatest() {
        val token = controller.beginReaderRequest() ?: return
        screenModelScope.launch(readerActionDispatcher) { controller.resumeLatest(token) }
    }

    fun getNextChapterForManga(mangaId: Long, chapterId: Long) {
        val token = controller.beginReaderRequest() ?: return
        screenModelScope.launch(readerActionDispatcher) {
            controller.resume(token, mangaId, chapterId)
        }
    }

    fun removeFromHistory(history: HistoryWithRelations) {
        controller.invalidateReaderRequests()
        screenModelScope.launchIO {
            controller.remove(history)
        }
    }

    fun removeAllFromHistory(mangaId: Long) {
        controller.invalidateReaderRequests()
        screenModelScope.launchIO {
            removeHistory.await(mangaId)
        }
    }

    fun removeAllHistory() {
        controller.invalidateReaderRequests()
        screenModelScope.launchIO {
            val result = controller.clear()
            if (!result) return@launchIO
        }
    }

    fun updateSearchQuery(query: String?) {
        controller.updateSearchQuery(query)
    }

    fun setDialog(dialog: tachiyomi.domain.history.service.HistoryDialog?) {
        controller.setDialog(dialog)
    }

    fun addFavorite(mangaId: Long) {
        screenModelScope.launchIO { controller.addFavorite(mangaId) }
    }

    fun addFavorite(manga: Manga) {
        screenModelScope.launchIO { controller.addFavorite(manga.id, allowDuplicate = true) }
    }

    fun moveMangaToCategoriesAndAddToLibrary(manga: Manga, categories: List<Long>) {
        screenModelScope.launchIO { controller.confirmCategory(manga, categories) }
    }

    fun confirmCategory() {
        screenModelScope.launchIO { controller.confirmCategory() }
    }

    fun showMigrateDialog(target: Manga, current: Manga) {
        controller.showMigration(current, target)
    }

    sealed interface Event {
        data class OpenChapter(val chapter: Chapter?, val requestToken: Long) : Event
        data object InternalError : Event
        data object HistoryCleared : Event
    }
}
