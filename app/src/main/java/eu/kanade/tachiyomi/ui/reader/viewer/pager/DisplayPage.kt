package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import mihon.domain.reader.ReaderPortraitSingleSlot

/**
 * A display unit in dual-page mode — represents what is shown for a single ViewPager position.
 *
 * [Single] — one page, in the chosen physical slot or across the full viewport.
 * [Double] — two portrait pages shown side-by-side:
 *   [rightPage] (read first) is on the right side of the screen,
 *   [leftPage]  (read second) is on the left side of the screen.
 */
sealed class DisplayPage {

    data class Single(
        val page: ReaderPage,
        val slot: ReaderPortraitSingleSlot = ReaderPortraitSingleSlot.FULL,
    ) : DisplayPage()

    data class Double(
        val rightPage: ReaderPage,
        val leftPage: ReaderPage,
    ) : DisplayPage()

    /** The page used for navigation, preloading, and restoring this display unit. */
    val firstPage: ReaderPage
        get() = when (this) {
            is Single -> page
            is Double -> rightPage
        }

    /** Logical source pages actually visible in this display unit. */
    val visiblePages: List<ReaderPage>
        get() = when (this) {
            is Single -> listOf(page)
            is Double -> listOf(rightPage, leftPage)
        }
}

internal fun DisplayPage.containsPage(page: ReaderPage?): Boolean = when (this) {
    is DisplayPage.Single -> this.page === page
    is DisplayPage.Double -> rightPage === page || leftPage === page
}
