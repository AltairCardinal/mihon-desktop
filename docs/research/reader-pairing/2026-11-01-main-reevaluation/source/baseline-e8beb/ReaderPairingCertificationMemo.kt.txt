package mihon.domain.reader

import mihon.domain.reader.session.ReaderPageId

/**
 * One bounded reuse entry: the certification result together with the complete
 * set of values the engine actually read to produce it.
 *
 * The result is a pure function of the recorded read set, so a hit is only
 * allowed while every recorded value still reads back exactly as recorded.
 */
internal class ReaderPairingMemoEntry(
    val target: ReaderPageId,
    val locationPageId: ReaderPageId?,
    val locationSequence: Long,
    val settings: ReaderPairingSettings,
    val pageOrder: List<ReaderPageId>,
    val reads: Map<ReaderPairingReadKey, ReaderPairingReadValue>,
    val result: ReaderPairingCertification,
    val dependentPageIds: Set<ReaderPageId>,
    val scanSteps: Int,
)

/** Why a reuse attempt did not return the stored result. */
enum class ReaderPairingMemoMissReason {
    /** No entry existed for this session yet. */
    FIRST_USE,

    /** The stored entry belongs to another target. */
    TARGET_CHANGED,

    /** A guard or a recorded read value no longer matches. */
    DEPENDENCY_CHANGED,
}

/**
 * Accounting for the certification reuse path.
 *
 * `entries` counts real certification requests, `engineRuns` counts the actual
 * certifier executions and `scanStepsExecuted` counts only the scanning work
 * that those executions performed. A reused result never adds to the last two.
 */
class ReaderPairingCertificationMetrics {
    var entries: Long = 0L
        private set
    var engineRuns: Long = 0L
        private set
    var memoHits: Long = 0L
        private set
    var memoMisses: Long = 0L
        private set
    var scanStepsExecuted: Long = 0L
        private set
    var evictions: Long = 0L
        private set
    var oversizedBypasses: Long = 0L
        private set

    /** Calls that re-read a resident entry after its guards matched. */
    var validationCalls: Long = 0L
        private set

    /**
     * Recorded key values actually re-read and compared, including the first mismatch.
     * This excludes guard comparisons and is not a count of nested owner/header lookups.
     */
    var readValuesChecked: Long = 0L
        private set

    internal fun recordValidation() {
        validationCalls++
    }

    internal fun recordReadValueCheck() {
        readValuesChecked++
    }

    private val missReasons = LinkedHashMap<ReaderPairingMemoMissReason, Long>()

    val missReasonCounts: Map<ReaderPairingMemoMissReason, Long> get() = missReasons.toMap()

    internal fun recordMiss(reason: ReaderPairingMemoMissReason) {
        memoMisses++
        missReasons[reason] = (missReasons[reason] ?: 0L) + 1L
    }

    internal fun recordHit() {
        memoHits++
    }

    internal fun recordEngineRun(scanSteps: Int) {
        engineRuns++
        scanStepsExecuted += scanSteps.coerceAtLeast(0).toLong()
    }

    internal fun recordEviction(total: Long) {
        evictions = total
    }

    internal fun recordOversizedBypass() {
        oversizedBypasses++
    }

    internal fun recordEntry() {
        entries++
    }
}

/**
 * Storage bounds of one certification reuse entry.
 *
 * They bound what may be stored, never what may be read: an oversized chapter
 * or dependency set bypasses the reuse path and certifies normally, with the
 * complete evidence and without reporting a resource failure.
 */
object ReaderPairingMemoLimits {
    const val MAX_PAGE_ORDER = 4096
    const val MAX_READ_KEYS = 4096

    /**
     * How many certified targets keep their reuse entry at the same time.
     *
     * One entry per target covers the location plus the mounted pager anchors
     * that the publisher certifies together; a single shared entry made the
     * reuse rate depend on the order in which those targets were asked.
     */
    const val DEFAULT_ENTRIES = 4
}

/**
 * The reuse entries of one session, one per certified target.
 *
 * A request for a target replaces that target's entry. When the number of
 * entries exceeds the capacity the oldest entry is dropped and counted as an
 * eviction, so the bound is always explicit and observable.
 */
internal class ReaderPairingCertificationMemo(private val capacity: Int = 1) {
    init {
        require(capacity > 0)
    }

    private val entries = LinkedHashMap<ReaderPageId, ReaderPairingMemoEntry>()
    private val evicted = LinkedHashSet<ReaderPageId>()
    private var dropped = 0L

    val evictions: Long get() = dropped

    /** True when this target had an entry that the capacity bound dropped. */
    fun wasEvicted(target: ReaderPageId): Boolean = target in evicted

    fun entryFor(target: ReaderPageId): ReaderPairingMemoEntry? = entries[target]

    fun store(next: ReaderPairingMemoEntry) {
        entries.remove(next.target)
        entries[next.target] = next
        while (entries.size > capacity) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
            evicted += oldest
            dropped++
        }
    }

    fun clear() {
        entries.clear()
        evicted.clear()
    }
}

/**
 * Re-reads every recorded value and reports the first key whose current value
 * differs. A null result means the recorded read set is still exactly what the
 * engine would observe now.
 */
internal fun firstChangedRead(
    reads: Map<ReaderPairingReadKey, ReaderPairingReadValue>,
    identity: ReaderDualPageIdentity,
    headers: Map<ReaderPageId, ReaderPageFact>,
    owner: ReaderSpreadSamplesOwner,
    settings: ReaderPairingSettings,
    metrics: ReaderPairingCertificationMetrics? = null,
): ReaderPairingReadKey? {
    metrics?.recordValidation()
    if (owner.isClosed) return ReaderPairingReadKey.Identity
    if (owner.identity != identity) return ReaderPairingReadKey.Identity
    for ((key, recorded) in reads) {
        val current: ReaderPairingReadValue = when (key) {
            ReaderPairingReadKey.Identity -> ReaderPairingReadValue.Identity(identity)
            ReaderPairingReadKey.Settings -> ReaderPairingReadValue.Settings(settings.snapshotCopy())
            is ReaderPairingReadKey.Analysis ->
                ReaderPairingReadValue.Analysis(owner.pageAnalysis(key.pageId))
            is ReaderPairingReadKey.Header -> ReaderPairingReadValue.Header(headers[key.pageId])
            is ReaderPairingReadKey.Dimension -> ReaderPairingReadValue.Dimension(
                effectiveFact(owner, headers, key.pageId)?.copy(sideHint = ReaderPageSideHint.UNKNOWN),
            )
            is ReaderPairingReadKey.Edge -> ReaderPairingReadValue.Edge(
                owner.edgeAnalysis(key.first, key.second, key.direction),
            )
        }
        metrics?.recordReadValueCheck()
        if (current != recorded) return key
    }
    return null
}
