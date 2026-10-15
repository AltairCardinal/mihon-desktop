package mihon.domain.reader

import mihon.domain.reader.session.ReaderPageId

/** Original owner-issued capability captured before a bounded sample decode begins. */
class ReaderSpreadSampleRequest internal constructor(
    val identity: ReaderDualPageIdentity,
    val content: ReaderDualPageContentIdentity,
)

/** Serial owner of immutable edge samples and scalar scores; never retains a decoded bitmap. */
class ReaderSpreadSamplesOwner(
    identity: ReaderDualPageIdentity,
    private val sampleCapacity: Int = 16,
) {
    init {
        require(sampleCapacity in 2..16)
    }

    var identity = identity
        private set
    private var contentByPage = identity.content.associateBy { it.pageId }
    private val ordinalByPage = identity.pageIds.withIndex().associate { it.value to it.index }
    private var closed = false
    val isClosed: Boolean get() = closed
    private val requests = mutableMapOf<ReaderPageId, ReaderSpreadSampleRequest>()
    private val samples = linkedMapOf<ReaderPageId, ReaderSpreadEdgeSamples>()
    private val originalSamples = mutableSetOf<ReaderPageId>()
    private data class Scores(val rtl: Double?, val ltr: Double?, val original: Boolean)
    private val scores = mutableMapOf<Pair<ReaderPageId, ReaderPageId>, Scores>()
    private val analyses = mutableMapOf<ReaderPageId, ReaderPageAnalysisEvidence>()
    private val sampleFailures = mutableMapOf<ReaderPageId, ReaderAnalysisFailure>()
    val cachedSampleCount: Int get() = samples.size
    val confirmedEdgeCount: Int get() = scores.size

    fun pageAnalysis(pageId: ReaderPageId): ReaderPageAnalysisEvidence =
        analyses[pageId] ?: ReaderPageAnalysisEvidence.NotAnalyzed

    fun edgeAnalysis(
        first: ReaderPageId,
        second: ReaderPageId,
        direction: ReaderDirection,
    ): ReaderEdgeAnalysisEvidence {
        if (closed || direction == ReaderDirection.VERTICAL) return ReaderEdgeAnalysisEvidence.NotAnalyzed
        val ordinal = ordinalByPage[first] ?: return ReaderEdgeAnalysisEvidence.NotAnalyzed
        if (ordinalByPage[second] != ordinal + 1) return ReaderEdgeAnalysisEvidence.NotAnalyzed
        val a =
            pageAnalysis(first) as? ReaderPageAnalysisEvidence.Computed ?: return ReaderEdgeAnalysisEvidence.NotAnalyzed
        val b =
            pageAnalysis(second) as? ReaderPageAnalysisEvidence.Computed
                ?: return ReaderEdgeAnalysisEvidence.NotAnalyzed
        if (a.fact.state == ReaderPageFactState.SPREAD ||
            b.fact.state == ReaderPageFactState.SPREAD
        ) {
            return ReaderEdgeAnalysisEvidence.Computed(a.content, b.content, null)
        }
        val score = scores[first to second]
        if (score == null || !score.original) {
            val failures = listOf(first, second).mapNotNull { page ->
                sampleFailures[page]?.let { page to it }
            }.toMap()
            return if (failures.isEmpty()) {
                ReaderEdgeAnalysisEvidence.NotAnalyzed
            } else {
                ReaderEdgeAnalysisEvidence.RetryableFailure(a.content, b.content, failures)
            }
        }
        return ReaderEdgeAnalysisEvidence.Computed(
            a.content,
            b.content,
            if (direction ==
                ReaderDirection.RTL
            ) {
                score.rtl
            } else {
                score.ltr
            },
        )
    }

    fun canAcceptOriginal(
        request: ReaderSpreadSampleRequest,
        fact: ReaderPageFact,
        value: ReaderSpreadEdgeSamples?,
    ): Boolean {
        if (!owns(request) || !fact.hasOriginalGeometry() || fact.rotation != request.content.rotation ||
            fact.sourceOrientation != request.content.sourceOrientation ||
            fact.state !in setOf(ReaderPageFactState.PORTRAIT, ReaderPageFactState.SPREAD)
        ) {
            return false
        }
        // Header dimensions have already consumed EXIF; only the user's rotation remains here.
        return value != null ||
            SharedSpreadMatching.plan(
                fact.width,
                fact.height,
                ReaderImageOrientation.NORMAL.rotatedAfter(fact.rotation),
            ) ==
            null
    }

    fun acceptOriginal(
        request: ReaderSpreadSampleRequest,
        fact: ReaderPageFact,
        value: ReaderSpreadEdgeSamples?,
    ): Boolean {
        if (!canAcceptOriginal(request, fact, value)) return false
        if (value != null && !acceptSamples(request, value, includeOpening = true)) return false
        analyses[request.content.pageId] = ReaderPageAnalysisEvidence.Computed(request.content, fact)
        sampleFailures.remove(request.content.pageId)
        return true
    }

    fun failOriginal(request: ReaderSpreadSampleRequest, reason: ReaderAnalysisFailure): Boolean {
        if (!owns(request)) return false
        sampleFailures[request.content.pageId] = reason
        if (pageAnalysis(request.content.pageId) !is ReaderPageAnalysisEvidence.Computed) {
            analyses[request.content.pageId] = ReaderPageAnalysisEvidence.RetryableFailure(request.content, reason)
        }
        return true
    }

    fun request(pageId: ReaderPageId): ReaderSpreadSampleRequest {
        check(!closed)
        return ReaderSpreadSampleRequest(identity, contentByPage.getValue(pageId)).also {
            requests[pageId] = it
        }
    }

    /** Metadata changes on another page do not revoke this owner's original physical-content request. */
    fun owns(request: ReaderSpreadSampleRequest): Boolean =
        !closed && requests[request.content.pageId] === request &&
            request.identity.generation == identity.generation &&
            request.identity.pageListRevision == identity.pageListRevision &&
            request.identity.pageIds == identity.pageIds && contentByPage[request.content.pageId] == request.content

    fun accept(request: ReaderSpreadSampleRequest, value: ReaderSpreadEdgeSamples): Boolean {
        if (pageAnalysis(request.content.pageId) is ReaderPageAnalysisEvidence.Computed) return false
        return acceptSamples(request, value, includeOpening = false)
    }

    private fun acceptSamples(
        request: ReaderSpreadSampleRequest,
        value: ReaderSpreadEdgeSamples,
        includeOpening: Boolean,
    ): Boolean {
        if (!owns(request)) return false
        val pageId = request.content.pageId
        samples.remove(pageId)
        samples[pageId] = ReaderSpreadEdgeSamples(value.left.toList(), value.right.toList())
        if (includeOpening) originalSamples += pageId else originalSamples -= pageId
        val ordered = identity.pageIds
        val index = ordinalByPage.getValue(pageId)
        for (firstIndex in listOf(index - 1, index)) {
            if (firstIndex < (if (includeOpening) 0 else 1) || firstIndex + 1 >= ordered.size) continue
            val pair = ordered[firstIndex] to ordered[firstIndex + 1]
            val first = samples[pair.first] ?: continue
            val second = samples[pair.second] ?: continue
            scores[pair] = Scores(
                SharedSpreadMatching.score(first, second, ReaderDirection.RTL),
                SharedSpreadMatching.score(first, second, ReaderDirection.LTR),
                pair.first in originalSamples && pair.second in originalSamples,
            )
        }
        while (samples.size > sampleCapacity) {
            val evicted = samples.keys.first()
            samples.remove(evicted)
            originalSamples.remove(evicted)
        }
        return true
    }

    /** Only same-byte metadata changes may preserve unchanged pages' issued capabilities. */
    fun updateMetadata(next: ReaderDualPageIdentity) {
        check(!closed)
        require(next.generation == identity.generation && next.pageListRevision == identity.pageListRevision)
        require(next.content.size == identity.content.size)
        require(
            next.content.zip(identity.content).all { (a, b) ->
                a.pageId == b.pageId && a.contentVersion == b.contentVersion && a.rotation == b.rotation
            },
        )
        val changed = next.content.zip(identity.content).filter { (a, b) -> a != b }.map { it.first.pageId }.toSet()
        changed.forEach {
            requests.remove(it)
            samples.remove(it)
            originalSamples.remove(it)
            analyses.remove(it)
            sampleFailures.remove(it)
        }
        scores.keys.removeAll { it.first in changed || it.second in changed }
        identity = next
        contentByPage = next.content.associateBy { it.pageId }
    }

    fun rebind(next: ReaderDualPageIdentity): ReaderSpreadSamplesOwner {
        check(!closed)
        val replacement = ReaderSpreadSamplesOwner(next, sampleCapacity)
        val unchanged = next.content.filter { contentByPage[it.pageId] == it }.map { it.pageId }.toSet()
        replacement.samples.putAll(samples.filterKeys { it in unchanged })
        replacement.originalSamples.addAll(originalSamples.filter { it in unchanged })
        replacement.scores.putAll(scores.filterKeys { it.first in unchanged && it.second in unchanged })
        replacement.analyses.putAll(analyses.filterKeys { it in unchanged })
        replacement.sampleFailures.putAll(sampleFailures.filterKeys { it in unchanged })
        close()
        return replacement
    }

    fun selected(
        direction: ReaderDirection,
        facts: Map<ReaderPageId, ReaderPageFact>,
        forcedSinglePages: Set<ReaderPageId> = emptySet(),
        enabled: Boolean = true,
    ): Set<Pair<ReaderPageId, ReaderPageId>> {
        if (closed || !enabled || direction == ReaderDirection.VERTICAL) return emptySet()
        val candidates = scores.mapNotNull { (pair, score) ->
            if (facts[pair.first]?.state != ReaderPageFactState.PORTRAIT ||
                facts[pair.second]?.state != ReaderPageFactState.PORTRAIT
            ) {
                return@mapNotNull null
            }
            if (listOf(pair.first, pair.second).any { pageId ->
                    val content = contentByPage.getValue(pageId)
                    facts[pageId]?.sourceOrientation != content.sourceOrientation ||
                        facts[pageId]?.rotation != content.rotation
                }
            ) {
                return@mapNotNull null
            }
            val distance = (if (direction == ReaderDirection.RTL) score.rtl else score.ltr) ?: return@mapNotNull null
            ReaderSpreadCandidate(pair.first, pair.second, distance)
        }
        return SharedSpreadMatching.select(identity.pageIds, candidates, forcedSinglePages)
    }

    /** Scalar lookup for one local decision; absence retains UNKNOWN/negative evidence internally. */
    fun distance(
        first: ReaderPageId,
        second: ReaderPageId,
        direction: ReaderDirection,
        facts: Map<ReaderPageId, ReaderPageFact>,
    ): Double? {
        if (closed || direction == ReaderDirection.VERTICAL) return null
        for (page in listOf(first, second)) {
            val content = contentByPage[page] ?: return null
            val fact = facts[page] ?: return null
            if (fact.state != ReaderPageFactState.PORTRAIT || fact.sourceOrientation != content.sourceOrientation ||
                fact.rotation != content.rotation
            ) {
                return null
            }
        }
        val score = scores[first to second] ?: return null
        return if (direction == ReaderDirection.RTL) score.rtl else score.ltr
    }

    fun requiredSamples(
        facts: Map<ReaderPageId, ReaderPageFact>,
        window: List<ReaderPageId>,
        forward: Boolean = true,
    ): List<ReaderPageId> {
        if (closed) return emptyList()
        val pairs = window.zipWithNext().let { if (forward) it else it.reversed() }
        return pairs.firstOrNull { pair ->
            pair.first != identity.pageIds.firstOrNull() && pair !in scores &&
                facts[pair.first]?.state == ReaderPageFactState.PORTRAIT &&
                facts[pair.second]?.state == ReaderPageFactState.PORTRAIT
        }?.let { pair -> listOf(pair.first, pair.second).filter { it !in samples } }.orEmpty()
    }

    /** Cancels physical work without discarding confirmed same-content samples or scores. */
    fun cancelRequests() = requests.clear()

    /** Resource failures belong to the preceding location budget; confirmed facts and read failures remain. */
    fun clearResourceFailures() {
        analyses.entries.removeAll {
            (it.value as? ReaderPageAnalysisEvidence.RetryableFailure)?.reason == ReaderAnalysisFailure.RESOURCE
        }
        sampleFailures.entries.removeAll { it.value == ReaderAnalysisFailure.RESOURCE }
    }

    fun retryFailures() {
        cancelRequests()
        analyses.entries.removeAll { it.value is ReaderPageAnalysisEvidence.RetryableFailure }
        sampleFailures.clear()
    }

    fun close() {
        closed = true
        requests.clear()
        samples.clear()
        originalSamples.clear()
        scores.clear()
        analyses.clear()
        sampleFailures.clear()
    }
}
