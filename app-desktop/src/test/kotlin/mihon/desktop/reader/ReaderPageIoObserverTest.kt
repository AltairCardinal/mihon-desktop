package mihon.desktop.reader

import java.util.concurrent.CopyOnWriteArrayList
import mihon.domain.reader.observability.ReaderIoEvent
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderPageIoObserverTest {
    @Test
    fun `real opens and decodes remain countable while first presentation is unique`() {
        val events = CopyOnWriteArrayList<ReaderIoEvent>()
        val observer = ReaderPageIoObserver(
            ReaderIoReporter(ReaderIoProbe(events::add), ReaderMonotonicClock { 1L }),
        )
        val pageId = ReaderPageId(ReaderChapterId(7L), 0)

        repeat(2) {
            observer.pageOpened(pageId, 3L)
            observer.pageDecoded(pageId, 3L)
            observer.pagePresented(pageId, 3L)
        }

        assertEquals(2, events.count { it.type == ReaderIoEventType.OPEN_PAGE })
        assertEquals(2, events.count { it.type == ReaderIoEventType.DECODE })
        assertEquals(1, events.count { it.type == ReaderIoEventType.FIRST_PAGE_PRESENTED })
    }
}
