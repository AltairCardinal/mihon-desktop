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
    private var closed = false
    private val requests = mutableMapOf<ReaderPageId, ReaderSpreadSampleRequest>()
    private val samples = linkedMapOf<ReaderPageId, ReaderSpreadEdgeSamples>()
    private data class Scores(val rtl: Double?, val ltr: Double?)
    private val scores = mutableMapOf<Pair<ReaderPageId, ReaderPageId>, Scores>()
    val cachedSampleCount: Int get() = samples.size
    val confirmedEdgeCount: Int get() = scores.size

    fun request(pageId: ReaderPageId): ReaderSpreadSampleRequest {
        check(!closed)
        return ReaderSpreadSampleRequest(identity, identity.content.single { it.pageId == pageId }).also {
            requests[pageId] = it
        }
    }

    /** Metadata changes on another page do not revoke this owner's original physical-content request. */
    fun owns(request: ReaderSpreadSampleRequest): Boolean =
        !closed && requests[request.content.pageId] === request &&
            request.identity.generation == identity.generation &&
            request.identity.pageListRevision == identity.pageListRevision &&
            request.identity.pageIds == identity.pageIds && identity.content.any { it == request.content }

    fun accept(request: ReaderSpreadSampleRequest, value: ReaderSpreadEdgeSamples): Boolean {
        if (!owns(request)) return false
        val pageId = request.content.pageId
        samples.remove(pageId)
        samples[pageId] = ReaderSpreadEdgeSamples(value.left.toList(), value.right.toList())
        val ordered = identity.pageIds
        val index = ordered.indexOf(pageId)
        for (firstIndex in listOf(index - 1, index)) {
            if (firstIndex < 1 || firstIndex + 1 >= ordered.size) continue
            val pair = ordered[firstIndex] to ordered[firstIndex + 1]
            val first = samples[pair.first] ?: continue
            val second = samples[pair.second] ?: continue
            scores[pair] = Scores(
                SharedSpreadMatching.score(first, second, ReaderDirection.RTL),
                SharedSpreadMatching.score(first, second, ReaderDirection.LTR),
            )
        }
        while (samples.size > sampleCapacity) samples.remove(samples.keys.first())
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
        }
        scores.keys.removeAll { it.first in changed || it.second in changed }
        identity = next
    }

    fun rebind(next: ReaderDualPageIdentity): ReaderSpreadSamplesOwner {
        check(!closed)
        val replacement = ReaderSpreadSamplesOwner(next, sampleCapacity)
        val unchanged = next.content.filter { it in identity.content }.map { it.pageId }.toSet()
        replacement.samples.putAll(samples.filterKeys { it in unchanged })
        replacement.scores.putAll(scores.filterKeys { it.first in unchanged && it.second in unchanged })
        close()
        return replacement
    }

    /**
     * Repairs one unscored adjacent edge at a time. The two samples meet before cache eviction,
     * so every completed demand confirms an edge and eviction cannot cause an endless replay.
     * These are existing encoded reads, not new materialization/network allowances.
     */
    fun requiredSamples(facts: Map<ReaderPageId, ReaderPageFact>): List<ReaderPageId> {
        if (closed) return emptyList()
        return identity.pageIds.drop(1).zipWithNext().firstOrNull { pair ->
            pair !in scores && facts[pair.first]?.state == ReaderPageFactState.PORTRAIT &&
                facts[pair.second]?.state == ReaderPageFactState.PORTRAIT
        }?.let { pair -> listOf(pair.first, pair.second).filter { it !in samples } }.orEmpty()
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
                    val content = identity.content.single { it.pageId == pageId }
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

    /** Cancels physical work without discarding confirmed same-content samples or scores. */
    fun cancelRequests() = requests.clear()

    fun close() {
        closed = true
        requests.clear()
        samples.clear()
        scores.clear()
    }
}
