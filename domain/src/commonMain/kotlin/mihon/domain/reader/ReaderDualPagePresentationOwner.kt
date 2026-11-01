package mihon.domain.reader

import mihon.domain.reader.session.ReaderPageId

/** A physical frame capability is separate from the chapter's logical partition. */
data class ReaderDualPageFrameId(val generation: Long, val context: Long, val serial: Long)

data class ReaderDualPageFrame(
    val id: ReaderDualPageFrameId,
    val unit: ReaderDualPageUnit,
    val lockedPageIds: Set<ReaderPageId>,
    val drawnPageIds: Set<ReaderPageId>,
)

/** A not-yet-mounted proposal has no frame id; mounted frames never share identity. */
data class ReaderDualPagePresentationFrame(val unit: ReaderDualPageUnit, val frameId: ReaderDualPageFrameId?)

/** Research implementation: metadata determines pairs; visible leases never feed pairing history. */
internal class ReaderDualPagePresentationOwner(
    private val identity: () -> ReaderDualPageIdentity,
    private val facts: Map<ReaderPageId, ReaderPageFact>,
    private val distance: (ReaderPageId, ReaderPageId) -> Double?,
    private var direction: ReaderDirection,
    private val preferred: (ReaderPageId) -> ReaderDualPagePresentationSelection?,
    private val currentPage: () -> ReaderPageId?,
) {
    private data class Record(
        val id: ReaderDualPageFrameId,
        var unit: ReaderDualPageUnit,
        val selectionPage: ReaderPageId,
        val promised: Set<ReaderPageId> = unit.pageIds.toSet(),
        val source: ReaderDualPagePresentationSource,
        val selectionRevision: Long,
        val locked: MutableSet<ReaderPageId> = mutableSetOf(),
        val drawn: MutableSet<ReaderPageId> = mutableSetOf(),
        var visible: Boolean = true,
        var appendable: Boolean = true,
        var activeSequence: Long = -1,
        var stale: Boolean = false,
    ) {
        fun snapshot() = ReaderDualPageFrame(id, unit, locked.toSet(), drawn.toSet())
    }

    private val pages = identity().pageIds
    private val ordinals = pages.withIndex().associate { it.value to it.index }
    private val records = linkedMapOf<ReaderDualPageFrameId, Record>()
    private val tickets = mutableMapOf<ReaderDualPageViewportTicket, Record>()
    private val inheritedReleases = mutableMapOf<ReaderDualPageViewportTicket, Record>()
    private val reported = mutableMapOf<Long, MutableSet<ReaderPageId>>()
    var inputRevision = 0L
        private set
    private var serial = 0L
    private var context = 0L
    private var currentLocationSequence = 0L
    private var forced: Set<ReaderPageId> = emptySet()
    private var manualPairStarts: Set<Int> = emptySet()
    private var openingMode = ReaderChapterOpeningMode.AUTO
    private var automatic = false
    private var singleMode = false
    private var projection: List<ReaderDualPageUnit>? = null
    private var defaultProjection: List<ReaderDualPageUnit>? = null
    private var defaultByPage: Map<ReaderPageId, ReaderDualPageUnit> = emptyMap()
    private var activeFrame: ReaderDualPageFrameId? = null
    val targetFrameId: ReaderDualPageFrameId? get() = activeFrame?.takeIf { records[it]?.visible == true }
    private var presentedProjection: List<ReaderDualPageUnit>? = null
    private var byPage: Map<ReaderPageId, ReaderDualPageUnit> = emptyMap()

    fun unit(page: ReaderPageId): ReaderDualPageUnit {
        plan()
        return byPage.getValue(page)
    }

    private fun plan(): List<ReaderDualPageUnit> = projection ?: (
        if (singleMode) {
            pages.map { ReaderDualPageUnit(listOf(ReaderDualPageSlot(it, ReaderPortraitSingleSlot.FULL)), known(it)) }
        } else {
            SharedDualPageRules.canonicalPlan(
                pages,
                facts,
                direction,
                forced,
                manualPairStarts,
                openingMode,
                automatic,
                distance,
            )
        }
        ).map { proposed ->
        // Reuse immutable values only after the canonical reducer has independently chosen them.
        byPage[proposed.anchor]?.takeIf { it == proposed } ?: proposed
    }.also { units ->
        projection = units
        byPage = units.flatMap { unit -> unit.pageIds.map { it to unit } }.toMap()
    }

    fun canonicalUnits(): List<ReaderDualPageUnit> = plan()

    /** Missing evidence uses the chapter-start rule, retaining only known hard geometry/manual boundaries. */
    private fun defaultPlan(): List<ReaderDualPageUnit> = defaultProjection ?: (
        if (singleMode) {
            plan()
        } else {
            SharedDualPageRules.canonicalPlan(
                pages,
                facts,
                direction,
                forced,
                manualPairStarts,
                openingMode,
                automatic = false,
            )
        }
        ).map { proposed ->
        // A restored manual single keeps reliable own-page side geometry without allowing
        // decoded side hints to choose the unknown chapter pairing phase.
        val unit = if (proposed.singleReason == ReaderDualPageSingleReason.MANUAL_BOUNDARY) {
            SharedDualPageRules.decide(
                pages,
                facts,
                direction,
                ordinals.getValue(proposed.anchor),
                forcedSinglePages = forced,
                usePageSideHints = automatic,
            ).unit
        } else {
            proposed
        }
        defaultByPage[unit.anchor]?.takeIf { it == unit } ?: unit
    }.also { units ->
        defaultProjection = units
        defaultByPage = units.flatMap { unit -> unit.pageIds.map { it to unit } }.toMap()
    }

    private fun candidate(page: ReaderPageId): ReaderDualPagePresentationSelection {
        val reliable = preferred(page) ?: records.values.lastOrNull {
            it.visible && it.selectionRevision == inputRevision &&
                it.source == ReaderDualPagePresentationSource.CERTIFIED && page in it.unit.pageIds
        }?.let { ReaderDualPagePresentationSelection(it.unit, it.source) }
        defaultPlan()
        return reliable?.takeIf { page in it.unit.pageIds } ?: ReaderDualPagePresentationSelection(
            defaultByPage.getValue(page),
            if (singleMode) ReaderDualPagePresentationSource.SINGLE else ReaderDualPagePresentationSource.DEFAULT,
        )
    }

    /** Outgoing and incoming physical frames may share a boundary page until native release. */
    fun presentationFrameProjection(): List<ReaderDualPageUnit> {
        records.values.toList().filter { it.visible }.forEach(::refresh)
        val active = records[activeFrame]?.takeIf { it.visible }?.unit ?: currentPage()?.let { candidate(it).unit }
        defaultPlan()
        // Explicitly prepared native candidates need their identity in the next publication before
        // a mount may obtain its first ticket. They remain replaceable until a real pixel lock.
        val retained = records.values.filter { it.visible }.map { it.unit }
        val selected = listOfNotNull(active)
        val claims = selected.flatMap { unit -> unit.pageIds.map { it to unit } }.toMap()
        val emitted = mutableSetOf<ReaderPageId>()
        val next = buildList {
            for (page in pages) {
                if (page in emitted) continue
                val proposed = claims[page] ?: defaultByPage.getValue(page)
                val slots = proposed.slots.filter { slot ->
                    slot.pageId !in emitted && (claims[slot.pageId] == null || claims[slot.pageId] == proposed)
                }
                if (slots.isEmpty()) continue
                add(if (slots == proposed.slots) proposed else proposed.copy(slots = slots))
                emitted += slots.map { it.pageId }
            }
        }.let { base ->
            // Do not globally de-duplicate source pages: each retained frame owns its own lease.
            val retainedPages = retained.flatMapTo(mutableSetOf()) { it.pageIds }
            (
                base.mapNotNull { proposed ->
                    if (retained.any { it.id == proposed.id }) return@mapNotNull null
                    if (selected.any { it.id == proposed.id }) return@mapNotNull proposed
                    val slots = proposed.slots.filter { it.pageId !in retainedPages }
                    if (slots.isEmpty()) {
                        null
                    } else if (slots == proposed.slots) {
                        proposed
                    } else {
                        proposed.copy(slots = slots)
                    }
                } + retained
                )
                .sortedBy { ordinals.getValue(it.anchor) }
        }
        return presentedProjection?.takeIf { it == next } ?: next.also { presentedProjection = it }
    }

    fun presentationUnit(page: ReaderPageId): ReaderDualPageUnit {
        val active = records[activeFrame]?.takeIf { it.visible && containsLocation(it, page) }
        if (active != null) {
            refresh(active)
            if (records[active.id] === active && containsLocation(active, page)) return active.unit
        }
        return candidate(page).unit
    }

    fun frameProjection(): List<ReaderDualPagePresentationFrame> = presentationFrameProjection().distinctBy {
        it.id
    }.flatMap { unit ->
        val mounted = records.values.filter { it.visible && it.unit.id == unit.id }
        if (mounted.isEmpty()) {
            listOf(ReaderDualPagePresentationFrame(unit, null))
        } else {
            mounted.map { ReaderDualPagePresentationFrame(it.unit, it.id) }
        }
    }

    fun frame(ticket: ReaderDualPageViewportTicket): ReaderDualPageFrame? {
        val record = tickets[ticket] ?: return null
        refresh(record)
        return tickets[ticket]?.snapshot()
    }

    val history: List<ReaderDualPageFrame> get() = records.values.filter {
        it.locked.isNotEmpty()
    }.map { it.snapshot() }
    val reservedPairs: Set<Pair<ReaderPageId, ReaderPageId>> get() = emptySet()
    val visibleContentPageIds: Set<ReaderPageId> get() = records.values.filter {
        it.visible
    }.flatMapTo(mutableSetOf()) {
        it.locked
    }

    fun readingEligible(sequence: Long, page: ReaderPageId): Boolean =
        presentationUnit(page).pageIds.any { it !in reported[sequence].orEmpty() }

    fun inheritProgress(previous: ReaderDualPagePresentationOwner) {
        reported.putAll(previous.reported.mapValues { (_, ids) -> ids.toMutableSet() })
    }

    fun canReplace(changed: Set<ReaderPageId>): Boolean = records.values.none { record ->
        record.visible && record.locked.any { it in changed }
    }

    fun inheritHistory(previous: ReaderDualPagePresentationOwner, unchanged: Set<ReaderPageId>) {
        context = previous.context
        serial = previous.serial
        for (old in previous.records.values) {
            val wholeUnitUnchanged = unchanged.containsAll(old.unit.pageIds)
            if (!wholeUnitUnchanged && (old.locked.isEmpty() || !unchanged.containsAll(old.locked))) continue
            // Unchanged locked pixels retain their mount while an unprepared partner retries.
            // Tickets are never transferred, and proof of replaced partner bytes is not inherited.
            val copy = old.copy(
                locked = old.locked.toMutableSet(),
                drawn = old.drawn.toMutableSet(),
                visible = old.visible,
                source = if (wholeUnitUnchanged) old.source else ReaderDualPagePresentationSource.DEFAULT,
                selectionRevision = -1,
            )
            records[copy.id] = copy
            if (old.visible) {
                (previous.tickets + previous.inheritedReleases).filterValues { it === old }.keys.forEach {
                    inheritedReleases[it] = copy
                }
            }
        }
        activeFrame = previous.activeFrame?.takeIf { it in records }
        currentLocationSequence = previous.currentLocationSequence
    }

    fun metadataChanged(page: ReaderPageId) {
        // Snapshot before filtering: a concurrent discard of another record would
        // otherwise break this iteration.
        records.values.toList()
            .filter { it.locked.isEmpty() && page in it.unit.pageIds }
            .forEach(::discard)
    }

    fun isManualUnit(unit: ReaderDualPageUnit): Boolean = unit.pageIds.size == 2 &&
        (
            ordinals.getValue(unit.anchor) in manualPairStarts ||
                (unit.anchor == pages.first() && openingMode == ReaderChapterOpeningMode.FIRST_PAIR)
            )

    private fun known(page: ReaderPageId) =
        facts[page]?.state in setOf(ReaderPageFactState.PORTRAIT, ReaderPageFactState.SPREAD)

    private fun ready(record: Record, page: ReaderPageId): Boolean =
        page in record.unit.pageIds && (singleMode || known(page))

    private fun containsLocation(record: Record, page: ReaderPageId): Boolean = page in record.unit.pageIds ||
        (record.id == activeFrame && page == currentPage() && page in record.promised && record.locked.isNotEmpty())

    private fun resolveRecord(
        page: ReaderPageId,
        location: ReaderDualPageLocationIntent,
        requestedUnit: ReaderDualPageUnitId?,
        requestedFrame: ReaderDualPageFrameId?,
    ): Record? {
        records.values.filter { it.visible }.toList().forEach(::refresh)
        val desired = candidate(page)
        val exact = requestedFrame?.let { id ->
            records[id]?.takeIf {
                it.visible && it.activeSequence == location.sequence && containsLocation(it, page) &&
                    (requestedUnit == null || it.unit.id == requestedUnit)
            }
                ?: return null
        }
        val active = records[activeFrame]?.takeIf {
            it.visible && containsLocation(it, page) && (requestedUnit == null || it.unit.id == requestedUnit)
        }
        if (exact == null && active == null && requestedUnit != null && desired.unit.id != requestedUnit) return null
        return exact ?: active
            ?: records.values.firstOrNull {
                it.visible && it.activeSequence == location.sequence && it.unit.id == desired.unit.id
            }
            ?: Record(
                ReaderDualPageFrameId(identity().generation, context, serial++),
                desired.unit,
                page,
                source = desired.source,
                selectionRevision = inputRevision,
            )
                .also { records[it.id] = it }
    }

    private fun activateRecord(record: Record, location: ReaderDualPageLocationIntent, activate: Boolean) {
        record.visible = true
        record.appendable = !record.stale
        record.activeSequence = location.sequence
        if (activate) {
            currentLocationSequence = location.sequence
            activeFrame = record.id
        }
    }

    fun prepareFrame(
        page: ReaderPageId,
        location: ReaderDualPageLocationIntent,
        requestedUnit: ReaderDualPageUnitId?,
        activate: Boolean,
    ): ReaderDualPageFrameId? {
        val record = resolveRecord(page, location, requestedUnit, null) ?: return null
        activateRecord(record, location, activate)
        return record.id
    }

    fun prepare(
        page: ReaderPageId,
        location: ReaderDualPageLocationIntent,
        layoutRevision: Long,
        preparingPages: Set<ReaderPageId>,
        readingEligible: Boolean,
        requestedUnit: ReaderDualPageUnitId? = null,
        requestedFrame: ReaderDualPageFrameId? = null,
        activate: Boolean = page == location.pageId,
        leaseOwner: Any? = null,
    ): ReaderDualPageViewportTicket? {
        val record = resolveRecord(page, location, requestedUnit, requestedFrame) ?: return null
        // A page the platform declares as preparing may enter the viewport before its fact is
        // known: the decoded fact is what makes it known, and it can only arrive through a
        // ticket. It still becomes drawable only after that fact is known, because
        // `preparePage` keeps the readiness check.
        if (!preparingPages.all { it in record.unit.pageIds && (!record.stale || it in record.locked) }) {
            return null
        }
        activateRecord(record, location, activate)
        record.locked += preparingPages
        return tickets.entries.firstOrNull {
            it.value === record && it.key.locationSequence == location.sequence &&
                it.key.readingEligible == readingEligible && it.key.leaseOwner === leaseOwner
        }?.key ?: ReaderDualPageViewportTicket(
            identity(),
            layoutRevision,
            location.sequence,
            record.unit.id,
            readingEligible,
            record.id,
            record.source,
            leaseOwner,
        ).also { tickets[it] = record }
    }

    fun preparePage(ticket: ReaderDualPageViewportTicket, page: ReaderPageId): Boolean {
        val record = tickets[ticket] ?: return false
        refresh(record)
        if (tickets[ticket] !== record || record.stale || !record.visible || !record.appendable ||
            ticket.locationSequence != record.activeSequence || !ready(record, page)
        ) {
            return false
        }
        record.locked += page
        return true
    }

    fun drawn(ticket: ReaderDualPageViewportTicket, pages: Set<ReaderPageId>): Boolean {
        val record = tickets[ticket] ?: return false
        refresh(record)
        // A prepared slot may paint for the first time after its unprepared partner leaves the frame.
        // Navigation revokes first-draw authority; an outgoing frame may only repaint prior pixels.
        if (tickets[ticket] !== record || !record.visible || pages.isEmpty() || !record.locked.containsAll(pages) ||
            (
                (
                    ticket.locationSequence != currentLocationSequence ||
                        ticket.locationSequence != record.activeSequence
                    ) &&
                    !record.drawn.containsAll(pages)
                )
        ) {
            return false
        }
        record.drawn += pages
        return true
    }

    fun progressPages(ticket: ReaderDualPageViewportTicket, pages: Set<ReaderPageId>): Set<ReaderPageId> {
        if (!ticket.readingEligible || !hasDrawn(ticket, pages)) return emptySet()
        val previous = reported.getOrPut(ticket.locationSequence) { mutableSetOf() }
        if ((pages - previous).isEmpty()) return emptySet()
        previous += pages
        return previous.toSet()
    }

    fun hasDrawn(ticket: ReaderDualPageViewportTicket, pages: Set<ReaderPageId>): Boolean {
        val record = tickets[ticket] ?: return false
        refresh(record)
        return tickets[ticket] === record && record.visible && pages.isNotEmpty() && record.drawn.containsAll(pages)
    }

    fun release(ticket: ReaderDualPageViewportTicket): Boolean {
        val record = tickets.remove(ticket) ?: inheritedReleases.remove(ticket) ?: return false
        if (tickets.values.none { it === record } && inheritedReleases.values.none { it === record }) {
            record.visible = false
            record.appendable = false
            // No slot trimming and no logical membership writeback on release.
            if (record.locked.isEmpty()) records.remove(record.id)
            // Released frames are bounded diagnostic metadata, never a logical pairing authority.
            records.values.filter { !it.visible }.dropLast(64).map { it.id }.forEach(records::remove)
        }
        return true
    }

    fun navigate(from: ReaderPageId, forward: Boolean): ReaderPageId? {
        val record = records[activeFrame]?.takeIf { it.visible }
        val current = record?.promised ?: presentationUnit(from).pageIds.toSet()
        val drawn = record?.drawn.orEmpty()
        val anchor = ordinals.getValue(from)
        val missing = (current - drawn).map(ordinals::getValue).filter { if (forward) it > anchor else it < anchor }
        if (missing.isNotEmpty()) return pages[if (forward) missing.min() else missing.max()]
        val ordinal = if (forward) {
            maxOf(anchor, drawn.maxOfOrNull(ordinals::getValue) ?: anchor)
        } else {
            minOf(anchor, drawn.minOfOrNull(ordinals::getValue) ?: anchor)
        }
        return pages.getOrNull(ordinal + if (forward) 1 else -1)
    }

    @Suppress("UNUSED_PARAMETER")
    fun move(target: ReaderPageId, forward: Boolean?, from: ReaderPageId?, sequence: Long) {
        currentLocationSequence = sequence
        val desired = candidate(target).unit
        val current = records[activeFrame]
        activeFrame = current?.takeIf { it.visible && it.unit.id == desired.id && target in it.unit.pageIds }?.id
            ?: records.values.firstOrNull { it.visible && it.unit.id == desired.id && target in it.unit.pageIds }?.id
        records.values.filter { it.visible }.forEach {
            it.appendable = !it.stale && it.id == activeFrame
            if (it.appendable) it.activeSequence = sequence
        }
        records.values.filter { record ->
            record.id != activeFrame && record.locked.isEmpty() &&
                tickets.values.none { it === record } && inheritedReleases.values.none { it === record }
        }.toList().forEach(::discard)
        reported.clear()
    }

    /** Lazy metadata rebuild: bulk fact ingestion performs no chapter scan until a consumer requests it. */
    @Suppress("UNUSED_PARAMETER")
    fun factsChanged(changedPage: ReaderPageId? = null) {
        inputRevision++
        projection = null
        defaultProjection = null
    }

    private fun refresh(record: Record) {
        if (!record.visible || record.stale) return
        val desired = candidate(record.selectionPage).unit
        if (desired.id == record.unit.id) {
            record.unit = desired
        } else if (record.locked.isEmpty()) {
            discard(record)
        } else {
            // Automatic regrouping cannot withdraw a compatible promised slot. Only actual wide
            // geometry makes an unprepared half-slot incompatible; its navigation duty is retained.
            record.unit = record.unit.copy(
                slots = record.unit.slots.filter {
                    it.pageId in record.locked || facts[it.pageId]?.state != ReaderPageFactState.SPREAD
                },
            )
        }
    }

    private fun discard(record: Record) {
        tickets.keys.removeAll { tickets[it] === record }
        inheritedReleases.keys.removeAll { inheritedReleases[it] === record }
        records.remove(record.id)
    }

    fun configure(direction: ReaderDirection, automatic: Boolean, singleMode: Boolean) {
        if (this.direction != direction || this.singleMode != singleMode) invalidateViewport()
        this.direction = direction
        this.automatic = automatic
        this.singleMode = singleMode
        factsChanged()
    }

    fun manualBoundaries(
        next: Set<ReaderPageId>,
        explicit: Boolean,
        protectVisible: Boolean = false,
        pairs: Set<Int> = emptySet(),
        opening: ReaderChapterOpeningMode = ReaderChapterOpeningMode.AUTO,
    ) {
        val following = next.mapNotNullTo(mutableSetOf()) { boundary ->
            val start = ordinals.getValue(boundary) + 1
            start.takeIf {
                it in 1 until pages.lastIndex && pages[it] !in next && pages[it + 1] !in next &&
                    pairs.none { explicitStart -> explicitStart in it - 1..it + 1 }
            }
        }
        forced = next
        manualPairStarts = pairs + following
        openingMode = opening
        factsChanged()
        if (explicit && !protectVisible) {
            records.values.filter { it.visible && unit(it.unit.anchor).id != it.unit.id }.toList().forEach(::discard)
            reported.clear()
        }
    }

    fun invalidateViewport() {
        inputRevision++
        activeFrame = null
        tickets.clear()
        inheritedReleases.clear()
        records.values.forEach {
            it.visible = false
            it.appendable = false
        }
        context++
        projection = null
        defaultProjection = null
    }

    fun reloadContent(page: ReaderPageId): Boolean {
        val affected = records.values.filter { page in it.unit.pageIds }
        val invalidated = affected.filter { it.locked.isEmpty() || page in it.locked }
        val visible = invalidated.any { it.visible && page in it.locked }
        invalidated.forEach(::discard)
        projection = null
        return visible
    }
}
