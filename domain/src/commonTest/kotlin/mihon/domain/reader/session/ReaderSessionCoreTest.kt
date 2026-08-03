package mihon.domain.reader.session

import mihon.domain.reader.materialize.ReaderChapterMaterializeResult
import mihon.domain.reader.materialize.ReaderPageMaterializeEvent
import mihon.domain.reader.scheduler.ReaderRequestKind
import mihon.domain.reader.scheduler.ReaderRequestScheduler
import mihon.domain.reader.scheduler.ReaderSchedulerPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderSessionCoreTest {

    @Test
    fun `open publishes zero-page loading then stable page identities and per-page states`() {
        val chapterId = ReaderChapterId(7)
        val core = core(chapterId)

        val opening = core.openChapter(chapterId)

        assertInstanceOf(
            ReaderChapterLoadState.LoadingPageList::class.java,
            opening.snapshot.activeChapter.loadState,
        )
        assertTrue(opening.snapshot.activeChapter.pages.isEmpty())
        val load = opening.effects.filterIsInstance<ReaderSessionEffect.LoadPageList>().single()

        val loaded = core.acceptChapterMaterialization(
            chapterId = chapterId,
            generation = load.generation,
            result = ReaderChapterMaterializeResult.Loaded(
                listOf(
                    ReaderPageDescriptor(sourcePageIndex = 0, url = "/page/0"),
                    ReaderPageDescriptor(sourcePageIndex = 1, url = "/page/1"),
                ),
            ),
        ).snapshot

        val pageIds = loaded.activeChapter.pages.map(ReaderPageSession::id)
        assertEquals(listOf(ReaderPageId(chapterId, 0), ReaderPageId(chapterId, 1)), pageIds)
        assertEquals(
            listOf(ReaderPageLoadState.Queued, ReaderPageLoadState.Queued),
            loaded.activeChapter.pages.map(ReaderPageSession::loadState),
        )

        core.settleViewport(
            visiblePageIds = setOf(pageIds.first()),
            anchorPageId = pageIds.first(),
            wasRead = false,
        )
        val request = requireNotNull(core.pollNextPageRequest())
        assertTrue(core.acceptPageMaterialization(request, ReaderPageMaterializeEvent.ResolvingImage))
        assertTrue(
            core.acceptPageMaterialization(
                request,
                ReaderPageMaterializeEvent.Ready(
                    imageUrl = "https://example.test/0.jpg",
                    encodedPageRef = EncodedPageRef("encoded:0"),
                ),
            ),
        )

        val ready = core.snapshot.activeChapter.pages.first()
        assertEquals(pageIds.first(), ready.id)
        assertEquals(ReaderPageLoadState.Ready, ready.loadState)
        assertEquals(EncodedPageRef("encoded:0"), ready.encodedPageRef)
        assertEquals(ReaderPageLoadState.Queued, core.snapshot.activeChapter.pages.last().loadState)
    }

    @Test
    fun `rapid viewport change promotes visible page and rejects the cancelled late result`() {
        val chapterId = ReaderChapterId(9)
        val core = loadedCore(chapterId, pageCount = 8)
        val firstPage = ReaderPageId(chapterId, 0)
        val farPage = ReaderPageId(chapterId, 6)

        core.settleViewport(setOf(firstPage), firstPage, wasRead = false)
        val staleVisible = requireNotNull(core.pollNextPageRequest())

        val moved = core.settleViewport(setOf(farPage), farPage, wasRead = false)

        assertTrue(staleVisible.jobKey in requireNotNull(moved.schedulePlan).cancelRequests)
        val promoted = requireNotNull(core.pollNextPageRequest())
        assertEquals(farPage, promoted.pageId)
        assertEquals(ReaderRequestKind.INTERACTIVE_VISIBLE, promoted.kind)
        assertTrue(
            !core.acceptPageMaterialization(
                staleVisible,
                ReaderPageMaterializeEvent.Ready("https://example.test/stale", EncodedPageRef("encoded:stale")),
            ),
        )
        assertEquals(ReaderPageLoadState.Queued, core.snapshot.activeChapter.pages.first().loadState)
        assertEquals(farPage, core.snapshot.activeChapter.pages[6].id)
    }

    @Test
    fun `retry keeps page identity and creates force-refresh P0 work`() {
        val chapterId = ReaderChapterId(11)
        val pageId = ReaderPageId(chapterId, 0)
        val core = loadedCore(chapterId, pageCount = 2)
        core.settleViewport(setOf(pageId), pageId, wasRead = false)
        val failedRequest = requireNotNull(core.pollNextPageRequest())
        core.acceptPageMaterialization(
            failedRequest,
            ReaderPageMaterializeEvent.Failed(mihon.domain.error.AppError.Network()),
        )
        core.completePageRequest(failedRequest.jobKey)

        val retry = core.retryPage(pageId)
        val request = requireNotNull(core.pollNextPageRequest())

        assertEquals(pageId, core.snapshot.activeChapter.pages.first().id)
        assertEquals(ReaderPageLoadState.Queued, core.snapshot.activeChapter.pages.first().loadState)
        assertEquals(ReaderRequestKind.EXPLICIT_RETRY, request.kind)
        assertTrue(request.forceRefresh)
        assertEquals(request, requireNotNull(retry.schedulePlan).requests.first())
    }

    private fun loadedCore(chapterId: ReaderChapterId, pageCount: Int): ReaderSessionCore {
        val core = core(chapterId)
        val opening = core.openChapter(chapterId)
        val generation = opening.effects.filterIsInstance<ReaderSessionEffect.LoadPageList>().single().generation
        core.acceptChapterMaterialization(
            chapterId,
            generation,
            ReaderChapterMaterializeResult.Loaded(
                List(pageCount) { index -> ReaderPageDescriptor(index, url = "/page/$index") },
            ),
        )
        return core
    }

    private fun core(chapterId: ReaderChapterId) = ReaderSessionCore(
        initialChapterId = chapterId,
        sessionId = "reader-session-test",
        requestScheduler = ReaderRequestScheduler(
            ReaderSchedulerPolicy(nearbyForward = 2, nearbyBackward = 0, maxConcurrentRequests = 1),
        ),
    )
}
