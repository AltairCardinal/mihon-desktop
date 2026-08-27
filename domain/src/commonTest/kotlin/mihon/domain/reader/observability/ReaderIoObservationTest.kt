package mihon.domain.reader.observability

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ReaderIoObservationTest {

    @Test
    fun `reporter stamps stable reader identity with the monotonic clock`() {
        val events = mutableListOf<ReaderIoEvent>()
        val reporter = ReaderIoReporter(
            probe = ReaderIoProbe(events::add),
            clock = ReaderMonotonicClock { 42L },
        )
        val pageId = ReaderPageId(ReaderChapterId(7L), 3)

        reporter.report(
            type = ReaderIoEventType.OPEN_PAGE,
            chapterId = pageId.chapterId,
            pageId = pageId,
            generation = 11L,
            purpose = ReaderIoPurpose.CURRENT_PAGE,
        )

        assertEquals(
            listOf(
                ReaderIoEvent(
                    type = ReaderIoEventType.OPEN_PAGE,
                    monotonicNanos = 42L,
                    chapterId = ReaderChapterId(7L),
                    pageId = pageId,
                    generation = 11L,
                    purpose = ReaderIoPurpose.CURRENT_PAGE,
                ),
            ),
            events,
        )
    }

    @Test
    fun `disabled reporter neither reads the clock nor allocates an event`() {
        var clockRead = false
        val reporter = ReaderIoReporter(
            probe = ReaderIoProbe.None,
            clock = ReaderMonotonicClock {
                clockRead = true
                42L
            },
        )

        reporter.report(
            type = ReaderIoEventType.OPEN_READER_INTENT,
            chapterId = ReaderChapterId(7L),
            generation = 1L,
            purpose = ReaderIoPurpose.READER_OPEN,
        )

        assertFalse(clockRead)
    }
}
