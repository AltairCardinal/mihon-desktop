package mihon.domain.reader

import mihon.domain.reader.session.ReaderPageId

enum class ReaderPageFactState { UNKNOWN, PORTRAIT, SPREAD, FAILED }

data class ReaderPageFact(
    val state: ReaderPageFactState,
    val width: Int = 0,
    val height: Int = 0,
    val rotation: PageRotation = PageRotation.NONE,
    val sourceOrientation: ReaderImageOrientation = ReaderImageOrientation.NORMAL,
) {
    companion object {
        fun dimensions(
            width: Int,
            height: Int,
            rotation: PageRotation = PageRotation.NONE,
            sourceOrientation: ReaderImageOrientation = ReaderImageOrientation.NORMAL,
        ): ReaderPageFact =
            ReaderPageFact(
                when (classifyPage(width, height, rotation)) {
                    PageLayout.PORTRAIT -> ReaderPageFactState.PORTRAIT
                    PageLayout.SPREAD -> ReaderPageFactState.SPREAD
                    PageLayout.UNKNOWN -> ReaderPageFactState.UNKNOWN
                },
                width,
                height,
                rotation,
                sourceOrientation,
            )
    }
}

enum class ReaderDualPageSingleReason {
    ONLY_PAGE,
    SPREAD,
    FIRST_PAGE,
    OPENING,
    CHAPTER_TAIL,
    REMAINDER_BEFORE_SPREAD,
    MANUAL_BOUNDARY,
    MATCH_BOUNDARY,
    AMBIGUOUS,
    UNRESOLVED,
}

data class ReaderDualPageSlot(val pageId: ReaderPageId, val position: ReaderPortraitSingleSlot)

/** Candidate content identity; native mounts use ReaderDualPageFrameId after preparation. */
data class ReaderDualPageUnitId(val slots: List<ReaderDualPageSlot>)

data class ReaderDualPageUnit(
    val slots: List<ReaderDualPageSlot>,
    val certain: Boolean,
    val singleReason: ReaderDualPageSingleReason? = null,
) {
    val id: ReaderDualPageUnitId get() = ReaderDualPageUnitId(slots)
    val pageIds: List<ReaderPageId> get() = slots.map(ReaderDualPageSlot::pageId)
    val anchor: ReaderPageId get() = pageIds.first()
}

/** A proposal has no reading authority. Publishing a reservation belongs to the serial session. */
data class ReaderDualPageDecision(
    val unit: ReaderDualPageUnit,
    val reservedPair: Pair<ReaderPageId, ReaderPageId>? = null,
)

