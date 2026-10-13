package mihon.domain.reader

import kotlinx.coroutines.CancellationException
import mihon.domain.reader.session.ReaderPageId
import okio.Buffer
import okio.ForwardingSource
import okio.Source

/** A byte-read capability for one admitted optional request; ordinary visible loading has no permit. */
class ReaderDualPageFactReadPermit(
    private val budget: ReaderDualPageFactBudget,
    private val pageId: ReaderPageId,
    private val clockMillis: () -> Long,
) {
    fun reserve(byteCount: Long): Long = budget.reserveRead(pageId, byteCount, clockMillis())
    fun returnUnread(byteCount: Long) = budget.returnUnreadBytes(pageId, byteCount)
    fun remainingWaitMillis(): Long = budget.remainingWaitMillis(clockMillis())
    val exhausted: Boolean get() = !budget.canRead(pageId, clockMillis())

    /**
     * Reserve every fresh encoded-source read, including Ready files and archive entries.
     * OS caching does not make these bytes an application-owned cached result. Reusing a
     * confirmed scalar or header performs no stream read and needs no new byte reservation.
     */
    fun bound(source: Source, nanoTime: () -> Long): Source = object : ForwardingSource(source) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (byteCount == 0L) return 0
            val granted = reserve(byteCount)
            if (granted == 0L) throw CancellationException("Optional page facts reached their read budget")
            val deadline = nanoTime() + remainingWaitMillis() * 1_000_000L
            val timeout = source.timeout()
            if (!timeout.hasDeadline() || timeout.deadlineNanoTime() > deadline) timeout.deadlineNanoTime(deadline)
            var consumed = 0L
            try {
                return super.read(sink, granted).also { if (it > 0) consumed = it }
            } finally {
                returnUnread(granted - consumed)
            }
        }
    }
}
