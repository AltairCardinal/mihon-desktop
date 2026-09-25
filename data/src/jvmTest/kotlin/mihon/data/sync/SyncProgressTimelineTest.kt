package mihon.data.sync

import mihon.data.sync.runtime.SyncProgressDirection
import mihon.data.sync.runtime.SyncProgressHold
import mihon.data.sync.runtime.SyncProgressStage
import mihon.data.sync.runtime.SyncProgressTimeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncProgressTimelineTest {
    private val second = 1_000_000_000L

    @Test
    fun `unknown total stays unknown and unique event count survives phase changes`() {
        val timeline = SyncProgressTimeline()
        timeline.begin(
            "space-1-generation-2-round-1",
            SyncProgressStage.PREPARING,
            SyncProgressDirection.UPLOAD,
            null,
            atNanos = 0,
        )
        timeline.prepared("event-1", second)
        timeline.prepared("event-1", 2 * second)
        assertEquals(1, timeline.snapshot(2 * second).completedItems)
        assertNull(timeline.snapshot(2 * second).totalItems)
        assertNull(timeline.snapshot(2 * second).stageEtaSeconds)

        timeline.begin(
            "space-1-generation-2-round-1",
            SyncProgressStage.CONFIRMING,
            SyncProgressDirection.UPLOAD,
            10,
            atNanos =
            3 * second,
        )
        timeline.confirmBatch("atomic-publish", 10, 4 * second)
        timeline.confirmBatch("atomic-publish", 10, 5 * second)
        assertEquals(10, timeline.snapshot(5 * second).completedItems)
        timeline.begin(
            "space-1-generation-2-round-1",
            SyncProgressStage.PREPARING,
            SyncProgressDirection.UPLOAD,
            null,
            atNanos =
            6 * second,
        )
        assertEquals(1, timeline.snapshot(6 * second).completedItems)
    }

    @Test
    fun `body offsets count effective bytes once while retry traffic remains visible`() {
        val timeline = SyncProgressTimeline()
        timeline.begin(
            "scope",
            SyncProgressStage.TRANSFERRING,
            SyncProgressDirection.UPLOAD,
            2,
            totalBytes = 100,
            atNanos = 0,
        )
        timeline.bodyProgress("batch-1", "attempt-1", 40, second)
        timeline.bodyProgress("batch-1", "attempt-1", 40, 2 * second)
        timeline.bodyProgress("batch-1", "attempt-2", 20, 3 * second)
        timeline.bodyProgress("batch-1", "attempt-2", 70, 4 * second)
        timeline.transferred("event-1", 4 * second)
        val state = timeline.snapshot(4 * second)
        assertEquals(70, state.effectiveBytes)
        assertEquals(110, state.networkBytes)
        assertEquals(1, state.completedItems)
        assertEquals(100, state.totalBytes)
    }

    @Test
    fun `eta warms on three increments over three seconds and expires after ten seconds`() {
        val timeline = SyncProgressTimeline()
        timeline.begin("scope", SyncProgressStage.PREPARING, SyncProgressDirection.UPLOAD, 12, atNanos = 0)
        timeline.prepared("1", second)
        timeline.prepared("2", 2 * second)
        assertNull(timeline.snapshot(2 * second).stageEtaSeconds)
        timeline.prepared("3", 4 * second)
        val warmed = timeline.snapshot(4 * second)
        assertTrue((warmed.stageEtaSeconds ?: 0) > 0)
        assertNull(timeline.snapshot(15 * second).stageEtaSeconds)
        timeline.setHold(SyncProgressHold.PAUSED, 15 * second)
        assertEquals(SyncProgressHold.PAUSED, timeline.snapshot(30 * second).hold)
        assertNull(timeline.snapshot(30 * second).stageEtaSeconds)
        assertEquals(30, timeline.snapshot(30 * second).elapsedSeconds)
    }

    @Test
    fun `atomic confirmation has no invented eta and future unknown prevents whole eta`() {
        val timeline = SyncProgressTimeline()
        timeline.begin(
            "scope",
            SyncProgressStage.TRANSFERRING,
            SyncProgressDirection.DOWNLOAD,
            10,
            totalBytes = 100,
            atNanos = 0,
        )
        timeline.bodyProgress("blob", "attempt", 25, second)
        timeline.bodyProgress("blob", "attempt", 50, 2 * second)
        timeline.bodyProgress("blob", "attempt", 75, 4 * second)
        val transferring = timeline.snapshot(4 * second)
        assertTrue((transferring.stageEtaSeconds ?: 0) > 0)
        assertNull(transferring.wholeEtaSeconds)
        timeline.begin("scope", SyncProgressStage.CONFIRMING, SyncProgressDirection.DOWNLOAD, 10, atNanos = 5 * second)
        assertNull(timeline.snapshot(5 * second).stageEtaSeconds)
        timeline.confirmBatch("transaction", 10, 8 * second)
        assertNull(timeline.snapshot(8 * second).stageEtaSeconds)
    }

    @Test
    fun `new scope or direction resets short term rate and its own count`() {
        val timeline = SyncProgressTimeline()
        timeline.begin("round-1", SyncProgressStage.PREPARING, SyncProgressDirection.UPLOAD, 10, atNanos = 0)
        timeline.prepared("1", second)
        timeline.prepared("2", 2 * second)
        timeline.prepared("3", 4 * second)
        assertTrue((timeline.snapshot(4 * second).stageEtaSeconds ?: 0) > 0)
        timeline.begin("round-1", SyncProgressStage.PREPARING, SyncProgressDirection.DOWNLOAD, 10, atNanos = 5 * second)
        assertEquals(0, timeline.snapshot(5 * second).completedItems)
        assertNull(timeline.snapshot(5 * second).stageEtaSeconds)
        timeline.begin("round-2", SyncProgressStage.PREPARING, SyncProgressDirection.UPLOAD, 10, atNanos = 6 * second)
        assertEquals(0, timeline.snapshot(6 * second).completedItems)
    }

    @Test
    fun `unknown total can freeze once and batches advance without event iteration`() {
        val timeline = SyncProgressTimeline()
        timeline.begin("scope", SyncProgressStage.PREPARING, SyncProgressDirection.UPLOAD, null, atNanos = 0)
        timeline.prepareBatch("batch-1", 4, second)
        timeline.prepareBatch("batch-1", 4, 2 * second)
        timeline.begin("scope", SyncProgressStage.PREPARING, SyncProgressDirection.UPLOAD, 10, atNanos = 3 * second)
        assertEquals(4, timeline.snapshot(3 * second).completedItems)
        assertEquals(10, timeline.snapshot(3 * second).totalItems)
        timeline.begin(
            "scope",
            SyncProgressStage.TRANSFERRING,
            SyncProgressDirection.UPLOAD,
            10,
            totalBytes = 100,
            atNanos =
            4 * second,
        )
        timeline.transferBatch("batch-1", 4, 5 * second)
        timeline.transferBatch("batch-1", 4, 6 * second)
        assertEquals(4, timeline.snapshot(6 * second).completedItems)
    }

    @Test
    fun `unidentified HTTP retry bytes never become effective work`() {
        val timeline = SyncProgressTimeline()
        timeline.begin("scope", SyncProgressStage.TRANSFERRING, SyncProgressDirection.UPLOAD, 1, atNanos = 0)
        timeline.networkProgress("request-1", 40, second)
        timeline.networkProgress("request-2", 40, 2 * second)
        val state = timeline.snapshot(2 * second)
        assertEquals(80, state.networkBytes)
        assertEquals(0, state.effectiveBytes)
        assertNull(state.stageEtaSeconds)
    }

    @Test
    fun `new work round is explicit and remains visible through stage changes`() {
        val timeline = SyncProgressTimeline()
        timeline.begin("round-1", SyncProgressStage.PREPARING, SyncProgressDirection.UPLOAD, 2, atNanos = 0)
        assertEquals(false, timeline.snapshot(0).additionalWork)
        timeline.begin(
            "round-2",
            SyncProgressStage.PREPARING,
            SyncProgressDirection.UPLOAD,
            3,
            atNanos = second,
            additionalWork = true,
        )
        timeline.begin("round-2", SyncProgressStage.TRANSFERRING, SyncProgressDirection.UPLOAD, 3, atNanos = 2 * second)
        assertEquals(true, timeline.snapshot(2 * second).additionalWork)
    }

    @Test
    fun `known event worklist can estimate transfer locally when whole byte total is unknown`() {
        val timeline = SyncProgressTimeline()
        timeline.begin("round", SyncProgressStage.TRANSFERRING, SyncProgressDirection.UPLOAD, 12, atNanos = 0)
        timeline.transferBatch("batch-1", 2, second)
        timeline.transferBatch("batch-2", 2, 2 * second)
        assertNull(timeline.snapshot(2 * second).stageEtaSeconds)
        timeline.transferBatch("batch-3", 2, 4 * second)
        assertTrue((timeline.snapshot(4 * second).stageEtaSeconds ?: 0) > 0)
        assertNull(timeline.snapshot(4 * second).wholeEtaSeconds)
    }

    @Test
    fun `current request body is separate from frozen round total and resets on retry`() {
        val timeline = SyncProgressTimeline()
        timeline.begin("round", SyncProgressStage.TRANSFERRING, SyncProgressDirection.UPLOAD, 1, atNanos = 0)
        timeline.bodyProgress("blob", "attempt-1", 40, second, bodyTotal = 100)
        assertEquals(40L, timeline.snapshot(second).activeBodyBytes)
        assertEquals(100L, timeline.snapshot(second).activeBodyTotal)
        assertNull(timeline.snapshot(second).totalBytes)
        timeline.bodyProgress("blob", "attempt-2", 20, 2 * second, bodyTotal = 100)
        assertEquals(20L, timeline.snapshot(2 * second).activeBodyBytes)
        assertEquals(40L, timeline.snapshot(2 * second).effectiveBytes)
        timeline.finishBody("attempt-2", 3 * second)
        assertNull(timeline.snapshot(3 * second).activeBodyBytes)
    }

    @Test
    fun `known current body gets only a local eta after warm increments and resets on retry`() {
        val timeline = SyncProgressTimeline()
        timeline.begin("round", SyncProgressStage.TRANSFERRING, SyncProgressDirection.DOWNLOAD, 1, atNanos = 0)
        timeline.bodyProgress("GET:blob", "GET:attempt-1", 10, second, bodyTotal = 100)
        timeline.bodyProgress("GET:blob", "GET:attempt-1", 20, 2 * second, bodyTotal = 100)
        assertNull(timeline.snapshot(2 * second).activeBodyEtaSeconds)
        timeline.bodyProgress("GET:blob", "GET:attempt-1", 40, 4 * second, bodyTotal = 100)
        val warmed = timeline.snapshot(4 * second)
        assertTrue((warmed.activeBodyEtaSeconds ?: 0) > 0)
        assertNull(warmed.stageEtaSeconds)
        assertNull(warmed.wholeEtaSeconds)

        timeline.bodyProgress("GET:blob", "GET:attempt-2", 10, 5 * second, bodyTotal = 100)
        assertNull(timeline.snapshot(5 * second).activeBodyEtaSeconds)
        assertEquals(40L, timeline.snapshot(5 * second).effectiveBytes)
        timeline.bodyProgress("GET:blob", "GET:attempt-2", 20, 6 * second, bodyTotal = 100)
        timeline.bodyProgress("GET:blob", "GET:attempt-2", 30, 9 * second, bodyTotal = 100)
        assertTrue((timeline.snapshot(9 * second).activeBodyEtaSeconds ?: 0) > 0)
        assertNull(timeline.snapshot(20 * second).activeBodyEtaSeconds)
        timeline.setHold(SyncProgressHold.WAITING, 20 * second)
        assertNull(timeline.snapshot(20 * second).activeBodyEtaSeconds)
    }

    @Test
    fun `unknown current body size and completed request never advertise a body eta`() {
        val timeline = SyncProgressTimeline()
        timeline.begin("round", SyncProgressStage.TRANSFERRING, SyncProgressDirection.UPLOAD, 1, atNanos = 0)
        timeline.networkProgress("request", 10, second)
        timeline.networkProgress("request", 20, 2 * second)
        timeline.networkProgress("request", 40, 4 * second)
        assertNull(timeline.snapshot(4 * second).activeBodyEtaSeconds)
        timeline.networkProgress("request", 50, 5 * second, bodyTotal = 100)
        timeline.finishBody("request", 6 * second)
        assertNull(timeline.snapshot(6 * second).activeBodyEtaSeconds)
    }

    @Test
    fun `transfer rate survives confirmation between batches without charging confirmation time`() {
        val timeline = SyncProgressTimeline()
        timeline.begin("round", SyncProgressStage.TRANSFERRING, SyncProgressDirection.UPLOAD, 10, atNanos = 0)
        timeline.transferBatch("b1", 2, second)
        timeline.begin("round", SyncProgressStage.CONFIRMING, SyncProgressDirection.UPLOAD, 10, atNanos = second)
        timeline.confirmBatch("b1", 2, 10 * second)
        timeline.begin("round", SyncProgressStage.TRANSFERRING, SyncProgressDirection.UPLOAD, 10, atNanos = 11 * second)
        timeline.transferBatch("b2", 2, 13 * second)
        timeline.begin("round", SyncProgressStage.CONFIRMING, SyncProgressDirection.UPLOAD, 10, atNanos = 13 * second)
        timeline.confirmBatch("b2", 2, 22 * second)
        timeline.begin("round", SyncProgressStage.TRANSFERRING, SyncProgressDirection.UPLOAD, 10, atNanos = 23 * second)
        timeline.transferBatch("b3", 2, 25 * second)
        assertTrue((timeline.snapshot(25 * second).stageEtaSeconds ?: 0) > 0)
    }

    @Test
    fun `confirmation rate is local while later batches still require transfer`() {
        val timeline = SyncProgressTimeline()
        timeline.begin("round", SyncProgressStage.CONFIRMING, SyncProgressDirection.UPLOAD, 10, atNanos = 0)
        timeline.confirmBatch("b1", 2, second)
        timeline.confirmBatch("b2", 2, 2 * second)
        timeline.confirmBatch("b3", 2, 4 * second)
        val fact = timeline.snapshot(4 * second)
        assertTrue((fact.stageEtaSeconds ?: 0) > 0)
        assertNull(fact.wholeEtaSeconds)
    }

    @Test
    fun `committed import pages are auxiliary and do not complete preparation`() {
        val timeline = SyncProgressTimeline()
        timeline.begin("run:import:one", SyncProgressStage.PREPARING, SyncProgressDirection.UPLOAD, null, atNanos = 0)
        timeline.importPage("one", "page-4", 10, 4, second)
        timeline.importPage("one", "page-4", 10, 4, 2 * second)
        val import = timeline.snapshot(2 * second)
        assertEquals(4L, import.importCompletedItems)
        assertEquals(10L, import.importTotalItems)
        assertEquals(0L, import.completedItems)
        assertNull(import.totalItems)
        timeline.begin(
            "run:upload:one",
            SyncProgressStage.PREPARING,
            SyncProgressDirection.UPLOAD,
            10,
            atNanos =
            3 * second,
        )
        assertNull(timeline.snapshot(3 * second).importCompletedItems)
    }

    @Test
    fun `confirmed run items advance only once across work scopes and directions`() {
        val timeline = SyncProgressTimeline()
        timeline.begin("upload-1", SyncProgressStage.CONFIRMING, SyncProgressDirection.UPLOAD, 2, atNanos = 0)
        assertEquals(0L, timeline.snapshot(0).confirmedThisRun)
        timeline.confirmBatch("batch-a", 2, second)
        timeline.begin("upload-2", SyncProgressStage.CONFIRMING, SyncProgressDirection.UPLOAD, 2, atNanos = 2 * second)
        timeline.confirmBatch("batch-a", 2, 3 * second)
        timeline.begin(
            "download",
            SyncProgressStage.CONFIRMING,
            SyncProgressDirection.DOWNLOAD,
            3,
            atNanos = 4 * second,
        )
        timeline.confirmBatch("batch-b", 3, 5 * second)
        assertEquals(5L, timeline.snapshot(5 * second).confirmedThisRun)
    }
}
