package mihon.domain.reader.observability

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import tachiyomi.core.common.feedback.DiagnosticCategory
import tachiyomi.core.common.feedback.DiagnosticEvent
import tachiyomi.core.common.feedback.DiagnosticEvents
import tachiyomi.core.common.feedback.DiagnosticOperation
import tachiyomi.core.common.feedback.DiagnosticStage

enum class ReaderIoEventType {
    OPEN_READER_INTENT,
    PAGE_LIST_READY,
    OPEN_PAGE,
    HEADER_READ,
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

    /** Bounded header preparation for an ordinary Ready neighbour, outside spread matching. */
    NORMAL_NEIGHBOUR_HEADER,

    /** Bounded header preparation issued by the shared spread-matching scheduler for a real need. */
    SPREAD_SAMPLE_HEADER,

    /** Bounded header preparation issued by the shared spread-matching scheduler as pure lookahead. */
    SPREAD_SPECULATIVE_HEADER,
}

data class ReaderIoEvent(
    val type: ReaderIoEventType,
    val monotonicNanos: Long,
    val chapterId: ReaderChapterId,
    val pageId: ReaderPageId? = null,
    val generation: Long,
    val purpose: ReaderIoPurpose,
    val byteLimit: Int? = null,
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
    val enabled: Boolean get() = probe.enabled || DiagnosticEvents.enabled(DiagnosticCategory.READER) ||
        DiagnosticEvents.enabled(DiagnosticCategory.PERFORMANCE)

    fun report(
        type: ReaderIoEventType,
        chapterId: ReaderChapterId,
        pageId: ReaderPageId? = null,
        generation: Long,
        purpose: ReaderIoPurpose,
        byteLimit: Int? = null,
    ) {
        if (!enabled) return
        val operation = when (type) {
            ReaderIoEventType.OPEN_READER_INTENT -> DiagnosticOperation.APPLICATION
            ReaderIoEventType.PAGE_LIST_READY, ReaderIoEventType.FIRST_PAGE_PRESENTED -> DiagnosticOperation.READER_PAGE
            else -> DiagnosticOperation.READER_IMAGE
        }
        val stage = when (type) {
            ReaderIoEventType.OPEN_READER_INTENT, ReaderIoEventType.OPEN_PAGE -> DiagnosticStage.START
            ReaderIoEventType.DECODE -> DiagnosticStage.DECODE
            else -> DiagnosticStage.SUCCESS
        }
        val pageNumber = pageId?.sourcePageIndex?.takeIf { it < 1_000_000_000 }?.plus(1)
        DiagnosticEvents.record(DiagnosticEvent(DiagnosticCategory.READER, operation, stage, count = pageNumber))
        DiagnosticEvents.record(DiagnosticEvent(DiagnosticCategory.PERFORMANCE, operation, stage, count = pageNumber))
        if (!probe.enabled) return
        probe.record(
            ReaderIoEvent(
                type = type,
                monotonicNanos = clock.nowNanos(),
                chapterId = chapterId,
                pageId = pageId,
                generation = generation,
                purpose = purpose,
                byteLimit = byteLimit,
            ),
        )
    }
}
