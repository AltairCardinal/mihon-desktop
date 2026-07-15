package mihon.desktop.reader

import mihon.domain.reader.PageLayout
import mihon.domain.reader.PagePair
import mihon.domain.reader.PagePairingOptions
import mihon.domain.reader.ReaderPairingState

enum class SinglePageSide { LEADING, TRAILING, CENTER }

internal fun singlePageBoxOnRight(side: SinglePageSide, isRtl: Boolean): Boolean = when (side) {
    SinglePageSide.TRAILING -> !isRtl
    SinglePageSide.LEADING -> isRtl
    SinglePageSide.CENTER -> true
}

/**
 * Desktop product adapter over the shared Mihon pairing state.
 * Cover placement, edge-matched pairs, and landscape parity are explicit Desktop enhancements.
 */
class DualPageState(
    val totalPages: Int,
    val spreadPages: Set<Int> = emptySet(),
    val forcedSinglePages: Set<Int> = emptySet(),
    val matchedPairs: Set<Pair<Int, Int>> = emptySet(),
) {
    private val shared = ReaderPairingState(
        pageCount = totalPages,
        isRtl = false,
        options = PagePairingOptions(
            forceFirstPageSingle = true,
            forcedSinglePages = forcedSinglePages,
            matchedPairs = matchedPairs.mapTo(mutableSetOf()) { PagePair(it.first, it.second) },
            preserveParityAfterSpread = true,
        ),
        initialLayouts = spreadPages.associateWith { PageLayout.SPREAD },
        defaultLayout = PageLayout.PORTRAIT,
    )
    private val groups: List<IntArray> = shared.pairings

    val groupCount: Int get() = groups.size

    fun getGroup(groupIndex: Int): List<Int> {
        require(groupIndex in groups.indices) { "groupIndex $groupIndex out of range [0, $groupCount)" }
        return groups[groupIndex].toList()
    }

    fun groupIndexForPage(pageIndex: Int): Int = shared.findDisplayUnitIndexForPage(pageIndex).coerceAtLeast(0)

    fun singlePageSide(groupIndex: Int): SinglePageSide {
        val group = getGroup(groupIndex)
        if (group.size != 1) return SinglePageSide.CENTER
        if (group.single() in spreadPages) return SinglePageSide.CENTER
        return if (groupIndex < groupCount - 1) SinglePageSide.TRAILING else SinglePageSide.LEADING
    }

    fun firstPageInGroup(groupIndex: Int): Int = groups[groupIndex].first()
}
