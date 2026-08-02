package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mihon.domain.error.AppError
import mihon.domain.reader.ReaderChapterState
import mihon.domain.reader.ReaderPageModel
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderPageDescriptor
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderSessionIntent
import mihon.domain.reader.session.ReaderSessionReducer
import mihon.domain.reader.session.ReaderSessionSnapshot
import tachiyomi.core.common.util.system.logcat

data class ReaderChapter(val chapter: Chapter) {

    private val mutableSharedStateFlow = MutableStateFlow<ReaderChapterState>(ReaderChapterState.Wait)
    val sharedStateFlow: StateFlow<ReaderChapterState> = mutableSharedStateFlow.asStateFlow()

    private val sharedSessionLock = Any()
    private val mutableSharedSessionStateFlow: MutableStateFlow<ReaderSessionSnapshot> by lazy {
        MutableStateFlow(ReaderSessionSnapshot.initial(sharedChapterId()))
    }
    val sharedSessionStateFlow: StateFlow<ReaderSessionSnapshot> by lazy {
        mutableSharedSessionStateFlow.asStateFlow()
    }

    private var mutableState: State = State.Wait
    var state: State
        get() = synchronized(sharedSessionLock) { mutableState }
        set(value) {
            synchronized(sharedSessionLock) {
                publishCompatibilityState(value)
            }
        }

    val pages: List<ReaderPage>?
        get() = (state as? State.Loaded)?.pages

    private var mutablePageLoader: PageLoader? = null
    private var pageLoaderGeneration: Long? = null
    var pageLoader: PageLoader?
        get() = synchronized(sharedSessionLock) { mutablePageLoader }
        internal set(value) {
            synchronized(sharedSessionLock) {
                if (mutablePageLoader === value) return@synchronized
                clearPageLoaderLocked()
                mutablePageLoader = value
                pageLoaderGeneration = null
            }
        }

    var requestedPage: Int = 0

    private var references = 0

    constructor(chapter: tachiyomi.domain.chapter.model.Chapter) : this(chapter.toDbChapter())

    fun ref() {
        synchronized(sharedSessionLock) {
            references++
        }
    }

    fun unref() {
        synchronized(sharedSessionLock) {
            references--
            if (references == 0) {
                if (mutablePageLoader != null) {
                    logcat { "Recycling chapter ${chapter.name}" }
                }
                clearPageLoaderLocked()
                publishCompatibilityState(State.Wait)
            }
        }
    }

    sealed interface State {
        data object Wait : State
        data object Loading : State
        data class Error(val error: Throwable) : State
        data class Loaded(val pages: List<ReaderPage>) : State
    }

    internal fun beginPageListLoadIfNeeded(): Long? = synchronized(sharedSessionLock) {
        if (mutableState is State.Loaded && mutablePageLoader?.isRecycled == false) {
            return@synchronized null
        }
        clearPageLoaderLocked()
        applyNonTerminalState(
            value = State.Loading,
            intent = ReaderSessionIntent.OpenChapter(sharedChapterId()),
        )
        mutableSharedSessionStateFlow.value.generation
    }

    internal fun completePageListLoad(
        generation: Long,
        pages: List<ReaderPage>,
    ): Boolean = synchronized(sharedSessionLock) {
        val chapterId = sharedChapterId()
        applyTerminalState(
            value = State.Loaded(pages),
            intent = ReaderSessionIntent.PageListLoaded(
                chapterId = chapterId,
                generation = generation,
                pages = pages.toSharedDescriptors(),
            ),
        )
    }

    internal fun failPageListLoad(
        generation: Long,
        error: Throwable,
    ): Boolean = synchronized(sharedSessionLock) {
        val chapterId = sharedChapterId()
        applyTerminalState(
            value = State.Error(error),
            intent = ReaderSessionIntent.PageListFailed(
                chapterId = chapterId,
                generation = generation,
                error = AppError.Unknown(error),
            ),
        )
    }

    internal fun installPageLoader(
        generation: Long,
        loader: PageLoader,
    ): Boolean = synchronized(sharedSessionLock) {
        val snapshot = mutableSharedSessionStateFlow.value
        if (
            snapshot.generation != generation ||
            snapshot.activeChapter.loadState !is ReaderChapterLoadState.LoadingPageList ||
            loader.isRecycled
        ) {
            return@synchronized false
        }
        if (mutablePageLoader !== loader) {
            clearPageLoaderLocked()
            mutablePageLoader = loader
        }
        pageLoaderGeneration = generation
        true
    }

    internal fun retirePageLoader(
        generation: Long,
        loader: PageLoader,
    ) = synchronized(sharedSessionLock) {
        if (mutablePageLoader === loader) {
            if (pageLoaderGeneration != generation) return@synchronized
            mutablePageLoader = null
            pageLoaderGeneration = null
        }
        recycleOnceLocked(loader)
    }

