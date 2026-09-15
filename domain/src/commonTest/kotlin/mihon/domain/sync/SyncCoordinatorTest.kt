package mihon.domain.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.sync.runtime.SyncCoordinator
import mihon.domain.sync.runtime.SyncRunPort
import mihon.domain.sync.runtime.SyncRunProblem
import mihon.domain.sync.runtime.SyncRunResult
import mihon.domain.sync.runtime.SyncRunStatus
import mihon.domain.sync.runtime.SyncTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncCoordinatorTest {
    private val success = SyncRunResult(SyncRunStatus.SUCCESS)

    @Test
    fun `overlapping callers share one flight and drain a burst once`() = runTest {
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        var calls = 0
        var active = 0
        var maximum = 0
        val coordinator = SyncCoordinator(
            SyncRunPort {
                active++
                maximum = maxOf(maximum, active)
                try {
                    when (++calls) {
                        1 -> first.await()
                        2 -> second.await()
                    }
                    success.copy(uploaded = calls)
                } finally {
                    active--
                }
            },
        )
        val leader = async { coordinator.synchronize(SyncTrigger.STARTUP) }
        runCurrent()
        assertTrue(coordinator.activity.value.running)
        val followers = List(20) { async { coordinator.synchronize(SyncTrigger.MANUAL) } }
        runCurrent()
        assertEquals(1, calls)
        first.complete(Unit)
        runCurrent()
        assertEquals(2, calls)
        second.complete(Unit)
        assertEquals(2, leader.await().uploaded)
        followers.forEach { assertEquals(leader.await(), it.await()) }
        assertEquals(1, maximum)
        assertFalse(coordinator.activity.value.running)
        assertEquals(1L, coordinator.activity.value.completion)
    }

    @Test
    fun `follower cancellation does not cancel the running exchange`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val coordinator = SyncCoordinator(
            SyncRunPort {
                calls++
                gate.await()
                success
            },
        )
        val leader = async { coordinator.synchronize(SyncTrigger.PERIODIC) }
        runCurrent()
        val follower = async { coordinator.synchronize(SyncTrigger.MANUAL) }
        runCurrent()
        follower.cancelAndJoin()
        assertTrue(leader.isActive)
        gate.complete(Unit)
        assertEquals(success, leader.await())
        assertEquals(2, calls)
    }

    @Test
    fun `worker cancellation releases all waiters and allows later recovery`() = runTest {
        var calls = 0
        var cleaned = false
        val coordinator = SyncCoordinator(
            SyncRunPort {
                if (++calls == 1) {
                    try {
                        awaitCancellation()
                    } finally {
                        cleaned = true
                    }
                }
                success
            },
        )
        val leader = async { coordinator.synchronize(SyncTrigger.PERIODIC) }
        runCurrent()
        val follower = async { coordinator.synchronize(SyncTrigger.MANUAL) }
        runCurrent()
        leader.cancelAndJoin()
        runCurrent()
        assertTrue(cleaned)
        assertTrue(follower.isCancelled)
        assertFalse(coordinator.activity.value.running)
        assertEquals(success, coordinator.synchronize(SyncTrigger.MANUAL))
        assertEquals(2, calls)
    }

    @Test
    fun `disconnect cancels actual work and joins cleanup`() = runTest {
        var cleaned = false
        val coordinator = SyncCoordinator(
            SyncRunPort {
                try {
                    awaitCancellation()
                } finally {
                    cleaned = true
                }
            },
        )
        val run = async { coordinator.synchronize(SyncTrigger.STARTUP) }
        runCurrent()
        coordinator.cancelAndJoin()
        assertTrue(cleaned)
        assertTrue(run.isCancelled)
        assertFalse(coordinator.activity.value.running)
    }

    @Test
    fun `failed exchange does not immediately retry queued automatic triggers`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val failed = SyncRunResult(SyncRunStatus.FAILED, problem = SyncRunProblem.NETWORK)
        val coordinator = SyncCoordinator(
            SyncRunPort {
                calls++
                gate.await()
                failed
            },
        )
        val leader = async { coordinator.synchronize(SyncTrigger.MANUAL) }
        runCurrent()
        val follower = async { coordinator.synchronize(SyncTrigger.PERIODIC) }
        runCurrent()
        gate.complete(Unit)
        assertEquals(failed, leader.await())
        assertEquals(failed, follower.await())
        assertEquals(1, calls)
        assertEquals(failed, coordinator.activity.value.result)
    }

    @Test
    fun `unexpected failure is sanitized and does not leave coordinator busy`() = runTest {
        val coordinator = SyncCoordinator(SyncRunPort { error("secret from backend") })
        val result = coordinator.synchronize(SyncTrigger.MANUAL)
        assertEquals(SyncRunStatus.FAILED, result.status)
        assertEquals(SyncRunProblem.UNKNOWN, result.problem)
        assertFalse(coordinator.activity.value.running)
        assertFalse(coordinator.activity.value.toString().contains("secret"))
    }
}
