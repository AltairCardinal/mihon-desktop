package mihon.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import mihon.data.sync.http.SyncHttpBodyDirection
import mihon.data.sync.runtime.SyncLiveProgressSession
import mihon.data.sync.runtime.SyncProgressDirection
import mihon.data.sync.runtime.SyncProgressFact
import mihon.data.sync.runtime.SyncProgressStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SyncLiveProgressSessionTest {
    @Test
    fun `rapid byte and batch callbacks publish at most every 250 milliseconds`() {
        var nowMillis = 0L
        var nowNanos = 0L
        val sink = MutableStateFlow<SyncProgressFact?>(null)
        val session = SyncLiveProgressSession("run", sink, nanos = { ++nowNanos }, millis = { nowMillis })
        session.activate()
        session.begin(session.scope("round"), SyncProgressStage.PREPARING, SyncProgressDirection.UPLOAD, 20)
        repeat(10) { session.batch(SyncProgressStage.PREPARING, "batch-$it", 1) }
        assertEquals(0L, sink.value?.completedItems)
        nowMillis = 250L
        session.batch(SyncProgressStage.PREPARING, "batch-10", 1)
        assertEquals(11L, sink.value?.completedItems)
    }

    @Test
    fun `old run cannot overwrite a newer run display`() {
        var active = true
        var nowNanos = 0L
        val sink = MutableStateFlow<SyncProgressFact?>(null)
        val old = SyncLiveProgressSession("old", sink, nanos = { ++nowNanos }, canPublish = { active })
        old.activate()
        active = false
        val fresh = SyncLiveProgressSession("fresh", sink, nanos = { ++nowNanos })
        fresh.activate()
        old.begin(old.scope("late"), SyncProgressStage.PREPARING, SyncProgressDirection.UPLOAD, 1)
        assertEquals("fresh:initial", sink.value?.scope)
    }

    @Test
    fun `tagged request and response have separate effective bytes while request retry is deduplicated`() {
        var nowNanos = 0L
        val sink = MutableStateFlow<SyncProgressFact?>(null)
        val session = SyncLiveProgressSession("run", sink, nanos = { ++nowNanos })
        session.activate()
        session.begin(session.scope("round"), SyncProgressStage.TRANSFERRING, SyncProgressDirection.UPLOAD, 1)
        session.onBodyWorkBytes(SyncHttpBodyDirection.UPLOAD, 1, 100, 100, "blob")
        session.onBodyWorkBytes(SyncHttpBodyDirection.DOWNLOAD, 1, 20, 20, "blob")
        session.onBodyWorkBytes(SyncHttpBodyDirection.UPLOAD, 2, 60, 100, "blob")
        assertEquals(120L, session.snapshot().effectiveBytes)
        assertEquals(180L, session.snapshot().networkBytes)
    }

    @Test
    fun `inconsistent telemetry cannot throw into the exchange caller`() {
        var nowNanos = 0L
        val sink = MutableStateFlow<SyncProgressFact?>(null)
        val session = SyncLiveProgressSession("run", sink, nanos = { ++nowNanos })
        session.activate()
        val scope = session.scope("frozen")
        session.begin(scope, SyncProgressStage.PREPARING, SyncProgressDirection.UPLOAD, 10)
        session.begin(scope, SyncProgressStage.PREPARING, SyncProgressDirection.UPLOAD, 11)
        assertEquals(10L, session.snapshot().totalItems)
    }
}
