package eu.kanade.tachiyomi.ui.reader.viewer.pager

import mihon.domain.reader.ReaderPairingState

/** Android compatibility facade over the shared pairing-state manager. */
class PairingState(
    val pageCount: Int,
    val isR2L: Boolean,
) {
    private val shared = ReaderPairingState(pageCount = pageCount, isRtl = isR2L)

    val pairings: List<IntArray> get() = shared.pairings

    fun updateDimensions(pageIndex: Int, width: Int, height: Int) =
        shared.updateDimensions(pageIndex, width, height)

    fun adjustPairing() = shared.adjustPairing()

    fun findDisplayUnitIndexForPage(pageIndex: Int): Int =
        shared.findDisplayUnitIndexForPage(pageIndex)
}
