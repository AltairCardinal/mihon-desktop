package eu.kanade.tachiyomi.ui.reader.viewer.pager

import mihon.domain.reader.PageLayout
import mihon.domain.reader.ReaderPagePairing

/** Android compatibility facade over the shared authoritative pairing algorithm. */
object PagePairingAlgorithm {
    fun buildPairings(
        pageCount: Int,
        isSpread: (Int) -> Boolean?,
        offset: Int = 0,
    ): List<IntArray> = ReaderPagePairing.build(
        pageCount = pageCount,
        layoutAt = { index ->
            when (isSpread(index)) {
                true -> PageLayout.SPREAD
                false -> PageLayout.PORTRAIT
                null -> PageLayout.UNKNOWN
            }
        },
        offset = offset,
    )
}
