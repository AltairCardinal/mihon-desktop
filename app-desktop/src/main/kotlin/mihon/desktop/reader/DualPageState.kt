package mihon.desktop.reader

/**
 * Groups manga pages into display pairs for dual-page mode.
 *
 * Layout (mirrors Android's double-page spread logic):
 *   Group 0 : [page 0]           ← cover shown alone
 *   Group 1 : [page 1, page 2]   ← first spread
 *   Group 2 : [page 3, page 4]
 *   …
 *
 * If [totalPages] is even the last group has a single page.
 *
 * Three mechanisms control which pages are shown alone vs. paired:
 *
 * 1. **[spreadPages]** — pages whose image is wider than tall (landscape).
 *    Detected automatically by [ZoomablePageBox] after Coil decodes each image.
 *
 * 2. **[forcedSinglePages]** — pages the user has manually forced to display
 *    alone via the "Adjust Spread" button. This shifts all subsequent pairings.
 *
 * 3. **[matchedPairs]** — page pairs detected by edge-pixel matching as being
 *    two halves of the same physical spread scan. These pairs are given priority
 *    during grouping so they always appear together.
 *
 * Priority order in [buildGroups]:
 *   forcedSinglePages/spreadPages (show alone) > matchedPairs (force pair) > default sequential pairing
 *
 * ──────────────────────────────────────────────────────────
 * Android migration note
 * ──────────────────────────────────────────────────────────
 * This class is pure Kotlin with zero platform dependencies. Copy it as-is
 * to the Android shared/domain layer.
 */
class DualPageState(
    val totalPages: Int,
    val spreadPages: Set<Int> = emptySet(),
    val forcedSinglePages: Set<Int> = emptySet(),
    val matchedPairs: Set<Pair<Int, Int>> = emptySet(),
) {

    /**
     * Number of pager "slots" (i.e. how many swipes the user has to make).
     *
     * When there are no detected spreads, forced singles, or matched pairs,
     * the classic formula is used:
     *   1 slot for the cover + ceil((totalPages - 1) / 2) for the rest.
     *
     * Otherwise, groups are built dynamically.
     */
    val groupCount: Int

    private val groups: List<List<Int>>

    init {
        val allSingles = spreadPages + forcedSinglePages
        if (totalPages <= 0) {
            groupCount = 0
            groups = emptyList()
        } else if (allSingles.isEmpty() && matchedPairs.isEmpty()) {
            // Fast path: original formula, no dynamic allocation
            groupCount = 1 + totalPages / 2
            groups = emptyList() // sentinel: use formula in getGroup / groupIndexForPage
        } else {
            val matchedMap = matchedPairs
                .filter { (a, b) -> a >= 0 && b >= 0 && a < totalPages && b < totalPages }
                .associate { (a, b) -> minOf(a, b) to maxOf(a, b) }
            groups = buildGroups(totalPages, allSingles, matchedMap)
            groupCount = groups.size
        }
    }

    /** Returns the logical page indices that belong to [groupIndex]. */
    fun getGroup(groupIndex: Int): List<Int> {
        require(groupIndex in 0 until groupCount) {
            "groupIndex $groupIndex out of range [0, $groupCount)"
        }
        return if (groups.isEmpty()) {
            // Formula path (no spreads)
            when (groupIndex) {
                0 -> listOf(0)
                else -> {
                    val first = 1 + (groupIndex - 1) * 2
                    val second = first + 1
                    if (second < totalPages) listOf(first, second) else listOf(first)
                }
            }
        } else {
            groups[groupIndex]
        }
    }

    /** Maps a logical page index to the group index that contains it. */
    fun groupIndexForPage(pageIndex: Int): Int {
        return if (groups.isEmpty()) {
            // Formula path
            if (pageIndex == 0) 0 else 1 + (pageIndex - 1) / 2
        } else {
            groups.indexOfFirst { pageIndex in it }.coerceAtLeast(0)
        }
    }

    /** Returns the first logical page index for a given group. */
    fun firstPageInGroup(groupIndex: Int): Int {
        return if (groups.isEmpty()) {
            if (groupIndex == 0) 0 else 1 + (groupIndex - 1) * 2
        } else {
            groups[groupIndex].first()
        }
    }

    companion object {
        /**
         * Builds groups dynamically with three-tier priority:
         *
         * 1. Page in [singles] (spreadPages ∪ forcedSinglePages) → show alone.
         * 2. Page is the first of a [matched] pair and the second is not single → force pair.
         * 3. Two consecutive non-single pages → pair them (default).
         *    Guard: if page i+1 is itself the start of a matched pair with i+2, do NOT
         *    consume i+1 here — show page i alone so that (i+1, i+2) can be matched in
         *    the next iteration via Priority 2.  This handles the common case where a
         *    full-spread page (shown alone) shifts the default pairing and the following
         *    page belongs to a detected matched pair.
         * 4. Otherwise → show alone (last page, or next page is single).
         *
         * Page 0 (cover) is always shown alone regardless of the above rules.
         */
        private fun buildGroups(
            total: Int,
            singles: Set<Int>,
            matched: Map<Int, Int>,
        ): List<List<Int>> {
            val result = mutableListOf<List<Int>>()
            var i = 0
            // Cover always shown alone
            result.add(listOf(i))
            i++
            while (i < total) {
                when {
                    // Priority 1: page forced/detected as single → show alone
                    i in singles -> {
                        result.add(listOf(i))
                        i++
                    }
                    // Priority 2: matched pair detected by edge-pixel scanning
                    matched[i] == i + 1 && i + 1 < total && (i + 1) !in singles -> {
                        result.add(listOf(i, i + 1))
                        i += 2
                    }
                    // Priority 3: default sequential pairing
                    // Guard: don't steal i+1 if it's already matched with i+2 — show i alone
                    // instead so that the (i+1, i+2) matched pair survives the next iteration.
                    i + 1 < total && (i + 1) !in singles &&
                        !(matched[i + 1] == i + 2 && i + 2 < total && (i + 2) !in singles) -> {
                        result.add(listOf(i, i + 1))
                        i += 2
                    }
                    // Fallback: last page, next page is single, or i+1 is reserved for i+2
                    else -> {
                        result.add(listOf(i))
                        i++
                    }
                }
            }
            return result
        }
    }
}
