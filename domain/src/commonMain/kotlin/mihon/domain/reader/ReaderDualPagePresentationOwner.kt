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

/** Research implementation: metadata determines pairs; visible leases never feed pairing history. */
internal class ReaderDualPagePresentationOwner(
    private val identity: () -> ReaderDualPageIdentity,
    private val facts: Map<ReaderPageId, ReaderPageFact>,
    private val distance: (ReaderPageId, ReaderPageId) -> Double?,
    private var direction: ReaderDirection,
) {
    private data class Record(
        val id: ReaderDualPageFrameId,
        var unit: ReaderDualPageUnit,
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
    private val reported = mutableMapOf<Long, MutableSet<ReaderPageId>>()
    private var serial = 0L
    private var context = 0L
    private var currentLocationSequence = 0L
    private var forced: Set<ReaderPageId> = emptySet()
    private var manualPairStarts: Set<Int> = emptySet()
    private var openingMode = ReaderChapterOpeningMode.AUTO
    private var automatic = false
    private var singleMode = false
    private var projection: List<ReaderDualPageUnit>? = null
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
        ).also { units ->
        projection = units
        byPage = units.flatMap { unit -> unit.pageIds.map { it to unit } }.toMap()
    }

    fun canonicalUnits(): List<ReaderDualPageUnit> = plan()

    /** Overlay current physical leases without changing the canonical proposal or retaining history claims. */
    fun presentationFrameProjection(): List<ReaderDualPageUnit> {
        records.values.filter { it.visible }.toList().forEach(::refresh)
        val claims = records.values.filter { it.visible && it.locked.isNotEmpty() }
            .flatMap { record -> record.unit.pageIds.map { it to record.unit } }.toMap()
        val emitted = mutableSetOf<ReaderPageId>()
        val next = buildList {
            for (page in pages) {
                if (page in emitted) continue
                val proposed = claims[page] ?: unit(page)
                val slots = proposed.slots.filter { slot ->
                    slot.pageId !in emitted && (claims[slot.pageId] == null || claims[slot.pageId] == proposed)
                }
                if (slots.isEmpty()) continue
                add(if (slots == proposed.slots) proposed else proposed.copy(slots = slots))
                emitted += slots.map { it.pageId }
            }
        }
        return presentedProjection?.takeIf { it == next } ?: next.also { presentedProjection = it }
    }

    fun presentationUnit(page: ReaderPageId): ReaderDualPageUnit =
        presentationFrameProjection().first { page in it.pageIds }

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
            if (!unchanged.containsAll(old.unit.pageIds)) continue
            // Diagnostics only. New content ownership cannot inherit an old pixel capability.
            val copy = old.copy(locked = old.locked.toMutableSet(), drawn = old.drawn.toMutableSet(), visible = false)
            records[copy.id] = copy
        }
    }

    fun metadataChanged(page: ReaderPageId) {
        records.values.filter { it.locked.isEmpty() && page in it.unit.pageIds }.toList().forEach(::discard)
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

    fun prepare(
        page: ReaderPageId,
        location: ReaderDualPageLocationIntent,
        layoutRevision: Long,
        preparingPages: Set<ReaderPageId>,
        readingEligible: Boolean,
    ): ReaderDualPageViewportTicket? {
        currentLocationSequence = location.sequence
        val desired = presentationUnit(page)
        records.values.filter { it.visible }.toList().forEach(::refresh)
        val record = records.values.firstOrNull { it.visible && it.unit.id == desired.id }
            ?: Record(ReaderDualPageFrameId(identity().generation, context, serial++), desired)
                .also { records[it.id] = it }
        if (!preparingPages.all { ready(record, it) && (!record.stale || it in record.locked) }) return null
        record.visible = true
        record.appendable = !record.stale
        record.activeSequence = location.sequence
        record.locked += preparingPages
        return tickets.entries.firstOrNull {
            it.value === record && it.key.locationSequence == location.sequence &&
                it.key.readingEligible == readingEligible
        }?.key ?: ReaderDualPageViewportTicket(
            identity(),
            layoutRevision,
            location.sequence,
            record.unit.id,
            readingEligible,
            record.id,
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
        val record = tickets.remove(ticket) ?: return false
        if (tickets.values.none { it === record }) {
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
        val current = presentationUnit(from)
        val ordinal = if (forward) {
            current.pageIds.maxOf(
                ordinals::getValue,
            )
        } else {
            current.pageIds.minOf(ordinals::getValue)
        }
        return pages.getOrNull(ordinal + if (forward) 1 else -1)
    }

    @Suppress("UNUSED_PARAMETER")
    fun move(target: ReaderPageId, forward: Boolean?, from: ReaderPageId?, sequence: Long) {
        currentLocationSequence = sequence
        val desired = presentationUnit(target)
        records.values.filter { it.visible }.forEach {
            it.appendable = !it.stale && it.unit.id == desired.id
            if (it.appendable) it.activeSequence = sequence
        }
        reported.clear()
    }

    /** Lazy metadata rebuild: bulk fact ingestion performs no chapter scan until a consumer requests it. */
    @Suppress("UNUSED_PARAMETER")
    fun factsChanged(changedPage: ReaderPageId? = null) {
        projection = null
    }

    private fun refresh(record: Record) {
        if (!record.visible || record.stale) return
        val desired = unit(record.unit.anchor)
        if (desired.id == record.unit.id) {
            record.unit = desired
        } else if (record.locked.isEmpty()) {
            discard(record)
        } else {
            // Unprepared partners have no geometry promise. A late wide partner must remain reachable.
            record.unit = record.unit.copy(slots = record.unit.slots.filter { it.pageId in record.locked })
            // Real prior draws may still settle. This old capability cannot authorize newly added slots.
            record.stale = true
            record.appendable = false
        }
    }

    private fun discard(record: Record) {
        tickets.keys.removeAll { tickets[it] === record }
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
        tickets.clear()
        records.values.forEach {
            it.visible = false
            it.appendable = false
        }
        context++
        projection = null
    }

    fun reloadContent(page: ReaderPageId): Boolean {
        val affected = records.values.filter { page in it.unit.pageIds }
        val visible = affected.any { it.visible }
        affected.forEach(::discard)
        projection = null
        return visible
    }
}
