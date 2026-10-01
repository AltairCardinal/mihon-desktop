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

/** Stable identity contains logical pages and physical slots, never cache references or pager offsets. */
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

/** The sole dual-page product policy. Platforms supply facts and user choices, not rule switches. */
object SharedDualPageRules {
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
        val indexById = pages.withIndex().associate { it.value to it.index }
        val states = pages.map { facts[it]?.state ?: ReaderPageFactState.UNKNOWN }
        val singles = forcedSinglePages.mapNotNullTo(mutableSetOf(), indexById::get)
        // The matcher supplies strength-resolved candidates. Defensively normalize ties by source order.
        val matches = if (automaticMatching) {
            matchedPairs.mapNotNull { (a, b) ->
                val first = indexById[a] ?: return@mapNotNull null
                val second = indexById[b] ?: return@mapNotNull null
                PagePair(first, second).normalized()
            }.filter {
                it.first > 0 && it.second == it.first + 1 &&
                    it.first !in singles && it.second !in singles &&
                    states[it.first] == ReaderPageFactState.PORTRAIT &&
                    states[it.second] == ReaderPageFactState.PORTRAIT
            }.sortedBy { it.first }.fold(mutableListOf<PagePair>()) { selected, pair ->
                if ((selected.lastOrNull()?.second ?: -1) < pair.first) selected += pair
                selected
            }.associate { it.first to it.second }
        } else {
            emptyMap()
        }

        val partners = possiblePartners(states, singles, matches)
        val groups = mutableListOf<Pair<List<Int>, Boolean>>()
        var index = 0
        while (index < pages.size) {
            val partner = partners[index].singleOrNull()
            val known = states[index] == ReaderPageFactState.PORTRAIT || states[index] == ReaderPageFactState.SPREAD
            val paired = known && partner == index + 1 && index + 1 < pages.size &&
                states[index + 1] == ReaderPageFactState.PORTRAIT && partners[index + 1] == setOf(index)
            if (paired) {
                groups += listOf(index, index + 1) to true
                index += 2
            } else {
                groups += listOf(index) to (known && partner == SINGLE)
                index++
            }
        }
        var openingCertain = true
        var segmentOpeningCertain = true
        return groups.mapIndexed { groupIndex, (group, certain) ->
            val unit = if (group.size == 2) {
                ReaderDualPageUnit(
                    group.mapIndexed { ordinal, page ->
                        ReaderDualPageSlot(
                            pages[page],
                            if ((ordinal == 0) == (direction == ReaderDirection.RTL)) {
                                ReaderPortraitSingleSlot.RIGHT
                            } else {
                                ReaderPortraitSingleSlot.LEFT
                            },
                        )
                    },
                    certain = true,
                )
            } else {
                val page = group.single()
                val reason = when {
                    pages.size == 1 -> ReaderDualPageSingleReason.ONLY_PAGE
                    states[page] == ReaderPageFactState.SPREAD -> ReaderDualPageSingleReason.SPREAD
                    !certain -> ReaderDualPageSingleReason.UNRESOLVED
                    page == 0 -> ReaderDualPageSingleReason.FIRST_PAGE
                    page == pages.lastIndex -> ReaderDualPageSingleReason.CHAPTER_TAIL
                    segmentOpeningCertain && page + 1 in matches -> ReaderDualPageSingleReason.MATCH_BOUNDARY
                    segmentOpeningCertain && page + 1 in singles -> ReaderDualPageSingleReason.MANUAL_BOUNDARY
                    openingCertain -> ReaderDualPageSingleReason.OPENING
                    groupIndex > 0 && groups[groupIndex - 1].first.size == 2 &&
                        page + 1 < pages.size && states[page + 1] == ReaderPageFactState.SPREAD ->
                        ReaderDualPageSingleReason.REMAINDER_BEFORE_SPREAD
                    else -> ReaderDualPageSingleReason.AMBIGUOUS
                }
                ReaderDualPageUnit(
                    listOf(ReaderDualPageSlot(pages[page], portraitSinglePageSlot(direction, reason))),
                    certain,
                    reason,
                )
            }
            if (!certain || group.size == 2) openingCertain = false
            segmentOpeningCertain = when {
                !certain -> false
                states[group.first()] == ReaderPageFactState.SPREAD || group.first() in singles ||
                    matches[group.first()] == group.last() -> true
                group.size == 2 -> false
                else -> segmentOpeningCertain
            }
            unit
        }
    }

    private const val SINGLE = -1

    /**
     * Each path has only two possible pairing phases: no pending portrait, or the previous page.
     * UNKNOWN branches into both legal classifications. Merging these phases bounds work to O(n),
     * while the union of each page's possible partners tells whether its group is invariant.
     * Temporary fallback singles are never fed back into this propagation as real boundaries.
     */
    private fun possiblePartners(
        states: List<ReaderPageFactState>,
        singles: Set<Int>,
        matches: Map<Int, Int>,
    ): List<Set<Int>> {
        val partners = List(states.size) { mutableSetOf<Int>() }
        var phases: Set<Int> = setOf(SINGLE)
        fun single(page: Int) {
            if (page != SINGLE) partners[page] += SINGLE
        }
        fun pair(first: Int, second: Int) {
            partners[first] += second
            partners[second] += first
        }
        var index = 0
        while (index < states.size) {
            if (index in matches) {
                phases.forEach(::single)
                pair(index, index + 1)
                phases = setOf(SINGLE)
                index += 2
                continue
            }
            val next = mutableSetOf<Int>()
            val state = states[index]
            val barrier = index == 0 || index in singles || state == ReaderPageFactState.SPREAD
            for (pending in phases) {
                if (barrier || state == ReaderPageFactState.UNKNOWN || state == ReaderPageFactState.FAILED) {
                    single(pending)
                    single(index)
                    next += SINGLE
                }
                if (!barrier && state != ReaderPageFactState.SPREAD) {
                    if (pending == SINGLE) {
                        next += index
                    } else {
                        pair(pending, index)
                        next += SINGLE
                    }
                }
            }
            phases = next
            index++
        }
        phases.forEach(::single)
        return partners
    }
}
