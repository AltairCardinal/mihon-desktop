package eu.kanade.tachiyomi.ui.deeplink

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.network.SourceCompatibilityException
import eu.kanade.tachiyomi.network.SourceOperationPhase
import eu.kanade.tachiyomi.network.guardSourceOperation
import eu.kanade.tachiyomi.network.rethrowSourceControlFailure
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.ResolvableSource
import eu.kanade.tachiyomi.source.online.UriType
import kotlinx.coroutines.flow.update
import mihon.domain.error.AppError
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.chapter.interactor.GetChapterByUrlAndMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.toSourceAppError
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DeepLinkScreenModel(
    query: String = "",
    private val sourceManager: SourceManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val getChapterByUrlAndMangaId: GetChapterByUrlAndMangaId = Injekt.get(),
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get(),
) : StateScreenModel<DeepLinkScreenModel.State>(State.Loading) {

    init {
        screenModelScope.launchIO {
            try {
                val source = sourceManager.firstResolvableSource(query)
                val sourceId = source?.let(sourceManager::getRegisteredSourceId)
                if (source != null && sourceId == null) {
                    mutableState.update { State.Failure(AppError.Unknown()) }
                    return@launchIO
                }

                val remoteManga = source?.let {
                    guardSourceOperation(SourceOperationPhase.DETAILS) { it.getManga(query) }
                }
                val manga = remoteManga?.let {
                    networkToLocalManga(it.toDomainManga(requireNotNull(sourceId), SourceOperationPhase.DETAILS))
                }

                val type = source?.let { guardSourceOperation(SourceOperationPhase.METADATA) { it.getUriType(query) } }
                val chapter = if (type == UriType.Chapter && manga != null) {
                    guardSourceOperation(SourceOperationPhase.CHAPTERS) { requireNotNull(source).getChapter(query) }
                        ?.let { getChapterFromSChapter(it, manga, requireNotNull(source)) }
                } else {
                    null
                }

                mutableState.update {
                    if (manga == null) {
                        State.NoResults
                    } else {
                        if (chapter == null) {
                            State.Result(manga)
                        } else {
                            State.Result(manga, chapter.id)
                        }
                    }
                }
            } catch (error: SourceCompatibilityException) {
                error.rethrowSourceControlFailure()
                mutableState.update { State.Failure(error.toSourceAppError()) }
            }
        }
    }

    private suspend fun getChapterFromSChapter(sChapter: SChapter, manga: Manga, source: Source): Chapter? {
        val chapterUrl = guardSourceOperation(SourceOperationPhase.CHAPTERS) { sChapter.url }
        val localChapter = getChapterByUrlAndMangaId.await(chapterUrl, manga.id)

        return if (localChapter == null) {
            val remoteManga = manga.toSManga()
            val sourceChapters = guardSourceOperation(SourceOperationPhase.CHAPTERS) {
                source.getChapterList(remoteManga)
            }
            val newChapters = syncChaptersWithSource.await(sourceChapters, manga, source, false)
            newChapters.find { it.url == chapterUrl }
        } else {
            localChapter
        }
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data object NoResults : State

        @Immutable
        data class Failure(val error: AppError) : State

        @Immutable
        data class Result(val manga: Manga, val chapterId: Long? = null) : State
    }
}

internal fun SourceManager.firstResolvableSource(query: String): ResolvableSource? =
    getCatalogueSources()
        .filterIsInstance<ResolvableSource>()
        .firstOrNull { source ->
            guardSourceOperation(SourceOperationPhase.METADATA) { source.getUriType(query) } != UriType.Unknown
        }
