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
            if (locked.isEmpty() || slots.isEmpty()) continue
            val copy = Record(
                old.id, old.unit.copy(slots = slots), locked,
                old.drawn.filterTo(mutableSetOf()) { it in unchanged },
                old.visible, old.appendable, old.reserved && slots.size == 2, old.activeSequence,
                old.origin.takeIf { it in unchanged } ?: slots.first().pageId, old.forward,
                old.invalidAfterRelease,
            )
            records[copy.id] = copy
            if (copy.visible) active[copy.id] = copy
            copy.unit.pageIds.filter { previous.membership[it] == old.id }.forEach { membership[it] = copy.id }
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

    private fun record(page: ReaderPageId): Record? = membership[page]?.let(records::get)
        ?: active.values.firstOrNull { it.appendable && page in it.unit.pageIds }
    private fun candidate(page: ReaderPageId, forward: Boolean = !reverse): ReaderDualPageDecision {
        if (singleMode) {
            return ReaderDualPageDecision(
                ReaderDualPageUnit(listOf(ReaderDualPageSlot(page, ReaderPortraitSingleSlot.FULL)), known(page)),
            )
        }
        reservations[page]?.let { pair ->
            return ReaderDualPageDecision(dual(pair.first, pair.second))
        }
        val index = ordinals.getValue(page)
        return SharedDualPageRules.decide(
            pages, facts, direction, index, forward, forced, membership.keys,
            opening = index == 1 || (!openingClosed && index == openingThrough + 1),
            distance = { a, b -> if (automatic) distance(a, b) else null },
        )
    }

    private fun dual(first: ReaderPageId, second: ReaderPageId) =
        SharedDualPageRules.pairedUnit(first, second, direction, known(first) && known(second))
    private fun known(page: ReaderPageId) =
        facts[page]?.state in setOf(ReaderPageFactState.PORTRAIT, ReaderPageFactState.SPREAD)

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
        // A later slot cannot appear while the earlier page's effective type is still unknown.
        val ready = preparingPages.filterTo(mutableSetOf()) { id ->
            id in record.unit.pageIds && (singleMode || known(id)) &&
                (singleMode || id == record.unit.anchor || known(record.unit.anchor)) &&
                (membership[id] == null || membership[id] == record.id)
        }
        if (ready.size != preparingPages.size) return null
        record.visible = true
        active[record.id] = record
        record.appendable = true
        record.activeSequence = location.sequence
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
            (!singleMode && page != record.unit.anchor && !known(record.unit.anchor)) ||
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
        // A later image already at the trailing slot commits the earlier loading slot as well.
        val keep = if (record.drawn.any { it != record.unit.anchor }) record.unit.pageIds.toSet() else record.drawn
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
        val record = record(from)
        if (record != null && record.drawn.isNotEmpty()) {
            val missing = record.unit.pageIds.firstOrNull { it !in record.drawn }
            if (missing != null && (forward || ordinals.getValue(missing) <= ordinals.getValue(from))) return missing
        }
        val ordinal = if (record == null || record.drawn.isEmpty()) {
            ordinals.getValue(from)
        } else {
            if (forward) record.drawn.maxOf(ordinals::getValue) else record.unit.pageIds.minOf(ordinals::getValue)
        }
        return pages.getOrNull(ordinal + if (forward) 1 else -1)
    }

    fun move(target: ReaderPageId, forward: Boolean?, from: ReaderPageId?, sequence: Long) {
        val outgoing = from?.let(::record)
        val incoming = record(target)?.takeUnless {
            // A mounted loading proposal has no geometry promise; the new logical target owns its decision.
            (it.locked.isEmpty() && it.drawn.isEmpty() && target != it.origin) ||
                (it === outgoing && target != it.unit.anchor && target !in it.drawn && it.unit.anchor in it.drawn)
        }
        val leaving = (active.values + listOfNotNull(outgoing)).distinct().filter { it !== incoming }
        for (record in leaving) {
            record.appendable = false
            if (record === outgoing && record.drawn.none { it != record.unit.anchor }) {
                val keep = record.drawn
                record.unit.pageIds.filter { it !in keep }.forEach {
                    if (membership[it] == record.id) membership.remove(it)
                }
                record.locked.retainAll(keep)
                if (keep.isNotEmpty()) {
                    record.unit =
                        record.unit.copy(slots = record.unit.slots.filter { it.pageId in keep })
                }
            }
        }
        incoming?.activeSequence = sequence
        reported.clear()
        reverse = forward == false
        if (forward == null || from == null || reservations[target] == null) reservations.clear()
        projectionTarget = target
        projection = null
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
        if (record.locked.isEmpty()) {
            val next = candidate(record.origin, record.forward).unit
            if (next != record.unit) {
                if (next.id != record.unit.id) tickets.keys.removeAll { tickets[it] === record }
                updateUnit(record, next)
            }
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
        val next = SharedDualPageRules.decide(
            pages, facts, direction, ordinals.getValue(record.origin), record.forward, forced,
            occupiedByOthers(record), opening = false,
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

    fun manualBoundaries(next: Set<ReaderPageId>, explicit: Boolean, protectVisible: Boolean = false) {
        val changed = next.symmetricDifference(forced)
        forced = next
        if (explicit) {
            if (changed.any { ordinals.getValue(it) <= openingEnd + 1 }) {
                openingThrough = 0
                openingEnd = 0
                openingClosed = false
            }
            val conflicting = records.values.filter { record ->
                record.unit.pageIds.any { it in changed || pages.getOrNull(ordinals.getValue(it) - 1) in changed }
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
        reservations.entries.removeAll { (_, pair) -> pair.first in next || pair.second in next }
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
