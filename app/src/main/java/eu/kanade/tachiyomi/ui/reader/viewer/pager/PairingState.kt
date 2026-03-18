package eu.kanade.tachiyomi.ui.reader.viewer.pager

/**
 * Pure, Android-free pairing-state manager used by [DualPageViewerAdapter].
 *
 * Holds cached image dimensions for each page index and rebuilds display units
 * whenever dimensions change or the pairing offset is adjusted.
 *
 * @param pageCount total pages in the current chapter
 * @param isR2L     whether the viewer uses right-to-left reading order
 */
class PairingState(
    val pageCount: Int,
    val isR2L: Boolean,
) {
    /** width × height for each page index that has been decoded */
    private val dimensionMap = mutableMapOf<Int, Pair<Int, Int>>()

    /** Current offset used by [PagePairingAlgorithm.buildPairings] */
    private var offset: Int = 0

    /**
     * Logical (non-reversed) display units built from [PagePairingAlgorithm].
     * For R2L mode the list is reversed before being exposed via [pairings].
     */
    private var logicalPairings: List<IntArray> = buildLogical()

    /**
     * Current display units in ViewPager order.
     * For R2L viewers the list is reversed so the last chapter page maps to position 0.
     */
    val pairings: List<IntArray>
        get() = if (isR2L) logicalPairings.asReversed() else logicalPairings

    // ── Mutation ────────────────────────────────────────────────────────────

    /** Record or update the dimensions for [pageIndex] and rebuild pairings. */
    fun updateDimensions(pageIndex: Int, width: Int, height: Int) {
        dimensionMap[pageIndex] = width to height
        rebuild()
    }

    /**
     * Shift the pairing offset by 1 (wraps around with modulo [pageCount]).
     * This allows the user to realign page pairs when the default pairing is off.
     */
    fun adjustPairing() {
        if (pageCount == 0) return
        offset = (offset + 1) % pageCount
        rebuild()
    }

    // ── Lookup ──────────────────────────────────────────────────────────────

    /**
     * Returns the ViewPager position (in [pairings] order) of the display unit
     * that contains [pageIndex].  Returns -1 if not found.
     */
    fun findDisplayUnitIndexForPage(pageIndex: Int): Int {
        return pairings.indexOfFirst { unit -> pageIndex in unit }
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun rebuild() {
        logicalPairings = buildLogical()
    }

    private fun buildLogical(): List<IntArray> =
        PagePairingAlgorithm.buildPairings(
            pageCount = pageCount,
            isSpread = { index ->
                dimensionMap[index]?.let { (w, h) -> w >= h }
            },
            offset = offset,
        )
}

/** Returns true if this index is contained in the IntArray. */
private operator fun IntArray.contains(value: Int): Boolean {
    for (element in this) if (element == value) return true
    return false
}
