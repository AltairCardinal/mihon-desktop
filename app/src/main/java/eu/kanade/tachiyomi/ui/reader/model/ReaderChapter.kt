package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mihon.domain.error.AppError
import mihon.domain.reader.ReaderChapterState
import tachiyomi.core.common.util.system.logcat

data class ReaderChapter(val chapter: Chapter) {

    private val mutableSharedStateFlow = MutableStateFlow<ReaderChapterState>(ReaderChapterState.Wait)
    val sharedStateFlow: StateFlow<ReaderChapterState> = mutableSharedStateFlow.asStateFlow()

    var state: State = State.Wait
        set(value) {
            field = value
            mutableSharedStateFlow.value = value.toSharedState(checkNotNull(chapter.id))
        }

    val pages: List<ReaderPage>?
        get() = (state as? State.Loaded)?.pages

    var pageLoader: PageLoader? = null

    var requestedPage: Int = 0

    private var references = 0

    constructor(chapter: tachiyomi.domain.chapter.model.Chapter) : this(chapter.toDbChapter())

    fun ref() {
        references++
    }

    fun unref() {
        references--
        if (references == 0) {
            if (pageLoader != null) {
                logcat { "Recycling chapter ${chapter.name}" }
            }
            pageLoader?.recycle()
            pageLoader = null
            state = State.Wait
        }
    }

    sealed interface State {
        data object Wait : State
        data object Loading : State
        data class Error(val error: Throwable) : State
        data class Loaded(val pages: List<ReaderPage>) : State
    }
}

private fun ReaderChapter.State.toSharedState(chapterId: Long): ReaderChapterState = when (this) {
    ReaderChapter.State.Wait -> ReaderChapterState.Wait
    ReaderChapter.State.Loading -> ReaderChapterState.Loading
    is ReaderChapter.State.Loaded -> ReaderChapterState.Loaded(pages.map(ReaderPage::toSharedPageModel))
    is ReaderChapter.State.Error -> ReaderChapterState.Error(
        error = AppError.Unknown(error),
        retryTargetChapterId = chapterId,
    )
}
