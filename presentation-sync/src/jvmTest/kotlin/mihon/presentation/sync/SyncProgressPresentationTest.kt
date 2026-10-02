package mihon.presentation.sync

import mihon.data.sync.runtime.SyncPanelState
import mihon.data.sync.runtime.SyncProgressDirection
import mihon.data.sync.runtime.SyncProgressFact
import mihon.data.sync.runtime.SyncProgressHold
import mihon.data.sync.runtime.SyncProgressStage
import mihon.data.sync.runtime.SyncRunPhase
import mihon.data.sync.runtime.SyncRunSnapshot
import mihon.data.sync.runtime.SyncRunState
import mihon.domain.sync.runtime.SyncTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncProgressPresentationTest {
    @Test fun `paused elapsed freezes across reopening and resumes without adding the pause`() {
        val session = SyncProgressDisplaySession()
        val running = state().copy(nowMillis = 10_000)
        assertEquals(10L, session.project(running, 0).elapsedSeconds)
        val paused = running.copy(
            nowMillis = 15_000,
            run = run.copy(state = SyncRunState.PAUSED_USER, updatedAt = 15_000, pausedAt = 15_000),
        )
        assertEquals(15L, session.project(paused, 5_000).elapsedSeconds)
        assertNull(session.project(paused, 5_000).nextDeadlineMillis)
        assertEquals(15L, session.project(paused.copy(nowMillis = 75_000), 65_000).elapsedSeconds)
        assertEquals(15L, SyncProgressDisplaySession().project(paused.copy(nowMillis = 75_000), 0).elapsedSeconds)
        val resumed = running.copy(nowMillis = 75_000, run = run.copy(updatedAt = 75_000, pausedMillis = 60_000))
        assertEquals(15L, session.project(resumed, 65_000).elapsedSeconds)
        assertEquals(17L, session.project(resumed, 67_000).elapsedSeconds)
        assertEquals(15L, SyncProgressDisplaySession().project(resumed, 0).elapsedSeconds)
        val finished = resumed.copy(run = resumed.run!!.copy(state = SyncRunState.SUCCEEDED, updatedAt = 78_000))
        assertEquals(18L, session.project(finished, 68_000).elapsedSeconds)
        assertEquals(18L, session.project(finished.copy(nowMillis = 100_000), 100_000).elapsedSeconds)
    }

    private val run = SyncRunSnapshot(
        "run", "space", 1, SyncTrigger.MANUAL, SyncRunState.RUNNING,
        SyncRunPhase.UPLOADING, 0, 0, 0, 0, 0, attemptId = 1, nextRetryAt = 0,
        lastProgressAt = 0, stopReason = null, ownerSession = null, createdAt = 0, updatedAt = 0,
        plannedItems = 100,
    )
    private val fact = SyncProgressFact(
        "run:a", SyncProgressStage.TRANSFERRING,
        SyncProgressDirection.UPLOAD, 6, 10, 64, 64, 100, 10, SyncProgressHold.ACTIVE,
        5, 20, confirmedThisRun = 999, receivedItems = 1280, secondsWithoutProgress = 0,
    )
    private fun state(confirmed: Long = 0, f: SyncProgressFact = fact) =
        SyncPanelState(visible = true, run = run.copy(confirmedItems = confirmed), progress = f)

    @Test fun `counting interval starts from zero before a durable run exists`() {
        val session = SyncProgressDisplaySession()
        val idle = SyncPanelState(visible = true)
        session.project(idle, 5_000_000)
        val counting = idle.copy(busy = true)
        assertEquals(0L, session.project(counting, 9_000_000).elapsedSeconds)
        assertEquals(1L, session.project(counting, 9_001_000).elapsedSeconds)
        val durable = state().copy(nowMillis = 7000, run = run.copy(createdAt = 1000))
        assertEquals(6L, session.project(durable, 9_002_000).elapsedSeconds)
        assertEquals(7L, session.project(durable.copy(nowMillis = -1000), 9_003_000).elapsedSeconds)
    }

    @Test fun `unrepresentable whole ETA stays unknown rather than saturating the duration`() {
        val session = SyncProgressDisplaySession()
        for (i in 0..3) {
            val large = state(
                i.toLong(),
            ).copy(run = run.copy(plannedItems = Long.MAX_VALUE, confirmedItems = i.toLong()))
            assertNull(session.project(large, i * 3333L).wholeEta)
        }
    }

    @Test fun `compact progress immediately uses the fixed run plan and safe confirmations`() {
        val session = SyncProgressDisplaySession()
        assertEquals(0L, session.project(state(), 0).confirmed)
        assertEquals(0f, session.project(state(), 0).fraction)
        val confirmed = session.project(state(40), 1)
        assertEquals(40L, confirmed.confirmed)
        assertEquals(0.4f, confirmed.fraction)
        assertNull(confirmed.wholeEta, "a local work or HTTP body estimate cannot become a whole-run ETA")
    }

    @Test fun `compact fraction survives direction stage attempt pause and scope changes`() {
        val session = SyncProgressDisplaySession()
        assertEquals(0.4f, session.project(state(40), 0).fraction)
        for ((index, f) in listOf(
            fact.copy(scope = "run:next", direction = SyncProgressDirection.DOWNLOAD),
            fact.copy(stage = SyncProgressStage.CONFIRMING),
            fact.copy(hold = SyncProgressHold.PAUSED),
            fact.copy(scope = "foreign", confirmedThisRun = 99999),
        ).withIndex()) {
            assertEquals(0.4f, session.project(state(40, f), index.toLong() + 1).fraction)
        }
        val paused = state(
            40,
        ).copy(run = run.copy(state = SyncRunState.PAUSED_USER, confirmedItems = 40, attemptId = 2))
        assertEquals(0.4f, session.project(paused, 10).fraction)
        assertFalse(session.project(paused, 10).active)
    }

    @Test fun `unknown and frozen zero plans remain distinct and full confirmation is still running`() {
        val session = SyncProgressDisplaySession()
        val unknown = state().copy(run = run.copy(plannedItems = null))
        assertEquals(0L, session.project(unknown, 0).confirmed)
        assertNull(session.project(unknown, 0).fraction)
        val zero = state().copy(run = run.copy(plannedItems = 0))
        assertNull(session.project(zero, 1).fraction)
        val complete = session.project(state(100), 2)
        assertEquals(1f, complete.fraction)
        assertTrue(complete.active)
        assertEquals(tachiyomi.i18n.MR.strings.sync_busy, complete.status)
        val terminal = state(100).copy(run = run.copy(state = SyncRunState.SUCCEEDED, confirmedItems = 100))
        assertEquals(tachiyomi.i18n.MR.strings.sync_result_completed, session.project(terminal, 3).status)
        assertNull(session.project(terminal, 3).wholeEta)
    }

    @Test fun `batch paced safety confirmations produce a whole ETA and a bounded cadence stale deadline`() {
        val session = SyncProgressDisplaySession()
        fun batchState(
            confirmed: Long,
        ) = state(confirmed).copy(run = run.copy(plannedItems = 10_000, confirmedItems = confirmed))
        assertNull(session.project(batchState(0), 0).wholeEta)
        assertNull(session.project(batchState(256), 20_000).wholeEta)
        assertNull(session.project(batchState(512), 40_000).wholeEta)
        assertEquals(722L, session.project(batchState(768), 60_000).wholeEta)
        assertEquals(722L, session.project(batchState(768), 75_000).wholeEta)
        assertNull(session.project(batchState(768), 100_000).wholeEta)
        assertNull(session.project(batchState(1024), 120_000).wholeEta)
    }

    @Test fun `whole remaining ETA requires three safe increments spanning three seconds`() {
        val session = SyncProgressDisplaySession()
        assertNull(session.project(state(), 0).wholeEta)
        assertNull(session.project(state(10), 1000).wholeEta)
        assertNull(session.project(state(20), 2000).wholeEta)
        assertEquals(7L, session.project(state(30), 3000).wholeEta)
        val switched = state(40, fact.copy(direction = SyncProgressDirection.DOWNLOAD, scope = "run:download"))
        assertEquals(6L, session.project(switched, 4000).wholeEta)
        assertNull(session.project(switched.copy(nowMillis = -900000), 14000).wholeEta)
    }

    @Test fun `whole rate follows the bounded recent confirmation cadence`() {
        val session = SyncProgressDisplaySession()
        var confirmed = 0L
        fun observed() = state(confirmed).copy(run = run.copy(plannedItems = 100_000, confirmedItems = confirmed))
        session.project(observed(), 0)
        for (batch in 1..21) {
            confirmed += if (batch <= 12) 256 else 512
            session.project(observed(), batch * 20_000L)
        }
        assertEquals(3607L, session.project(observed(), 420_000).wholeEta)
        assertNull(session.project(observed(), 540_000).wholeEta)
    }

    @Test fun `holds reset whole-run rate and recovery never estimates from elapsed suspended time`() {
        val session = SyncProgressDisplaySession()
        for (i in 0..3) session.project(state(i * 10L), i * 1000L)
        val waiting = state(30).copy(run = run.copy(state = SyncRunState.WAITING_NETWORK, confirmedItems = 30))
        assertNull(session.project(waiting, 4000).wholeEta)
        assertNull(session.project(state(30), 10000).wholeEta)
        assertNull(session.project(state(40), 11000).wholeEta)
        assertNull(session.project(state(50), 12000).wholeEta)
        assertEquals(4L, session.project(state(60), 13000).wholeEta)
    }

    @Test fun `new run and recovery attempt invalidate rate without inventing denominator or confirming bytes`() {
        val session = SyncProgressDisplaySession()
        for (i in 0..3) session.project(state(i * 10L), i * 1000L)
        val recovery = state(30).copy(run = run.copy(attemptId = 2, confirmedItems = 30))
        assertNull(session.project(recovery, 4000).wholeEta)
        assertEquals(0.3f, session.project(recovery, 4000).fraction)
        val next = state().copy(run = run.copy(runId = "new-run", plannedItems = null))
        assertNull(session.project(next, 5000).fraction)
        assertNull(session.project(next, 5000).wholeEta)
        assertEquals(0L, session.project(next, 5000).confirmed)
    }
}