    private fun publishCompatibilityState(value: State) {
        val chapterId = sharedChapterId()
        when (value) {
            State.Wait -> applyNonTerminalState(value, ReaderSessionIntent.ResetChapter(chapterId))
            State.Loading -> applyNonTerminalState(value, ReaderSessionIntent.OpenChapter(chapterId))
            is State.Error -> {
                val generation = ensureLoadingGeneration(chapterId)
                applyTerminalState(
                    value = value,
                    intent = ReaderSessionIntent.PageListFailed(
                        chapterId = chapterId,
                        generation = generation,
                        error = AppError.Unknown(value.error),
                    ),
                )
            }
            is State.Loaded -> {
                val generation = ensureLoadingGeneration(chapterId)
                applyTerminalState(
                    value = value,
                    intent = ReaderSessionIntent.PageListLoaded(
                        chapterId = chapterId,
                        generation = generation,
                        pages = value.pages.toSharedDescriptors(),
                    ),
                )
            }
        }
    }

    private fun applyNonTerminalState(
        value: State,
        intent: ReaderSessionIntent,
    ) {
        (mutableState as? State.Loaded)?.pages?.forEach(ReaderPage::unbindSharedState)
        mutableState = value
        dispatch(intent)
    }

    private fun applyTerminalState(
        value: State,
        intent: ReaderSessionIntent,
    ): Boolean {
        val current = mutableSharedSessionStateFlow.value
        val next = ReaderSessionReducer.reduce(current, intent).snapshot
        if (next === current) return false

        (mutableState as? State.Loaded)?.pages?.forEach(ReaderPage::unbindSharedState)
        mutableState = value
        mutableSharedSessionStateFlow.value = next
        mutableSharedStateFlow.value = next.toLegacyState()
        if (value is State.Loaded) {
            bindPages(value.pages, next.activeChapter.id, next.generation)
        }
        return true
    }

    private fun bindPages(
        pages: List<ReaderPage>,
        chapterId: ReaderChapterId,
        generation: Long,
    ) {
        pages.forEach { page ->
            val pageId = ReaderPageId(chapterId, page.index)
            page.bindSharedState { loadState ->
                dispatch(
                    ReaderSessionIntent.PageStateChanged(
                        pageId = pageId,
                        generation = generation,
                        loadState = loadState,
                    ),
                )
            }
        }
    }

    private fun List<ReaderPage>.toSharedDescriptors(): List<ReaderPageDescriptor> = map { page ->
        ReaderPageDescriptor(
            sourcePageIndex = page.index,
            url = page.url,
            imageUrl = page.imageUrl,
            initialLoadState = page.toSharedLoadState(),
        )
    }

    private fun clearPageLoaderLocked() {
        val loader = mutablePageLoader
        mutablePageLoader = null
        pageLoaderGeneration = null
        loader?.let(::recycleOnceLocked)
    }

    private fun recycleOnceLocked(loader: PageLoader) {
        if (!loader.isRecycled) {
            loader.recycle()
        }
    }

    private fun ensureLoadingGeneration(chapterId: ReaderChapterId): Long {
        val snapshot = mutableSharedSessionStateFlow.value
        if (
            snapshot.activeChapter.id != chapterId ||
            snapshot.activeChapter.loadState !is ReaderChapterLoadState.LoadingPageList
        ) {
            dispatch(ReaderSessionIntent.OpenChapter(chapterId), projectLegacyState = false)
        }
        return mutableSharedSessionStateFlow.value.generation
    }

    private fun dispatch(
        intent: ReaderSessionIntent,
        projectLegacyState: Boolean = true,
    ) = synchronized(sharedSessionLock) {
        val current = mutableSharedSessionStateFlow.value
        mutableSharedSessionStateFlow.value = ReaderSessionReducer.reduce(current, intent).snapshot
        if (projectLegacyState) {
            mutableSharedStateFlow.value = mutableSharedSessionStateFlow.value.toLegacyState()
        }
    }

    private fun sharedChapterId(): ReaderChapterId = ReaderChapterId(checkNotNull(chapter.id))
}

private fun ReaderSessionSnapshot.toLegacyState(): ReaderChapterState = when (val loadState = activeChapter.loadState) {
    ReaderChapterLoadState.Wait -> ReaderChapterState.Wait
    ReaderChapterLoadState.LoadingPageList -> ReaderChapterState.Loading
    ReaderChapterLoadState.Loaded -> ReaderChapterState.Loaded(
        activeChapter.pages.map { page ->
            ReaderPageModel(
                index = page.id.sourcePageIndex,
                url = page.url,
                imageUrl = page.imageUrl,
            )
        },
    )
    is ReaderChapterLoadState.Error -> ReaderChapterState.Error(
        error = loadState.error,
        retryTargetChapterId = activeChapter.id.value,
    )
}
