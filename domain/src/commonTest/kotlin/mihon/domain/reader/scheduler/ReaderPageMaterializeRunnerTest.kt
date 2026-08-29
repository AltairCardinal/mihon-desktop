package mihon.domain.reader.scheduler

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mihon.domain.reader.materialize.ReaderPageFetchPort
import mihon.domain.reader.materialize.ReaderPageFetchRequest
import mihon.domain.reader.materialize.ReaderPageMaterializeEvent
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderPageMaterializeRunnerTest {

    @Test
    fun `cancel before dispatched body still completes work exactly once`() = runTest {
        val scheduler = ReaderRequestScheduler(
            ReaderSchedulerPolicy(nearbyForward = 0, nearbyBackward = 0, maxConcurrentRequests = 1),
        )
        val completions = mutableListOf<Pair<Int, ReaderPageMaterializeCompletion>>()
        val port = schedulerPort(
            scheduler = scheduler,
            fetchPort = { pageIndex -> readyFetchPort(pageIndex) },
            completions = completions,
        )
        val runner = ReaderPageMaterializeRunner(
            scope = this,
            maxConcurrentRequests = 1,
            port = port,
        )
        scheduler.moveTo(TEST_CHAPTER_ID, currentPage = 0, pageCount = 2)
        runner.pump()
        assertEquals(setOf(0), runner.snapshot().activeRequestKeys.mapTo(mutableSetOf()) { it.pageIndex })

        val replacement = scheduler.moveTo(TEST_CHAPTER_ID, currentPage = 1, pageCount = 2)
        runner.cancel(replacement.cancelRequests)
        runCurrent()

        val oldCompletions = completions.filter { it.first == 0 }
        assertEquals(1, oldCompletions.size)
        assertInstanceOf(ReaderPageMaterializeCompletion.Cancelled::class.java, oldCompletions.single().second)
        runner.close()
    }

    @Test
    fun `P0 preempts P4 and cooperative cancellation stays cancellation`() = runTest {
        val scheduler = serialSchedulerAfterInitialWindow()
        val backgroundStarted = CompletableDeferred<Unit>()
        val backgroundCancelled = CompletableDeferred<Unit>()
        val starts = mutableListOf<Int>()
        val completions = mutableListOf<Pair<Int, ReaderPageMaterializeCompletion>>()
        var backgroundAttempts = 0
        val port = schedulerPort(
            scheduler = scheduler,
            fetchPort = { pageIndex ->
                object : ReaderPageFetchPort {
                    override suspend fun resolveImageUrl(request: ReaderPageFetchRequest) = "image:$pageIndex"

                    override suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef? = null

                    override suspend fun fetchEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef {
                        starts += pageIndex
                        if (pageIndex == 8 && backgroundAttempts++ == 0) {
                            backgroundStarted.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                backgroundCancelled.complete(Unit)
                            }
                        }
                        return EncodedPageRef("encoded:$pageIndex")
                    }
                }
            },
            completions = completions,
        )
        val runner = ReaderPageMaterializeRunner(
            scope = this,
            maxConcurrentRequests = 1,
            port = port,
        )
        scheduler.enqueue(pageId(8), ReaderRequestKind.ADJACENT_BACKGROUND)
        runner.pump()
        backgroundStarted.await()

        val visible = scheduler.enqueue(pageId(1), ReaderRequestKind.INTERACTIVE_VISIBLE)
        runner.cancel(visible.cancelRequests)
        runner.pump()
        advanceUntilIdle()

        assertTrue(backgroundCancelled.isCompleted)
        assertEquals(listOf(8, 1, 8), starts)
        assertInstanceOf(
            ReaderPageMaterializeCompletion.Cancelled::class.java,
            completions.first { it.first == 8 }.second,
        )
        assertInstanceOf(
            ReaderPageMaterializeCompletion.Completed::class.java,
            completions.first { it.first == 1 }.second,
        )
        runner.close()
    }

    @Test
    fun `non cooperative old generation cannot publish after replacement`() = runTest {
        val scheduler = ReaderRequestScheduler(
            ReaderSchedulerPolicy(nearbyForward = 0, nearbyBackward = 0, maxConcurrentRequests = 1),
        )
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val acceptedReadyPages = mutableListOf<Int>()
        val completions = mutableListOf<Pair<Int, ReaderPageMaterializeCompletion>>()
        val port = schedulerPort(
            scheduler = scheduler,
            fetchPort = { pageIndex ->
                object : ReaderPageFetchPort {
                    override suspend fun resolveImageUrl(request: ReaderPageFetchRequest) = "image:$pageIndex"

                    override suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef? = null

                    override suspend fun fetchEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef {
                        if (pageIndex == 0) {
                            oldStarted.complete(Unit)
                            return withContext(NonCancellable) {
                                releaseOld.await()
                                EncodedPageRef("encoded:old")
                            }
                        }
                        return EncodedPageRef("encoded:new")
                    }
                }
            },
            completions = completions,
            onAcceptedEvent = { pageIndex, event ->
                if (event is ReaderPageMaterializeEvent.Ready) acceptedReadyPages += pageIndex
            },
        )
        val runner = ReaderPageMaterializeRunner(
            scope = this,
            maxConcurrentRequests = 1,
            maxStalePhysicalRequests = 1,
            port = port,
        )
        val oldPlan = scheduler.moveTo(TEST_CHAPTER_ID, currentPage = 0, pageCount = 2)
        runner.pump()
        oldStarted.await()

        val replacement = scheduler.moveTo(TEST_CHAPTER_ID, currentPage = 1, pageCount = 2)
        runner.cancel(replacement.cancelRequests)
        runner.pump()
        runCurrent()
        assertEquals(listOf(1), acceptedReadyPages)

        releaseOld.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(1), acceptedReadyPages)
        assertTrue(
            completions.any { (pageIndex, completion) ->
                pageIndex == 0 && when (completion) {
                    is ReaderPageMaterializeCompletion.Completed ->
                        completion.result is mihon.domain.reader.materialize.ReaderPageMaterializeResult.Rejected
                    else -> true
                }
            },
        )
        assertTrue(oldPlan.cancelRequests.isEmpty())
        runner.close()
    }

    private fun schedulerPort(
        scheduler: ReaderRequestScheduler,
        fetchPort: (Int) -> ReaderPageFetchPort,
        completions: MutableList<Pair<Int, ReaderPageMaterializeCompletion>>,
        onAcceptedEvent: (Int, ReaderPageMaterializeEvent) -> Unit = { _, _ -> },
    ) = object : ReaderPageMaterializeRunnerPort<Int> {
        override fun pollNext(): ReaderPageMaterializeWork<Int>? {
            val request = scheduler.pollNext() ?: return null
            val pageIndex = request.pageIndex
            return ReaderPageMaterializeWork(
                request = request,
                fetchRequest = ReaderPageFetchRequest(
                    pageId = request.pageId,
                    generation = request.generation,
                    url = "/$pageIndex",
                    imageUrl = "image:$pageIndex",
                ),
                fetchPort = fetchPort(pageIndex),
                binding = pageIndex,
            )
        }

        override fun accepts(work: ReaderPageMaterializeWork<Int>): Boolean =
            scheduler.accepts(work.request.jobKey)

        override fun publish(
            work: ReaderPageMaterializeWork<Int>,
            event: ReaderPageMaterializeEvent,
        ): Boolean {
            if (!scheduler.accepts(work.request.jobKey)) return false
            onAcceptedEvent(work.binding, event)
            return true
        }

        override fun complete(
            work: ReaderPageMaterializeWork<Int>,
            completion: ReaderPageMaterializeCompletion,
        ) {
            completions += work.binding to completion
            scheduler.complete(work.request.jobKey)
        }
    }

    private fun serialSchedulerAfterInitialWindow(): ReaderRequestScheduler = ReaderRequestScheduler(
        ReaderSchedulerPolicy(nearbyForward = 0, nearbyBackward = 0, maxConcurrentRequests = 1),
    ).also { scheduler ->
        scheduler.moveTo(TEST_CHAPTER_ID, currentPage = 0, pageCount = 1).requests.single().also { initial ->
            scheduler.pollNext()
            scheduler.complete(initial.jobKey)
        }
    }

    private fun pageId(index: Int) = ReaderPageId(TEST_CHAPTER_ID, index)

    private fun readyFetchPort(pageIndex: Int) = object : ReaderPageFetchPort {
        override suspend fun resolveImageUrl(request: ReaderPageFetchRequest) = "image:$pageIndex"

        override suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef? = null

        override suspend fun fetchEncodedPage(request: ReaderPageFetchRequest) = EncodedPageRef("encoded:$pageIndex")
    }

    private companion object {
        val TEST_CHAPTER_ID = ReaderChapterId(1)
    }
}
