package mihon.domain.reader.scheduler

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.Executors

class ReaderPageMaterializeRunnerTest {

    @Test
    fun `cancel before dispatched body still completes work exactly once`() = runTest {
        val processBaseline = ReaderPageMaterializeRunner.processPhysicalWorkCount.value
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
        assertEquals(1, runner.physicalWorkCount.value)
        assertEquals(processBaseline + 1, ReaderPageMaterializeRunner.processPhysicalWorkCount.value)

        val replacement = scheduler.moveTo(TEST_CHAPTER_ID, currentPage = 1, pageCount = 2)
        runner.cancel(replacement.cancelRequests)
        runCurrent()

        val oldCompletions = completions.filter { it.first == 0 }
        assertEquals(1, oldCompletions.size)
        assertInstanceOf(ReaderPageMaterializeCompletion.Cancelled::class.java, oldCompletions.single().second)
        assertEquals(0, runner.physicalWorkCount.value)
        assertEquals(processBaseline + 0, ReaderPageMaterializeRunner.processPhysicalWorkCount.value)
        assertEquals(processBaseline, ReaderPageMaterializeRunner.processPhysicalWorkCount.value)
        runner.close()
    }

    @Test
    fun `already cancelled scope balances queued physical work without running the body`() = runTest {
        val processBaseline = ReaderPageMaterializeRunner.processPhysicalWorkCount.value
        val scheduler = ReaderRequestScheduler(
            ReaderSchedulerPolicy(nearbyForward = 0, nearbyBackward = 0, maxConcurrentRequests = 1),
        )
        val completions = mutableListOf<Pair<Int, ReaderPageMaterializeCompletion>>()
        val cancelledScope = CoroutineScope(coroutineContext + SupervisorJob().apply { cancel() })
        val runner = ReaderPageMaterializeRunner(
            scope = cancelledScope,
            maxConcurrentRequests = 1,
            port = schedulerPort(scheduler, { readyFetchPort(it) }, completions),
        )
        scheduler.moveTo(TEST_CHAPTER_ID, currentPage = 0, pageCount = 1)
        runner.pump()
        runCurrent()
        assertEquals(0, runner.physicalWorkCount.value)
        assertEquals(processBaseline + 0, ReaderPageMaterializeRunner.processPhysicalWorkCount.value)
        assertEquals(1, completions.size)
        assertInstanceOf(ReaderPageMaterializeCompletion.Cancelled::class.java, completions.single().second)
        assertEquals(processBaseline, ReaderPageMaterializeRunner.processPhysicalWorkCount.value)
        runner.close()
    }

