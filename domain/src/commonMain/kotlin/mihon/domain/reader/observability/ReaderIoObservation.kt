package mihon.domain.reader.observability

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId

enum class ReaderIoEventType {
    OPEN_READER_INTENT,
    PAGE_LIST_READY,
    OPEN_PAGE,
    DECODE,
    FIRST_PAGE_PRESENTED,
    CACHE_RECONCILE,
    ADJACENT_IO,
}

enum class ReaderIoPurpose {
    READER_OPEN,
    PAGE_LIST,
    CURRENT_PAGE,
    VISIBLE_DECODE,
    FIRST_PRESENTATION,
    CACHE_MAINTENANCE,
    ADJACENT_PREFETCH,
}

data class ReaderIoEvent(
    val type: ReaderIoEventType,
    val monotonicNanos: Long,
    val chapterId: ReaderChapterId,
    val pageId: ReaderPageId? = null,
    val generation: Long,
    val purpose: ReaderIoPurpose,
) {
    init {
        require(monotonicNanos >= 0L) { "monotonicNanos must be non-negative" }
        require(generation >= 0L) { "generation must be non-negative" }
        require(pageId == null || pageId.chapterId == chapterId) { "Page and chapter identities must match" }
    }
}

fun interface ReaderMonotonicClock {
    fun nowNanos(): Long
}

fun interface ReaderIoProbe {
    val enabled: Boolean get() = true

    fun record(event: ReaderIoEvent)

    /** Binds a runtime to the current probe scenario; dynamic probes may return an isolated view. */
    fun bind(): ReaderIoProbe = this

    companion object {
        val None: ReaderIoProbe = object : ReaderIoProbe {
            override val enabled: Boolean = false
            override fun record(event: ReaderIoEvent) = Unit
        }
    }
}

class ReaderIoReporter(
    private val probe: ReaderIoProbe = ReaderIoProbe.None,
    private val clock: ReaderMonotonicClock,
) {
    val enabled: Boolean get() = probe.enabled

    fun report(
        type: ReaderIoEventType,
        chapterId: ReaderChapterId,
        pageId: ReaderPageId? = null,
        generation: Long,
        purpose: ReaderIoPurpose,
    ) {
        if (!enabled) return
        probe.record(
            ReaderIoEvent(
                type = type,
                monotonicNanos = clock.nowNanos(),
                chapterId = chapterId,
                pageId = pageId,
                generation = generation,
                purpose = purpose,
            ),
        )
    }
}
