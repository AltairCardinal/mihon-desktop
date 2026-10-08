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
        // The evidenced opening pair splits at page one; page zero is not a persistable boundary.
        ReaderPairingAdjustment(forcedSinglePages + first.coerceAtLeast(1), pages.max())
    }
}
