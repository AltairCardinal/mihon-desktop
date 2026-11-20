package mihon.domain.sync.runtime

private val operationSequence = java.util.concurrent.atomic.AtomicLong()

/** Work shares belong to real boundaries, not elapsed time or a cap on item counts. */
enum class SyncOperationStage(val start: Double, val end: Double) {
    CHECKING(0.0, 0.02),
    AUTHORIZATION(0.02, 0.06),
    BASELINE(0.06, 0.14),
    DISCOVERY(0.14, 0.18),
    PLAN(0.18, 0.20),
    ITEMS(0.20, 0.85),
    HEAD(0.85, 0.90),
    INTEGRITY(0.90, 0.94),
    PERSISTING(0.94, 0.98),
}

data class SyncOperationToken(val id: String, val segment: Int)

data class SyncOperationProgress(
    val id: String,
    val sequence: Long = operationSequence.incrementAndGet(),
    val localOnly: Boolean = false,
    val segment: Int = 0,
    val runId: String? = null,
    val running: Boolean = true,
    val stage: SyncOperationStage = SyncOperationStage.CHECKING,
    val fraction: Double = 0.0,
    val segmentBase: Double = 0.0,
    val durableSuccess: Boolean = false,
    val outcome: SyncRunStatus? = null,
) {
    val token: SyncOperationToken get() = SyncOperationToken(id, segment)

    fun advance(stage: SyncOperationStage, completed: Double = 1.0, runId: String? = null): SyncOperationProgress {
        if (!running || !completed.isFinite()) return this
        val bounds = if (localOnly) {
            when (stage) {
                SyncOperationStage.CHECKING -> 0.0 to 0.10
                SyncOperationStage.BASELINE -> 0.10 to 0.20
                SyncOperationStage.ITEMS -> 0.20 to 0.85
                SyncOperationStage.INTEGRITY -> 0.85 to 0.94
                SyncOperationStage.PERSISTING -> 0.94 to 0.98
                else -> return this
            }
        } else {
            stage.start to stage.end
        }
        val part = bounds.first + (bounds.second - bounds.first) * completed.coerceIn(0.0, 1.0)
        val next = segmentBase + (1.0 - segmentBase) * part
        return copy(stage = stage, runId = runId ?: this.runId, fraction = maxOf(fraction, next))
    }

    fun nextSegment(): SyncOperationProgress = copy(
        segment = segment + 1,
        segmentBase = fraction,
        runId = null,
        durableSuccess = false,
        stage = SyncOperationStage.CHECKING,
    )

    fun finish(result: SyncRunResult): SyncOperationProgress {
        val success = result.status == SyncRunStatus.SUCCESS && durableSuccess
        return copy(running = false, fraction = if (success) 1.0 else fraction, outcome = result.status)
    }

    /** Float rounding must not turn an unfinished operation into a completed accessibility value. */
    fun displayFraction(): Float = if (fraction < 1.0) {
        fraction.toFloat().coerceAtMost(Float.fromBits(1f.toRawBits() - 1))
    } else if (!running && durableSuccess && outcome == SyncRunStatus.SUCCESS) {
        1f
    } else {
        Float.fromBits(1f.toRawBits() - 1)
    }
}
