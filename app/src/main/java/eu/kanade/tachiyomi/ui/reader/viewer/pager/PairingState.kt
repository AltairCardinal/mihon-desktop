package eu.kanade.tachiyomi.ui.reader.viewer.pager

import mihon.domain.reader.PagePairingOptions
import mihon.domain.reader.ReaderPairingState

/** Android compatibility facade that explicitly enables the fork-added dual-page enhancement. */
class PairingState(
    val pageCount: Int,
    val isR2L: Boolean,
) {
    private val shared = ReaderPairingState(
        pageCount = pageCount,
        isRtl = isR2L,
        options = PagePairingOptions(pairAdjacentPortraitPages = true),
    )

    val pairings: List<IntArray> get() = shared.pairings

    fun updateDimensions(pageIndex: Int, width: Int, height: Int) =
        shared.updateDimensions(pageIndex, width, height)

    fun adjustPairing() = shared.adjustPairing()

    fun findDisplayUnitIndexForPage(pageIndex: Int): Int =
        shared.findDisplayUnitIndexForPage(pageIndex)
}