    @Test
    fun `rejected executor submission completes original work and releases physical qualification`() = runTest {
        val processBaseline = ReaderPageMaterializeRunner.processPhysicalWorkCount.value
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        executor.shutdown()
        val rejectedScope = CoroutineScope(SupervisorJob() + dispatcher)
        val scheduler = ReaderRequestScheduler(
            ReaderSchedulerPolicy(nearbyForward = 0, nearbyBackward = 0, maxConcurrentRequests = 1),
        )
        val completions = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, ReaderPageMaterializeCompletion>>()
        val runner = ReaderPageMaterializeRunner(
            scope = rejectedScope,
            maxConcurrentRequests = 1,
            port = schedulerPort(scheduler, { readyFetchPort(it) }, completions),
        )
        try {
            scheduler.moveTo(TEST_CHAPTER_ID, currentPage = 0, pageCount = 1)
            runner.pump()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    runner.physicalWorkCount.first { it == 0 }
                    ReaderPageMaterializeRunner.processPhysicalWorkCount.first { it == processBaseline }
                }
            }
            assertEquals(1, completions.size)
            assertInstanceOf(ReaderPageMaterializeCompletion.Cancelled::class.java, completions.single().second)
            assertTrue(runner.snapshot().activeRequestKeys.isEmpty())
        } finally {
            assertEquals(processBaseline, ReaderPageMaterializeRunner.processPhysicalWorkCount.value)
            runner.close()
            rejectedScope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun `P0 preempts P4 and cooperative cancellation stays cancellation`() = runTest {
        val processBaseline = ReaderPageMaterializeRunner.processPhysicalWorkCount.value
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
        assertEquals(processBaseline, ReaderPageMaterializeRunner.processPhysicalWorkCount.value)
        runner.close()
    }

    @Test
    fun `preempted request with fact permit requeued under new job key is not claimed by late completing old job`() =
        runTest {
            val processBaseline = ReaderPageMaterializeRunner.processPhysicalWorkCount.value
            val scheduler = serialSchedulerAfterInitialWindow()
            val oldStarted = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val completions = mutableListOf<Pair<Int, ReaderPageMaterializeCompletion>>()
            val published = mutableListOf<String>()
            val budget = mihon.domain.reader.ReaderDualPageFactBudget()
            val permit = mihon.domain.reader.ReaderDualPageFactReadPermit(
                budget,
                pageId(8),
                clockMillis = { 0L },
                ownerKey = 42L,
            )
            var attempts = 0

            val port = schedulerPort(
                scheduler = scheduler,
                fetchPort = { pageIndex ->
                    object : ReaderPageFetchPort {
                        override suspend fun resolveImageUrl(request: ReaderPageFetchRequest) = "image:$pageIndex"

                        override suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef? = null

                        override suspend fun fetchEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef {
                            if (pageIndex == 8 && attempts++ == 0) {
                                oldStarted.complete(Unit)
                                withContext(NonCancellable) {
                                    releaseOld.await()
                                }
                                return EncodedPageRef("encoded:old-8")
                            }
                            return EncodedPageRef("encoded:new-$pageIndex")
                        }
                    }
                },
                completions = completions,
                onAcceptedEvent = { binding, event ->
                    if (event is ReaderPageMaterializeEvent.Ready) {
                        published += "$binding:${event.encodedPageRef.value}"
                    }
                },
            )
            val runner = ReaderPageMaterializeRunner(
                scope = this,
                maxConcurrentRequests = 1,
                port = port,
            )

            // 1. Enqueue page 8 as background with fact permit
            scheduler.enqueue(pageId(8), ReaderRequestKind.ADJACENT_BACKGROUND, factPermit = permit)
            runner.pump()
            oldStarted.await()

            // 2. Preempt page 8 with visible page 1. Preemption cancels old job
            // and requeues page 8 with new job key preserving fact permit
            val visible = scheduler.enqueue(pageId(1), ReaderRequestKind.INTERACTIVE_VISIBLE)
            runner.cancel(visible.cancelRequests)
            runner.pump()
            advanceUntilIdle()

            // Page 1 has completed
            assertTrue(
                completions.any { it.first == 1 && it.second is ReaderPageMaterializeCompletion.Completed },
            )

            // 3. Now release the old non-cooperative job 8 so it finishes late
            releaseOld.complete(Unit)
            advanceUntilIdle()

            // Requeued page 8 runs and finishes
            runner.pump()
            advanceUntilIdle()

            // 4. Assert: old late job did NOT publish into port or steal the new job
            assertTrue(
                published.any { it == "8:encoded:new-8" },
                "New job must publish its own result: $published",
            )
            org.junit.jupiter.api.Assertions.assertFalse(
                published.any { it == "8:encoded:old-8" },
                "Old late job must not publish into port: $published",
            )

            assertEquals(processBaseline, ReaderPageMaterializeRunner.processPhysicalWorkCount.value)
            assertEquals(0, ReaderPageMaterializeRunner.blockingWorkExcept(0L))
            runner.close()
        }

    @Test
    fun `non cooperative old generation cannot publish after replacement`() = runTest {
        val processBaseline = ReaderPageMaterializeRunner.processPhysicalWorkCount.value
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
        assertEquals(1, runner.physicalWorkCount.value)
        assertEquals(processBaseline + 1, ReaderPageMaterializeRunner.processPhysicalWorkCount.value)

        val replacement = scheduler.moveTo(TEST_CHAPTER_ID, currentPage = 1, pageCount = 2)
        runner.cancel(replacement.cancelRequests)
        runner.pump()
        runCurrent()
        assertEquals(listOf(1), acceptedReadyPages)
        assertEquals(1, runner.physicalWorkCount.value, "Old native work still runs after logical cancellation")
        assertEquals(processBaseline + 1, ReaderPageMaterializeRunner.processPhysicalWorkCount.value)

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
        assertEquals(0, runner.physicalWorkCount.value)
        assertEquals(processBaseline + 0, ReaderPageMaterializeRunner.processPhysicalWorkCount.value)
        assertEquals(processBaseline, ReaderPageMaterializeRunner.processPhysicalWorkCount.value)
        runner.close()
    }

    @Test
    fun `blockingWorkExcept isolates publisher owner and ordinary work blocks every owner`() = runTest {
        val budget = mihon.domain.reader.ReaderDualPageFactBudget()
        val scheduler = ReaderRequestScheduler(
            ReaderSchedulerPolicy(nearbyForward = 0, nearbyBackward = 0, maxConcurrentRequests = 2),
        )
        val startedOrdinary = CompletableDeferred<Unit>()
        val releaseOrdinary = CompletableDeferred<Unit>()
        val startedPermit100 = CompletableDeferred<Unit>()
        val releasePermit100 = CompletableDeferred<Unit>()
        val completions = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, ReaderPageMaterializeCompletion>>()

        val port = object : ReaderPageMaterializeRunnerPort<Int> {
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
                    fetchPort = object : ReaderPageFetchPort {
                        override suspend fun resolveImageUrl(request: ReaderPageFetchRequest) = "image:$pageIndex"
                        override suspend fun findEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef? = null
                        override suspend fun fetchEncodedPage(request: ReaderPageFetchRequest): EncodedPageRef {
                            if (pageIndex == 0) {
                                startedOrdinary.complete(Unit)
                                releaseOrdinary.await()
                            } else if (pageIndex == 1) {
                                startedPermit100.complete(Unit)
                                releasePermit100.await()
                            }
                            return EncodedPageRef("encoded:$pageIndex")
                        }
                    },
                    binding = pageIndex,
                )
            }

            override fun accepts(work: ReaderPageMaterializeWork<Int>): Boolean =
                scheduler.accepts(work.request.jobKey)

            override fun publish(work: ReaderPageMaterializeWork<Int>, event: ReaderPageMaterializeEvent): Boolean =
                scheduler.accepts(work.request.jobKey)

            override fun complete(work: ReaderPageMaterializeWork<Int>, completion: ReaderPageMaterializeCompletion) {
                completions += work.binding to completion
                scheduler.complete(work.request.jobKey)
            }
        }

        val runner = ReaderPageMaterializeRunner(
            scope = this,
            maxConcurrentRequests = 2,
            port = port,
        )

        try {
            // 1. Ordinary work (pageIndex = 0, no permit)
            scheduler.enqueue(pageId(0), ReaderRequestKind.INTERACTIVE_VISIBLE)
            runner.pump()
            startedOrdinary.await()

            // Ordinary work must be blocking for all publisher owners
            val ordinaryBlocking100 = ReaderPageMaterializeRunner.blockingWorkExcept(100L)
            val ordinaryBlocking200 = ReaderPageMaterializeRunner.blockingWorkExcept(200L)
            assertTrue(ordinaryBlocking100 >= 1, "Ordinary work must block publisher 100")
            assertTrue(ordinaryBlocking200 >= 1, "Ordinary work must block publisher 200")
            assertEquals(
                0,
                ReaderPageMaterializeRunner.blockingWorkExcept(ReaderPageMaterializeRunner.ORDINARY_WORK_KEY),
                "Ordinary work key should not block ordinary work key itself",
            )

            // 2. Publisher 100 work (pageIndex = 1, factPermit ownerKey = 100L)
            val permit100 = mihon.domain.reader.ReaderDualPageFactReadPermit(
                budget = budget,
                pageId = pageId(1),
                clockMillis = { 0L },
                ownerKey = 100L,
            )
            scheduler.enqueue(pageId(1), ReaderRequestKind.INTERACTIVE_VISIBLE, factPermit = permit100)
            runner.pump()
            startedPermit100.await()

            // Publisher 100's own work should NOT block publisher 100 itself (only ordinary work blocks it)
            assertEquals(
                ordinaryBlocking100,
                ReaderPageMaterializeRunner.blockingWorkExcept(100L),
                "Publisher 100 must not be blocked by its own work",
            )
            // But publisher 100's work MUST block publisher 200
            assertEquals(
                ordinaryBlocking200 + 1,
                ReaderPageMaterializeRunner.blockingWorkExcept(200L),
                "Publisher 200 must be blocked by publisher 100's work",
            )

            // Release both works
            releaseOrdinary.complete(Unit)
            releasePermit100.complete(Unit)
            advanceUntilIdle()

            // Both works completed, blocking counts must balance back to baseline
            assertEquals(
                0,
                ReaderPageMaterializeRunner.blockingWorkExcept(100L),
                "All blocking work should be cleared after completion for 100",
            )
            assertEquals(
                0,
                ReaderPageMaterializeRunner.blockingWorkExcept(200L),
                "All blocking work should be cleared after completion for 200",
            )
        } finally {
            releaseOrdinary.complete(Unit)
            releasePermit100.complete(Unit)
            runner.close()
        }
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
