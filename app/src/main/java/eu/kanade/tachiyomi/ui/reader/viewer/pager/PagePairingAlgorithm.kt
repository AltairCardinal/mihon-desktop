package eu.kanade.tachiyomi.ui.reader.viewer.pager

/**
 * Pure pairing algorithm for dual-page display mode.
 *
 * Groups page indices into display units of 1 or 2 pages:
 * - Each IntArray of size 1 → single page displayed alone
 * - Each IntArray of size 2 → [rightPageIndex, leftPageIndex] displayed side-by-side (R2L order)
 *
 * Pairing rules:
 * - Spread page (width >= height): displayed alone
 * - Portrait page followed by portrait page: paired together
 * - Portrait page followed by spread or unknown: displayed alone
 * - Unknown page: displayed alone (re-evaluated when dimensions become available)
 */
object PagePairingAlgorithm {

    /**
     * @param pageCount total number of pages in the chapter
     * @param isSpread returns true if page at [index] is a spread (width >= height),
     *                 false if portrait (height > width), null if dimensions unknown
     * @param offset number of leading pages to force as singles before pairing begins;
     *               automatically wrapped with modulo [pageCount] to stay in bounds
     */
    fun buildPairings(
        pageCount: Int,
        isSpread: (Int) -> Boolean?,
        offset: Int = 0,
    ): List<IntArray> {
        if (pageCount == 0) return emptyList()

        val effectiveOffset = if (pageCount > 0) offset % pageCount else 0
        val result = mutableListOf<IntArray>()
        var i = 0

        // Leading single pages forced by offset
        while (i < effectiveOffset) {
            result.add(intArrayOf(i))
            i++
        }

        // Main pairing loop
        while (i < pageCount) {
            when (isSpread(i)) {
                null -> {
                    // Unknown dimensions: show alone, adapter will rebuild when known
                    result.add(intArrayOf(i))
                    i++
                }
                true -> {
                    // Spread: always shown alone
                    result.add(intArrayOf(i))
                    i++
                }
                false -> {
                    // Portrait: try to pair with next
                    val next = i + 1
                    if (next >= pageCount) {
                        // Last page: shown alone
                        result.add(intArrayOf(i))
                        i++
                    } else {
                        when (isSpread(next)) {
                            false -> {
                                // Both portrait: pair them (right=i, left=next for R2L)
                                result.add(intArrayOf(i, next))
                                i += 2
                            }
                            else -> {
                                // Next is spread or unknown: current shown alone
                                result.add(intArrayOf(i))
                                i++
                            }
                        }
                    }
                }
            }
        }

        return result
    }
}
