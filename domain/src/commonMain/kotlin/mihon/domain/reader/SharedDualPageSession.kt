package mihon.domain.reader

import mihon.domain.reader.progress.ReaderProgressEffect
import mihon.domain.reader.progress.ReaderProgressPolicy
import mihon.domain.reader.progress.ReaderProgressSignal
import mihon.domain.reader.session.ReaderPageId

data class ReaderDualPageContentIdentity(
    val pageId: ReaderPageId,
    val contentVersion: String,
    val rotation: PageRotation = PageRotation.NONE,
    val sourceOrientation: ReaderImageOrientation = ReaderImageOrientation.NORMAL,
)

data class ReaderDualPageIdentity(
    val generation: Long,
    val pageListRevision: Long,
    val content: List<ReaderDualPageContentIdentity>,
) {
    init {
        require(generation >= 0 && pageListRevision >= 0)
        require(content.map { it.pageId }.distinct().size == content.size)
        require(content.map { it.pageId.chapterId }.distinct().size <= 1)
        require(content.all { it.contentVersion.isNotBlank() })
    }
    val pageIds: List<ReaderPageId> get() = content.map { it.pageId }
}

data class ReaderDualPageLocationIntent(val pageId: ReaderPageId, val generation: Long, val sequence: Long)

data class ReaderDualPageFactRequest(
    val identity: ReaderDualPageIdentity,
    val content: ReaderDualPageContentIdentity,
    val locationSequence: Long,
    val requestId: Long,
)

enum class ReaderDualPageSettlementReason { USER_NAVIGATION, LAYOUT_ONLY }

enum class ReaderPresentationMode { SINGLE, DUAL }

/** Opaque owner-issued capability captured when a native viewport starts presenting this layout. */
class ReaderDualPageViewportTicket internal constructor(
    val identity: ReaderDualPageIdentity,
    val layoutRevision: Long,
    val locationSequence: Long,
    val unitId: ReaderDualPageUnitId,
    val readingEligible: Boolean,
)

data class ReaderDualPageSettlement(
    val identity: ReaderDualPageIdentity,
    val layoutRevision: Long,
    val locationSequence: Long,
    val unitId: ReaderDualPageUnitId,
    val renderedPageIds: Set<ReaderPageId>,
    val settled: Boolean,
    val reason: ReaderDualPageSettlementReason,
    val settlementSequence: Long,
    val ticket: ReaderDualPageViewportTicket,
)

/**
 * Presentation state is separate from the I/O session and the persistent progress owner.
 * All methods must be consumed serially on the platform's existing reader owner/actor; I/O callbacks
 * are marshalled there before accepting facts or persistence results. This class is not thread safe.
 */
