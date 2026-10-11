package mihon.domain.reader

/** A layout-only operation: change the boundary and its logical target together. */
data class ReaderPairingAdjustment(
    val forcedSinglePages: Set<Int>,
    val currentPage: Int,
    val openingMode: ReaderChapterOpeningMode = ReaderChapterOpeningMode.AUTO,
    val manualPairStarts: Set<Int> = emptySet(),
)

fun adjustReaderPairing(
    currentPage: Int,
    visiblePageIndices: List<Int>,
    forcedSinglePages: Set<Int>,
    pageCount: Int? = null,
    openingMode: ReaderChapterOpeningMode = ReaderChapterOpeningMode.AUTO,
    manualPairStarts: Set<Int> = emptySet(),
): ReaderPairingAdjustment {
    if (pageCount != null) {
        val pages = visiblePageIndices.distinct().sorted()
        val first =
            pages.firstOrNull()
                ?: return ReaderPairingAdjustment(forcedSinglePages, currentPage, openingMode, manualPairStarts)
        if (first == 0 &&
            pages.size == 1 && pageCount > 1
        ) {
            return ReaderPairingAdjustment(
                forcedSinglePages - 1,
                0,
                ReaderChapterOpeningMode.FIRST_PAIR,
                manualPairStarts - 1,
            )
        }
        if (first == 0 && pages.size == 2) {
            return ReaderPairingAdjustment(
                forcedSinglePages + 1,
                1,
                ReaderChapterOpeningMode.FIRST_SINGLE,
                manualPairStarts - 1,
            )
        }
        if (first == 1 && pages.size == 1 && first in forcedSinglePages) {
            return ReaderPairingAdjustment(
                forcedSinglePages - 1,
                1,
                ReaderChapterOpeningMode.FIRST_PAIR,
                manualPairStarts - 1,
            )
        }
        val restore = (first - 1).takeIf { it in forcedSinglePages }
        val pairStart = when {
            restore != null -> restore
            pages.size == 2 -> first + 1
            first in forcedSinglePages -> first
            first == pageCount - 1 -> first - 1
            else -> first
        }
        if (pairStart !in 1 until pageCount - 1) {
            if (pages.size == 2 &&
                restore == null
            ) {
                return ReaderPairingAdjustment(
                    forcedSinglePages + first,
                    pages.last(),
                    openingMode,
                    manualPairStarts.filterTo(mutableSetOf()) {
                        it !in
                            first - 1..first + 1
                    },
                )
            }
            return ReaderPairingAdjustment(forcedSinglePages, currentPage, openingMode, manualPairStarts)
        }
        val split = pages.size == 2 && restore == null
        val forced = (forcedSinglePages - pairStart - (pairStart + 1) - listOfNotNull(restore).toSet()).let {
            if (split) it + first.coerceAtLeast(1) else it
        }
        val pairs =
            manualPairStarts.filterTo(mutableSetOf()) {
                it !in pairStart - 1..pairStart + 1 && it !in forced &&
                    it + 1 !in forced
            } +
                pairStart
        return ReaderPairingAdjustment(forced, pairStart, openingMode, pairs)
    }
    val pages = visiblePageIndices.distinct()
    val forcedSingle = pages.singleOrNull()?.takeIf(forcedSinglePages::contains)
    if (forcedSingle != null) return ReaderPairingAdjustment(forcedSinglePages - forcedSingle, forcedSingle)
    if (pages.size != 2) return ReaderPairingAdjustment(forcedSinglePages, currentPage)
    val first = pages.min()
    val preceding = (first - 1).takeIf(forcedSinglePages::contains)
    return if (preceding != null) {
        ReaderPairingAdjustment(forcedSinglePages - preceding, preceding)
    } else {
        // The evidenced opening pair splits at page one; page zero is not a persistable boundary.
        ReaderPairingAdjustment(forcedSinglePages + first.coerceAtLeast(1), pages.max())
    }
}
