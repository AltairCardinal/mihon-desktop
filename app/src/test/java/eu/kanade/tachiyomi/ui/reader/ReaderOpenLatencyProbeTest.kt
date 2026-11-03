package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderOpenLatencyProbeTest {
    @Test
    fun reconfigurationRejectsPriorEpoch() {
        ReaderOpenLatencyProbe.configure(true, 10, 20, "first")
        val old = ReaderOpenLatencyProbe.startChapter(10, 20)
        ReaderOpenLatencyProbe.configure(true, 10, 20, "second")
        val current = ReaderOpenLatencyProbe.startChapter(10, 20)
        assertNotEquals(old, current)
        assertFalse(ReaderOpenLatencyProbe.record("FIRST_DRAW", 0, 20, old))
        assertTrue(ReaderOpenLatencyProbe.record("FIRST_DRAW", 0, 20, current))
    }

    @Test
    fun targetHeaderCallIsObservableAndFirstEventWins() {
        ReaderOpenLatencyProbe.configure(true, 10, 20, "target-header")
        val request = ReaderOpenLatencyProbe.startChapter(10, 20)
        assertFalse(ReaderOpenLatencyProbe.record("TARGET_HEADER_BEGIN", 1, 20, request))
        assertFalse(ReaderOpenLatencyProbe.record("TARGET_HEADER_BEGIN", 0, 21, request))
        assertFalse(ReaderOpenLatencyProbe.record("TARGET_HEADER_BEGIN", 0, 20, request + 1))
        assertTrue(ReaderOpenLatencyProbe.record("TARGET_HEADER_BEGIN", 0, 20, request))
        val begin = ReaderOpenLatencyProbe.capturedEvents().single { it.event == "TARGET_HEADER_BEGIN" }
        assertFalse(ReaderOpenLatencyProbe.record("TARGET_HEADER_BEGIN", 0, 20, request))
        assertEquals(begin, ReaderOpenLatencyProbe.capturedEvents().single { it.event == "TARGET_HEADER_BEGIN" })
        assertFalse(ReaderOpenLatencyProbe.record("TARGET_HEADER_END", 1, 20, request))
        assertFalse(ReaderOpenLatencyProbe.record("TARGET_HEADER_END", 0, 20, request + 1))
        assertTrue(ReaderOpenLatencyProbe.record("TARGET_HEADER_END", 0, 20, request))
        assertFalse(ReaderOpenLatencyProbe.record("TARGET_HEADER_END", 0, 20, request))
        val end = ReaderOpenLatencyProbe.capturedEvents().single { it.event == "TARGET_HEADER_END" }
        assertTrue(begin.monotonicNanos <= end.monotonicNanos)
        ReaderOpenLatencyProbe.configure(false, 10, 20, "off")
    }

    @Test
    fun diagnosticBudgetIsBoundedAndDisabledDoesNothing() {
        ReaderOpenLatencyProbe.configure(true, 10, 20, "bounded")
        repeat(32) { assertTrue(ReaderOpenLatencyProbe.startChapter(10, 20) > 0) }
        assertEquals(0L, ReaderOpenLatencyProbe.startChapter(10, 20))
        ReaderOpenLatencyProbe.configure(false, 10, 20, "off")
        assertEquals(0L, ReaderOpenLatencyProbe.startChapter(10, 20))
        assertFalse(ReaderOpenLatencyProbe.record("FIRST_DRAW"))
    }
}
