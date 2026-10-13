package mihon.domain.reader

import mihon.domain.reader.session.ReaderPageId

sealed interface ReaderPageAnalysisEvidence {
    data object NotAnalyzed : ReaderPageAnalysisEvidence
    data class Computed(
        val content: ReaderDualPageContentIdentity,
        val fact: ReaderPageFact,
        val algorithmVersion: Int = ReaderOriginalPageSampling.ALGORITHM_VERSION,
    ) : ReaderPageAnalysisEvidence
    data class RetryableFailure(
        val content: ReaderDualPageContentIdentity,
        val reason: ReaderAnalysisFailure,
    ) : ReaderPageAnalysisEvidence
}

enum class ReaderAnalysisFailure { READ, CANCELLED, RESOURCE, UNSUPPORTED, PAIRING_RESTORE }

sealed interface ReaderEdgeAnalysisEvidence {
    data object NotAnalyzed : ReaderEdgeAnalysisEvidence
    data class Computed(
        val first: ReaderDualPageContentIdentity,
        val second: ReaderDualPageContentIdentity,
        val score: Double?,
        val algorithmVersion: Int = ReaderOriginalPageSampling.ALGORITHM_VERSION,
    ) : ReaderEdgeAnalysisEvidence
    data class RetryableFailure(
        val first: ReaderDualPageContentIdentity,
        val second: ReaderDualPageContentIdentity,
        val failures: Map<ReaderPageId, ReaderAnalysisFailure>,
    ) : ReaderEdgeAnalysisEvidence
}

data class ReaderPairingSettings(
    val direction: ReaderDirection,
    val automatic: Boolean,
    val forcedSinglePages: Set<ReaderPageId> = emptySet(),
    val manualPairStarts: Set<Int> = emptySet(),
    val openingMode: ReaderChapterOpeningMode = ReaderChapterOpeningMode.AUTO,
    val manualRevision: Long = 0,
)

enum class ReaderPairingFactKind { DIMENSIONS, ORIGINAL_ANALYSIS, EDGE_SAMPLES, PAIRING_RULES }
data class ReaderPairingFactNeed(
    val pageId: ReaderPageId,
    val kind: ReaderPairingFactKind,
    val secondPageId: ReaderPageId? = null,
)

sealed interface ReaderPairingCertification {
    data class Certified(val segment: ReaderCertifiedPairingSegment) : ReaderPairingCertification
    data class NeedFacts(
        val facts: Set<ReaderPairingFactNeed>,
        val continuationFirstIndex: Int,
        val scanSteps: Int,
    ) : ReaderPairingCertification
    data class RetryableBlocked(
        val failures: Map<ReaderPageId, ReaderAnalysisFailure>,
        val facts: Set<ReaderPairingFactNeed>,
        val continuationFirstIndex: Int,
        val scanSteps: Int,
    ) : ReaderPairingCertification
}

/** Immutable proof dependencies, not an exposure history or a persisted byte identity. */
class ReaderCertifiedPairingSegment internal constructor(
    val unit: ReaderDualPageUnit,
    val firstIndex: Int,
    val scanSteps: Int,
    private val generation: Long,
    private val pageListRevision: Long,
    private val pages: List<ReaderPageId>,
    private val settings: ReaderPairingSettings,
    private val content: Map<ReaderPageId, ReaderDualPageContentIdentity>,
    private val dimensions: Map<ReaderPageId, ReaderPageFact>,
    private val analyses: Map<ReaderPageId, ReaderPageAnalysisEvidence.Computed>,
    private val edges: Map<Pair<ReaderPageId, ReaderPageId>, ReaderEdgeAnalysisEvidence.Computed>,
) {
    val dependentPageIds: Set<ReaderPageId> get() = content.keys

    fun isCurrent(
        identity: ReaderDualPageIdentity,
        settings: ReaderPairingSettings,
        owner: ReaderSpreadSamplesOwner,
        headers: Map<ReaderPageId, ReaderPageFact>,
    ): Boolean {
        if (owner.isClosed) return false
        if (identity.generation != generation || identity.pageListRevision != pageListRevision ||
            identity.pageIds != pages || settings != this.settings
        ) {
            return false
        }
        val current = identity.content.associateBy { it.pageId }
        if (content.any { (page, value) -> current[page] != value }) return false
        if (dimensions.any { (page, value) ->
                effectiveFact(owner, headers, page)?.copy(sideHint = ReaderPageSideHint.UNKNOWN) != value
            }
        ) {
            return false
        }
        if (analyses.any { (page, value) -> owner.pageAnalysis(page) != value }) return false
        return edges.all { (pair, value) -> owner.edgeAnalysis(pair.first, pair.second, settings.direction) == value }
    }
}

