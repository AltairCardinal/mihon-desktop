package mihon.domain.reader.session

import mihon.domain.error.AppError

data class ReaderChapterId(val value: Long)

data class ReaderPageId(
    val chapterId: ReaderChapterId,
    val sourcePageIndex: Int,
) {
    init {
        require(sourcePageIndex >= 0) { "sourcePageIndex must be non-negative" }
    }
}

@JvmInline
value class EncodedPageRef(val value: String) {
    init {
        require(value.isNotBlank()) { "Encoded page reference must not be blank" }
    }
}

sealed interface ReaderChapterLoadState {
    data object Wait : ReaderChapterLoadState
    data object LoadingPageList : ReaderChapterLoadState
    data object Loaded : ReaderChapterLoadState
    data class Error(val error: AppError) : ReaderChapterLoadState
}

sealed interface ReaderPageLoadState {
    data object Queued : ReaderPageLoadState
    data object ResolvingImage : ReaderPageLoadState
    data class Downloading(val progressPercent: Int? = null) : ReaderPageLoadState {
        init {
            require(progressPercent == null || progressPercent in 0..100) {
                "progressPercent must be null or within 0..100"
            }
        }
    }
    data object Ready : ReaderPageLoadState
    data class Error(val error: AppError) : ReaderPageLoadState
}

data class ReaderPageDescriptor(
    val sourcePageIndex: Int,
    val url: String = "",
    val imageUrl: String? = null,
    val encodedPageRef: EncodedPageRef? = null,
    val initialLoadState: ReaderPageLoadState = ReaderPageLoadState.Queued,
) {
    init {
        require(sourcePageIndex >= 0) { "sourcePageIndex must be non-negative" }
    }
}

data class ReaderPageSession(
    val id: ReaderPageId,
    val url: String,
    val imageUrl: String?,
    val encodedPageRef: EncodedPageRef?,
    val loadState: ReaderPageLoadState,
)

data class ReaderChapterSession(
    val id: ReaderChapterId,
    val generation: Long,
    val loadState: ReaderChapterLoadState,
    val pages: List<ReaderPageSession>,
) {
    init {
        require(generation >= 0) { "generation must be non-negative" }
        require(pages.all { it.id.chapterId == id }) { "Every page must belong to this chapter" }
        require(pages.map(ReaderPageSession::id).distinct().size == pages.size) {
            "Page identities must be unique within a chapter"
        }
        require(loadState is ReaderChapterLoadState.Loaded || pages.isEmpty()) {
            "Only a loaded chapter can expose pages"
        }
    }
}

data class ReaderSessionSnapshot(
    val generation: Long,
    val activeChapter: ReaderChapterSession,
) {
    init {
        require(generation >= 0) { "generation must be non-negative" }
        require(activeChapter.generation == generation) { "Active chapter generation must match the session" }
    }

    companion object {
        fun initial(chapterId: ReaderChapterId): ReaderSessionSnapshot = ReaderSessionSnapshot(
            generation = 0,
            activeChapter = ReaderChapterSession(
                id = chapterId,
                generation = 0,
                loadState = ReaderChapterLoadState.Wait,
                pages = emptyList(),
            ),
        )
    }
}

sealed interface ReaderSessionIntent {
    data class OpenChapter(val chapterId: ReaderChapterId) : ReaderSessionIntent
    data class ResetChapter(val chapterId: ReaderChapterId) : ReaderSessionIntent
    data class PageListLoaded(
        val chapterId: ReaderChapterId,
        val generation: Long,
        val pages: List<ReaderPageDescriptor>,
    ) : ReaderSessionIntent
    data class PageListFailed(
        val chapterId: ReaderChapterId,
        val generation: Long,
        val error: AppError,
    ) : ReaderSessionIntent
    data class PageStateChanged(
        val pageId: ReaderPageId,
        val generation: Long,
        val loadState: ReaderPageLoadState,
    ) : ReaderSessionIntent
    data class PageContentChanged(
        val pageId: ReaderPageId,
        val generation: Long,
        val imageUrl: String?,
        val encodedPageRef: EncodedPageRef?,
        val loadState: ReaderPageLoadState,
    ) : ReaderSessionIntent
}

sealed interface ReaderSessionEffect {
    data class LoadPageList(
        val chapterId: ReaderChapterId,
        val generation: Long,
    ) : ReaderSessionEffect
}

data class ReaderSessionReduction(
    val snapshot: ReaderSessionSnapshot,
    val effects: List<ReaderSessionEffect> = emptyList(),
)

object ReaderSessionReducer {

    fun reduce(
        snapshot: ReaderSessionSnapshot,
        intent: ReaderSessionIntent,
    ): ReaderSessionReduction = when (intent) {
        is ReaderSessionIntent.OpenChapter -> openChapter(snapshot, intent.chapterId)
        is ReaderSessionIntent.ResetChapter -> resetChapter(snapshot, intent.chapterId)
        is ReaderSessionIntent.PageListLoaded -> pageListLoaded(snapshot, intent)
        is ReaderSessionIntent.PageListFailed -> pageListFailed(snapshot, intent)
        is ReaderSessionIntent.PageStateChanged -> pageStateChanged(snapshot, intent)
        is ReaderSessionIntent.PageContentChanged -> pageContentChanged(snapshot, intent)
    }