/** The sole dual-page product policy. Platforms supply facts and user choices, not rule switches. */
object SharedDualPageRules {
    /** Reads only the local three-page window and already available boundary/occupancy summaries. */
    fun decide(
        pages: List<ReaderPageId>,
        facts: Map<ReaderPageId, ReaderPageFact>,
        direction: ReaderDirection,
        targetIndex: Int,
        forward: Boolean = true,
        forcedSinglePages: Set<ReaderPageId> = emptySet(),
        occupied: Set<ReaderPageId> = emptySet(),
        opening: Boolean = targetIndex == 1,
        distance: (ReaderPageId, ReaderPageId) -> Double? = { _, _ -> null },
    ): ReaderDualPageDecision {
        require(direction != ReaderDirection.VERTICAL)
        val target = pages[targetIndex]
        fun state(index: Int) = pages.getOrNull(index)?.let { facts[it]?.state } ?: ReaderPageFactState.UNKNOWN
        fun portrait(index: Int) = state(index) == ReaderPageFactState.PORTRAIT
        fun free(index: Int) = pages.getOrNull(index)?.let { it !in forcedSinglePages && it !in occupied } == true
        fun single(reason: ReaderDualPageSingleReason, slot: ReaderPortraitSingleSlot? = null) =
            ReaderDualPageDecision(
                ReaderDualPageUnit(
                    listOf(ReaderDualPageSlot(target, slot ?: portraitSinglePageSlot(direction, reason))),
                    state(targetIndex) in setOf(ReaderPageFactState.PORTRAIT, ReaderPageFactState.SPREAD),
                    reason,
                ),
            )
        fun pair(a: Int, b: Int) = ReaderDualPageDecision(
            pairedUnit(pages[a], pages[b], direction, portrait(a) && portrait(b)),
        )
        val leadingSlot = if (direction == ReaderDirection.RTL) {
            ReaderPortraitSingleSlot.RIGHT
        } else {
            ReaderPortraitSingleSlot.LEFT
        }
        if (pages.size == 1) return single(ReaderDualPageSingleReason.ONLY_PAGE)
        if (state(targetIndex) == ReaderPageFactState.SPREAD) return single(ReaderDualPageSingleReason.SPREAD)
        if (targetIndex == 0) return single(ReaderDualPageSingleReason.FIRST_PAGE)
        if (target in forcedSinglePages) return single(ReaderDualPageSingleReason.MANUAL_BOUNDARY, leadingSlot)
        val step = if (forward) 1 else -1
        val partner = targetIndex + step
        val beyond = targetIndex + step * 2
        if (free(partner) && free(beyond) && portrait(partner) && portrait(beyond)) {
            val later = if (forward) {
                distance(
                    pages[partner],
                    pages[beyond],
                )
            } else {
                distance(pages[beyond], pages[partner])
            }
            val near = if (forward) distance(target, pages[partner]) else distance(pages[partner], target)
            if (later != null && (near == null || later < near)) {
                val reserved = if (forward) pages[partner] to pages[beyond] else pages[beyond] to pages[partner]
                return single(ReaderDualPageSingleReason.MATCH_BOUNDARY, leadingSlot).copy(reservedPair = reserved)
            }
        }
        if (!free(partner) || state(partner) == ReaderPageFactState.SPREAD) {
            val reason = when {
                pages.getOrNull(partner) in forcedSinglePages -> ReaderDualPageSingleReason.MANUAL_BOUNDARY
                forward && opening && state(partner) == ReaderPageFactState.SPREAD -> ReaderDualPageSingleReason.OPENING
                portrait(targetIndex) && state(targetIndex - 1) == ReaderPageFactState.SPREAD &&
                    state(targetIndex + 1) == ReaderPageFactState.SPREAD -> ReaderDualPageSingleReason.AMBIGUOUS
                targetIndex == pages.lastIndex -> ReaderDualPageSingleReason.CHAPTER_TAIL
                else -> ReaderDualPageSingleReason.REMAINDER_BEFORE_SPREAD
            }
            return single(
                reason,
                when (reason) {
                    ReaderDualPageSingleReason.AMBIGUOUS -> ReaderPortraitSingleSlot.FULL
                    ReaderDualPageSingleReason.MANUAL_BOUNDARY -> leadingSlot
                    else -> null
                },
            )
        }
        // Unknown is not a portrait fact: it only permits a pending forward half-slot.
        if (!forward &&
            (!portrait(targetIndex) || !portrait(partner))
        ) {
            return single(ReaderDualPageSingleReason.UNRESOLVED, leadingSlot)
        }
        return if (forward) pair(targetIndex, partner) else pair(partner, targetIndex)
    }
    internal fun pairedUnit(
        first: ReaderPageId,
        second: ReaderPageId,
        direction: ReaderDirection,
        certain: Boolean,
    ): ReaderDualPageUnit {
        require(direction != ReaderDirection.VERTICAL)
        val leading = if (direction ==
            ReaderDirection.RTL
        ) {
            ReaderPortraitSingleSlot.RIGHT
        } else {
            ReaderPortraitSingleSlot.LEFT
        }
        val trailing = if (direction ==
            ReaderDirection.RTL
        ) {
            ReaderPortraitSingleSlot.LEFT
        } else {
            ReaderPortraitSingleSlot.RIGHT
        }
        return ReaderDualPageUnit(
            listOf(ReaderDualPageSlot(first, leading), ReaderDualPageSlot(second, trailing)),
            certain,
        )
    }

    fun build(
        pages: List<ReaderPageId>,
        facts: Map<ReaderPageId, ReaderPageFact>,
        direction: ReaderDirection,
        forcedSinglePages: Set<ReaderPageId> = emptySet(),
        matchedPairs: Set<Pair<ReaderPageId, ReaderPageId>> = emptySet(),
        automaticMatching: Boolean = false,
    ): List<ReaderDualPageUnit> {
        require(direction != ReaderDirection.VERTICAL)
        require(pages.distinct().size == pages.size)
        require(pages.map { it.chapterId }.distinct().size <= 1)
        if (pages.isEmpty()) return emptyList()
        val validMatches = if (automaticMatching) matchedPairs else emptySet()
        val result = mutableListOf<ReaderDualPageUnit>()
        var index = 0
        var opening = true
        var reserved: Pair<ReaderPageId, ReaderPageId>? = null
        while (index < pages.size) {
            val decision = if (reserved?.first == pages[index]) {
                val pair = requireNotNull(reserved)
                reserved = null
                ReaderDualPageDecision(pairedUnit(pair.first, pair.second, direction, certain = true))
            } else {
                decide(
                    pages,
                    facts,
                    direction,
                    index,
                    forcedSinglePages = forcedSinglePages,
                    opening = opening,
                    distance = { a, b -> if ((a to b) in validMatches) 0.0 else null },
                )
            }
            result += decision.unit
            reserved = decision.reservedPair ?: reserved
            if (decision.unit.pageIds.size == 2 || !decision.unit.certain) opening = false
            index += decision.unit.pageIds.size
        }
        return result
    }
}
