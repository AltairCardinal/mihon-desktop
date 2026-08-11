package mihon.desktop.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.desktop.task.DesktopTaskScheduler
import mihon.desktop.task.FileTaskCheckpointStore
import mihon.desktop.tracking.DesktopNetworkConnectivity
import mihon.domain.error.AppError
import mihon.domain.task.TaskCheckpoint
import mihon.domain.task.TaskConstraint
import mihon.domain.task.TaskStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.creator.service.CreatorDiscoveryResult
import tachiyomi.domain.creator.service.CreatorDiscoverySourceResult
import tachiyomi.domain.creator.service.CreatorSourceFailure
import java.nio.file.Path

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CreatorDiscoverySchedulerTest {

    @TempDir lateinit var directory: Path

    @Test
    fun `runNow registers a network constrained task and completes on full success`() = runTest {
        val scheduler = scheduler(
            discoverDue = { result(completedSources = 2, totalSources = 2) },
        )

        scheduler.runNow().join()

        val snapshot = scheduler.taskSnapshot()
        assertEquals(TaskStatus.Completed, snapshot?.status)
        assertTrue(TaskConstraint.NetworkConnected in snapshot!!.task.constraints)
        assertEquals(CreatorDiscoveryRunScope.Due, scheduler.state.value.scope)
        assertEquals(TaskStatus.Completed, scheduler.state.value.status)
    }

    @Test
    fun `concurrent runNow calls share one occurrence`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val scheduler = scheduler(
            discoverDue = {
                calls++
                entered.complete(Unit)
                release.await()
                result(completedSources = 1, totalSources = 1)
            },
        )

        val jobs = (1..10).map { async { scheduler.runNow() } }.awaitAll()
        entered.await()
        assertEquals(1, jobs.distinct().size)
        release.complete(Unit)
        jobs.first().join()

        assertEquals(1, calls)
        scheduler.stop()
    }

    @Test
    fun `partial source failure ends in Failed with typed failed units`() = runTest {
        val scheduler = scheduler(
            discoverDue = {
                result(
                    sourceResults = listOf(
                        sourceResult(sourceId = 1L),
                        sourceResult(sourceId = 2L, failure = CreatorSourceFailure.Timeout),
                    ),
                    completedSources = 1,
                    totalSources = 2,
                )
            },
        )

        scheduler.runNow().join()

        val snapshot = scheduler.taskSnapshot()
        assertEquals(TaskStatus.Failed, snapshot?.status)
        assertEquals(listOf("source:2"), snapshot?.failedUnits)
        assertTrue(snapshot?.failure?.message?.contains("1 of 2 sources failed", ignoreCase = true) == true)
        assertEquals(1, scheduler.state.value.errorCount)
        assertEquals(1, scheduler.state.value.completedSources)
        assertEquals(2, scheduler.state.value.totalSources)
        scheduler.stop()
    }

    @Test
    fun `all sources failed ends in Failed with all units listed`() = runTest {
        val scheduler = scheduler(
            discoverDue = {
                result(
                    sourceResults = listOf(
                        sourceResult(sourceId = 1L, failure = CreatorSourceFailure.Http(500)),
                        sourceResult(sourceId = 2L, failure = CreatorSourceFailure.Network("boom")),
                    ),
                    completedSources = 0,
                    totalSources = 2,
                )
            },
        )

        scheduler.runNow().join()

        assertEquals(TaskStatus.Failed, scheduler.taskSnapshot()?.status)
        assertEquals(listOf("source:1", "source:2"), scheduler.taskSnapshot()?.failedUnits)
        assertEquals(2, scheduler.state.value.errorCount)
        scheduler.stop()
    }

    @Test
    fun `lease busy or no due work completes without failing`() = runTest {
        val busy = scheduler(discoverDue = { result(leaseBusy = true) })
        busy.runNow().join()
        assertEquals(TaskStatus.Completed, busy.taskSnapshot()?.status)
        assertTrue(busy.state.value.leaseBusy)
        busy.stop()

        val skipped = scheduler(discoverDue = { result(skipped = true) })
        skipped.runNow().join()
        assertEquals(TaskStatus.Completed, skipped.taskSnapshot()?.status)
        assertTrue(skipped.state.value.skipped)
        skipped.stop()
    }

    @Test
    fun `cancelling a running discovery cancels the executor and stores Cancelled`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var sawCancellation = false
        val scheduler = scheduler(
            discoverDue = {
                entered.complete(Unit)
                try {
                    release.await()
                } catch (error: CancellationException) {
                    sawCancellation = true
                    throw error
                }
                result(completedSources = 1, totalSources = 1)
            },
        )

        val job = scheduler.runNow()
        entered.await()
        assertEquals(TaskStatus.Running, scheduler.state.value.status)
        assertTrue(scheduler.cancel())
        release.complete(Unit)
        job.join()

        assertTrue(sawCancellation)
        assertEquals(TaskStatus.Cancelled, scheduler.taskSnapshot()?.status)
        assertEquals(TaskStatus.Cancelled, scheduler.state.value.status)
        scheduler.stop()
    }

    @Test
    fun `host restart recovers a pending manual creator task on start`() = runTest {
        val file = directory.resolve("tasks.json")
        DesktopTaskScheduler(FileTaskCheckpointStore(file)).register(
            CreatorDiscoveryScheduler.CREATOR_DISCOVERY_TASK.copy(
                checkpoint = TaskCheckpoint("creator:42"),
            ),
        )
        val calls = mutableListOf<Long>()
        val scheduler = scheduler(
            file = file,
            discoverDue = { error("manual task must not resume as due") },
            discoverCreator = { creatorId ->
                calls += creatorId
                result(completedSources = 1, totalSources = 1)
            },
        )

        val initial = scheduler.start()
        initial.join()

        assertEquals(listOf(42L), calls)
        assertEquals(TaskStatus.Completed, scheduler.taskSnapshot()?.status)
        scheduler.stop()
    }

    @Test
    fun `network constraint leaves task pending without calling an executor`() = runTest {
        var calls = 0
        val scheduler = scheduler(
            discoverDue = {
                calls++
                result(completedSources = 1, totalSources = 1)
            },
            connectivity = DesktopNetworkConnectivity { false },
        )

        scheduler.runNow().join()

        assertEquals(0, calls)
        assertEquals(TaskStatus.Pending, scheduler.taskSnapshot()?.status)
        assertEquals(TaskStatus.Pending, scheduler.state.value.status)
        assertTrue(scheduler.cancel())
        assertEquals(TaskStatus.Cancelled, scheduler.taskSnapshot()?.status)
    }

    @Test
    fun `host restart recovers a failed task and re-drives discovery on start`() = runTest {
        val file = directory.resolve("tasks.json")
        DesktopTaskScheduler(FileTaskCheckpointStore(file)).apply {
            register(CreatorDiscoveryScheduler.CREATOR_DISCOVERY_TASK)
            start(CreatorDiscoveryScheduler.CREATOR_DISCOVERY_TASK.id)
            fail(CreatorDiscoveryScheduler.CREATOR_DISCOVERY_TASK.id, AppError.Unknown())
        }
        val calls = mutableListOf<Int>()
        val scheduler = scheduler(
            file = file,
            discoverDue = {
                calls += 1
                result(completedSources = 1, totalSources = 1)
            },
        )

        val initial = scheduler.start()
        initial.join()

        assertEquals(listOf(1), calls)
        assertEquals(TaskStatus.Completed, scheduler.taskSnapshot()?.status)
        scheduler.stop()
    }

    @Test
    fun `polling loop only drives due work when hasDueWork reports it`() = runTest {
        var due = false
        var dueRuns = 0
        val scheduler = scheduler(
            hasDueWork = { due },
            discoverDue = {
                dueRuns++
                result(completedSources = 1, totalSources = 1)
            },
            scope = this,
        )

        scheduler.start()
        advanceTimeBy(CreatorDiscoveryScheduler.CHECK_INTERVAL_MS * 3 + 1_000)
        runCurrent()
        assertEquals(0, dueRuns)

        due = true
        advanceTimeBy(CreatorDiscoveryScheduler.CHECK_INTERVAL_MS + 1_000)
        runCurrent()
        assertEquals(1, dueRuns)
        assertEquals(TaskStatus.Completed, scheduler.taskSnapshot()?.status)
        scheduler.stop()
    }

    @Test
    fun `runForCreator drives the per creator executor and records its scope`() = runTest {
        var discoveredCreator: Long? = null
        val scheduler = scheduler(
            discoverCreator = { creatorId ->
                discoveredCreator = creatorId
                result(completedSources = 3, totalSources = 3, newCandidateCount = 2)
            },
        )

        scheduler.runForCreator(42L).join()

        assertEquals(42L, discoveredCreator)
        assertEquals(CreatorDiscoveryRunScope.Creator, scheduler.state.value.scope)
        assertEquals(TaskStatus.Completed, scheduler.taskSnapshot()?.status)
        assertEquals(2, scheduler.state.value.newCandidateCount)
        assertEquals(3, scheduler.state.value.totalSources)
        scheduler.stop()
    }

    @Test
    fun `runForCreator while a task is running merges into the running occurrence`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var dueCalls = 0
        val scheduler = scheduler(
            discoverDue = {
                dueCalls++
                entered.complete(Unit)
                release.await()
                result(completedSources = 1, totalSources = 1)
            },
        )

        val dueJob = scheduler.runNow()
        entered.await()
        val manualJob = scheduler.runForCreator(7L)

        assertSame(dueJob, manualJob)
        release.complete(Unit)
        dueJob.join()
        assertEquals(1, dueCalls)
        scheduler.stop()
    }

    @Test
    fun `second run after terminal state uses a fresh idempotency key`() = runTest {
        val scheduler = scheduler(discoverDue = { result(completedSources = 1, totalSources = 1) })

        scheduler.runNow().join()
        val first = scheduler.taskSnapshot()
        scheduler.runNow().join()
        val second = scheduler.taskSnapshot()

        assertNotSame(first, second)
        assertNotEquals(first?.task?.idempotencyKey, second?.task?.idempotencyKey)
        scheduler.stop()
    }

    @Test
    fun `state exposes last result for settings and author detail`() = runTest {
        val scheduler = scheduler(
            discoverDue = {
                result(
                    sourceResults = listOf(
                        sourceResult(sourceId = 1L),
                        sourceResult(sourceId = 2L, failure = CreatorSourceFailure.Network("offline")),
                    ),
                    completedSources = 1,
                    totalSources = 2,
                    newCandidateCount = 3,
                )
            },
        )

        scheduler.runNow().join()

        val state = scheduler.state.first { it.status == TaskStatus.Failed }
        assertEquals(3, state.newCandidateCount)
        assertEquals(1, state.errorCount)
        assertEquals(1, state.completedSources)
        assertEquals(2, state.totalSources)
        assertEquals(listOf("source:2"), state.failedUnits)
        assertFalse(state.leaseBusy)
        assertFalse(state.skipped)
        scheduler.stop()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun scheduler(
        file: Path = directory.resolve("tasks.json"),
        discoverDue: suspend () -> CreatorDiscoveryResult = { result() },
        discoverCreator: suspend (Long) -> CreatorDiscoveryResult = { result() },
        hasDueWork: suspend () -> Boolean = { false },
        connectivity: DesktopNetworkConnectivity = DesktopNetworkConnectivity { true },
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    ): CreatorDiscoveryScheduler {
        return CreatorDiscoveryScheduler(
            taskScheduler = DesktopTaskScheduler(FileTaskCheckpointStore(file)),
            discoverDue = discoverDue,
            discoverCreator = discoverCreator,
            hasDueWork = hasDueWork,
            connectivity = connectivity,
            scope = scope,
        )
    }

    private fun result(
        newCandidateCount: Int = 0,
        errorCount: Int = 0,
        sourceResults: List<CreatorDiscoverySourceResult> = emptyList(),
        completedSources: Int = 0,
        totalSources: Int = 0,
        leaseBusy: Boolean = false,
        skipped: Boolean = false,
    ) = CreatorDiscoveryResult(
        newCandidateCount = newCandidateCount,
        errorCount = errorCount,
        candidates = emptyList(),
        sourceResults = sourceResults,
        completedSources = completedSources,
        totalSources = totalSources,
        leaseBusy = leaseBusy,
        skipped = skipped,
    )

    private fun sourceResult(
        sourceId: Long,
        failure: CreatorSourceFailure? = null,
    ) = CreatorDiscoverySourceResult(
        sourceId = sourceId,
        pageCount = 1,
        matchedCount = 1,
        possibleCount = 0,
        insertedVerifiedCount = 1,
        notificationEligibleCount = 0,
        truncated = false,
        failure = failure,
    )
}
