package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import android.view.ViewGroup
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.calculateChapterGap
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import tachiyomi.core.common.util.system.logcat

/**
 * ViewPager adapter for [DualPageR2LPagerViewer].
 *
 * Items list contains:
 * - [DisplayPage]       → mapped to a [DualPagerPageHolder]
 * - [ChapterTransition] → mapped to a [PagerTransitionHolder] (reused from single-page viewer)
 *
 * Pairings are managed by [PairingState] and rebuilt whenever page dimensions
 * are decoded or the user presses "adjust pairing".
 */
class DualPageViewerAdapter(private val viewer: DualPageR2LPagerViewer) : ViewPagerAdapter() {

    /** All items in ViewPager order (DisplayPage + ChapterTransition). */
    var items: MutableList<Any> = mutableListOf()
        private set

    var nextTransition: ChapterTransition.Next? = null
        private set

    var currentChapter: ReaderChapter? = null

    private var readerThemedContext = viewer.activity.createReaderThemeContext()

    /** Chapter pages (non-reversed logical order) currently loaded. */
    private var currentPages: List<ReaderPage> = emptyList()

    /** Pairing state for the current chapter. */
    private var pairingState: PairingState = PairingState(pageCount = 0, isR2L = true)

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Updates this adapter with the given [chapters].
     * Handles prev/next chapter pages for seamless transitions.
     */
    fun setChapters(chapters: ViewerChapters, forceTransition: Boolean) {
        val prevHasMissingChapters = calculateChapterGap(chapters.currChapter, chapters.prevChapter) > 0
        val nextHasMissingChapters = calculateChapterGap(chapters.nextChapter, chapters.currChapter) > 0

        currentChapter = chapters.currChapter
        currentPages = chapters.currChapter.pages ?: emptyList()

        // Rebuild PairingState for the new chapter (resets offset to 0)
        pairingState = PairingState(pageCount = currentPages.size, isR2L = true)

        rebuildItems(chapters, forceTransition, prevHasMissingChapters, nextHasMissingChapters)
    }

    /**
     * Called when a page's image dimensions become known.
     * Rebuilds pairings and refreshes the adapter.
     */
    fun updatePageDimensions(pageIndex: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        pairingState.updateDimensions(pageIndex, width, height)
        rebuildCurrentChapterItems()
    }

    /**
     * Shifts the pairing offset by 1, allowing the user to realign double-page spreads.
     * Returns the ViewPager position that should be shown after the adjustment.
     */
    fun adjustPairing(currentFirstPageIndex: Int): Int {
        pairingState.adjustPairing()
        rebuildCurrentChapterItems()
        return pairingState.findDisplayUnitIndexForPage(currentFirstPageIndex)
            .coerceAtLeast(0)
    }

    fun refresh() {
        readerThemedContext = viewer.activity.createReaderThemeContext()
    }

    // ── ViewPagerAdapter implementation ─────────────────────────────────────

    override fun getCount(): Int = items.size

    override fun createView(container: ViewGroup, position: Int): View {
        return when (val item = items[position]) {
            is DisplayPage -> DualPagerPageHolder(readerThemedContext, viewer, item)
            is ChapterTransition -> PagerTransitionHolder(readerThemedContext, viewer, item)
            else -> throw NotImplementedError("Holder for ${item.javaClass} not implemented")
        }
    }

    override fun getItemPosition(view: Any): Int {
        if (view is ViewPagerAdapter.PositionableView) {
            val position = items.indexOf(view.item)
            if (position != -1) return position
            logcat { "Position for ${view.item} not found" }
        }
        return POSITION_NONE
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Rebuilds [items] from the current chapters, pairing state, and chapter transitions.
     * Chapter pages from prev/next chapters are added as raw [DisplayPage.Single] items
     * around the central chapter's paired display units.
     */
    private fun rebuildItems(
        chapters: ViewerChapters,
        forceTransition: Boolean,
        prevHasMissingChapters: Boolean,
        nextHasMissingChapters: Boolean,
    ) {
        val newItems = mutableListOf<Any>()

        // Previous chapter pages (shown as singles — no dual-page pairing across chapters)
        chapters.prevChapter?.pages?.forEach { newItems.add(DisplayPage.Single(it)) }

        if (prevHasMissingChapters || forceTransition ||
            chapters.prevChapter?.state !is ReaderChapter.State.Loaded
        ) {
            newItems.add(ChapterTransition.Prev(chapters.currChapter, chapters.prevChapter))
        }

        // Current chapter display units (paired by PairingState)
        newItems.addAll(buildDisplayPages(currentPages, pairingState))

        nextTransition = ChapterTransition.Next(chapters.currChapter, chapters.nextChapter)
            .also {
                if (nextHasMissingChapters || forceTransition ||
                    chapters.nextChapter?.state !is ReaderChapter.State.Loaded
                ) {
                    newItems.add(it)
                }
            }

        // Next chapter pages (singles)
        chapters.nextChapter?.pages?.forEach { newItems.add(DisplayPage.Single(it)) }

        items = newItems
        notifyDataSetChanged()
    }

    /**
     * Rebuilds only the current chapter's display units in [items] after a pairing change,
     * while preserving prev/next chapter items.
     */
    private fun rebuildCurrentChapterItems() {
        if (currentPages.isEmpty()) return

        val newUnits = buildDisplayPages(currentPages, pairingState)

        // Replace the slice of items that belongs to the current chapter
        val newItems = mutableListOf<Any>()
        for (item in items) {
            when {
                item is DisplayPage && item.firstPage.chapter.chapter.id == currentChapter?.chapter?.id -> {
                    // Will be replaced — skip old items
                }
                else -> newItems.add(item)
            }
        }

        // Find insertion point: after Prev transition (or start of list)
        val insertIndex = newItems.indexOfFirst {
            it is ChapterTransition.Next && it.from.chapter.id == currentChapter?.chapter?.id
        }.let { if (it == -1) newItems.size else it }

        newItems.addAll(insertIndex, newUnits)
        items = newItems
        notifyDataSetChanged()
    }

    private fun buildDisplayPages(pages: List<ReaderPage>, state: PairingState): List<DisplayPage> {
        return state.pairings.map { unit ->
            if (unit.size == 1) {
                DisplayPage.Single(pages[unit[0]])
            } else {
                DisplayPage.Double(
                    rightPage = pages[unit[0]],
                    leftPage = pages[unit[1]],
                )
            }
        }
    }
}
