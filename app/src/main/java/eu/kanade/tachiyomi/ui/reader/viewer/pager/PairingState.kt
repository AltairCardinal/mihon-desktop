package eu.kanade.tachiyomi.ui.reader.viewer.pager

import mihon.domain.reader.PageLayout
import mihon.domain.reader.PagePairingOptions
import mihon.domain.reader.ReaderPairingState

/** Android compatibility facade that explicitly enables the fork-added dual-page enhancement. */
class PairingState(
    val pageCount: Int,
    val isR2L: Boolean,
    initialLayout: PageLayout = PageLayout.UNKNOWN,
    forceFirstPageSingle: Boolean = false,
) {
    private var options = PagePairingOptions(
        pairAdjacentPortraitPages = true,
        forceFirstPageSingle = forceFirstPageSingle,
    )
    private val shared = ReaderPairingState(
        pageCount = pageCount,
        isRtl = isR2L,
        defaultLayout = initialLayout,
        options = options,
    )

    val pairings: List<IntArray> get() = shared.pairings

    fun updateDimensions(pageIndex: Int, width: Int, height: Int) =
        shared.updateDimensions(pageIndex, width, height)

    fun adjustPairing() = shared.adjustPairing()

    fun adjustPairing(currentPage: Int): Int {
        val index = shared.findDisplayUnitIndexForPage(currentPage)
        val visible = shared.pairings.getOrNull(index)?.toList().orEmpty()
        val adjustment = mihon.domain.reader.adjustReaderPairing(currentPage, visible, options.forcedSinglePages)
        options = options.copy(forcedSinglePages = adjustment.forcedSinglePages)
        shared.updateOptions(options)
        return adjustment.currentPage
    }

    fun isPortrait(pageIndex: Int): Boolean = shared.pageLayout(pageIndex) == PageLayout.PORTRAIT

    fun findDisplayUnitIndexForPage(pageIndex: Int): Int =
        shared.findDisplayUnitIndexForPage(pageIndex)
}
