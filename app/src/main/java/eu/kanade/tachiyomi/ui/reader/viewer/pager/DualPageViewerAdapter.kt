package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import android.view.ViewGroup
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.model.loadedEntryPage
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import mihon.domain.reader.PageLayout
import mihon.domain.reader.ReaderChapterBoundary
import mihon.domain.reader.readerChapterBoundary
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
class DualPageViewerAdapter(
    private val viewer: DualPageR2LPagerViewer,
    store: DualPagePairingStore = DualPagePairingStore(),
) : ViewPagerAdapter() {

    /** All items in ViewPager order (DisplayPage + ChapterTransition). */
    var items: MutableList<Any> = mutableListOf()
        private set

    var nextTransition: ChapterTransition.Next? = null
        private set

    var currentChapter: ReaderChapter? = null

    private var readerThemedContext = viewer.activity.createReaderThemeContext()

    private val chapterPairings = store.chapters
    private var chapters: ViewerChapters? = null

    /** Keep decode facts and manual pairing while the same chapter page identities remain loaded. */
    fun setChapters(chapters: ViewerChapters, @Suppress("UNUSED_PARAMETER") forceTransition: Boolean) {
        this.chapters = chapters
        currentChapter = chapters.currChapter
        val window = listOfNotNull(chapters.prevChapter, chapters.currChapter, chapters.nextChapter)
        chapterPairings.keys.retainAll(window.toSet())
        for (chapter in window) {
            val pages = chapter.pages
            if (pages == null) {
                chapterPairings.remove(chapter)
                continue
            }
            if (chapterPairings[chapter]?.pages != pages) {
                chapterPairings[chapter] = DualPagePairingStore.ChapterPairing(
                    pages,
                    PairingState(
                        pages.size,
                        isR2L = false,
                        initialLayout = PageLayout.PORTRAIT,
                        forceFirstPageSingle = true,
                    ),
                )
            }
        }
        rebuildItems()
    }

    /** The chapter identity is part of every decode fact; page ordinals alone are not unique. */
    fun updatePageDimensions(page: ReaderPage, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val pairing = chapterPairings[page.chapter] ?: return
        if (pairing.pages.getOrNull(page.index) !== page) return
        pairing.state.updateDimensions(page.index, width, height)
        rebuildItems()
    }

    fun adjustPairing(currentFirstPageIndex: Int) {
        val chapter = currentChapter ?: return
        val target = chapterPairings[chapter]?.state?.adjustPairing(currentFirstPageIndex) ?: return
        rebuildItems(chapter.pages?.getOrNull(target))
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

    /** Assemble in story order and reverse the entire window once, including its boundaries. */
    private fun rebuildItems(targetPage: ReaderPage? = null) {
        val chapters = chapters ?: return
        val selected = items.getOrNull(viewer.pager.currentItem)
        val anchor = targetPage ?: when (selected) {
            is DisplayPage -> selected.firstPage
            is ChapterTransition -> selected.loadedEntryPage()
            else -> null
        }
        val logical = buildList<Any> {
            chapters.prevChapter?.let { chapter ->
                chapterPairings[chapter]?.let { addAll(buildDisplayPages(it.pages, it.state)) }
            }
            if (readerChapterBoundary(
                    chapters.prevChapter != null,
                    chapters.prevChapter?.state is ReaderChapter.State.Loaded,
                ) != ReaderChapterBoundary.NONE
            ) {
                add(ChapterTransition.Prev(chapters.currChapter, chapters.prevChapter))
            }
            chapterPairings[chapters.currChapter]?.let { addAll(buildDisplayPages(it.pages, it.state)) }
            nextTransition = ChapterTransition.Next(chapters.currChapter, chapters.nextChapter)
            if (readerChapterBoundary(
                    chapters.nextChapter != null,
                    chapters.nextChapter?.state is ReaderChapter.State.Loaded,
                ) != ReaderChapterBoundary.NONE
            ) {
                add(requireNotNull(nextTransition))
            }
            chapters.nextChapter?.let { chapter ->
                chapterPairings[chapter]?.let { addAll(buildDisplayPages(it.pages, it.state)) }
            }
        }
        val replacement = logical.asReversed().toMutableList()
        if (items == replacement) return
        val layoutOnly = selected is DisplayPage && selected.firstPage.chapter === currentChapter
        viewer.replacePairing(anchor, layoutOnly) {
            items = replacement
            notifyDataSetChanged()
        }
    }

    private fun buildDisplayPages(pages: List<ReaderPage>, state: PairingState): List<DisplayPage> {
        return state.pairings.map { unit ->
            if (unit.size == 1) {
                DisplayPage.Single(pages[unit[0]], coverSlot = unit[0] == 0 && state.isPortrait(0))
            } else {
                DisplayPage.Double(
                    rightPage = pages[unit[0]],
                    leftPage = pages[unit[1]],
                )
            }
        }
    }
}