class SharedDualPageSession(
    identity: ReaderDualPageIdentity,
    direction: ReaderDirection,
    initialPage: ReaderPageId? = identity.pageIds.firstOrNull(),
    private val mangaId: Long? = null,
) {
    var identity: ReaderDualPageIdentity = identity
        private set
    private var direction = direction
    var presentationMode: ReaderPresentationMode = ReaderPresentationMode.DUAL
        private set
    private var forcedSinglePages: Set<ReaderPageId> = emptySet()
    private var automaticMatching = false
    private val facts = mutableMapOf<ReaderPageId, ReaderPageFact>()
    private val requests = mutableMapOf<ReaderPageId, ReaderDualPageFactRequest>()
    private val accepted = mutableMapOf<Long, ReaderPageFact>()
    private var nextRequestId = 0L
    private var closed = false
    private var lastReadLocationSequence: Long? = null
    private var lastSettlementSequence = -1L
    private var issuedViewport: ReaderDualPageViewportTicket? = null
    private var pairingPersistence = ReaderPairingPersistenceOwner(identity, mangaId)
    private var spreadSamples = ReaderSpreadSamplesOwner(identity)
    var sampleWorkRevision: Long = 0
        private set
    var factBudget = ReaderDualPageFactBudget()
        private set
    private var ownsFactBudget = true
    var factWorkRevision: Long = 0
        private set
    val pairingState: ReaderPairingPersistenceState get() = pairingPersistence.state

    init {
        require(initialPage == null || initialPage in identity.pageIds)
    }
    var location: ReaderDualPageLocationIntent? = initialPage?.let {
        ReaderDualPageLocationIntent(it, identity.generation, 0)
    }
        private set
    var layoutRevision: Long = 0
        private set
    var units: List<ReaderDualPageUnit> = SharedDualPageRules.build(identity.pageIds, emptyMap(), direction)
        private set
    val presentationUnits: List<ReaderDualPageUnit> get() = when (presentationMode) {
        ReaderPresentationMode.DUAL -> units
        ReaderPresentationMode.SINGLE -> identity.pageIds.map { pageId ->
            ReaderDualPageUnit(
                listOf(ReaderDualPageSlot(pageId, ReaderPortraitSingleSlot.FULL)),
                facts[pageId]?.state in setOf(ReaderPageFactState.PORTRAIT, ReaderPageFactState.SPREAD),
            )
        }
    }
    val targetUnit: ReaderDualPageUnit? get() = presentationUnits.firstOrNull { location?.pageId in it.pageIds }

    /** Existing encoded sample reads share the platform's bounded decode/lease resources. */
    fun spreadSampleDependencies(): List<ReaderPageId> =
        if (closed || !automaticMatching || presentationMode != ReaderPresentationMode.DUAL) {
            emptyList()
        } else {
            spreadSamples.requiredSamples(facts)
        }

    fun requestSpreadSample(pageId: ReaderPageId): ReaderSpreadSampleRequest? {
        if (closed || !automaticMatching || presentationMode != ReaderPresentationMode.DUAL ||
            pageId == identity.pageIds.firstOrNull() || facts[pageId]?.state != ReaderPageFactState.PORTRAIT
        ) {
            return null
        }
        return spreadSamples.request(pageId)
    }

    fun ownsSpreadSampleRequest(request: ReaderSpreadSampleRequest): Boolean =
        !closed && automaticMatching && presentationMode == ReaderPresentationMode.DUAL && spreadSamples.owns(request)

    fun acceptSpreadSample(request: ReaderSpreadSampleRequest, samples: ReaderSpreadEdgeSamples): Boolean {
        if (!ownsSpreadSampleRequest(request) || !spreadSamples.accept(request, samples)) return false
        sampleWorkRevision++
        rebuild()
        return true
    }

    fun configurePresentationMode(mode: ReaderPresentationMode) {
        if (closed || mode == presentationMode) return
        presentationMode = mode
        spreadSamples.cancelRequests()
        sampleWorkRevision++
        layoutRevision++
        issuedViewport = null
    }

    /** Additional facts required for this location; its visible unit keeps normal loading ownership. */
    fun factDependencies(): List<ReaderPageId> {
        if (closed || presentationMode == ReaderPresentationMode.SINGLE ||
            targetUnit?.certain != false
        ) {
            return emptyList()
        }
        val target = identity.pageIds.indexOf(location?.pageId)
        if (target < 0) return emptyList()
        val boundary = units.lastOrNull { unit ->
            unit.certain && identity.pageIds.indexOf(unit.pageIds.last()) < target
        }?.pageIds?.last()?.let(identity.pageIds::indexOf) ?: -1
        val visible = targetUnit?.pageIds.orEmpty().toSet()
        val candidates = listOf(target + 1) + (target - 1 downTo boundary + 1).toList()
        return candidates.mapNotNull(identity.pageIds::getOrNull).distinct().filter {
            it !in visible && (facts[it]?.state ?: ReaderPageFactState.UNKNOWN) == ReaderPageFactState.UNKNOWN
        }
    }

    /** Replaces actual content while retaining this owner’s logical navigation and committed business state. */
    fun rebindContent(nextIdentity: ReaderDualPageIdentity): SharedDualPageSession {
        check(!closed)
        require(nextIdentity.pageIds == identity.pageIds)
        require(nextIdentity.generation == identity.generation)
        val next = SharedDualPageSession(nextIdentity, direction, location?.pageId, mangaId)
        next.pairingPersistence = pairingPersistence.rebindContent(nextIdentity)
        next.location = location?.copy(generation = nextIdentity.generation)
        next.lastReadLocationSequence = lastReadLocationSequence
        next.lastSettlementSequence = lastSettlementSequence
        next.automaticMatching = automaticMatching
        next.presentationMode = presentationMode
        next.spreadSamples = spreadSamples.rebind(nextIdentity)
        next.sampleWorkRevision = sampleWorkRevision + 1
        next.factWorkRevision = factWorkRevision
        next.factBudget = factBudget
        ownsFactBudget = false
        val unchanged = nextIdentity.content.filter { content -> content in identity.content }.map { it.pageId }.toSet()
        next.facts.putAll(facts.filterKeys { it in unchanged })
        next.layoutRevision = layoutRevision + 1
        next.applyPairingState()
        close()
        return next
    }

    /** A location is retained until a real navigation or identity disposal; pager settling never replaces it. */
    fun goToPage(pageId: ReaderPageId): Boolean {
        if (closed || pageId !in identity.pageIds) return false
        location = ReaderDualPageLocationIntent(pageId, identity.generation, (location?.sequence ?: -1) + 1)
        requests.clear()
        accepted.clear()
        issuedViewport = null
        factBudget.close()
        factBudget = ReaderDualPageFactBudget()
        factWorkRevision++
        return true
    }

    /** Explicit retry renews only optional work; navigation and consumed reading remain unchanged. */
    fun retryFacts(): Boolean {
        if (closed) return false
        requests.clear()
        accepted.clear()
        facts.entries.removeAll { it.value.state == ReaderPageFactState.FAILED }
        issuedViewport = null
        factBudget.close()
        factBudget = ReaderDualPageFactBudget()
        factWorkRevision++
        rebuild()
        return true
    }

    fun navigate(forward: Boolean): Boolean {
        val displayed = presentationUnits
        val index = displayed.indexOfFirst { it.id == targetUnit?.id }
        if (index < 0) return false
        val next = displayed.getOrNull(index + if (forward) 1 else -1) ?: return false
        return goToPage(next.anchor)
    }

    /** The existing I/O owner captures and returns this exact ticket with its normal header request. */
    fun requestFact(pageId: ReaderPageId): ReaderDualPageFactRequest {
        check(!closed)
        val content = requireNotNull(identity.content.firstOrNull { it.pageId == pageId })
        return ReaderDualPageFactRequest(identity, content, location?.sequence ?: 0, nextRequestId++).also {
            requests[pageId] = it
        }
    }

    private fun isCurrentFactRequest(request: ReaderDualPageFactRequest): Boolean =
        !closed && request.identity.generation == identity.generation &&
            request.identity.pageListRevision == identity.pageListRevision &&
            request.identity.pageIds == identity.pageIds &&
            request.content == identity.content.firstOrNull { it.pageId == request.content.pageId } &&
            request.locationSequence == (location?.sequence ?: 0) && requests[request.content.pageId] === request

    /** Reuses a still-issued physical request without treating other pages' metadata as replacement. */
    fun ownsFactRequest(request: ReaderDualPageFactRequest): Boolean = isCurrentFactRequest(request)

    fun acceptFact(request: ReaderDualPageFactRequest, fact: ReaderPageFact): Boolean {
        if (fact.sourceOrientation != request.content.sourceOrientation) return false
        return acceptOriginalFact(request, fact)
    }

    private fun acceptOriginalFact(request: ReaderDualPageFactRequest, fact: ReaderPageFact): Boolean {
        if (!isCurrentFactRequest(request) || request.locationSequence != (location?.sequence ?: 0) ||
            requests[request.content.pageId] !== request || fact.rotation != request.content.rotation
        ) {
            return false
        }
        accepted[request.requestId]?.let { return it == fact }
        accepted[request.requestId] = fact
        if (facts[request.content.pageId] != fact) {
            facts[request.content.pageId] = fact
            sampleWorkRevision++
            rebuild()
        }
        return true
    }

    /** Confirms source metadata only through the original encoded-content request. */
    fun acceptOrientedFact(request: ReaderDualPageFactRequest, fact: ReaderPageFact): SharedDualPageSession? {
        val confirmed = fact.state == ReaderPageFactState.PORTRAIT || fact.state == ReaderPageFactState.SPREAD
        val currentFact = if (confirmed) fact else fact.copy(sourceOrientation = request.content.sourceOrientation)
        if (!acceptOriginalFact(request, currentFact)) return null
        if (confirmed && request.content.sourceOrientation != currentFact.sourceOrientation) {
            identity = identity.copy(
                content = identity.content.map { content ->
                    if (content.pageId ==
                        request.content.pageId
                    ) {
                        content.copy(sourceOrientation = currentFact.sourceOrientation)
                    } else {
                        content
                    }
                },
            )
            requests.remove(request.content.pageId)
            accepted.remove(request.requestId)
            spreadSamples.updateMetadata(identity)
            sampleWorkRevision++
            issuedViewport = null
            layoutRevision++
            rebuild()
        }
        return this
    }
    fun configure(
        direction: ReaderDirection,
        automaticMatching: Boolean = false,
    ) {
        if (closed) return
        require(direction != ReaderDirection.VERTICAL)
        if (this.direction != direction || this.automaticMatching != automaticMatching) sampleWorkRevision++
        if (this.automaticMatching && !automaticMatching) spreadSamples.cancelRequests()
        this.direction = direction
        this.automaticMatching = automaticMatching
        rebuild()
    }

    fun beginPairingRestore(): ReaderPairingPersistenceRequest.Load? = pairingPersistence.beginRestore()

    fun acceptPairingRestore(request: ReaderPairingPersistenceRequest.Load, snapshot: ChapterPairingSnapshot): Boolean {
        if (!pairingPersistence.acceptRestore(request, snapshot)) return false
        applyPairingState()
        return true
    }

    fun failPairingRestore(request: ReaderPairingPersistenceRequest.Load): Boolean =
        pairingPersistence.failRestore(request)

    fun useDefaultPairingForSession(): Boolean {
        if (!pairingPersistence.useDefaultForSession()) return false
        applyPairingState()
        return true
    }

    /** Uses the already presented logical unit; unknown fallback singles cannot manufacture a record. */
    fun adjustPairing(): ReaderPairingPersistenceRequest.Save? {
        if (closed || presentationMode != ReaderPresentationMode.DUAL) return null
        val unit = targetUnit ?: return null
        if (!unit.certain) return null
        val ordinalById = identity.pageIds.withIndex().associate { it.value to it.index }
        val current = location?.pageId?.let(ordinalById::get) ?: return null
        val adjustment = adjustReaderPairing(
            current,
            unit.pageIds.map(ordinalById::getValue),
            pairingState.committedForcedSinglePages,
        )
        if (adjustment.forcedSinglePages == pairingState.committedForcedSinglePages) return null
        val request = pairingPersistence.beginSave(adjustment, location?.sequence ?: 0)
        if ((
                pairingState.status == ReaderPairingPersistenceStatus.TEMPORARY ||
                    pairingState.status == ReaderPairingPersistenceStatus.DEFAULT_UNVERIFIED
                ) &&
            pairingState.committedForcedSinglePages == adjustment.forcedSinglePages
        ) {
            applyPairingState(adjustment.currentPage)
        }
        return request
    }

    fun acceptPairingSave(request: ReaderPairingPersistenceRequest.Save, snapshot: ChapterPairingSnapshot): Boolean {
        if (!pairingPersistence.acceptSave(request, snapshot)) return false
        applyPairingState(request.adjustment.currentPage.takeIf { request.locationSequence == location?.sequence })
        return true
    }

    fun failPairingSave(request: ReaderPairingPersistenceRequest.Save, conflict: Boolean = false): Boolean =
        pairingPersistence.failSave(request, conflict)

    /** Capture on presentation, not inside a late settled callback by reading the current fields. */
    fun captureViewport(): ReaderDualPageViewportTicket? {
        if (closed) return null
        val location = location ?: return null
        val unit = targetUnit ?: return null
        issuedViewport?.takeIf {
            it.layoutRevision == layoutRevision && it.locationSequence == location.sequence && it.unitId == unit.id
        }?.let { return it }
        return ReaderDualPageViewportTicket(
            identity,
            layoutRevision,
            location.sequence,
            unit.id,
            lastReadLocationSequence != location.sequence,
        ).also { issuedViewport = it }
    }

    /** Release only this presentation's original capability; a stale presenter cannot release its successor. */
    fun releaseViewport(ticket: ReaderDualPageViewportTicket): Boolean {
        if (closed || issuedViewport !== ticket) return false
        issuedViewport = null
        return true
    }

    /** Layout-only, stale, incomplete and placeholder events cannot create persistent reading. */
    fun settle(event: ReaderDualPageSettlement, wasRead: Boolean, sessionId: String): ReaderProgressEffect? {
        val location = location ?: return null
        val unit = targetUnit ?: return null
        if (closed || event.identity != identity || event.layoutRevision != layoutRevision ||
            event.locationSequence != location.sequence || event.unitId != unit.id || !event.settled ||
            event.ticket !== issuedViewport || !event.ticket.readingEligible ||
            event.ticket.identity != event.identity || event.ticket.layoutRevision != event.layoutRevision ||
            event.ticket.locationSequence != event.locationSequence || event.ticket.unitId != event.unitId ||
            event.reason != ReaderDualPageSettlementReason.USER_NAVIGATION ||
            event.renderedPageIds != unit.pageIds.toSet() || lastReadLocationSequence == location.sequence ||
            event.settlementSequence <= lastSettlementSequence
        ) {
            return null
        }
        val effect = ReaderProgressPolicy.reduce(
            ReaderProgressSignal.ViewportSettled(
                activeChapterId = location.pageId.chapterId,
                chapterId = location.pageId.chapterId,
                visiblePageIds = event.renderedPageIds,
                totalPages = identity.pageIds.size,
                wasRead = wasRead,
                sessionId = sessionId,
                settlementSequence = event.settlementSequence,
                pageIdsInOrder = identity.pageIds,
            ),
        ) ?: return null
        lastReadLocationSequence = location.sequence
        lastSettlementSequence = event.settlementSequence
        return effect
    }

    fun close() {
        closed = true
        if (ownsFactBudget) factBudget.close()
        pairingPersistence.close()
        spreadSamples.close()
        requests.clear()
        accepted.clear()
        issuedViewport = null
    }

    private fun applyPairingState(targetOrdinal: Int? = null) {
        forcedSinglePages = pairingState.committedForcedSinglePages.mapTo(mutableSetOf()) { identity.pageIds[it] }
        sampleWorkRevision++
        if (targetOrdinal != null) {
            // Adjusting pairing relocates the display, but does not create or retain a reading cause.
            lastReadLocationSequence = location?.sequence
            issuedViewport = null
            if (presentationMode == ReaderPresentationMode.DUAL) {
                location = location?.copy(pageId = identity.pageIds[targetOrdinal])
            }
        }
        rebuild()
    }

    private fun rebuild() {
        val next = SharedDualPageRules.build(
            identity.pageIds,
            facts,
            direction,
            forcedSinglePages,
            spreadSamples.selected(direction, facts, forcedSinglePages, automaticMatching),
            automaticMatching,
        )
        if (next == units) return
        units = next
        if (presentationMode == ReaderPresentationMode.DUAL) {
            layoutRevision++
            issuedViewport = null
        }
    }
}