private fun effectiveFact(
    owner: ReaderSpreadSamplesOwner,
    headers: Map<ReaderPageId, ReaderPageFact>,
    page: ReaderPageId,
): ReaderPageFact? =
    (owner.pageAnalysis(page) as? ReaderPageAnalysisEvidence.Computed)?.fact ?: headers[page]

/** Width/height already include EXIF normalization; only the user's rotation is classified here. */
internal fun ReaderPageFact.hasOriginalGeometry(): Boolean = width > 0 && height > 0 && state == when (
    classifyPage(width, height, rotation)
) {
    PageLayout.PORTRAIT -> ReaderPageFactState.PORTRAIT
    PageLayout.SPREAD -> ReaderPageFactState.SPREAD
    PageLayout.UNKNOWN -> ReaderPageFactState.UNKNOWN
}

/** Conservative finite-entry proof. It never lets the requested location select a different pair. */
object ReaderPairingCertifier {
    fun certify(
        identity: ReaderDualPageIdentity,
        headers: Map<ReaderPageId, ReaderPageFact>,
        owner: ReaderSpreadSamplesOwner,
        settings: ReaderPairingSettings,
        target: ReaderPageId,
    ): ReaderPairingCertification {
        require(settings.direction != ReaderDirection.VERTICAL)
        check(!owner.isClosed)
        require(owner.identity == identity)
        val pages = identity.pageIds
        val targetIndex = pages.indexOf(target)
        require(targetIndex >= 0)
        val contents = identity.content.associateBy { it.pageId }
        val needs = linkedSetOf<ReaderPairingFactNeed>()
        val failures = linkedMapOf<ReaderPageId, ReaderAnalysisFailure>()
        val dimensions = linkedMapOf<ReaderPageId, ReaderPageFact>()
        val analyses = linkedMapOf<ReaderPageId, ReaderPageAnalysisEvidence.Computed>()
        val edges = linkedMapOf<Pair<ReaderPageId, ReaderPageId>, ReaderEdgeAnalysisEvidence.Computed>()
        fun dimension(index: Int): ReaderPageFact? {
            val page = pages.getOrNull(index) ?: return null
            val fact = effectiveFact(owner, headers, page)
            if (fact == null || !fact.hasOriginalGeometry() ||
                fact.rotation != contents.getValue(page).rotation ||
                fact.sourceOrientation != contents.getValue(page).sourceOrientation
            ) {
                val unavailable = owner.pageAnalysis(page) as? ReaderPageAnalysisEvidence.RetryableFailure
                if (unavailable != null) {
                    failures[page] = unavailable.reason
                } else if (fact?.state == ReaderPageFactState.FAILED) {
                    failures[page] = ReaderAnalysisFailure.READ
                }
                needs += ReaderPairingFactNeed(page, ReaderPairingFactKind.DIMENSIONS)
                return null
            }
            dimensions[page] = fact.copy(sideHint = ReaderPageSideHint.UNKNOWN)
            return fact
        }
        fun side(index: Int): ReaderPageSideHint {
            val page = pages[index]
            return when (val evidence = owner.pageAnalysis(page)) {
                is ReaderPageAnalysisEvidence.Computed -> {
                    analyses[page] = evidence
                    evidence.fact.sideHint
                }
                is ReaderPageAnalysisEvidence.RetryableFailure -> {
                    failures[page] = evidence.reason
                    needs += ReaderPairingFactNeed(page, ReaderPairingFactKind.ORIGINAL_ANALYSIS)
                    ReaderPageSideHint.UNKNOWN
                }
                ReaderPageAnalysisEvidence.NotAnalyzed -> {
                    needs += ReaderPairingFactNeed(page, ReaderPairingFactKind.ORIGINAL_ANALYSIS)
                    ReaderPageSideHint.UNKNOWN
                }
            }
        }
        fun resolvedSide(index: Int): ReaderPageSideHint? {
            val page = pages[index]
            val evidence = owner.pageAnalysis(page) as? ReaderPageAnalysisEvidence.Computed ?: return null
            analyses[page] = evidence
            return evidence.fact.sideHint
        }
        val scan = ReaderCanonicalPairingScan(
            pages,
            ::dimension,
            ::side,
            settings.direction,
            settings.forcedSinglePages,
            settings.manualPairStarts,
            settings.openingMode,
            settings.automatic,
            ::resolvedSide,
        ) { a, b ->
            when (val evidence = owner.edgeAnalysis(a, b, settings.direction)) {
                is ReaderEdgeAnalysisEvidence.Computed -> {
                    edges[a to b] = evidence
                    evidence.score
                }
                is ReaderEdgeAnalysisEvidence.RetryableFailure -> {
                    failures.putAll(evidence.failures)
                    needs += ReaderPairingFactNeed(a, ReaderPairingFactKind.EDGE_SAMPLES, b)
                    null
                }
                ReaderEdgeAnalysisEvidence.NotAnalyzed -> {
                    needs += ReaderPairingFactNeed(a, ReaderPairingFactKind.EDGE_SAMPLES, b)
                    null
                }
            }
        }
        data class State(val index: Int, val opening: Boolean, val reserved: Boolean)
        val memo = mutableMapOf<State, ReaderDualPageUnit?>()
        var steps = 0
        fun targetUnit(initial: ReaderPairingScanCursor): ReaderDualPageUnit? {
            var cursor = initial
            val path = mutableListOf<State>()
            var result: ReaderDualPageUnit? = null
            while (cursor.index <= targetIndex) {
                val key = State(cursor.index, cursor.opening, cursor.reservedStart == cursor.index)
                if (memo.containsKey(key)) {
                    result = memo[key]
                    break
                }
                path += key
                val next = scan.step(
                    cursor.copy(reservedStart = if (key.reserved) cursor.index else -1),
                    includeSingleUnit = cursor.index == targetIndex,
                    includePairUnit = cursor.index + 1 >= targetIndex,
                )
                steps++
                if (next.unit != null && target in next.unit.pageIds) {
                    result = next.unit
                    break
                }
                cursor = next.next
            }
            path.forEach { memo[it] = result }
            return result
        }
        val targetFact = dimension(targetIndex)
        if (targetFact == null) {
            return if (failures.isEmpty()) {
                ReaderPairingCertification.NeedFacts(needs.toSet(), targetIndex, steps)
            } else {
                ReaderPairingCertification.RetryableBlocked(failures.toMap(), needs.toSet(), targetIndex, steps)
            }
        }
        val isolatedSpread = targetFact.state == ReaderPageFactState.SPREAD
        var first = if (isolatedSpread) targetIndex else maxOf(0, targetIndex - 2)
        fun certified(unit: ReaderDualPageUnit): ReaderPairingCertification.Certified {
            val used = dimensions.keys + analyses.keys + edges.keys.flatMap { listOf(it.first, it.second) }
            return ReaderPairingCertification.Certified(
                ReaderCertifiedPairingSegment(
                    unit,
                    first,
                    steps,
                    identity.generation,
                    identity.pageListRevision,
                    pages.toList(),
                    settings.copy(
                        forcedSinglePages = settings.forcedSinglePages.toSet(),
                        manualPairStarts = settings.manualPairStarts.toSet(),
                    ),
                    used.associateWith(contents::getValue),
                    dimensions.toMap(),
                    analyses.toMap(),
                    edges.toMap(),
                ),
            )
        }
        while (true) {
            val entries = if (first == 0) {
                listOf(ReaderPairingScanCursor(0, true))
            } else if (isolatedSpread) {
                listOf(ReaderPairingScanCursor(first, true), ReaderPairingScanCursor(first, false))
            } else {
                buildList {
                    for (opening in listOf(true, false)) {
                        add(ReaderPairingScanCursor(first, opening))
                        add(ReaderPairingScanCursor(first, opening, first))
                    }
                    add(ReaderPairingScanCursor(first + 1, false))
                }
            }
            val candidates = entries.map(::targetUnit)
            if (failures.isNotEmpty()) {
                return ReaderPairingCertification.RetryableBlocked(failures.toMap(), needs.toSet(), first, steps)
            }
            if (needs.isNotEmpty()) return ReaderPairingCertification.NeedFacts(needs.toSet(), first, steps)
            val unit = candidates.firstOrNull()
            if (unit != null && candidates.all { it == unit }) {
                return certified(unit)
            }
            // A boundary proof is needed only after complete finite entries actually disagree.
            // Its reads are real dependencies; no tentative manual-cache reads are rolled back.
            if (first > 0 && !scan.allowed(first - 1)) {
                if (failures.isNotEmpty()) {
                    return ReaderPairingCertification.RetryableBlocked(failures.toMap(), needs.toSet(), first, steps)
                }
                if (needs.isNotEmpty()) return ReaderPairingCertification.NeedFacts(needs.toSet(), first, steps)
                val normal = listOf(true, false).map { targetUnit(ReaderPairingScanCursor(first, it)) }
                val agreed = normal.firstOrNull()
                if (agreed != null && normal.all { it == agreed }) return certified(agreed)
            }
            check(first > 0) { "The actual chapter entry must have one deterministic target unit" }
            first--
        }
    }
}
