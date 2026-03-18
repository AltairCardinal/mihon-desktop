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
 * When [spreadPages] is non-empty, any page whose image is wider than it is
 * tall is treated as a "double-page spread" and shown alone in its own slot.
 * Adjacent non-spread pages are still paired normally.
 */
class DualPageState(
    val totalPages: Int,
    val spreadPages: Set<Int> = emptySet(),
) {

    /**
     * Number of pager "slots" (i.e. how many swipes the user has to make).
     *
     * When there are no detected spreads the classic formula is used:
     *   1 slot for the cover + ceil((totalPages - 1) / 2) for the rest.
     *
     * When spreads are present the groups are built dynamically.
     */
    val groupCount: Int

    private val groups: List<List<Int>>

    init {
        if (totalPages <= 0) {
            groupCount = 0
            groups = emptyList()
        } else if (spreadPages.isEmpty()) {
            // Fast path: original formula, no dynamic allocation
            groupCount = 1 + totalPages / 2
            groups = emptyList() // sentinel: use formula in getGroup / groupIndexForPage
        } else {
            groups = buildGroups(totalPages, spreadPages)
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
         * Builds groups dynamically, respecting spread pages:
         *  - Page 0 (cover) always alone.
         *  - Spread pages always alone.
         *  - Two consecutive non-spread pages are paired.
         */
        private fun buildGroups(total: Int, spreads: Set<Int>): List<List<Int>> {
            val result = mutableListOf<List<Int>>()
            var i = 0
            // Cover always shown alone
            result.add(listOf(i))
            i++
            while (i < total) {
                when {
                    i in spreads -> {
                        // Wide (landscape) page: show alone
                        result.add(listOf(i))
                        i++
                    }
                    i + 1 < total && (i + 1) !in spreads -> {
                        // Two consecutive non-spread pages: pair them
                        result.add(listOf(i, i + 1))
                        i += 2
                    }
                    else -> {
                        // Last page, or next page is a spread → show alone
                        result.add(listOf(i))
                        i++
                    }
                }
            }
            return result
        }
    }
}
