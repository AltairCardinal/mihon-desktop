package mihon.desktop.reader

import mihon.domain.reader.scheduler.ReaderScheduledRequest

enum class ReaderIoGatePoint {
    NON_CURRENT_PAGE,
    CACHE_SCAN,
    ADJACENT_IO,
}

/** Test-only timing control injected at real production I/O boundaries. */
fun interface ReaderIoGate {
    suspend fun await(point: ReaderIoGatePoint)

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
