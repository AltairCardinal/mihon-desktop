package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import mihon.domain.reader.ReaderDualPageFrameId
import mihon.domain.reader.ReaderDualPageSlot
import mihon.domain.reader.ReaderDualPageUnitId
import mihon.domain.reader.ReaderPortraitSingleSlot
import mihon.domain.reader.SharedDualPageSession

/**
 * A display unit in dual-page mode — represents what is shown for a single ViewPager position.
 *
 * [Single] — one page, in the chosen physical slot or across the full viewport.
 * [Double] — two portrait pages shown side-by-side:
 *   [rightPage] is on the right side and [leftPage] on the left.
 *   Logical navigation remains in source order independently of physical direction.
 */
sealed class DisplayPage {
    abstract val frameId: ReaderDualPageFrameId?

    data class Single(
        val page: ReaderPage,
        val slot: ReaderPortraitSingleSlot = ReaderPortraitSingleSlot.FULL,
        override val frameId: ReaderDualPageFrameId? = null,
    ) : DisplayPage()

    data class Double(
        val rightPage: ReaderPage,
        val leftPage: ReaderPage,
        override val frameId: ReaderDualPageFrameId? = null,
    ) : DisplayPage()

    /** The page used for navigation, preloading, and restoring this display unit. */
    val firstPage: ReaderPage
        get() = when (this) {
            is Single -> page
            is Double -> if (rightPage.index < leftPage.index) rightPage else leftPage
        }

    /** Logical source pages actually visible in this display unit. */
    val visiblePages: List<ReaderPage>
        get() = when (this) {
            is Single -> listOf(page)
            is Double -> listOf(rightPage, leftPage).sortedBy { it.index }
        }
}

internal fun DisplayPage.containsPage(page: ReaderPage?): Boolean = when (this) {
    is DisplayPage.Single -> this.page === page
    is DisplayPage.Double -> rightPage === page || leftPage === page
}

/** Physical adapter identity only; all grouping and navigation remain with the shared owner. */
internal fun DisplayPage.sharedUnitId(owner: SharedDualPageSession): ReaderDualPageUnitId? {
    val slots = visiblePages.map { page ->
        val id = owner.identity.pageIds.getOrNull(page.index) ?: return null
        val position = when (this) {
            is DisplayPage.Single -> slot
            is DisplayPage.Double -> if (page ===
                rightPage
            ) {
                ReaderPortraitSingleSlot.RIGHT
            } else {
                ReaderPortraitSingleSlot.LEFT
            }
        }
        ReaderDualPageSlot(id, position)
    }
    return ReaderDualPageUnitId(slots)
}
