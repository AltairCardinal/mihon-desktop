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
    val pageIds: List<ReaderPageId> = content.map { it.pageId }
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
    val frameId: ReaderDualPageFrameId,
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
    private var navigationForward = true
    private val facts = mutableMapOf<ReaderPageId, ReaderPageFact>()
    private val requests = mutableMapOf<ReaderPageId, ReaderDualPageFactRequest>()
    private val accepted = mutableMapOf<Long, ReaderPageFact>()
    private var nextRequestId = 0L
    private var closed = false
    private var lastReadLocationSequence: Long? = null
    private var lastSettlementSequence = -1L
    private var pairingPersistence = ReaderPairingPersistenceOwner(identity, mangaId)
    private var spreadSamples = ReaderSpreadSamplesOwner(identity)
    private val presentation = ReaderDualPagePresentationOwner(
        { this.identity },
        facts,
        { first, second -> spreadSamples.distance(first, second, this.direction, facts) },
        direction,
    )
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
    val units: List<ReaderDualPageUnit> get() = presentation.units(location?.pageId)
    val presentationUnits: List<ReaderDualPageUnit> get() = units
    val targetUnit: ReaderDualPageUnit? get() = location?.pageId?.let(presentation::unit)
    val displayedHistory: List<ReaderDualPageFrame> get() = presentation.history
    val reservedPairs: Set<Pair<ReaderPageId, ReaderPageId>> get() = presentation.reservedPairs
    fun pageFact(pageId: ReaderPageId): ReaderPageFact? = facts[pageId]

    /** Existing encoded sample reads share the platform's bounded decode/lease resources. */
    fun spreadSampleDependencies(): List<ReaderPageId> =
        if (closed || !automaticMatching || presentationMode != ReaderPresentationMode.DUAL) {
            emptyList()
        } else {
            spreadSamples.requiredSamples(facts, decisionWindow(), navigationForward)
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
        rebuild(request.content.pageId)
        return true
    }

    fun configurePresentationMode(mode: ReaderPresentationMode) {
        if (closed || mode == presentationMode) return
        presentationMode = mode
        presentation.configure(direction, automaticMatching, mode == ReaderPresentationMode.SINGLE)
        spreadSamples.cancelRequests()
        sampleWorkRevision++
        layoutRevision++
    }

    /** Additional facts required for this location; its visible unit keeps normal loading ownership. */
    fun factDependencies(): List<ReaderPageId> {
        if (closed || presentationMode == ReaderPresentationMode.SINGLE) return emptyList()
        val visible = targetUnit?.pageIds.orEmpty().toSet()
        return decisionWindow().filter {
            it !in visible && (facts[it]?.state ?: ReaderPageFactState.UNKNOWN) == ReaderPageFactState.UNKNOWN
        }
    }

    private fun decisionWindow(): List<ReaderPageId> {
        val index = identity.pageIds.indexOf(location?.pageId)
        if (index < 0) return emptyList()
        val range = if (navigationForward) {
            index..minOf(index + 4, identity.pageIds.lastIndex)
        } else {
            maxOf(0, index - 4)..index
        }
        return range.map(identity.pageIds::get)
    }

    /** The adapter must retain visible old leases until release, or request an explicit user reload. */
    val visibleContentPageIds: Set<ReaderPageId> get() = presentation.visibleContentPageIds

    fun canRebindContent(nextIdentity: ReaderDualPageIdentity): Boolean {
        if (closed || nextIdentity.pageIds != identity.pageIds ||
            nextIdentity.generation != identity.generation
        ) {
            return false
        }
        val changed = identity.content.filter { it !in nextIdentity.content }.mapTo(mutableSetOf()) { it.pageId }
        return presentation.canReplace(changed)
    }

    /** Rebind unexposed content; explicit reload is the only way to replace a current pixel context. */
    fun rebindContent(nextIdentity: ReaderDualPageIdentity, explicitReload: Boolean = false): SharedDualPageSession {
        check(!closed)
        require(nextIdentity.pageIds == identity.pageIds)
        require(nextIdentity.generation == identity.generation)
        check(explicitReload || canRebindContent(nextIdentity)) {
            "Visible content must retain its original lease until release"
        }
        val next = SharedDualPageSession(nextIdentity, direction, location?.pageId, mangaId)
        next.pairingPersistence = pairingPersistence.rebindContent(nextIdentity)
        next.location = location?.copy(generation = nextIdentity.generation)
        next.lastReadLocationSequence = lastReadLocationSequence
        next.lastSettlementSequence = lastSettlementSequence
        next.automaticMatching = automaticMatching
        next.navigationForward = navigationForward
        next.presentationMode = presentationMode
        next.spreadSamples = spreadSamples.rebind(nextIdentity)
        next.sampleWorkRevision = sampleWorkRevision + 1
        next.factWorkRevision = factWorkRevision
        next.factBudget = factBudget
        ownsFactBudget = false
        val unchanged = nextIdentity.content.filter { content -> content in identity.content }.map { it.pageId }.toSet()
        next.facts.putAll(facts.filterKeys { it in unchanged })
        next.layoutRevision = layoutRevision + 1
        next.presentation.configure(direction, automaticMatching, presentationMode == ReaderPresentationMode.SINGLE)
        next.presentation.inheritProgress(presentation)
        if (!explicitReload) next.presentation.inheritHistory(presentation, unchanged)
        next.applyPairingState()
        close()
        return next
    }

    /** A location is retained until a real navigation or identity disposal; pager settling never replaces it. */
    fun goToPage(pageId: ReaderPageId, forward: Boolean? = null): Boolean {
        if (closed || pageId !in identity.pageIds) return false
        val sequence = (location?.sequence ?: -1) + 1
        navigationForward = forward ?: true
        presentation.move(pageId, forward, location?.pageId, sequence)
        location = ReaderDualPageLocationIntent(pageId, identity.generation, sequence)
        requests.clear()
        accepted.clear()
        spreadSamples.cancelRequests()
        sampleWorkRevision++
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
        factBudget.close()
        factBudget = ReaderDualPageFactBudget()
        factWorkRevision++
        rebuild()
        return true
    }

    fun navigate(forward: Boolean): Boolean {
        if (closed) return false
        val from = location?.pageId ?: return false
        val next = presentation.navigate(from, forward) ?: return false
        val sequence = (location?.sequence ?: -1) + 1
        navigationForward = forward
        presentation.move(next, forward, from, sequence)
        location = ReaderDualPageLocationIntent(next, identity.generation, sequence)
        requests.clear()
        accepted.clear()
        spreadSamples.cancelRequests()
        factBudget.close()
        factBudget = ReaderDualPageFactBudget()
        factWorkRevision++
        sampleWorkRevision++
        return true
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
        val retained = if (fact.state == ReaderPageFactState.PORTRAIT && fact.sideHint == ReaderPageSideHint.UNKNOWN) {
            fact.copy(sideHint = facts[request.content.pageId]?.sideHint ?: ReaderPageSideHint.UNKNOWN)
        } else {
            fact
        }
        if (facts[request.content.pageId] != retained) {
            facts[request.content.pageId] = retained
            sampleWorkRevision++
            rebuild(request.content.pageId)
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
            presentation.metadataChanged(request.content.pageId)
            sampleWorkRevision++
            rebuild(request.content.pageId)
        }
        return this
    }
    fun configure(
        direction: ReaderDirection,
        automaticMatching: Boolean = false,
    ) {
        if (closed) return
        require(direction != ReaderDirection.VERTICAL)
        if (this.direction == direction && this.automaticMatching == automaticMatching) return
        if (this.direction != direction || this.automaticMatching != automaticMatching) sampleWorkRevision++
        if (this.direction != direction || this.automaticMatching != automaticMatching) spreadSamples.cancelRequests()
        this.direction = direction
        this.automaticMatching = automaticMatching
        presentation.configure(direction, automaticMatching, presentationMode == ReaderPresentationMode.SINGLE)
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
        val currentOperation = request.locationSequence == location?.sequence
        applyPairingState(
            request.adjustment.currentPage.takeIf { currentOperation },
            explicit = true,
            protectVisible = !currentOperation,
        )
        return true
    }

    fun failPairingSave(request: ReaderPairingPersistenceRequest.Save, conflict: Boolean = false): Boolean =
        pairingPersistence.failSave(request, conflict)

    /** Prepare immediately before exposure, never during offscreen prefetch/decode. */
    fun captureViewport(
        pageId: ReaderPageId? = location?.pageId,
        preparingPageIds: Set<ReaderPageId>? = null,
    ): ReaderDualPageViewportTicket? {
        if (closed) return null
        val current = location ?: return null
        val page = pageId?.takeIf { it in identity.pageIds } ?: return null
        val unit = presentation.unit(page)
        val ready = preparingPageIds ?: unit.pageIds.filterTo(mutableSetOf()) {
            facts[it]?.state in setOf(ReaderPageFactState.PORTRAIT, ReaderPageFactState.SPREAD) &&
                (
                    it == unit.anchor ||
                        facts[unit.anchor]?.state in setOf(ReaderPageFactState.PORTRAIT, ReaderPageFactState.SPREAD)
                    )
        }
        return presentation.prepare(
            page,
            current,
            layoutRevision,
            ready,
            page == current.pageId && lastReadLocationSequence != current.sequence &&
                presentation.readingEligible(current.sequence, page),
        )
    }

    fun frame(ticket: ReaderDualPageViewportTicket): ReaderDualPageFrame? = presentation.frame(ticket)

    /** Actual drawable dimensions classify this viewport without consuming its encoded header capability. */
    fun acceptDecodedFact(
        ticket: ReaderDualPageViewportTicket,
        pageId: ReaderPageId,
        width: Int,
        height: Int,
        sideHint: ReaderPageSideHint = ReaderPageSideHint.UNKNOWN,
    ): Boolean {
        if (closed || width <= 0 || height <= 0 || ticket.locationSequence != location?.sequence) return false
        val frame = presentation.frame(ticket) ?: return false
        if (pageId !in frame.unit.pageIds) return false
        val content = identity.content.firstOrNull { it.pageId == pageId } ?: return false
        val captured = ticket.identity.content.firstOrNull { it.pageId == pageId } ?: return false
        if (captured.copy(sourceOrientation = content.sourceOrientation) != content) return false
        // Width/height already describe the native oriented drawable; keep encoded metadata separate.
        val actual = ReaderPageFact.dimensions(width, height).copy(
            rotation = content.rotation,
            sourceOrientation = content.sourceOrientation,
            sideHint = if (width < height) {
                sideHint.takeUnless { it == ReaderPageSideHint.UNKNOWN }
                    ?: facts[pageId]?.sideHint ?: ReaderPageSideHint.UNKNOWN
            } else {
                ReaderPageSideHint.UNKNOWN
            },
        )
        if (facts[pageId]?.state != actual.state || facts[pageId]?.sideHint != actual.sideHint) {
            facts[pageId] = actual
            sampleWorkRevision++
            rebuild(pageId)
        }
        return true
    }

    /** Every newly drawable slot must obtain its lock before the native first pixel. */
    fun preparePage(ticket: ReaderDualPageViewportTicket, pageId: ReaderPageId): Boolean =
        !closed && presentation.preparePage(ticket, pageId)

    /** Exposure, including animation, establishes history; it alone never creates reading progress. */
    fun reportDrawn(ticket: ReaderDualPageViewportTicket, pageIds: Set<ReaderPageId>): Boolean =
        !closed && presentation.drawn(ticket, pageIds)

    fun releaseViewport(ticket: ReaderDualPageViewportTicket): Boolean = !closed && presentation.release(ticket)

    /** A user viewport can append real newly drawn pages; placeholders and stale tickets cannot. */
    fun settle(event: ReaderDualPageSettlement, wasRead: Boolean, sessionId: String): ReaderProgressEffect? {
        val location = location ?: return null
        if (closed || event.identity != event.ticket.identity || event.layoutRevision != event.ticket.layoutRevision ||
            event.locationSequence != event.ticket.locationSequence || event.unitId != event.ticket.unitId ||
            !presentation.drawn(event.ticket, event.renderedPageIds) || !event.settled ||
            event.locationSequence != location.sequence || !event.ticket.readingEligible ||
            event.reason != ReaderDualPageSettlementReason.USER_NAVIGATION ||
            event.settlementSequence < lastSettlementSequence
        ) {
            return null
        }
        val newlyDrawn = presentation.progressPages(event.ticket, event.renderedPageIds)
        if (newlyDrawn.isEmpty()) return null
        val effect = ReaderProgressPolicy.reduce(
            ReaderProgressSignal.ViewportSettled(
                activeChapterId = location.pageId.chapterId,
                chapterId = location.pageId.chapterId,
                visiblePageIds = newlyDrawn,
                totalPages = identity.pageIds.size,
                wasRead = wasRead,
                sessionId = sessionId,
                settlementSequence = event.settlementSequence,
                pageIdsInOrder = identity.pageIds,
            ),
        ) ?: return null
        lastSettlementSequence = event.settlementSequence
        return effect
    }

    /** An explicit viewport/geometry event revokes old pixel and progress capabilities. */
    fun invalidateViewport() {
        if (closed) return
        presentation.invalidateViewport()
        layoutRevision++
    }

    /** Explicit retry revokes only groups containing the requested replacement content. */
    fun reloadContent(pageId: ReaderPageId): Boolean {
        if (closed || pageId !in identity.pageIds) return false
        val changedVisible = presentation.reloadContent(pageId)
        layoutRevision++
        return changedVisible
    }

    fun close() {
        closed = true
        presentation.invalidateViewport()
        if (ownsFactBudget) factBudget.close()
        pairingPersistence.close()
        spreadSamples.close()
        requests.clear()
        accepted.clear()
    }

    private fun applyPairingState(
        targetOrdinal: Int? = null,
        explicit: Boolean = targetOrdinal != null,
        protectVisible: Boolean = false,
    ) {
        forcedSinglePages = pairingState.committedForcedSinglePages.mapTo(mutableSetOf()) { identity.pageIds[it] }
        presentation.manualBoundaries(forcedSinglePages, explicit, protectVisible)
        sampleWorkRevision++
        if (targetOrdinal != null) {
            // Adjusting pairing relocates the display, but does not create or retain a reading cause.
            lastReadLocationSequence = location?.sequence
            if (presentationMode == ReaderPresentationMode.DUAL) {
                location = location?.copy(pageId = identity.pageIds[targetOrdinal])
            }
        }
        rebuild()
    }

    private fun rebuild(changedPage: ReaderPageId? = null) {
        presentation.factsChanged(changedPage)
        if (presentationMode == ReaderPresentationMode.DUAL) layoutRevision++
    }
}