    private fun openChapter(
        snapshot: ReaderSessionSnapshot,
        chapterId: ReaderChapterId,
    ): ReaderSessionReduction {
        val generation = snapshot.generation + 1
        return ReaderSessionReduction(
            snapshot = snapshot.withChapter(chapterId, generation, ReaderChapterLoadState.LoadingPageList),
            effects = listOf(ReaderSessionEffect.LoadPageList(chapterId, generation)),
        )
    }

    private fun resetChapter(
        snapshot: ReaderSessionSnapshot,
        chapterId: ReaderChapterId,
    ): ReaderSessionReduction {
        val generation = snapshot.generation + 1
        return ReaderSessionReduction(snapshot.withChapter(chapterId, generation, ReaderChapterLoadState.Wait))
    }

    private fun pageListLoaded(
        snapshot: ReaderSessionSnapshot,
        intent: ReaderSessionIntent.PageListLoaded,
    ): ReaderSessionReduction {
        if (!snapshot.acceptsPageListTerminal(intent.chapterId, intent.generation)) {
            return ReaderSessionReduction(snapshot)
        }
        require(intent.pages.map(ReaderPageDescriptor::sourcePageIndex).distinct().size == intent.pages.size) {
            "Page source indices must be unique within one page list"
        }
        val pages = intent.pages.map { descriptor ->
            ReaderPageSession(
                id = ReaderPageId(intent.chapterId, descriptor.sourcePageIndex),
                url = descriptor.url,
                imageUrl = descriptor.imageUrl,
                encodedPageRef = descriptor.encodedPageRef,
                loadState = descriptor.initialLoadState,
            )
        }
        return ReaderSessionReduction(
            snapshot.copy(
                activeChapter = snapshot.activeChapter.copy(
                    loadState = ReaderChapterLoadState.Loaded,
                    pages = pages,
                ),
            ),
        )
    }

    private fun pageListFailed(
        snapshot: ReaderSessionSnapshot,
        intent: ReaderSessionIntent.PageListFailed,
    ): ReaderSessionReduction {
        if (!snapshot.acceptsPageListTerminal(intent.chapterId, intent.generation)) {
            return ReaderSessionReduction(snapshot)
        }
        return ReaderSessionReduction(
            snapshot.copy(
                activeChapter = snapshot.activeChapter.copy(
                    loadState = ReaderChapterLoadState.Error(intent.error),
                    pages = emptyList(),
                ),
            ),
        )
    }

    private fun pageStateChanged(
        snapshot: ReaderSessionSnapshot,
        intent: ReaderSessionIntent.PageStateChanged,
    ): ReaderSessionReduction {
        if (!snapshot.accepts(intent.pageId.chapterId, intent.generation)) return ReaderSessionReduction(snapshot)
        val pageIndex = snapshot.activeChapter.pages.indexOfFirst { it.id == intent.pageId }
        if (pageIndex < 0) return ReaderSessionReduction(snapshot)
        val currentPage = snapshot.activeChapter.pages[pageIndex]
        if (currentPage.loadState == intent.loadState) return ReaderSessionReduction(snapshot)
        val pages = snapshot.activeChapter.pages.toMutableList().apply {
            this[pageIndex] = currentPage.copy(loadState = intent.loadState)
        }
        return ReaderSessionReduction(
            snapshot.copy(activeChapter = snapshot.activeChapter.copy(pages = pages)),
        )
    }

    private fun pageContentChanged(
        snapshot: ReaderSessionSnapshot,
        intent: ReaderSessionIntent.PageContentChanged,
    ): ReaderSessionReduction {
        if (!snapshot.accepts(intent.pageId.chapterId, intent.generation)) return ReaderSessionReduction(snapshot)
        val pageIndex = snapshot.activeChapter.pages.indexOfFirst { it.id == intent.pageId }
        if (pageIndex < 0) return ReaderSessionReduction(snapshot)
        val currentPage = snapshot.activeChapter.pages[pageIndex]
        val updatedPage = currentPage.copy(
            imageUrl = intent.imageUrl,
            encodedPageRef = intent.encodedPageRef,
            loadState = intent.loadState,
        )
        if (updatedPage == currentPage) return ReaderSessionReduction(snapshot)
        val pages = snapshot.activeChapter.pages.toMutableList().apply {
            this[pageIndex] = updatedPage
        }
        return ReaderSessionReduction(
            snapshot.copy(activeChapter = snapshot.activeChapter.copy(pages = pages)),
        )
    }

    private fun ReaderSessionSnapshot.accepts(chapterId: ReaderChapterId, generation: Long): Boolean =
        generation == this.generation && activeChapter.id == chapterId

    private fun ReaderSessionSnapshot.acceptsPageListTerminal(
        chapterId: ReaderChapterId,
        generation: Long,
    ): Boolean = accepts(chapterId, generation) && activeChapter.loadState is ReaderChapterLoadState.LoadingPageList

    private fun ReaderSessionSnapshot.withChapter(
        chapterId: ReaderChapterId,
        generation: Long,
        loadState: ReaderChapterLoadState,
    ): ReaderSessionSnapshot = ReaderSessionSnapshot(
        generation = generation,
        activeChapter = ReaderChapterSession(
            id = chapterId,
            generation = generation,
            loadState = loadState,
            pages = emptyList(),
        ),
    )
}
