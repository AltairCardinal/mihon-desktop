package mihon.domain.reader

import mihon.domain.reader.session.ReaderPageId

/**
 * One key of the certification read set. Every value the certifier reads has
 * exactly one key, so a reuse decision compares the whole observed input.
 */
internal sealed interface ReaderPairingReadKey {
    data object Identity : ReaderPairingReadKey
    data object Settings : ReaderPairingReadKey
    data class Analysis(val pageId: ReaderPageId) : ReaderPairingReadKey
    data class Header(val pageId: ReaderPageId) : ReaderPairingReadKey
    data class Dimension(val pageId: ReaderPageId) : ReaderPairingReadKey
    data class Edge(
        val first: ReaderPageId,
        val second: ReaderPageId,
        val direction: ReaderDirection,
    ) : ReaderPairingReadKey
}

/**
 * The exact value observed at one read point. Absent, failed and null-valued
 * evidence stay distinguishable: a missing edge, a failed edge and an edge that
 * is computed without a matching score are three different facts.
 */
internal sealed interface ReaderPairingReadValue {
    data object Absent : ReaderPairingReadValue
    data class Identity(val identity: ReaderDualPageIdentity) : ReaderPairingReadValue
    data class Settings(val settings: ReaderPairingSettings) : ReaderPairingReadValue
    data class Analysis(val evidence: ReaderPageAnalysisEvidence) : ReaderPairingReadValue
    data class Header(val fact: ReaderPageFact?) : ReaderPairingReadValue
    data class Dimension(val fact: ReaderPageFact?) : ReaderPairingReadValue
    data class Edge(val evidence: ReaderEdgeAnalysisEvidence) : ReaderPairingReadValue
}

/**
 * Bounded collector of the values the certifier actually read.
 *
 * It never influences the certification result and it never keeps an alias of a
 * mutable collection: the recorded values are immutable snapshots.
 */
internal class ReaderPairingReadSet {
    private val values = LinkedHashMap<ReaderPairingReadKey, ReaderPairingReadValue>()

    fun record(key: ReaderPairingReadKey, value: ReaderPairingReadValue) {
        values[key] = value
    }

    fun snapshot(): Map<ReaderPairingReadKey, ReaderPairingReadValue> = values.toMap()

    val size: Int get() = values.size
}

/** The settings snapshot is compared as a whole; its sets must not alias mutable ones. */
internal fun ReaderPairingSettings.snapshotCopy(): ReaderPairingSettings = copy(
    forcedSinglePages = forcedSinglePages.toSet(),
    manualPairStarts = manualPairStarts.toSet(),
)
