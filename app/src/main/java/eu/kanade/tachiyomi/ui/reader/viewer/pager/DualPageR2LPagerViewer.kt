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
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import logcat.LogPriority
import mihon.domain.reader.ChapterPairingSnapshot
import mihon.domain.reader.ReaderAdjacentChapterPolicy
import mihon.domain.reader.ReaderDualPageSettlement
import mihon.domain.reader.ReaderDualPageSettlementReason
import mihon.domain.reader.ReaderDualPageViewportTicket
import mihon.domain.reader.ReaderPairingAdjustment
import mihon.domain.reader.SharedDualPageSession
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
    private val pairingStore: DualPagePairingStore = DualPagePairingStore(),
) : Viewer, ViewerWithPager {

    override val downloadManager: DownloadManager by injectLazy()

    private val scope = MainScope()

    override val pager = Pager(activity)

    val config = PagerConfig(this, scope)

    val adapter = DualPageViewerAdapter(this, pairingStore)

    private var currentPage: Any? = null
    private var replacingPairing = false
    private var awaitingReplacementIdle = false
    private var destroyed = false
    private var gestureInProgress = false
    internal val hasActiveGesture: Boolean get() = gestureInProgress
    private var viewportSize: Pair<Int, Int>? = null
    private fun geometrySettings() = listOf(
        config.imageScaleType,
        config.imageZoomType,
        config.imageCropBorders,
        config.landscapeZoom,
        config.dualPageSplit,
        config.dualPageInvert,
        config.dualPageRotateToFit,
        config.dualPageRotateToFitInvert,
    )
    private var presentationSettings = geometrySettings()
    private val presentedViewports = mutableMapOf<SharedDualPageSession, MutableSet<ReaderDualPageViewportTicket>>()

    private var awaitingIdleViewerChapters: ViewerChapters? = null

    private var isIdle = true
        set(value) {
            field = value
            if (value) {
                pager.children.filterIsInstance<DualPagerPageHolder>().forEach { it.onViewportMoved() }
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
        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
            if (destroyed) return
            pager.children.filterIsInstance<DualPagerPageHolder>().forEach { it.onViewportMoved() }
        }

        override fun onPageSelected(position: Int) {
            if (replacingPairing || destroyed) return
            if (gestureInProgress) {
                val previousPosition = adapter.positionOf(currentPage)
                val selected = adapter.items.getOrNull(position) as? DisplayPage
                if (selected != null && previousPosition >= 0 && previousPosition != position) {
                    adapter.selectGesturePage(selected, forward = position < previousPosition)
                }
            }
            if (!activity.isScrollingThroughPages) {
                activity.hideMenu()
            }
            onPageChange(position)
            logNavigationState("selected")
        }

        override fun onPageScrollStateChanged(state: Int) {
            if (destroyed) return
            if (state == ViewPager.SCROLL_STATE_IDLE && replacingPairing) {
                // ViewPager can settle its old position while notifyDataSetChanged replaces the slots.
                // Reconcile only after the replacement restores its stable logical anchor.
                awaitingReplacementIdle = true
                return
            }
            if (state == ViewPager.SCROLL_STATE_DRAGGING) gestureInProgress = true
            if (state == ViewPager.SCROLL_STATE_IDLE) {
                gestureInProgress = false
                // Reconcile the final native slot before deferred chapter/fact publication can move it.
                onPageChange(pager.currentItem)
                isIdle = true
                adapter.publishPendingPairing()
                logNavigationState("idle")
            } else {
                isIdle = false
            }
        }
    }

    private fun completeReplacementIdle() {
        if (replacingPairing || !awaitingReplacementIdle) return
        awaitingReplacementIdle = false
        pagerListener.onPageScrollStateChanged(ViewPager.SCROLL_STATE_IDLE)
    }

    init {
        pager.isVisible = false
        pager.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        pager.isFocusable = false
        pager.offscreenPageLimit = 1
        pager.id = R.id.reader_pager
        pager.adapter = adapter
        pager.addOnPageChangeListener(pagerListener)
        pager.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val size = (right - left) to (bottom - top)
            if (size.first > 0 && size.second > 0) {
                val previous = viewportSize
                viewportSize = size
                if (previous != null && previous != size) invalidateGeometry()
            }
        }
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
        config.automaticSpreadMatchingChangedListener = adapter::matchingSettingChanged

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.setViewerNavigation(config.navigator, showOnStart)
        }
    }

    override fun destroy() {
        destroyed = true
        presentedViewports.toMap().forEach { (owner, tickets) -> tickets.forEach(owner::releaseViewport) }
        presentedViewports.clear()
        pager.removeOnPageChangeListener(pagerListener)
        adapter.close()
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
            val target = adapter.session(chapters.currChapter)?.location?.pageId?.sourcePageIndex
                ?: chapters.currChapter.requestedPage
            positionPage(pages[min(target, pages.lastIndex)], notifyIfUnchanged = false)
            pager.isVisible = true
        }

        pager.addOnPageChangeListener(pagerListener)
        onPageChange(pager.currentItem)
    }

    override fun moveToPage(page: ReaderPage) {
        eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnostics.record("jump", page, activity.diagnosticSession)
        adapter.selectPage(page, publishLayout = true)
        positionPage(page)
    }

    private fun positionPage(page: ReaderPage, notifyIfUnchanged: Boolean = true) {
        // Find the ViewPager position of the display unit containing this page
        val position = adapter.items.indexOfFirst { item ->
            item is DisplayPage && item.containsPage(page)
        }
        if (position != -1) {
            val currentPosition = pager.currentItem
            pager.setCurrentItem(position, true)
            if (currentPosition == position && notifyIfUnchanged) {
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
        if (navigateSource(forward = true)) return
        if (pager.currentItem != 0) {
            pager.setCurrentItem(pager.currentItem - 1, config.usePageTransitions)
        }
    }

    private fun moveRight() {
        if (navigateSource(forward = false)) return
        if (pager.currentItem != adapter.count - 1) {
            pager.setCurrentItem(pager.currentItem + 1, config.usePageTransitions)
        }
    }

    private fun moveUp() = moveToPrevious()
    private fun moveDown() = moveToNext()

    private fun navigateSource(forward: Boolean, animate: Boolean = config.usePageTransitions): Boolean {
        val chapter = (currentPage as? DisplayPage)?.firstPage?.chapter ?: return false
        eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnostics.record(
            if (forward) "next" else "previous",
            chapter.chapter.id,
            adapter.session(chapter)?.location?.pageId?.sourcePageIndex,
            session = activity.diagnosticSession,
        )
        val wasReplacing = replacingPairing
        replacingPairing = true
        try {
            val target = adapter.navigate(chapter, forward) ?: return false
            val position = adapter.items.indexOfFirst { it is DisplayPage && it.containsPage(target) }
            if (position < 0) return false
            pager.setCurrentItem(position, animate)
        } finally {
            replacingPairing = wasReplacing
        }
        onPageChange(pager.currentItem)
        completeReplacementIdle()
        return true
    }

    // ── Page change handling ─────────────────────────────────────────────────

    private fun onPageChange(position: Int, layoutOnly: Boolean = false) {
        val item = adapter.items.getOrNull(position)
        // A window rebuild can keep the same selected pair while an adjacent chapter loads.
        // Preserve that selection so its pending rendered viewport can still settle.
        if (item != null && currentPage != item) {
            currentPage = item
            when (item) {
                is DisplayPage -> onDisplayPageSelected(item, layoutOnly)
                is ChapterTransition -> onTransitionSelected(item)
            }
        } else if (item != null) {
            currentPage = item
            if (item is DisplayPage) onDisplayPageSelected(item, layoutOnly)
        }
        reportSettledDualViewport()
    }

    internal fun onDisplayPageSelected(displayPage: DisplayPage, layoutOnly: Boolean = false) {
        val page = displayPage.firstPage
        val pages = page.chapter.pages ?: return
        logcat { "onDisplayPageSelected: ${page.number}/${pages.size}" }
        if (!layoutOnly) {
            val session = adapter.session(page.chapter)
            if (session?.location?.pageId !in
                displayPage.visiblePages.map { session?.identity?.pageIds?.get(it.index) }
            ) {
                adapter.selectPage(page)
            }
        }
        refreshPresentation()
        val logicalPage =
            pages.getOrNull(adapter.session(page.chapter)?.location?.pageId?.sourcePageIndex ?: page.index) ?: page
        activity.onViewerPageAnchorSelected(this, logicalPage, layoutOnly)
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

    internal fun logNavigationState(event: String) {
        val selected = adapter.items.getOrNull(pager.currentItem)
        val chapter = when (selected) {
            is DisplayPage -> selected.firstPage.chapter
            is ChapterTransition -> selected.from
            else -> null
        }
        val owner = chapter?.let(adapter::session)
        val slot = when (selected) {
            is DisplayPage -> selected.visiblePages.joinToString(",") { it.index.toString() }
            is ChapterTransition.Next -> "next"
            is ChapterTransition.Prev -> "previous"
            else -> "none"
        }
        eu.kanade.tachiyomi.ui.reader.diagnostics.ReaderDiagnostics.record(
            event,
            chapter?.chapter?.id,
            owner?.location?.pageId?.sourcePageIndex,
            mapOf(
                "position" to pager.currentItem,
                "visible" to slot,
                "sequence" to owner?.location?.sequence,
                "gesture" to gestureInProgress,
                "idle" to isIdle,
            ),
            activity.diagnosticSession,
        )
        logcat(LogPriority.INFO) {
            "DualPageNavigation event=$event native=${pager.currentItem} slots=$slot " +
                "source=${owner?.location?.pageId?.sourcePageIndex} sequence=${owner?.location?.sequence} " +
                "gesture=$gestureInProgress idle=$isIdle"
        }
    }

    /** Starts the new presentation scope even if the selected native page and its slots were retained. */
    internal fun refreshPresentation() {
        discardObsoletePresentations()
        val selected = currentPage as? DisplayPage ?: return
        pager.children.filterIsInstance<DualPagerPageHolder>()
            .filter { it.displayPage == selected }.forEach { it.beginPresentation() }
    }

    private fun invalidateGeometry() {
        if (destroyed) return
        presentedViewports.keys.toList().forEach { it.invalidateViewport() }
        presentedViewports.clear()
        pager.children.filterIsInstance<DualPagerPageHolder>().forEach { it.beginPresentation() }
    }

    internal fun capturePresentation(
        displayPage: DisplayPage,
    ): Pair<SharedDualPageSession, ReaderDualPageViewportTicket>? {
        if (destroyed) return null
        discardObsoletePresentations()
        val owner = adapter.session(displayPage.firstPage.chapter) ?: return null
        if (adapter.requirePairing(
                displayPage,
            ) !is mihon.domain.reader.ReaderPairingCertification.Certified
        ) {
            return null
        }
        val visible = displayPage.visiblePages.map { owner.identity.pageIds[it.index] }
        val pageId = owner.location?.pageId?.takeIf { it in visible } ?: visible.firstOrNull() ?: return null
        return owner.captureCertifiedViewport(pageId)?.let { ticket ->
            // Native neighbours may expose pixels during a drag; only the logical scope can read.
            presentedViewports.getOrPut(owner) { mutableSetOf() } += ticket
            owner to ticket
        }
    }

    internal fun releasePresentation(scope: Pair<SharedDualPageSession, ReaderDualPageViewportTicket>) {
        val (owner, ticket) = scope
        if (owner.releaseViewport(ticket)) adapter.presentationReleased(owner)
        presentedViewports[owner]?.let { tickets ->
            tickets.remove(ticket)
            if (tickets.isEmpty()) presentedViewports.remove(owner)
        }
    }

    private fun discardObsoletePresentations() {
        presentedViewports.keys.toList().filterNot(adapter::ownsSession).forEach { owner ->
            presentedViewports.remove(owner)?.forEach(owner::releaseViewport)
        }
    }

    private fun reportSettledDualViewport() {
        if (destroyed || replacingPairing || !isIdle) return
        val displayPage = adapter.items.getOrNull(pager.currentItem) as? DisplayPage ?: return
        if (displayPage != currentPage) return
        val holder = pager.children.filterIsInstance<DualPagerPageHolder>()
            .firstOrNull { it.displayPage == displayPage } ?: return
        val (owner, ticket) = holder.presentationScope ?: return
        val drawn = holder.renderedPageIds(owner).intersect(owner.frame(ticket)?.drawnPageIds.orEmpty())
        if (drawn.isEmpty()) return
        val effect = owner.settle(
            ReaderDualPageSettlement(
                ticket.identity,
                ticket.layoutRevision,
                ticket.locationSequence,
                ticket.unitId,
                drawn,
                settled = true,
                reason = ReaderDualPageSettlementReason.USER_NAVIGATION,
                settlementSequence = ticket.locationSequence,
                ticket = ticket,
            ),
            wasRead = displayPage.firstPage.chapter.chapter.read,
            sessionId = "android-dual-${ticket.identity.generation}",
        ) ?: return
        val logicalPage =
            displayPage.firstPage.chapter.pages?.getOrNull(owner.location?.pageId?.sourcePageIndex ?: return)
                ?: return
        val actualPages = displayPage.firstPage.chapter.pages.orEmpty().filter {
            ticket.identity.pageIds[it.index] in drawn
        }
        activity.onViewerViewportSettled(this, logicalPage, actualPages)
    }

    internal fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            val effect = ReaderAdjacentChapterPolicy.transitionPageEffect()
            val pages = toChapter.pages
            if (pages != null && pairingStore.requiresRestoration && !pairingStore.isRestored(toChapter, pages)) {
                activity.requestTransitionChapterPairing(this, toChapter, effect)
            } else {
                activity.requestPreloadChapter(toChapter, effect)
            }
        } else if (transition is ChapterTransition.Next) {
            activity.showMenu()
        }
    }

    internal fun isTransitionSelected(chapter: ReaderChapter): Boolean =
        !destroyed && (currentPage as? ChapterTransition)?.to === chapter

    // ── Adjust pairing ───────────────────────────────────────────────────────

    /**
     * Shifts the page-pairing offset by 1, effectively allowing the user to realign
     * which pages appear together on screen.
     */
    fun adjustPagePairing() {
        if (currentPage !is DisplayPage) return
        adapter.adjustPairing()
    }

    fun pairingAdjustmentRequest(): PairingAdjustmentRequest? {
        val selected = currentPage as? DisplayPage ?: return null
        val chapter = selected.firstPage.chapter
        if (chapter !== adapter.currentChapter) return null
        val pages = chapter.pages ?: return null
        val owner = adapter.session(chapter) ?: return null
        logcat(LogPriority.INFO) {
            "DualPageManual request ui=${selected.visiblePages.map { it.index }}" +
                " target=${owner.location?.pageId?.sourcePageIndex}" +
                " direction=RTL" +
                " opening=${owner.pairingState.openingMode}" +
                " boundaries=${owner.pairingState.committedForcedSinglePages.sorted().take(16)}" +
                " pairs=${owner.pairingState.manualPairStarts.sorted().take(16)}"
        }
        val save = adapter.beginPairingAdjustment()
        if (save == null) {
            logcat(LogPriority.INFO) {
                "DualPageManual rejected state=${owner.pairingState.status}" +
                    " reason=${owner.targetUnit?.singleReason}" +
                    " opening=${owner.pairingState.openingMode}"
            }
            return null
        }
        return PairingAdjustmentRequest(chapter, pages, owner, save)
    }

    fun refreshPairing() = adapter.refreshPairing()

    internal fun replacePairing(
        anchor: ReaderPage?,
        layoutOnly: Boolean,
        transition: ChapterTransition? = null,
        replace: () -> Unit,
    ) {
        val wasReplacing = replacingPairing
        replacingPairing = true
        try {
            replace()
            if ((anchor != null || transition != null) && !wasReplacing) {
                val position = if (anchor != null) {
                    adapter.items.indexOfFirst { it is DisplayPage && it.containsPage(anchor) }
                } else {
                    adapter.positionOf(transition)
                }
                if (position >= 0) pager.setCurrentItem(position, false)
            }
        } finally {
            replacingPairing = wasReplacing
        }
        if (!replacingPairing && pager.isVisible && (anchor != null || transition != null)) {
            onPageChange(pager.currentItem, layoutOnly = layoutOnly)
        }
        completeReplacementIdle()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun refreshAdapter() {
        val settings = geometrySettings()
        if (settings != presentationSettings) {
            presentationSettings = settings
            invalidateGeometry()
        }
        adapter.refresh()
        pager.children.filterIsInstance<DualPagerPageHolder>().forEach { it.refreshImageProperties() }
    }

    private fun getPageHolder(page: ReaderPage): DualPagerPageHolder? =
        pager.children
            .filterIsInstance<DualPagerPageHolder>()
            .firstOrNull { it.displayPage.firstPage == page }
}

data class PairingAdjustmentRequest(
    val chapter: ReaderChapter,
    val pages: List<ReaderPage>,
    val owner: SharedDualPageSession,
    val save: mihon.domain.reader.ReaderPairingPersistenceRequest.Save,
)
