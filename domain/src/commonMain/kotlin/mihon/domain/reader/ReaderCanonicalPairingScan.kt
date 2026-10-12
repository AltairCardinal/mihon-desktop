package mihon.domain.reader

import mihon.domain.reader.session.ReaderPageId

internal data class ReaderPairingScanCursor(val index: Int, val opening: Boolean, val reservedStart: Int = -1)
internal data class ReaderPairingScanStep(val unit: ReaderDualPageUnit?, val next: ReaderPairingScanCursor)

/** The same step is consumed by the complete oracle and the finite-entry proof. Ordinals never shift. */
internal class ReaderCanonicalPairingScan(
    private val pages: List<ReaderPageId>,
    private val dimension: (Int) -> ReaderPageFact?,
    private val sideHint: (Int) -> ReaderPageSideHint,
    private val direction: ReaderDirection,
    private val forced: Set<ReaderPageId>,
    manualPairStarts: Set<Int>,
    private val openingMode: ReaderChapterOpeningMode,
    private val automatic: Boolean,
    private val distance: (ReaderPageId, ReaderPageId) -> Double?,
) {
    private val leading = if (direction == ReaderDirection.RTL) ReaderPageSideHint.RIGHT else ReaderPageSideHint.LEFT
    private val trailing = if (direction == ReaderDirection.RTL) ReaderPageSideHint.LEFT else ReaderPageSideHint.RIGHT
    private val starts =
        manualPairStarts + setOfNotNull(0.takeIf { openingMode == ReaderChapterOpeningMode.FIRST_PAIR })
    private val manualCache = mutableMapOf<Int, Boolean>()
    private fun wide(i: Int) = i in pages.indices && dimension(i)?.state == ReaderPageFactState.SPREAD
    private fun known(i: Int) =
        i in pages.indices && dimension(i)?.state in setOf(ReaderPageFactState.PORTRAIT, ReaderPageFactState.SPREAD)
    private fun side(i: Int) = if (automatic && i in pages.indices) sideHint(i) else ReaderPageSideHint.UNKNOWN

    /** Resolve only the relevant overlapping manual cluster; its earlier validity is a real dependency. */
    private fun manual(i: Int): Boolean {
        if (i !in starts || i !in 0 until pages.lastIndex) return false
        manualCache[i]?.let { return it }
        var ordinal = i
        val pending = mutableListOf<Int>()
        var previousAccepted = false
        while (ordinal >= 0 && ordinal in starts) {
            manualCache[ordinal]?.let {
                previousAccepted = it
                break
            }
            val valid =
                pages[ordinal] !in forced && pages[ordinal + 1] !in forced && !wide(ordinal) && !wide(ordinal + 1)
            if (!valid) {
                manualCache[ordinal] = false
                break
            }
            pending += ordinal
            ordinal--
        }
        for (candidate in pending.asReversed()) {
            previousAccepted = !previousAccepted
            manualCache[candidate] = previousAccepted
        }
        return manualCache.getValue(i)
    }

    fun allowed(i: Int): Boolean {
        if (i !in 0 until pages.lastIndex || pages[i] in forced || pages[i + 1] in forced || wide(i) ||
            wide(i + 1)
        ) {
            return false
        }
        if (manual(i)) return true
        if (manual(i - 1) || manual(i + 1)) return false
        if (i == 0 &&
            (
                openingMode != ReaderChapterOpeningMode.AUTO || !known(0) || !known(1) || side(1) != trailing ||
                    side(0) == trailing
                )
        ) {
            return false
        }
        return side(i) != trailing && side(i + 1) != leading
    }

    private fun strength(i: Int): Int = when {
        !allowed(i) -> -1
        manual(i) -> 3
        side(i) == leading || side(i + 1) == trailing -> 2
        else -> 0
    }

    private fun laterWins(i: Int): Boolean {
        val near = strength(i)
        val later = strength(i + 1)
        if (later != near) return later > near
        if (!automatic || later < 0 || i + 2 > pages.lastIndex) return false
        val nearDistance = distance(pages[i], pages[i + 1])
        val laterDistance = distance(pages[i + 1], pages[i + 2])
        return laterDistance != null && (nearDistance == null || laterDistance < nearDistance)
    }

    fun step(
        cursor: ReaderPairingScanCursor,
        includeSingleUnit: Boolean = true,
        includePairUnit: Boolean = true,
    ): ReaderPairingScanStep {
        val i = cursor.index
        require(i in pages.indices)
        val canPair = allowed(i)
        if (canPair && (i == cursor.reservedStart || !laterWins(i))) {
            val next = cursor.copy(index = i + 2, opening = false)
            if (!includePairUnit) return ReaderPairingScanStep(null, next)
            val first = if (direction ==
                ReaderDirection.RTL
            ) {
                ReaderPortraitSingleSlot.RIGHT
            } else {
                ReaderPortraitSingleSlot.LEFT
            }
            val second = if (direction ==
                ReaderDirection.RTL
            ) {
                ReaderPortraitSingleSlot.LEFT
            } else {
                ReaderPortraitSingleSlot.RIGHT
            }
            val unit =
                ReaderDualPageUnit(
                    listOf(ReaderDualPageSlot(pages[i], first), ReaderDualPageSlot(pages[i + 1], second)),
                    known(i) && known(i + 1),
                )
            return ReaderPairingScanStep(unit, next)
        }
        // A permitted non-cover pair which lost above can only have lost to the later pair.
        // Keep that transition when the discarded prefix unit's physical slot is not requested.
        val reserved = if (i > 0 && canPair) i + 1 else cursor.reservedStart
        val next = cursor.copy(index = i + 1, reservedStart = reserved)
        if (!includeSingleUnit) return ReaderPairingScanStep(null, next)
        val reason = when {
            pages.size == 1 -> ReaderDualPageSingleReason.ONLY_PAGE
            wide(i) -> ReaderDualPageSingleReason.SPREAD
            i == 0 -> ReaderDualPageSingleReason.FIRST_PAGE
            pages[i] in forced -> ReaderDualPageSingleReason.MANUAL_BOUNDARY
            i == pages.lastIndex -> ReaderDualPageSingleReason.CHAPTER_TAIL
            cursor.opening && wide(i + 1) -> ReaderDualPageSingleReason.OPENING
            wide(i + 1) && wide(i - 1) -> ReaderDualPageSingleReason.AMBIGUOUS
            wide(i + 1) -> ReaderDualPageSingleReason.REMAINDER_BEFORE_SPREAD
            laterWins(i) -> ReaderDualPageSingleReason.MATCH_BOUNDARY
            else -> ReaderDualPageSingleReason.UNRESOLVED
        }
        val position = when {
            reason in setOf(
                ReaderDualPageSingleReason.SPREAD,
                ReaderDualPageSingleReason.ONLY_PAGE,
            ) -> ReaderPortraitSingleSlot.FULL
            side(i) == ReaderPageSideHint.LEFT -> ReaderPortraitSingleSlot.LEFT
            side(i) == ReaderPageSideHint.RIGHT -> ReaderPortraitSingleSlot.RIGHT
            reason == ReaderDualPageSingleReason.MANUAL_BOUNDARY -> if (direction ==
                ReaderDirection.RTL
            ) {
                ReaderPortraitSingleSlot.RIGHT
            } else {
                ReaderPortraitSingleSlot.LEFT
            }
            else -> portraitSinglePageSlot(direction, reason)
        }
        val unit = ReaderDualPageUnit(listOf(ReaderDualPageSlot(pages[i], position)), known(i), reason)
        return ReaderPairingScanStep(unit, next)
    }
}
