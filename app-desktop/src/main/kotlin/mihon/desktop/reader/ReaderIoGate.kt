package mihon.desktop.reader

import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.scheduler.ReaderScheduledRequest

enum class ReaderIoGatePoint {
    NON_CURRENT_PAGE,
    CACHE_SCAN,
    ADJACENT_IO,
}

/** Test-only timing control injected at real production I/O boundaries. */
fun interface ReaderIoGate {
    suspend fun await(point: ReaderIoGatePoint)

    /** Freeze the explicit test controller for this real content owner. */
    fun bindContentReads(): ReaderIoGate = this
    fun registerContentOwner(): Long = 0L
    fun closeContentOwner(ownerId: Long) = Unit
    suspend fun awaitContentRead(ownerId: Long, request: ReaderPageContentOpenRequest) = Unit
    fun contentReadCompleted(
        ownerId: Long,
        request: ReaderPageContentOpenRequest,
        bytes: Int?,
        failure: Throwable?,
    ) = Unit

    companion object {
        val None = ReaderIoGate { }
    }
}

internal fun ReaderScheduledRequest.gatePoint(isAdjacentPrefetch: Boolean): ReaderIoGatePoint? = when {
    isAdjacentPrefetch -> ReaderIoGatePoint.ADJACENT_IO
    kind.priority != mihon.domain.reader.scheduler.ReaderRequestPriority.P0_INTERACTIVE ->
        ReaderIoGatePoint.NON_CURRENT_PAGE
    else -> null
}
