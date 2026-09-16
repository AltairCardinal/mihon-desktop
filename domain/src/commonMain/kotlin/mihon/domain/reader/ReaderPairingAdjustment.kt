package mihon.domain.reader

/** A layout-only operation: change the boundary and its logical target together. */
data class ReaderPairingAdjustment(val forcedSinglePages: Set<Int>, val currentPage: Int)

fun adjustReaderPairing(
    currentPage: Int,
    visiblePageIndices: List<Int>,
    forcedSinglePages: Set<Int>,
): ReaderPairingAdjustment {
    val pages = visiblePageIndices.distinct()
    val forcedSingle = pages.singleOrNull()?.takeIf(forcedSinglePages::contains)
    if (forcedSingle != null) return ReaderPairingAdjustment(forcedSinglePages - forcedSingle, forcedSingle)
    if (pages.size != 2) return ReaderPairingAdjustment(forcedSinglePages, currentPage)
    val first = pages.min()
    val preceding = (first - 1).takeIf(forcedSinglePages::contains)
    return if (preceding != null) {
        ReaderPairingAdjustment(forcedSinglePages - preceding, preceding)
    } else {
        ReaderPairingAdjustment(forcedSinglePages + first, pages.max())
    }
}
