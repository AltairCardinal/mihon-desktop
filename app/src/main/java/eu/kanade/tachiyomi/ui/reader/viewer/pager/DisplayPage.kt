package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

/**
 * A display unit in dual-page mode — represents what is shown for a single ViewPager position.
 *
 * [Single] — one page, displayed centred (same behaviour as single-page R2L mode).
 * [Double] — two portrait pages shown side-by-side:
 *   [rightPage] (read first) is on the right side of the screen,
 *   [leftPage]  (read second) is on the left side of the screen.
 */
sealed class DisplayPage {

    data class Single(val page: ReaderPage) : DisplayPage()

    data class Double(
        val rightPage: ReaderPage,
        val leftPage: ReaderPage,
    ) : DisplayPage()

    /** The page whose index is used to record reading progress. */
    val firstPage: ReaderPage
        get() = when (this) {
            is Single -> page
            is Double -> rightPage
        }
}
