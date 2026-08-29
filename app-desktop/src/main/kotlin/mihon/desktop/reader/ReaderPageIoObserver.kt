package mihon.desktop.reader

import java.util.concurrent.ConcurrentHashMap
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoPurpose
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.session.ReaderPageId

class ReaderPageIoObserver internal constructor(
    private val reporter: ReaderIoReporter,
    private val onFirstPagePresented: (ReaderPageId, Long) -> Unit = { _, _ -> },
) {
    private data class EventIdentity(
        val type: ReaderIoEventType,
        val pageId: ReaderPageId,
        val generation: Long,
    )

    private val reported = ConcurrentHashMap.newKeySet<EventIdentity>()

    fun pageOpened(pageId: ReaderPageId, generation: Long) = reporter.report(
        ReaderIoEventType.OPEN_PAGE,
        pageId.chapterId,
        pageId,
        generation,
        ReaderIoPurpose.CURRENT_PAGE,
    )

    fun pageDecoded(pageId: ReaderPageId, generation: Long) = reporter.report(
        ReaderIoEventType.DECODE,
        pageId.chapterId,
        pageId,
        generation,
        ReaderIoPurpose.VISIBLE_DECODE,
    )

    fun pagePresented(pageId: ReaderPageId, generation: Long) {
        if (
            reportOnce(
                ReaderIoEventType.FIRST_PAGE_PRESENTED,
                pageId,
                generation,
                ReaderIoPurpose.FIRST_PRESENTATION,
            )
        ) {
            onFirstPagePresented(pageId, generation)
        }
    }

    private fun reportOnce(
        type: ReaderIoEventType,
        pageId: ReaderPageId,
        generation: Long,
        purpose: ReaderIoPurpose,
    ): Boolean {
        if (!reported.add(EventIdentity(type, pageId, generation))) return false
        reporter.report(type, pageId.chapterId, pageId, generation, purpose)
        return true
    }
}
