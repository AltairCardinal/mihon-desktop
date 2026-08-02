package mihon.domain.reader.scheduler

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderRequestSchedulerTest {

    @Test
    fun `original Mihon policy schedules visible page then four forward pages serially`() {
        val scheduler = ReaderRequestScheduler(ReaderSchedulerPolicy.originalMihon())

        val plan = scheduler.moveTo(currentPage = 2, pageCount = 10)

        assertEquals(listOf(2, 3, 4, 5, 6), plan.requests.map { it.pageIndex })
        assertEquals(ReaderRequestPriority.P0_INTERACTIVE, plan.requests.first().priority)
        assertTrue(plan.requests.drop(1).all { it.priority == ReaderRequestPriority.P1_NEARBY })
        val visible = scheduler.pollNext()
        assertEquals(2, visible?.pageIndex)
        assertNull(scheduler.pollNext(), "Original Android policy must remain serial")
        assertTrue(scheduler.complete(requireNotNull(visible).jobKey))
        assertEquals(3, scheduler.pollNext()?.pageIndex)
    }

    @Test
    fun `every visible logical page is P0 before nearby work`() {
        val scheduler = ReaderRequestScheduler(
            ReaderSchedulerPolicy(nearbyForward = 2, nearbyBackward = 0, maxConcurrentRequests = 2),
        )

        val plan = scheduler.moveTo(
            chapterId = TEST_CHAPTER_ID,
            visiblePageIndices = listOf(2, 3),
            anchorPage = 2,
            pageCount = 8,
        )

        assertEquals(listOf(2, 3, 4), plan.requests.map { it.pageIndex })
        assertTrue(plan.requests.take(2).all { it.priority == ReaderRequestPriority.P0_INTERACTIVE })
        assertEquals(ReaderRequestPriority.P1_NEARBY, plan.requests.last().priority)
    }

    @Test
    fun `P0 is ordered before P1 P2 P3 and P4 regardless of enqueue order`() {
        val scheduler = ReaderRequestScheduler(
            ReaderSchedulerPolicy(nearbyForward = 0, nearbyBackward = 0, maxConcurrentRequests = 1),
        )
        scheduler.moveTo(currentPage = 0, pageCount = 1).requests.single().also {
            assertEquals(it, scheduler.pollNext())
            scheduler.complete(it.jobKey)
        }

        scheduler.enqueue(4, ReaderRequestKind.ADJACENT_BACKGROUND)
        scheduler.enqueue(3, ReaderRequestKind.ADJACENT_METADATA)
        scheduler.enqueue(2, ReaderRequestKind.CURRENT_BACKGROUND)
        scheduler.enqueue(1, ReaderRequestKind.NEARBY)
        scheduler.enqueue(0, ReaderRequestKind.INTERACTIVE_VISIBLE)

        val ordered = buildList {
            repeat(5) {
                val request = requireNotNull(scheduler.pollNext())
                add(request.priority)
                scheduler.complete(request.jobKey)
            }
        }
        assertEquals(
            listOf(
                ReaderRequestPriority.P0_INTERACTIVE,
                ReaderRequestPriority.P1_NEARBY,
                ReaderRequestPriority.P2_CURRENT_BACKGROUND,
                ReaderRequestPriority.P3_ADJACENT_METADATA,
                ReaderRequestPriority.P4_ADJACENT_BACKGROUND,
            ),
            ordered,
        )
    }

    @Test
    fun `generation replacement discards queued work cancels active work and rejects late results`() {
        val scheduler = ReaderRequestScheduler(
            ReaderSchedulerPolicy(nearbyForward = 2, nearbyBackward = 0, maxConcurrentRequests = 1),
        )
        val oldPlan = scheduler.moveTo(currentPage = 0, pageCount = 8)
        val oldActive = requireNotNull(scheduler.pollNext())

        val replacement = scheduler.moveTo(currentPage = 6, pageCount = 8)

        assertEquals(setOf(oldActive.jobKey), replacement.cancelRequests)
        assertEquals(
            oldPlan.requests.drop(1).mapTo(mutableSetOf()) { it.jobKey },
            replacement.discardRequests,
        )
        assertFalse(scheduler.accepts(oldActive.jobKey))
        assertFalse(scheduler.complete(oldActive.jobKey))
        assertEquals(6, scheduler.pollNext()?.pageIndex)
    }

    @Test
    fun `explicit retry starts a new generation forces refresh and outranks the restored nearby window`() {
        val scheduler = ReaderRequestScheduler(ReaderSchedulerPolicy.originalMihon())
        scheduler.moveTo(currentPage = 2, pageCount = 10)
        val oldVisible = requireNotNull(scheduler.pollNext())
        scheduler.complete(oldVisible.jobKey)
        val oldNearby = requireNotNull(scheduler.pollNext())

        val retryPlan = scheduler.retry(pageIndex = 2, pageCount = 10)

        assertTrue(oldNearby.jobKey in retryPlan.cancelRequests)
        assertEquals(ReaderRequestKind.EXPLICIT_RETRY, retryPlan.requests.first().kind)
        assertTrue(retryPlan.requests.first().forceRefresh)
        assertEquals(ReaderRequestPriority.P0_INTERACTIVE, retryPlan.requests.first().priority)
        assertEquals(listOf(2, 3, 4, 5, 6), retryPlan.requests.map { it.pageIndex })
        assertEquals(ReaderRequestKind.EXPLICIT_RETRY, scheduler.pollNext()?.kind)
    }

    @Test
    fun `same generation P0 preempts active background and requeues it with a fresh request identity`() {
        val scheduler = ReaderRequestScheduler(
            ReaderSchedulerPolicy(nearbyForward = 0, nearbyBackward = 0, maxConcurrentRequests = 1),
        )
        val initial = scheduler.moveTo(currentPage = 0, pageCount = 1).requests.single()
        scheduler.pollNext()
        scheduler.complete(initial.jobKey)
        scheduler.enqueue(8, ReaderRequestKind.ADJACENT_BACKGROUND)
        val background = requireNotNull(scheduler.pollNext())

        val promotion = scheduler.enqueue(1, ReaderRequestKind.INTERACTIVE_VISIBLE)

        assertEquals(setOf(background.jobKey), promotion.cancelRequests)
        assertFalse(scheduler.accepts(background.jobKey))
        val visible = requireNotNull(scheduler.pollNext())
        assertEquals(1, visible.pageIndex)
        scheduler.complete(visible.jobKey)
        val restartedBackground = requireNotNull(scheduler.pollNext())
        assertEquals(background.pageIndex, restartedBackground.pageIndex)
        assertNotEquals(background.jobKey, restartedBackground.jobKey)
    }

    @Test
    fun `visible promotion preempts the same page when it is already active as background`() {
        val scheduler = ReaderRequestScheduler(
            ReaderSchedulerPolicy(nearbyForward = 0, nearbyBackward = 0, maxConcurrentRequests = 1),
        )
        scheduler.moveTo(currentPage = 0, pageCount = 1).requests.single().also {
            scheduler.pollNext()
            scheduler.complete(it.jobKey)
        }
        scheduler.enqueue(4, ReaderRequestKind.ADJACENT_BACKGROUND)
        val background = requireNotNull(scheduler.pollNext())

        val promotion = scheduler.enqueue(4, ReaderRequestKind.INTERACTIVE_VISIBLE)

        assertEquals(setOf(background.jobKey), promotion.cancelRequests)
        val visible = requireNotNull(scheduler.pollNext())
        assertEquals(background.pageIndex, visible.pageIndex)
        assertEquals(ReaderRequestKind.INTERACTIVE_VISIBLE, visible.kind)
        assertNotEquals(background.jobKey, visible.jobKey)
    }

    @Test
    fun `configured concurrency is a hard upper bound`() {
        val scheduler = ReaderRequestScheduler(
            ReaderSchedulerPolicy(nearbyForward = 3, nearbyBackward = 0, maxConcurrentRequests = 2),
        )
        scheduler.moveTo(currentPage = 0, pageCount = 4)

        val first = requireNotNull(scheduler.pollNext())
        val second = requireNotNull(scheduler.pollNext())

        assertEquals(2, scheduler.snapshot().activeRequests.size)
        assertNull(scheduler.pollNext())
        scheduler.complete(first.jobKey)
        assertEquals(2, requireNotNull(scheduler.pollNext()).pageIndex)
        assertTrue(scheduler.accepts(second.jobKey))
    }

    @Test
    fun `same source index in current and adjacent chapters remains two independent targets`() {
        val scheduler = ReaderRequestScheduler(
            ReaderSchedulerPolicy(nearbyForward = 0, nearbyBackward = 0, maxConcurrentRequests = 2),
        )
        val currentChapter = ReaderChapterId(11)
        val adjacentChapter = ReaderChapterId(12)
        scheduler.moveTo(chapterId = currentChapter, currentPage = 0, pageCount = 1).requests.single().also {
            scheduler.pollNext()
            scheduler.complete(it.jobKey)
        }
        val currentPage = ReaderPageId(currentChapter, sourcePageIndex = 0)
        val adjacentPage = ReaderPageId(adjacentChapter, sourcePageIndex = 0)

        scheduler.enqueue(currentPage, ReaderRequestKind.CURRENT_BACKGROUND)
        scheduler.enqueue(adjacentPage, ReaderRequestKind.ADJACENT_BACKGROUND)

        assertEquals(
            setOf(currentPage, adjacentPage),
            scheduler.snapshot().pendingRequests.mapTo(mutableSetOf()) { it.pageId },
        )
    }

    private fun ReaderRequestScheduler.moveTo(currentPage: Int, pageCount: Int) =
        moveTo(TEST_CHAPTER_ID, currentPage, pageCount)

    private fun ReaderRequestScheduler.enqueue(
        pageIndex: Int,
        kind: ReaderRequestKind,
        forceRefresh: Boolean = false,
    ) = enqueue(ReaderPageId(TEST_CHAPTER_ID, pageIndex), kind, forceRefresh)

    private fun ReaderRequestScheduler.retry(pageIndex: Int, pageCount: Int) =
        retry(ReaderPageId(TEST_CHAPTER_ID, pageIndex), pageCount)

    private companion object {
        val TEST_CHAPTER_ID = ReaderChapterId(1)
    }
}
