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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncProgressPresentationTest {
    private val run = SyncRunSnapshot(
        "run", "space", 1, SyncTrigger.MANUAL, SyncRunState.RUNNING,
        SyncRunPhase.UPLOADING, 0, 0, 0, 0, 0, attemptId = 1, nextRetryAt = 0,
        lastProgressAt = 0, stopReason = null, ownerSession = null, createdAt = 0, updatedAt = 0,
    )
    private val fact = SyncProgressFact(
        "run:a", SyncProgressStage.TRANSFERRING,
        SyncProgressDirection.UPLOAD, 6, 10, 64, 64, 100, 10, SyncProgressHold.ACTIVE,
        5, 20, confirmedThisRun = 0, secondsWithoutProgress = 0,
    )
    private fun state(f: SyncProgressFact = fact) = SyncPanelState(visible = true, run = run, progress = f)

    @Test fun `D01 and D03 promote action at 800 and eta at 2000 without another event`() {
        val session = SyncProgressDisplaySession()
        assertNull(session.project(state(), 0).fraction)
        assertNull(session.project(state(), 799).fraction)
        assertEquals(0.64f, session.project(state(), 800).fraction)
        assertNull(session.project(state(fact.copy(wholeEtaSeconds = 18)), 1999).wholeEta)
        assertEquals(18L, session.project(state(fact.copy(wholeEtaSeconds = 18)), 2000).wholeEta)
        assertNull(session.project(state(fact.copy(secondsWithoutProgress = 10)), 2001).wholeEta)
    }

    @Test fun `D02 scope hold and attempt changes invalidate a displayed percentage immediately`() {
        val session = SyncProgressDisplaySession()
        session.project(state(), 0)
        assertEquals(0.64f, session.project(state(), 800).fraction)
        assertNull(session.project(state(fact.copy(scope = "run:b")), 801).fraction)
        assertNull(session.project(state(fact.copy(hold = SyncProgressHold.PAUSING)), 1601).fraction)
        assertFalse(session.project(state().copy(run = run.copy(state = SyncRunState.PAUSED_USER)), 1602).active)
    }

    @Test fun `D04 null zero and terminal counts use safe fields and ignore foreign facts`() {
        val session = SyncProgressDisplaySession()
        assertEquals(0L, session.project(state(), 0).confirmed)
        assertNull(session.project(state(fact.copy(confirmedThisRun = null, receivedItems = 1280)), 1000).confirmed)
        assertNull(session.project(state(fact.copy(scope = "other", confirmedThisRun = 1280)), 2000).confirmed)
        val terminal = state().copy(run = run.copy(state = SyncRunState.SUCCEEDED, confirmedItems = 7))
        assertEquals(7L, session.project(terminal, 2001).confirmed)
        assertNull(session.project(terminal, 2001).wholeEta)
    }

    @Test fun `D01 rapid changes lock for two seconds and numeric updates do not count`() {
        val session = SyncProgressDisplaySession()
        session.project(state(), 0)
        session.project(state(fact.copy(direction = SyncProgressDirection.DOWNLOAD)), 500)
        session.project(state(fact.copy(stage = SyncProgressStage.CONFIRMING)), 1000)
        val final = fact.copy(scope = "run:final")
        assertNull(session.project(state(final), 1500).fraction)
        assertNull(session.project(state(final.copy(completedItems = 999)), 2300).fraction)
        assertNull(session.project(state(final.copy(completedItems = 1000)), 3499).fraction)
        assertEquals(0.64f, session.project(state(final.copy(completedItems = 1000)), 3500).fraction)
    }

    @Test fun `D03 ten and sixty second deadlines survive numeric callbacks and wall clock changes`() {
        val session = SyncProgressDisplaySession()
        session.project(state(), 0)
        assertEquals(20L, session.project(state(), 2000).wholeEta)
        val refreshed = state(fact.copy(completedItems = 7)).copy(nowMillis = -900000)
        assertNull(session.project(refreshed, 10000).wholeEta)
        assertEquals(10L, session.project(refreshed, 10000).idleSeconds)
        assertEquals(60L, session.project(refreshed, 60000).idleSeconds)
        assertNull(session.project(refreshed, 60000).wholeEta)
        assertNull(session.project(state(fact.copy(secondsWithoutProgress = 1)), 60001).wholeEta)
        assertEquals(20L, session.project(state(fact.copy(secondsWithoutProgress = 1)), 62001).wholeEta)
    }

    @Test fun `D02 attempt direction and invalid byte values never retain an old denominator`() {
        val session = SyncProgressDisplaySession()
        session.project(state(), 0)
        session.project(state(), 800)
        assertNull(session.project(state().copy(run = run.copy(attemptId = 2)), 801).fraction)
        assertNull(session.project(state(fact.copy(direction = SyncProgressDirection.DOWNLOAD)), 802).fraction)
        assertNull(session.project(state(fact.copy(effectiveBytes = 101)), 2000).fraction)
        assertNull(session.project(state(fact.copy(effectiveBytes = -1)), 3000).fraction)
        assertNull(session.project(state(fact.copy(totalBytes = 0)), 4000).fraction)
    }

    @Test fun `D05 transfer complete is active and zero terminal counts do not imply latest`() {
        val session = SyncProgressDisplaySession()
        val transferring = state(fact.copy(effectiveBytes = 100))
        session.project(transferring, 0)
        val p = session.project(transferring, 800)
        assertEquals(1f, p.fraction)
        assertTrue(p.active)
        assertNotEquals(tachiyomi.i18n.MR.strings.sync_phase_complete, p.status)
        val success = session.project(state().copy(run = run.copy(state = SyncRunState.SUCCEEDED)), 801)
        assertEquals(tachiyomi.i18n.MR.strings.sync_last_succeeded, success.status)
        assertEquals(0L, success.confirmed)
    }
}
