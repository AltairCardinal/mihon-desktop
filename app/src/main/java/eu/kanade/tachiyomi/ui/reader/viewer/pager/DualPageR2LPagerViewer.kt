package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.PointF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.viewpager.widget.ViewPager
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import mihon.domain.reader.ReaderAdjacentChapterPolicy
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import kotlin.math.min

/**
 * Dual-page right-to-left viewer.
 *
 * Displays pages in pairs side-by-side (right page first, left page second) where both pages
 * are portrait orientation.  Spread pages (landscape) are shown alone like in normal R2L mode.
 *
 * Architecture mirrors [PagerViewer] but uses its own [DualPageViewerAdapter] instead of
 * [PagerViewerAdapter] to manage page pairings.
 */
@Suppress("LeakingThis")
class DualPageR2LPagerViewer(
    override val activity: ReaderActivity,
    pairingStore: DualPagePairingStore = DualPagePairingStore(),
) : Viewer, ViewerWithPager {

    override val downloadManager: DownloadManager by injectLazy()

    private val scope = MainScope()

    override val pager = Pager(activity)

    val config = PagerConfig(this, scope)

    val adapter = DualPageViewerAdapter(this, pairingStore)

    private var currentPage: Any? = null
    private var layoutOnlyDisplayPage: DisplayPage? = null
    private var reportedDisplayPage: DisplayPage? = null
    private var replacingPairing = false
    private var destroyed = false

    private var awaitingIdleViewerChapters: ViewerChapters? = null

    private var isIdle = true
        set(value) {
            field = value
            if (value) {
                awaitingIdleViewerChapters?.let { viewerChapters ->
                    setChaptersInternal(viewerChapters)
                    awaitingIdleViewerChapters = null
                    val currentChapterAlreadySelected =
                        (currentPage as? DisplayPage)?.firstPage?.chapter == viewerChapters.currChapter
                    if (viewerChapters.currChapter.pages?.size == 1 && !currentChapterAlreadySelected) {
                        val effect = ReaderAdjacentChapterPolicy.effectForPageAnchor(
                            anchorPageIndex = 0,
                            pageCount = 1,
                        )
                        val target = adapter.nextTransition?.to
                        if (effect != null && target != null) {
                            activity.requestPreloadChapter(target, effect)
                        }
                    }
                }
                reportSettledDualViewport()
            }
        }

    private val pagerListener = object : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            if (replacingPairing || destroyed) return
            if (!activity.isScrollingThroughPages) {
                activity.hideMenu()
            }
            onPageChange(position)
        }

        override fun onPageScrollStateChanged(state: Int) {
            isIdle = state == ViewPager.SCROLL_STATE_IDLE
        }
    }

    init {
        pager.isVisible = false
        pager.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        pager.isFocusable = false
        pager.offscreenPageLimit = 1
        pager.id = R.id.reader_pager
        pager.adapter = adapter
        pager.addOnPageChangeListener(pagerListener)
        pager.tapListener = { event ->
            val viewPosition = IntArray(2)
            pager.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            pager.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / pager.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / pager.height,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> activity.toggleMenu()
                NavigationRegion.NEXT -> moveToNext()
                NavigationRegion.PREV -> moveToPrevious()
                NavigationRegion.RIGHT -> moveRight()
                NavigationRegion.LEFT -> moveLeft()
            }
        }
        pager.longTapListener = f@{
            if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                val item = adapter.items.getOrNull(pager.currentItem)
                if (item is DisplayPage) {
                    activity.onPageLongTap(item.firstPage)
                    return@f true
                }
            }
            false
        }

        config.imagePropertyChangedListener = { refreshAdapter() }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.setViewerNavigation(config.navigator, showOnStart)
        }
    }

    override fun destroy() {
        destroyed = true
        pager.removeOnPageChangeListener(pagerListener)
        scope.cancel()
    }

    override fun getView(): View = pager

    override fun setChapters(chapters: ViewerChapters) {
        if (isIdle) {
            setChaptersInternal(chapters)
        } else {
            awaitingIdleViewerChapters = chapters
        }
    }

    private fun setChaptersInternal(chapters: ViewerChapters) {
        pager.removeOnPageChangeListener(pagerListener)

        val forceTransition = config.alwaysShowChapterTransition ||
            adapter.items.getOrNull(pager.currentItem) is ChapterTransition
        adapter.setChapters(chapters, forceTransition)

        if (pager.isGone) {
            logcat { "Pager first layout" }
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
            pager.isVisible = true
        }

        pager.addOnPageChangeListener(pagerListener)
        onPageChange(pager.currentItem)
    }

    override fun moveToPage(page: ReaderPage) {
        // Find the ViewPager position of the display unit containing this page
        val position = adapter.items.indexOfFirst { item ->
            item is DisplayPage && item.containsPage(page)
        }
        if (position != -1) {
            val currentPosition = pager.currentItem
            pager.setCurrentItem(position, true)
            if (currentPosition == position) {
                onPageChange(position)
            }
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    // ── R2L navigation ───────────────────────────────────────────────────────

    /** The complete R2L window is reversed, so next is the lower ViewPager index. */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP
        val ctrlPressed = event.metaState.and(KeyEvent.META_CTRL_ON) > 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveDown() else moveUp()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveUp() else moveDown()
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (isUp) {
                if (ctrlPressed) moveToNext() else moveRight()
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (isUp) {
                if (ctrlPressed) moveToPrevious() else moveLeft()
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (isUp) moveDown()
            KeyEvent.KEYCODE_DPAD_UP -> if (isUp) moveUp()
            KeyEvent.KEYCODE_PAGE_DOWN -> if (isUp) moveDown()
            KeyEvent.KEYCODE_PAGE_UP -> if (isUp) moveUp()
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()
            else -> return false
        }
        return true
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_POINTER != 0) {
            when (event.action) {
                MotionEvent.ACTION_SCROLL -> {
                    if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0.0f) {
                        moveDown()
                    } else {
                        moveUp()
                    }
                    return true
                }
            }
        }
        return false
    }

    private fun moveToNext() = moveLeft()
    private fun moveToPrevious() = moveRight()

    private fun moveLeft() {
        if (pager.currentItem != 0) {
            pager.setCurrentItem(pager.currentItem - 1, config.usePageTransitions)
        }
    }

    private fun moveRight() {
        if (pager.currentItem != adapter.count - 1) {
            pager.setCurrentItem(pager.currentItem + 1, config.usePageTransitions)
        }
    }

    private fun moveUp() = moveToPrevious()
    private fun moveDown() = moveToNext()

    // ── Page change handling ─────────────────────────────────────────────────

    private fun onPageChange(position: Int, layoutOnly: Boolean = false) {
        val item = adapter.items.getOrNull(position)
        // A window rebuild can keep the same selected pair while an adjacent chapter loads.
        // Preserve that selection so its pending rendered viewport can still settle.
        if (layoutOnly && item is DisplayPage && item != currentPage) layoutOnlyDisplayPage = item
        if (item != null && currentPage != item) {
            currentPage = item
            reportedDisplayPage = null
            if (!layoutOnly) layoutOnlyDisplayPage = null
            when (item) {
                is DisplayPage -> onDisplayPageSelected(item, layoutOnly)
                is ChapterTransition -> onTransitionSelected(item)
            }
        } else if (item != null) {
            currentPage = item
        }
        reportSettledDualViewport()
    }

    internal fun onDisplayPageSelected(displayPage: DisplayPage, layoutOnly: Boolean = false) {
        val page = displayPage.firstPage
        val pages = page.chapter.pages ?: return
        logcat { "onDisplayPageSelected: ${page.number}/${pages.size}" }
        if (displayPage is DisplayPage.Double) {
            activity.onViewerPageAnchorSelected(this, page, layoutOnly)
        } else {
            activity.onViewerPageSelected(this, page, layoutOnly, displayPage.visiblePages)
        }
        if (layoutOnly) return

        // Preload next chapter when near the end
        val adjacentEffect = ReaderAdjacentChapterPolicy.effectForPageAnchor(page.index, pages.size)
        if (adjacentEffect != null && page.chapter == adapter.currentChapter) {
            adapter.nextTransition?.to?.let { target ->
                activity.requestPreloadChapter(target, adjacentEffect)
            }
        }
    }

    internal fun onHolderDisplayStateChanged(holder: DualPagerPageHolder) {
        if (holder.displayPage == currentPage) reportSettledDualViewport()
    }

    private fun reportSettledDualViewport() {
        if (destroyed || replacingPairing || !isIdle) return
        val displayPage = adapter.items.getOrNull(pager.currentItem) as? DisplayPage.Double ?: return
        if (displayPage != currentPage || displayPage == layoutOnlyDisplayPage) return
        val holder = pager.children.filterIsInstance<DualPagerPageHolder>()
            .firstOrNull { it.displayPage == displayPage } ?: return
        if (!holder.hasRenderedVisiblePages()) return
        if (reportedDisplayPage == displayPage) return
        reportedDisplayPage = displayPage
        activity.onViewerViewportSettled(this, displayPage.firstPage, displayPage.visiblePages)
    }

    internal fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            activity.requestPreloadChapter(
                toChapter,
                ReaderAdjacentChapterPolicy.transitionPageEffect(),
            )
        } else if (transition is ChapterTransition.Next) {
            activity.showMenu()
        }
    }

    // ── Adjust pairing ───────────────────────────────────────────────────────

    /**
     * Shifts the page-pairing offset by 1, effectively allowing the user to realign
     * which pages appear together on screen.
     */
    fun adjustPagePairing() {
        val currentFirstPageIndex = (currentPage as? DisplayPage)?.firstPage?.index ?: return
        adapter.adjustPairing(currentFirstPageIndex)
    }

    internal fun replacePairing(anchor: ReaderPage?, layoutOnly: Boolean, replace: () -> Unit) {
        replacingPairing = true
        try {
            replace()
            if (anchor != null) {
                val position = adapter.items.indexOfFirst { it is DisplayPage && it.containsPage(anchor) }
                if (position >= 0) pager.setCurrentItem(position, false)
            }
        } finally {
            replacingPairing = false
        }
        if (pager.isVisible && anchor != null) onPageChange(pager.currentItem, layoutOnly = layoutOnly)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun refreshAdapter() {
        val currentItem = pager.currentItem
        adapter.refresh()
        pager.adapter = adapter
        pager.setCurrentItem(currentItem, false)
    }

    private fun getPageHolder(page: ReaderPage): DualPagerPageHolder? =
        pager.children
            .filterIsInstance<DualPagerPageHolder>()
            .firstOrNull { it.displayPage.firstPage == page }
}
