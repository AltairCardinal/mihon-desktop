package mihon.domain.reader

import mihon.domain.reader.session.ReaderPageId

/** Stable mount identity is independent of a pending partner and cache/score revisions. */
data class ReaderDualPageFrameId(val generation: Long, val context: Long, val serial: Long)

data class ReaderDualPageFrame(
    val id: ReaderDualPageFrameId,
    val unit: ReaderDualPageUnit,
    val lockedPageIds: Set<ReaderPageId>,
    val drawnPageIds: Set<ReaderPageId>,
)

/** Lightweight serial metadata owner; it never holds decoded images or starts optional I/O. */
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
        var reserved: Boolean = false,
        var activeSequence: Long = -1,
        val origin: ReaderPageId = unit.anchor,
        val forward: Boolean = true,
        var invalidAfterRelease: Boolean = false,
        var entered: Boolean = false,
    ) {
        fun snapshot() = ReaderDualPageFrame(id, unit, locked.toSet(), drawn.toSet())
    }

    private val pages = identity().pageIds
    private val ordinals = pages.withIndex().associate { it.value to it.index }
    private val records = linkedMapOf<ReaderDualPageFrameId, Record>()
    private val active = linkedMapOf<ReaderDualPageFrameId, Record>()
    private val membership = mutableMapOf<ReaderPageId, ReaderDualPageFrameId>()
    private val reservations = mutableMapOf<ReaderPageId, Pair<ReaderPageId, ReaderPageId>>()
    private val tickets = mutableMapOf<ReaderDualPageViewportTicket, Record>()
    private val reported = mutableMapOf<Long, MutableSet<ReaderPageId>>()
    private var serial = 0L
    private var context = 0L
    private var reverse = false
    private var forced: Set<ReaderPageId> = emptySet()
    private var manualPairStarts: Set<Int> = emptySet()
    private var openingMode = ReaderChapterOpeningMode.AUTO
    private var automatic = false
    private var singleMode = false
    private var openingThrough = 0
    private var openingEnd = 0
    private var openingClosed = false
    private var projectionTarget: ReaderPageId? = null
    private var projection: List<ReaderDualPageUnit>? = null

    fun unit(page: ReaderPageId): ReaderDualPageUnit = if (singleMode) {
        candidate(page).unit
    } else {
        record(page)?.unit
            ?: candidate(page).unit
    }
    fun frame(ticket: ReaderDualPageViewportTicket): ReaderDualPageFrame? = tickets[ticket]?.snapshot()
    val history: List<ReaderDualPageFrame> get() = records.values.filter {
        it.locked.isNotEmpty()
    }.map { it.snapshot() }
    val reservedPairs: Set<Pair<ReaderPageId, ReaderPageId>> get() = reservations.values.toSet()
    val visibleContentPageIds: Set<ReaderPageId> get() = active.values.flatMapTo(mutableSetOf()) { it.locked }
    fun readingEligible(sequence: Long, page: ReaderPageId): Boolean =
        unit(page).pageIds.any { it !in reported[sequence].orEmpty() }

    fun inheritProgress(previous: ReaderDualPagePresentationOwner) {
        reported.putAll(previous.reported.mapValues { (_, pages) -> pages.toMutableSet() })
    }

    fun canReplace(changed: Set<ReaderPageId>): Boolean = active.values.none { record ->
        record.locked.any { it in changed }
    }

    fun inheritHistory(previous: ReaderDualPagePresentationOwner, unchanged: Set<ReaderPageId>) {
        context = previous.context
        serial = previous.serial
        reverse = previous.reverse
        if ((0..previous.openingEnd).all { pages[it] in unchanged }) {
            openingThrough = previous.openingThrough
            openingEnd = previous.openingEnd
            openingClosed = previous.openingClosed
        }
        for (old in previous.records.values) {
            val slots = old.unit.slots.filter { it.pageId in unchanged || it.pageId !in old.locked }
            val locked = old.locked.filterTo(mutableSetOf()) { it in unchanged }
            if ((locked.isEmpty() && !old.entered) || slots.isEmpty()) continue
            val copy = Record(
                old.id, old.unit.copy(slots = slots), locked,
                old.drawn.filterTo(mutableSetOf()) { it in unchanged },
                old.visible, old.appendable, old.reserved && slots.size == 2, old.activeSequence,
                old.origin.takeIf { it in unchanged } ?: slots.first().pageId, old.forward,
                old.invalidAfterRelease,
                old.entered && old.unit.pageIds.all { it in unchanged },
            )
            records[copy.id] = copy
            if (copy.visible) active[copy.id] = copy
            copy.unit.pageIds.filter { it in unchanged && previous.membership[it] == old.id }
                .forEach { membership[it] = copy.id }
        }
    }

    /** First content orientation confirmation replaces only an unpainted proposal's capabilities. */
    fun metadataChanged(page: ReaderPageId) {
        val affected = active.values.filter { it.locked.isEmpty() && page in it.unit.pageIds }.toSet()
        tickets.keys.removeAll { tickets[it] in affected }
    }

    /** A list projection for existing pagers. Fact callbacks never build this chapter-wide projection. */
    fun units(target: ReaderPageId?): List<ReaderDualPageUnit> {
        if (projectionTarget != target) projection = null
        projectionTarget = target
        return projection ?: project(target, 0, pages.lastIndex).also { projection = it }
    }

    private fun project(target: ReaderPageId?, start: Int, end: Int): List<ReaderDualPageUnit> = buildList {
        if (singleMode) {
            addAll((start..end).map { candidate(pages[it]).unit })
            return@buildList
        }
        val targetUnit = target?.let(::unit)
        val claims = mutableMapOf<ReaderPageId, ReaderDualPageUnit>()
        for (ordinal in start..end) {
            membership[pages[ordinal]]?.let(records::get)?.let { record ->
                record.unit.pageIds.forEach {
                    claims[it] =
                        record.unit
                }
            }
        }
        targetUnit?.pageIds?.forEach { claims[it] = targetUnit }
        val emitted = mutableSetOf<ReaderPageId>()
        for (ordinal in start..end) {
            val page = pages[ordinal]
            if (page in emitted) continue
            var proposed = when {
                claims[page] != null -> claims.getValue(page)
                else -> candidate(page, forward = true).unit
            }
            val slots = proposed.slots.filter {
                it.pageId !in emitted && ordinals.getValue(it.pageId) in start..end &&
                    (claims[it.pageId] == null || claims[it.pageId] == proposed)
            }
            if (slots.isEmpty()) continue
            if (slots != proposed.slots) proposed = proposed.copy(slots = slots)
            add(proposed)
            emitted += proposed.pageIds
        }
    }

    // Only its origin owns an empty proposal until a physical slot is locked or drawn.
    private fun record(page: ReaderPageId): Record? = membership[page]?.let(records::get)
        ?: active.values.firstOrNull {
            it.appendable && page in it.unit.pageIds &&
                (page == it.origin || it.locked.isNotEmpty() || it.drawn.isNotEmpty())
        }
    private fun candidate(page: ReaderPageId, forward: Boolean = !reverse): ReaderDualPageDecision {
        if (singleMode) {
            return ReaderDualPageDecision(
                ReaderDualPageUnit(listOf(ReaderDualPageSlot(page, ReaderPortraitSingleSlot.FULL)), known(page)),
            )
        }
        explicitUnit(page)?.let { return ReaderDualPageDecision(it) }
        reservations[page]?.let { pair ->
            val unit = dual(pair.first, pair.second)
            val compatible = !automatic || unit.slots.all { slot ->
                when (facts[slot.pageId]?.sideHint ?: ReaderPageSideHint.UNKNOWN) {
                    ReaderPageSideHint.UNKNOWN -> true
                    ReaderPageSideHint.LEFT -> slot.position == ReaderPortraitSingleSlot.LEFT
                    ReaderPageSideHint.RIGHT -> slot.position == ReaderPortraitSingleSlot.RIGHT
                }
            }
            if (compatible) return ReaderDualPageDecision(unit)
            reservations.entries.removeAll { it.value == pair }
        }
        val index = ordinals.getValue(page)
        return SharedDualPageRules.decide(
            pages, facts, direction, index, forward, forced, membership.keys + manualOccupied() + openingOccupied(),
            opening = index == 1 || (!openingClosed && index == openingThrough + 1),
            usePageSideHints = automatic,
            distance = { a, b -> if (automatic) distance(a, b) else null },
        )
    }

    private fun manualOccupied(): Set<ReaderPageId> {
        val starts = manualPairStarts + setOfNotNull(0.takeIf { openingMode == ReaderChapterOpeningMode.FIRST_PAIR })
        return starts.filter { start ->
            start in 0 until pages.lastIndex &&
                facts[pages[start]]?.state != ReaderPageFactState.SPREAD &&
                facts[pages[start + 1]]?.state != ReaderPageFactState.SPREAD
        }.flatMapTo(mutableSetOf()) { listOf(pages[it], pages[it + 1]) }
    }

    private fun openingOccupied(): Set<ReaderPageId> = if (openingMode ==
        ReaderChapterOpeningMode.FIRST_SINGLE
    ) {
        pages.take(1).toSet()
    } else {
        emptySet()
    }

    private fun explicitUnit(page: ReaderPageId, owner: Record? = null): ReaderDualPageUnit? {
        val index = ordinals.getValue(page)
        val pairStart = manualPairStarts.firstOrNull { index == it || index == it + 1 }
            ?: 0.takeIf { index in 0..1 && openingMode == ReaderChapterOpeningMode.FIRST_PAIR }
        if (pairStart != null) {
            val a = pages.getOrNull(pairStart) ?: return null
            val b = pages.getOrNull(pairStart + 1) ?: return null
            // User intent overrides heuristic sides, but never real image structure.
            if (facts[a]?.state != ReaderPageFactState.SPREAD && facts[b]?.state != ReaderPageFactState.SPREAD &&
                a !in forced &&
                b !in forced
            ) {
                val desired = listOf(a, b)
                val conflicts = desired.mapNotNull { membership[it]?.let(records::get) }.distinct().filter {
                    it !==
                        owner &&
                        it.unit.pageIds != desired
                }
                if (conflicts.any { it.visible }) return null
                conflicts.forEach(::discard)
                return dual(a, b)
            }
        }
        if (index == 0 && openingMode == ReaderChapterOpeningMode.FIRST_SINGLE &&
            facts[page]?.state != ReaderPageFactState.SPREAD
        ) {
            return ReaderDualPageUnit(
                listOf(ReaderDualPageSlot(page, ReaderPortraitSingleSlot.FULL)),
                known(page),
                ReaderDualPageSingleReason.FIRST_PAGE,
            )
        }
        return null
    }

    fun isManualUnit(unit: ReaderDualPageUnit): Boolean = unit.pageIds.size == 2 &&
        (
            ordinals.getValue(unit.anchor) in manualPairStarts ||
                (unit.anchor == pages.first() && openingMode == ReaderChapterOpeningMode.FIRST_PAIR)
            )

    private fun hasExplicitPair(record: Record) =
        isManualUnit(record.unit) && record.unit.pageIds.none { facts[it]?.state == ReaderPageFactState.SPREAD }

    private fun dual(first: ReaderPageId, second: ReaderPageId) =
        SharedDualPageRules.pairedUnit(first, second, direction, known(first) && known(second))
    private fun known(page: ReaderPageId) =
        facts[page]?.state in setOf(ReaderPageFactState.PORTRAIT, ReaderPageFactState.SPREAD)

    private fun confirmsOwnSide(record: Record, page: ReaderPageId): Boolean {
        if (!automatic) return false
        val side = facts[page]?.sideHint ?: return false
        val position = when (side) {
            ReaderPageSideHint.LEFT -> ReaderPortraitSingleSlot.LEFT
            ReaderPageSideHint.RIGHT -> ReaderPortraitSingleSlot.RIGHT
            ReaderPageSideHint.UNKNOWN -> return false
        }
        return record.unit.slots.any { it.pageId == page && it.position == position }
    }

    private fun publishReservation(pair: Pair<ReaderPageId, ReaderPageId>?) {
        if (pair == null || listOf(pair.first, pair.second).any { it in membership || it in reservations }) return
        reservations[pair.first] = pair
        reservations[pair.second] = pair
    }

    /** Preparation and slot locking occur in this owner turn before a platform may emit pixels. */
    fun prepare(
        page: ReaderPageId,
        location: ReaderDualPageLocationIntent,
        layoutRevision: Long,
        preparingPages: Set<ReaderPageId>,
        readingEligible: Boolean,
    ): ReaderDualPageViewportTicket? {
        val decision = if (record(page) == null) candidate(page) else null
        val record = record(page) ?: Record(
            ReaderDualPageFrameId(identity().generation, context, serial++),
            requireNotNull(decision).unit,
            reserved = reservations[page] != null,
            origin = page,
            forward = !reverse,
        ).also { records[it.id] = it }
        refresh(record)
        // A later slot needs a known anchor or its own decoded evidence fixing its physical side.
        val ready = preparingPages.filterTo(mutableSetOf()) { id ->
            id in record.unit.pageIds && (singleMode || known(id)) &&
                (
                    singleMode || hasExplicitPair(record) || id == record.unit.anchor || known(record.unit.anchor) ||
                        confirmsOwnSide(record, id)
                    ) &&
                (membership[id] == null || membership[id] == record.id)
        }
        if (ready.size != preparingPages.size) return null
        record.visible = true
        active[record.id] = record
        record.appendable = true
        record.activeSequence = location.sequence
        if (page == location.pageId) enter(record)
        for (id in ready) {
            record.locked += id
            membership[id] = record.id
        }
        if (ready.isNotEmpty()) {
            if (record.reserved) reservations.entries.removeAll { it.value.first in record.unit.pageIds }
            publishReservation(decision?.reservedPair)
        }
        patchProjection(ordinals.getValue(page))
        val existing = tickets.entries.firstOrNull {
            it.value === record && it.key.locationSequence == location.sequence &&
                it.key.readingEligible == readingEligible
        }?.key
        if (existing != null) return existing
        return ReaderDualPageViewportTicket(
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
        if (!record.visible || !record.appendable || ticket.locationSequence != record.activeSequence ||
            page !in record.unit.pageIds || (!singleMode && !known(page)) ||
            (
                !singleMode && !hasExplicitPair(record) && page != record.unit.anchor && !known(
                    record.unit.anchor,
                ) && !confirmsOwnSide(record, page)
                ) ||
            membership[page]?.let { it != record.id } == true
        ) {
            return false
        }
        record.locked += page
        membership[page] = record.id
        return true
    }

    fun drawn(ticket: ReaderDualPageViewportTicket, pages: Set<ReaderPageId>): Boolean {
        val record = tickets[ticket] ?: return false
        if (!record.visible || pages.isEmpty() || !record.locked.containsAll(pages) ||
            (
                (!record.appendable || ticket.locationSequence != record.activeSequence) &&
                    !record.drawn.containsAll(pages)
                )
        ) {
            return false
        }
        record.drawn += pages
        val nextOpening = this.pages.getOrNull(openingThrough + 1)
        if (!openingClosed && !singleMode && nextOpening in record.unit.pageIds) {
            if (record.unit.pageIds.size == 2) {
                openingClosed = true
                openingEnd = record.unit.pageIds.maxOf(ordinals::getValue)
            } else if (nextOpening in record.drawn) {
                openingThrough++
                openingEnd = openingThrough
            }
        }
        return true
    }

    fun progressPages(ticket: ReaderDualPageViewportTicket, pages: Set<ReaderPageId>): Set<ReaderPageId> {
        if (!ticket.readingEligible || !drawn(ticket, pages)) return emptySet()
        val previous = reported.getOrPut(ticket.locationSequence) { mutableSetOf() }
        if ((pages - previous).isEmpty()) return emptySet()
        previous += pages
        // Passing the accumulated real set protects the existing maximum-page progress semantics.
        return previous.toSet()
    }

    fun release(ticket: ReaderDualPageViewportTicket): Boolean {
        val record = tickets.remove(ticket) ?: return false
        if (tickets.values.none { it === record }) seal(record)
        return true
    }

    private fun seal(record: Record) {
        record.visible = false
        active.remove(record.id)
        if (record.invalidAfterRelease) {
            discard(record)
            projection = null
            return
        }
        val keep = if (record.entered || record.drawn.any { it != record.unit.anchor }) {
            record.unit.pageIds.toSet()
        } else {
            record.drawn
        }
        val removed = record.unit.pageIds.filter { it !in keep }
        removed.forEach { if (membership[it] == record.id) membership.remove(it) }
        record.locked.retainAll(keep)
        if (keep.isEmpty()) {
            records.remove(record.id)
        } else {
            record.unit = record.unit.copy(slots = record.unit.slots.filter { it.pageId in keep })
            keep.forEach { membership[it] = record.id }
        }
    }

    fun navigate(from: ReaderPageId, forward: Boolean): ReaderPageId? {
        val current = enteredRecord(from)
        val ordinal = if (forward) {
            current.unit.pageIds.maxOf(ordinals::getValue)
        } else {
            current.unit.pageIds.minOf(ordinals::getValue)
        }
        return pages.getOrNull(ordinal + if (forward) 1 else -1)
    }

    private fun enter(record: Record) {
        check(record.unit.pageIds.all { membership[it] == null || membership[it] == record.id })
        record.entered = true
        record.unit.pageIds.forEach { membership[it] = record.id }
    }

    private fun enteredRecord(page: ReaderPageId, unit: ReaderDualPageUnit? = null): Record =
        (
            record(page) ?: Record(
                ReaderDualPageFrameId(identity().generation, context, serial++),
                unit ?: candidate(page).unit,
                visible = false,
                origin = page,
                forward = !reverse,
            ).also { records[it.id] = it }
            ).also {
            it.appendable = true
            refresh(it)
            enter(it)
        }

    fun move(target: ReaderPageId, forward: Boolean?, from: ReaderPageId?, sequence: Long) {
        val outgoing = from?.let(::enteredRecord)
        val incoming = record(target)?.takeUnless {
            // A mounted loading proposal has no geometry promise; the new logical target owns its decision.
            !it.entered && it.locked.isEmpty() && it.drawn.isEmpty() && target != it.origin
        }
        val leaving = (active.values + listOfNotNull(outgoing)).distinct().filter { it !== incoming }
        for (record in leaving) {
            record.appendable = false
        }
        incoming?.activeSequence = sequence
        reported.clear()
        reverse = forward == false
        if (forward == null || from == null || reservations[target] == null) reservations.clear()
        projectionTarget = target
        projection = null
        enteredRecord(target)
    }

    /** Only active windows need reconsideration; already locked physical slots are copied unchanged. */
    fun factsChanged(changedPage: ReaderPageId? = null) {
        reservations.entries.removeAll { (_, pair) ->
            facts[pair.first]?.state != ReaderPageFactState.PORTRAIT ||
                facts[pair.second]?.state != ReaderPageFactState.PORTRAIT
        }
        active.values.filter { it.appendable }.forEach(::refresh)
        val target = projectionTarget?.let(ordinals::get) ?: return
        val changed = changedPage?.let(ordinals::get)
        if (changed == null || changed in target - 2..target + 4 ||
            active.values.any { changedPage in it.unit.pageIds }
        ) {
            patchProjection(target)
        }
        if (changed != null && changed !in target - 2..target + 4) patchProjection(changed)
        active.values.filter { it.appendable && (changedPage == null || changedPage in it.unit.pageIds) }
            .map {
                ordinals.getValue(it.origin)
            }.filter { it !in target - 2..target + 4 }.distinct().forEach(::patchProjection)
    }

    /** Replace only a bounded window; unrelated fact arrivals reuse the same immutable projection. */
    private fun patchProjection(center: Int) {
        val current = projection ?: return
        fun containing(index: Int): Int {
            var lo = 0
            var hi = current.lastIndex
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val unit = current[mid]
                when {
                    index < ordinals.getValue(unit.anchor) -> hi = mid - 1
                    index > ordinals.getValue(unit.pageIds.last()) -> lo = mid + 1
                    else -> return mid
                }
            }
            return lo.coerceIn(0, current.lastIndex)
        }
        if (current.isEmpty()) return
        val firstUnit = containing((center - 2).coerceAtLeast(0))
        val lastUnit = containing((center + 4).coerceAtMost(pages.lastIndex))
        val first = ordinals.getValue(current[firstUnit].anchor)
        val last = ordinals.getValue(current[lastUnit].pageIds.last())
        val updated = project(projectionTarget, first, last)
        if (updated == current.subList(firstUnit, lastUnit + 1)) return
        projection = current.take(firstUnit) + updated + current.drop(lastUnit + 1)
    }

    private fun refresh(record: Record) {
        if (!record.appendable || record.invalidAfterRelease) return
        val decisionFacts = if (record.entered) {
            object : Map<ReaderPageId, ReaderPageFact> by facts {
                override fun get(key: ReaderPageId): ReaderPageFact? = facts[key]?.let {
                    if (it.state == ReaderPageFactState.FAILED) it.copy(state = ReaderPageFactState.UNKNOWN) else it
                }
            }
        } else {
            facts
        }
        val nextDecision = if (record.entered) {
            explicitUnit(record.origin, record)?.let { ReaderDualPageDecision(it) } ?: SharedDualPageRules.decide(
                pages, decisionFacts, direction, ordinals.getValue(record.origin), record.forward, forced,
                occupiedByOthers(record) + manualOccupied() + openingOccupied(), usePageSideHints = automatic,
                distance = { a, b -> if (automatic) distance(a, b) else null },
            )
        } else {
            null
        }
        if (record.entered) {
            val wasSpread = record.unit.singleReason == ReaderDualPageSingleReason.SPREAD
            val structuralChange = record.unit.slots.any { slot ->
                val fact = facts[slot.pageId]
                val hintConflicts = when (fact?.sideHint) {
                    ReaderPageSideHint.LEFT -> slot.position != ReaderPortraitSingleSlot.LEFT
                    ReaderPageSideHint.RIGHT -> slot.position != ReaderPortraitSingleSlot.RIGHT
                    else -> false
                }
                (fact?.state == ReaderPageFactState.SPREAD && slot.position != ReaderPortraitSingleSlot.FULL) ||
                    (record.unit.pageIds.size == 2 && slot.pageId in forced) ||
                    (
                        automatic && !hasExplicitPair(record) && fact?.state == ReaderPageFactState.PORTRAIT &&
                            hintConflicts
                        ) ||
                    (wasSpread && fact?.state == ReaderPageFactState.PORTRAIT)
            }
            val matchedBoundary = !hasExplicitPair(record) && !record.reserved && nextDecision?.reservedPair != null
            val openingPair = record.unit.singleReason == ReaderDualPageSingleReason.FIRST_PAGE &&
                nextDecision?.unit?.pageIds?.size == 2
            if (!structuralChange && !matchedBoundary && !openingPair) {
                if (!record.unit.certain && record.unit.pageIds.all(::known)) {
                    updateUnit(record, record.unit.copy(certain = true))
                }
                return
            }
        }
        if (record.locked.isEmpty()) {
            val next = nextDecision?.unit ?: candidate(record.origin, record.forward).unit
            if (next != record.unit) {
                if (next.id != record.unit.id) tickets.keys.removeAll { tickets[it] === record }
                updateUnit(record, next)
            }
            return
        }
        // A real wide image may never occupy an unpainted half slot, including a manual pending anchor.
        val compatibleSlots = record.unit.slots.filter {
            it.pageId in record.locked ||
                facts[it.pageId]?.state != ReaderPageFactState.SPREAD
        }
        if (compatibleSlots != record.unit.slots && record.unit.pageIds.size == 2) {
            updateUnit(record, record.unit.copy(slots = compatibleSlots))
            return
        }
        val first = record.unit.anchor
        val fixed = record.unit.slots.filter { it.pageId in record.locked }
        val leading = if (direction ==
            ReaderDirection.RTL
        ) {
            ReaderPortraitSingleSlot.RIGHT
        } else {
            ReaderPortraitSingleSlot.LEFT
        }
        if (automatic && !hasExplicitPair(record) &&
            fixed.any { (facts[it.pageId]?.sideHint ?: ReaderPageSideHint.UNKNOWN) != ReaderPageSideHint.UNKNOWN }
        ) {
            val kept = record.unit.slots.filter { slot ->
                if (slot.pageId in record.locked) {
                    true
                } else {
                    val fact = facts[slot.pageId]
                    val side = fact?.sideHint ?: ReaderPageSideHint.UNKNOWN
                    fact?.state != ReaderPageFactState.SPREAD &&
                        slot.pageId !in forced && membership[slot.pageId]?.let { it != record.id } != true &&
                        (
                            side == ReaderPageSideHint.UNKNOWN ||
                                (side == ReaderPageSideHint.LEFT && slot.position == ReaderPortraitSingleSlot.LEFT) ||
                                (side == ReaderPageSideHint.RIGHT && slot.position == ReaderPortraitSingleSlot.RIGHT)
                            )
                }
            }
            if (kept != record.unit.slots) updateUnit(record, record.unit.copy(slots = kept))
            return
        }
        if (fixed.size != 1 || fixed.single().pageId != first || fixed.single().position != leading) return
        if (record.reserved) {
            val partner = record.unit.pageIds.getOrNull(1) ?: return
            if (facts[partner]?.state == ReaderPageFactState.SPREAD || partner in forced ||
                membership[partner]?.let { it != record.id } == true
            ) {
                updateUnit(record, record.unit.copy(slots = fixed))
            }
            return
        }
        val next =
            explicitUnit(record.origin, record)?.let { ReaderDualPageDecision(it) } ?: SharedDualPageRules.decide(
                pages, decisionFacts, direction, ordinals.getValue(record.origin), record.forward, forced,
                occupiedByOthers(record) + manualOccupied() + openingOccupied(), opening = false,
                usePageSideHints = automatic,
                distance = { a, b -> if (automatic) distance(a, b) else null },
            )
        if (next.reservedPair != null) {
            publishReservation(next.reservedPair)
            updateUnit(record, record.unit.copy(slots = fixed))
        } else if (next.unit.pageIds.size == 2 && next.unit.anchor == first && record.unit.pageIds.size == 2) {
            updateUnit(record, next.unit.copy(slots = fixed + next.unit.slots.filter { it.pageId !in record.locked }))
        } else {
            updateUnit(record, record.unit.copy(slots = fixed))
        }
    }

    private fun occupiedByOthers(record: Record): Set<ReaderPageId> {
        val index = ordinals.getValue(record.origin)
        return ((index - 2).coerceAtLeast(0)..(index + 2).coerceAtMost(pages.lastIndex))
            .mapNotNullTo(mutableSetOf()) { ordinal ->
                pages[ordinal].takeIf { membership[it]?.let { owner -> owner != record.id } == true }
            }
    }

    private fun updateUnit(record: Record, next: ReaderDualPageUnit) {
        (record.unit.pageIds - next.pageIds.toSet()).forEach {
            if (membership[it] == record.id) membership.remove(it)
        }
        record.unit = next
        if (record.entered) next.pageIds.forEach { membership[it] = record.id }
    }

    private fun discard(record: Record) {
        tickets.keys.removeAll { tickets[it] === record }
        record.unit.pageIds.forEach { if (membership[it] == record.id) membership.remove(it) }
        records.remove(record.id)
        active.remove(record.id)
    }

    fun configure(direction: ReaderDirection, automatic: Boolean, singleMode: Boolean) {
        val explicitContext = this.direction != direction || this.singleMode != singleMode
        val changedDirection = this.direction != direction
        val changedMode = this.singleMode != singleMode
        this.direction = direction
        if (explicitContext) {
            detachForContext()
            context++
            // Preserve source combinations for a viewport/direction change; recompute its geometry.
            for (record in records.values) {
                record.unit = if (record.unit.pageIds.size == 2) {
                    dual(record.unit.pageIds[0], record.unit.pageIds[1])
                } else {
                    record.unit.copy(
                        slots = record.unit.slots.map { slot ->
                            slot.copy(
                                position = if (!changedDirection) {
                                    slot.position
                                } else {
                                    when (slot.position) {
                                        ReaderPortraitSingleSlot.LEFT -> ReaderPortraitSingleSlot.RIGHT
                                        ReaderPortraitSingleSlot.RIGHT -> ReaderPortraitSingleSlot.LEFT
                                        ReaderPortraitSingleSlot.FULL -> ReaderPortraitSingleSlot.FULL
                                    }
                                },
                            )
                        },
                    )
                }
            }
            projection = null
            if (changedMode) {
                records.clear()
                active.clear()
                membership.clear()
            } else {
                renewHistoryFrames()
            }
        }
        this.direction = direction
        this.automatic = automatic
        this.singleMode = singleMode
        if (!automatic || explicitContext) reservations.clear()
        factsChanged()
    }

    fun manualBoundaries(
        next: Set<ReaderPageId>,
        explicit: Boolean,
        protectVisible: Boolean = false,
        pairs: Set<Int> = emptySet(),
        opening: ReaderChapterOpeningMode = ReaderChapterOpeningMode.AUTO,
    ) {
        // A legacy single boundary promises its immediately following legal pair. Derive only
        // this local relation, never a whole-chapter phase and never a new persisted user choice.
        val followingPairs = next.mapNotNullTo(mutableSetOf()) { boundary ->
            val start = ordinals.getValue(boundary) + 1
            start.takeIf {
                it in 1 until pages.lastIndex && pages[it] !in next && pages[it + 1] !in next &&
                    pairs.none { explicitStart -> explicitStart in it - 1..it + 1 }
            }
        }
        val nextPairs = pairs + followingPairs
        val changed = next.symmetricDifference(forced).toMutableSet()
        (nextPairs.symmetricDifference(manualPairStarts)).forEach { ordinal ->
            pages.getOrNull(ordinal)?.let(changed::add)
            pages.getOrNull(ordinal + 1)?.let(changed::add)
        }
        if (opening != openingMode) changed.addAll(pages.take(2))
        forced = next
        manualPairStarts = nextPairs
        openingMode = opening
        if (explicit) {
            if (changed.any { ordinals.getValue(it) <= openingEnd + 1 }) {
                openingThrough = 0
                openingEnd = 0
                openingClosed = false
            }
            val conflicting = records.values.filter { record ->
                record.unit.pageIds.any {
                    it in changed || pages.getOrNull(ordinals.getValue(it) - 1) in changed ||
                        pages.getOrNull(ordinals.getValue(it) + 1) in changed
                }
            }
            for (record in conflicting) {
                if (protectVisible && record.visible) {
                    // A delayed save changes future visits, never the pixels still being exposed.
                    record.invalidAfterRelease = true
                } else {
                    discard(record)
                }
            }
            if (!protectVisible) reported.clear()
            context++
            projection = null
        }
        reservations.entries.removeAll { (_, pair) ->
            pair.first in next || pair.second in next || pair.first in changed ||
                pair.second in changed
        }
        factsChanged()
    }

    fun invalidateViewport() {
        detachForContext()
        context++
        renewHistoryFrames()
        projection = null
    }

    fun reloadContent(page: ReaderPageId): Boolean {
        val affected = records.values.filter { page in it.locked }.toList()
        val visible = affected.any { it.visible }
        affected.forEach(::discard)
        reservations.entries.removeAll { page == it.value.first || page == it.value.second }
        projection = null
        return visible
    }

    /** Explicit geometry preserves the promised source combination, unlike leaving a partial group. */
    private fun detachForContext() {
        tickets.clear()
        active.values.forEach { it.visible = false }
        active.clear()
    }

    private fun renewHistoryFrames() {
        val previous = records.values.toList()
        records.clear()
        membership.clear()
        for (old in previous) {
            if (old.locked.isEmpty() || old.invalidAfterRelease) continue
            val replacement = old.copy(id = old.id.copy(context = context))
            records[replacement.id] = replacement
            replacement.unit.pageIds.forEach { membership[it] = replacement.id }
        }
    }

    private fun <T> Set<T>.symmetricDifference(other: Set<T>): Set<T> = (this - other) + (other - this)
}
