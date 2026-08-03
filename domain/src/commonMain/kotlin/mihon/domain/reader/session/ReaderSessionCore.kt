package mihon.domain.reader.session

import mihon.domain.reader.materialize.ReaderChapterMaterializeResult
import mihon.domain.reader.materialize.ReaderPageMaterializeEvent
import mihon.domain.reader.progress.ReaderProgressEffect
import mihon.domain.reader.progress.ReaderProgressPolicy
import mihon.domain.reader.progress.ReaderProgressSignal
import mihon.domain.reader.scheduler.ReaderEnqueueResult
import mihon.domain.reader.scheduler.ReaderRequestCancellation
import mihon.domain.reader.scheduler.ReaderRequestKey
import mihon.domain.reader.scheduler.ReaderRequestKind
import mihon.domain.reader.scheduler.ReaderRequestScheduler
import mihon.domain.reader.scheduler.ReaderSchedulePlan
import mihon.domain.reader.scheduler.ReaderScheduledRequest
import mihon.domain.reader.scheduler.ReaderSchedulerPolicy
import mihon.domain.reader.scheduler.ReaderSchedulerSnapshot

data class ReaderSessionCoreUpdate(
    val snapshot: ReaderSessionSnapshot,
    val effects: List<ReaderSessionEffect> = emptyList(),
    val schedulePlan: ReaderSchedulePlan? = null,
    val progressEffect: ReaderProgressEffect? = null,
)

/**
 * Canonical reader-session coordinator shared by platform runtimes.
 *
 * The core owns the authoritative session state, request ordering/generations, Retry semantics,
 * and settled progress decisions. Platforms execute the returned page-list and page-I/O work,
 * then publish its results back through the generation-checked methods below.
 */
