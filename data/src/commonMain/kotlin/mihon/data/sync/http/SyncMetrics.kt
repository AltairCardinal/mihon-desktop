package mihon.data.sync.http

/** Low-cardinality diagnostics used by sync tests and optional runtime reporting. */
data class SyncMetricSnapshot(
    val httpCalls: Long = 0,
    val httpBytes: Long = 0,
    val httpFailures: Long = 0,
    val totalsReconciliations: Long = 0,
)

interface SyncMetrics {
    fun recordHttp(callBytes: Long, failed: Boolean)

    fun recordTotalsReconciliation()

    fun snapshot(): SyncMetricSnapshot
}

object NoopSyncMetrics : SyncMetrics {
    override fun recordHttp(callBytes: Long, failed: Boolean) = Unit

    override fun recordTotalsReconciliation() = Unit

    override fun snapshot(): SyncMetricSnapshot = SyncMetricSnapshot()
}

class InMemorySyncMetrics : SyncMetrics {
    private var calls = 0L
    private var bytes = 0L
    private var failures = 0L
    private var totalsReconciliations = 0L

    @Synchronized
    override fun recordHttp(callBytes: Long, failed: Boolean) {
        calls++
        bytes += callBytes.coerceAtLeast(0L)
        if (failed) failures++
    }

    @Synchronized
    override fun recordTotalsReconciliation() {
        totalsReconciliations++
    }

    @Synchronized
    override fun snapshot(): SyncMetricSnapshot =
        SyncMetricSnapshot(calls, bytes, failures, totalsReconciliations)
}