class ReaderSessionCore(
    initialChapterId: ReaderChapterId,
    private val sessionId: String,
    private val requestScheduler: ReaderRequestScheduler = ReaderRequestScheduler(
        ReaderSchedulerPolicy.originalMihon(),
    ),
) {
    var snapshot: ReaderSessionSnapshot = ReaderSessionSnapshot.initial(initialChapterId)
        private set

    private var settlementSequence = 0L

    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    }

    fun openChapter(chapterId: ReaderChapterId): ReaderSessionCoreUpdate {
        val schedulePlan = requestScheduler.moveTo(chapterId, currentPage = 0, pageCount = 0)
        val reduction = ReaderSessionReducer.reduce(snapshot, ReaderSessionIntent.OpenChapter(chapterId))
        snapshot = reduction.snapshot
        return ReaderSessionCoreUpdate(snapshot, reduction.effects, schedulePlan)
    }

    fun acceptChapterMaterialization(
        chapterId: ReaderChapterId,
        generation: Long,
        result: ReaderChapterMaterializeResult,
    ): ReaderSessionCoreUpdate {
        val intent = when (result) {
            is ReaderChapterMaterializeResult.Loaded -> ReaderSessionIntent.PageListLoaded(
                chapterId = chapterId,
                generation = generation,
                pages = result.pages,
            )
            is ReaderChapterMaterializeResult.Failed -> ReaderSessionIntent.PageListFailed(
                chapterId = chapterId,
                generation = generation,
                error = result.error,
            )
        }
        val reduction = ReaderSessionReducer.reduce(snapshot, intent)
        snapshot = reduction.snapshot
        return ReaderSessionCoreUpdate(snapshot, reduction.effects)
    }

    fun settleViewport(
        visiblePageIds: Set<ReaderPageId>,
        anchorPageId: ReaderPageId,
        wasRead: Boolean,
    ): ReaderSessionCoreUpdate {
        val chapter = snapshot.activeChapter
        require(chapter.loadState is ReaderChapterLoadState.Loaded) {
            "Only a loaded chapter can settle a viewport"
        }
        require(visiblePageIds.isNotEmpty()) { "A settled viewport must contain a visible page" }
        require(anchorPageId in visiblePageIds) { "The anchor page must be visible" }
        require(visiblePageIds.all { pageId -> chapter.pages.any { it.id == pageId } }) {
            "Every visible page must belong to the active chapter page list"
        }

        val schedulePlan = requestScheduler.moveTo(
            chapterId = chapter.id,
            visiblePageIndices = visiblePageIds.map(ReaderPageId::sourcePageIndex),
            anchorPage = anchorPageId.sourcePageIndex,
            pageCount = chapter.pages.size,
        )
        val plannedPageIds = schedulePlan.requests.mapTo(mutableSetOf(), ReaderScheduledRequest::pageId)
        chapter.pages.forEach { page ->
            if (page.id !in plannedPageIds && page.loadState !is ReaderPageLoadState.Ready) {
                requestScheduler.enqueue(page.id, ReaderRequestKind.CURRENT_BACKGROUND)
            }
        }

        settlementSequence++
        val progressEffect = ReaderProgressPolicy.reduce(
            ReaderProgressSignal.ViewportSettled(
                activeChapterId = chapter.id,
                chapterId = chapter.id,
                visiblePageIds = visiblePageIds,
                totalPages = chapter.pages.size,
                wasRead = wasRead,
                sessionId = sessionId,
                settlementSequence = settlementSequence,
            ),
        )
        return ReaderSessionCoreUpdate(snapshot, schedulePlan = schedulePlan, progressEffect = progressEffect)
    }

    fun retryPage(pageId: ReaderPageId): ReaderSessionCoreUpdate {
        val chapter = snapshot.activeChapter
        require(chapter.pages.any { it.id == pageId }) { "Retry page must belong to the active chapter" }
        snapshot = ReaderSessionReducer.reduce(
            snapshot,
            ReaderSessionIntent.PageStateChanged(
                pageId = pageId,
                generation = snapshot.generation,
                loadState = ReaderPageLoadState.Queued,
            ),
        ).snapshot
        val schedulePlan = requestScheduler.retry(pageId, chapter.pages.size)
        return ReaderSessionCoreUpdate(snapshot, schedulePlan = schedulePlan)
    }

    /** Adds cache-only work for a retained adjacent chapter without changing the active snapshot. */
    fun enqueueAdjacentPage(pageId: ReaderPageId): ReaderEnqueueResult {
        require(pageId.chapterId != snapshot.activeChapter.id) {
            "Adjacent page work must not target the active chapter"
        }
        return requestScheduler.enqueue(pageId, ReaderRequestKind.ADJACENT_BACKGROUND)
    }

    fun cancelChapterPageRequests(chapterId: ReaderChapterId): ReaderRequestCancellation =
        requestScheduler.cancelChapter(chapterId)

    fun acceptsPageRequest(jobKey: ReaderRequestKey): Boolean = requestScheduler.accepts(jobKey)

    fun acceptPageMaterialization(
        request: ReaderScheduledRequest,
        event: ReaderPageMaterializeEvent,
    ): Boolean {
        if (!requestScheduler.accepts(request.jobKey)) return false
        if (snapshot.activeChapter.pages.none { it.id == request.pageId }) return false
        val intent = when (event) {
            ReaderPageMaterializeEvent.ResolvingImage -> ReaderSessionIntent.PageStateChanged(
                request.pageId,
                snapshot.generation,
                ReaderPageLoadState.ResolvingImage,
            )
            is ReaderPageMaterializeEvent.Downloading -> ReaderSessionIntent.PageContentChanged(
                pageId = request.pageId,
                generation = snapshot.generation,
                imageUrl = event.imageUrl,
                encodedPageRef = null,
                loadState = ReaderPageLoadState.Downloading(),
            )
            is ReaderPageMaterializeEvent.Ready -> ReaderSessionIntent.PageContentChanged(
                pageId = request.pageId,
                generation = snapshot.generation,
                imageUrl = event.imageUrl,
                encodedPageRef = event.encodedPageRef,
                loadState = ReaderPageLoadState.Ready,
            )
            is ReaderPageMaterializeEvent.Failed -> ReaderSessionIntent.PageStateChanged(
                request.pageId,
                snapshot.generation,
                ReaderPageLoadState.Error(event.error),
            )
        }
        snapshot = ReaderSessionReducer.reduce(snapshot, intent).snapshot
        return true
    }

    fun pollNextPageRequest(): ReaderScheduledRequest? = requestScheduler.pollNext()

    fun completePageRequest(jobKey: ReaderRequestKey): Boolean = requestScheduler.complete(jobKey)

    fun schedulerSnapshot(): ReaderSchedulerSnapshot = requestScheduler.snapshot()

    fun close(): ReaderSessionCoreUpdate {
        val schedulePlan = requestScheduler.moveTo(
            chapterId = snapshot.activeChapter.id,
            currentPage = 0,
            pageCount = 0,
        )
        return ReaderSessionCoreUpdate(snapshot, schedulePlan = schedulePlan)
    }
}
