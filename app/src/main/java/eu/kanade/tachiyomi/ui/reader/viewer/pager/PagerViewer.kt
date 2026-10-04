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
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.model.loadedEntryPage
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mihon.domain.reader.ReaderAdjacentChapterPolicy
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderDualPageSettlement
import mihon.domain.reader.ReaderDualPageSettlementReason
import mihon.domain.reader.ReaderDualPageViewportTicket
import mihon.domain.reader.ReaderPresentationMode
import mihon.domain.reader.SharedDualPageSession
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import kotlin.math.min

/**
 * Implementation of a [Viewer] to display pages with a [ViewPager].
 */
@Suppress("LeakingThis")
abstract class PagerViewer(override val activity: ReaderActivity) : Viewer, ViewerWithPager {

    override val downloadManager: DownloadManager by injectLazy()

    private val scope = MainScope()
    private val pairingStore get() = activity.viewModel.dualPagePairings
    private val factCollectors = mutableMapOf<ReaderPage, Job>()
    private val presentedViewports = mutableMapOf<SharedDualPageSession, ReaderDualPageViewportTicket>()
    private var destroyed = false

    /**
     * View pager used by this viewer. It's abstract to implement L2R, R2L and vertical pagers on
     * top of this class.
     */
    override val pager = createPager()

    /**
     * Configuration used by the pager, like allow taps, scale mode on images, page transitions...
     */
    val config = PagerConfig(this, scope)

    /**
     * Adapter of the pager.
     */
    private val adapter = PagerViewerAdapter(this)

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    private var currentPage: Any? = null

    /**
     * Viewer chapters to set when the pager enters idle mode. Otherwise, if the view was settling
     * or dragging, there'd be a noticeable and annoying jump.
     */
    private var awaitingIdleViewerChapters: ViewerChapters? = null

    /**
     * Whether the view pager is currently in idle mode. It sets the awaiting chapters if setting
     * this field to true.
     */
    private var isIdle = true
        set(value) {
            field = value
            if (value) {
                awaitingIdleViewerChapters?.let { viewerChapters ->
                    setChaptersInternal(viewerChapters)
                    awaitingIdleViewerChapters = null
                    val currentChapterAlreadySelected =
                        (currentPage as? ReaderPage)?.chapter == viewerChapters.currChapter
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
            }
        }

    private val pagerListener = object : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            if (!activity.isScrollingThroughPages) {
                activity.hideMenu()
            }
            onPageChange(position)
        }

        override fun onPageScrollStateChanged(state: Int) {
            isIdle = state == ViewPager.SCROLL_STATE_IDLE
            if (isIdle) reportSettledViewport()
        }
    }

    init {
        pager.isVisible = false // Don't layout the pager yet
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
                if (item is ReaderPage) {
                    activity.onPageLongTap(item)
                    return@f true
                }
            }
            false
        }

        config.dualPageSplitChangedListener = { enabled ->
            if (!enabled) {
                cleanupPageSplit()
            }
        }

        config.imagePropertyChangedListener = {
            refreshAdapter()
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.setViewerNavigation(config.navigator, showOnStart)
        }
    }

    override fun destroy() {
        destroyed = true
        presentedViewports.forEach { (owner, ticket) -> owner.releaseViewport(ticket) }
        presentedViewports.clear()
        super.destroy()
        scope.cancel()
    }

    /**
     * Creates a new ViewPager.
     */
    abstract fun createPager(): Pager

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return pager
    }

    /**
     * Returns the PagerPageHolder for the provided page
     */
    private fun getPageHolder(page: ReaderPage): PagerPageHolder? =
        pager.children
            .filterIsInstance(PagerPageHolder::class.java)
            .firstOrNull { it.item == page }

    /**
     * Called when a new page (either a [ReaderPage] or [ChapterTransition]) is marked as active
     */
    private fun onPageChange(position: Int) {
        val page = adapter.items.getOrNull(position)
        if (page != null && currentPage != page) {
            val allowPreload = checkAllowPreload(page as? ReaderPage)
            val forward = when {
                currentPage is ReaderPage && page is ReaderPage -> {
                    // if both pages have the same number, it's a split page with an InsertPage
                    if (page.number == (currentPage as ReaderPage).number) {
                        // the InsertPage is always the second in the reading direction
                        page is InsertPage
                    } else {
                        page.number > (currentPage as ReaderPage).number
                    }
                }
                currentPage is ChapterTransition.Prev && page is ReaderPage ->
                    false
                else -> true
            }
            currentPage = page
            when (page) {
                is ReaderPage -> onReaderPageSelected(page, allowPreload, forward)
                is ChapterTransition -> onTransitionSelected(page)
            }
        } else if (page is ReaderPage) {
            if (page !is InsertPage) activity.onViewerPageAnchorSelected(this, page, layoutOnly = false)
            getPageHolder(page)?.beginPresentation()
        }
        reportSettledViewport()
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentPage ?: return true

        // Allow preload for
        // 1. Going to next chapter from chapter transition
        // 2. Going between pages of same chapter
        // 3. Next chapter page
        return when (page.chapter) {
            (currentPage as? ChapterTransition.Next)?.to -> true
            (currentPage as? ReaderPage)?.chapter -> true
            adapter.nextTransition?.to -> true
            else -> false
        }
    }

    /**
     * Called when a [ReaderPage] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    internal fun onReaderPageSelected(page: ReaderPage, allowPreload: Boolean, forward: Boolean) {
        val pages = page.chapter.pages ?: return
        logcat { "onReaderPageSelected: ${page.number}/${pages.size}" }
        if (page !is InsertPage) {
            val owner = ensureSingleOwner(page) ?: return
            if (owner.location?.pageId != owner.identity.pageIds[page.index]) {
                owner.goToPage(owner.identity.pageIds[page.index])
            }
            activity.onViewerPageAnchorSelected(this, page, layoutOnly = false)
            getPageHolder(page)?.beginPresentation()
        }

        // Notify holder of page change
        getPageHolder(page)?.onPageSelected(forward)

        // Skip preload on inserts it causes unwanted page jumping
        if (page is InsertPage) {
            return
        }

        // Preload next chapter once we're within the last 5 pages of the current chapter
        val adjacentEffect = ReaderAdjacentChapterPolicy.effectForPageAnchor(page.index, pages.size)
        if (adjacentEffect != null && allowPreload && page.chapter == adapter.currentChapter) {
            logcat { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            adapter.nextTransition?.to?.let { target ->
                activity.requestPreloadChapter(target, adjacentEffect)
            }
        }
    }

    /**
     * Called when a [ChapterTransition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    internal fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(
                toChapter,
                ReaderAdjacentChapterPolicy.transitionPageEffect(),
            )
        } else if (transition is ChapterTransition.Next) {
            // No more chapters, show menu because the user is probably going to close the reader
            activity.showMenu()
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active. If the pager is currently idle,
     * it sets the chapters immediately, otherwise they are saved and set when it becomes idle.
     */
    override fun setChapters(chapters: ViewerChapters) {
        if (isIdle) {
            setChaptersInternal(chapters)
        } else {
            awaitingIdleViewerChapters = chapters
        }
    }

    /**
     * Sets the active [chapters] on this pager.
     */
    private fun setChaptersInternal(chapters: ViewerChapters) {
        // Remove listener so the change in item doesn't trigger it
        pager.removeOnPageChangeListener(pagerListener)

        val entryPage = (adapter.items.getOrNull(pager.currentItem) as? ChapterTransition)?.loadedEntryPage()
        val forceTransition = config.alwaysShowChapterTransition ||
            adapter.items.getOrNull(pager.currentItem) is ChapterTransition
        bindSingleFacts(chapters)
        adapter.setChapters(chapters, forceTransition)

        // Layout the pager once a chapter is being set
        if (pager.isGone) {
            logcat { "Pager first layout" }
            val pages = chapters.currChapter.pages ?: return
            positionPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
            pager.isVisible = true
        }

        if (entryPage != null) {
            val position = adapter.items.indexOf(entryPage)
            if (position >= 0) pager.setCurrentItem(position, false)
        }

        pager.addOnPageChangeListener(pagerListener)
        // Manually call onPageChange to update the UI
        onPageChange(pager.currentItem)
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage) {
        if (page !is InsertPage) {
            ensureSingleOwner(page)?.let { it.goToPage(it.identity.pageIds[page.index]) }
        }
        positionPage(page)
    }

    private fun positionPage(page: ReaderPage) {
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            val currentPosition = pager.currentItem
            pager.setCurrentItem(position, true)
            // manually call onPageChange since ViewPager listener is not triggered in this case
            if (currentPosition == position) {
                onPageChange(position)
            }
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    private fun ensureSingleOwner(page: ReaderPage): SharedDualPageSession? {
        if (destroyed || page is InsertPage) return null
        val pages = page.chapter.pages ?: return null
        if (pages.getOrNull(page.index) !== page) return null
        val previous = pairingStore.chapters[page.chapter]?.session
        if (previous != null &&
            previous.identity.content.getOrNull(page.index)?.contentVersion != page.encodedContentIdentity()
        ) {
            // Ordinary Single already replaces its decoded image on a normal content snapshot.
            // End that old presentation before rebinding, rather than assigning new bytes to its frame.
            presentedViewports.remove(previous)?.let(previous::releaseViewport)
        }
        return pairingStore.ensure(page.chapter, pages).session.also {
            it.configure(if (this is L2RPagerViewer) ReaderDirection.LTR else ReaderDirection.RTL)
            it.configurePresentationMode(ReaderPresentationMode.SINGLE)
        }
    }

    private fun bindSingleFacts(chapters: ViewerChapters) {
        val window = listOfNotNull(chapters.prevChapter, chapters.currChapter, chapters.nextChapter)
        val retained = window.flatMap { it.pages.orEmpty() }.toSet()
        factCollectors.keys.toList().filter { it !in retained }.forEach { factCollectors.remove(it)?.cancel() }
        window.forEach { chapter ->
            val pages = chapter.pages ?: return@forEach
            pages.firstOrNull()?.let(::ensureSingleOwner)
            pages.filter { it !in factCollectors }.forEach { page ->
                factCollectors[page] = scope.launch {
                    page.contentFact.collect { captured ->
                        if (chapter.pages !== pages || destroyed) return@collect
                        val owner = ensureSingleOwner(page) ?: return@collect
                        if (page.contentFact.value != captured) return@collect
                        val entry = pairingStore.chapters[chapter] ?: return@collect
                        val previous = entry.factRetrySequences[page] ?: captured.retrySequence
                        if (captured.retrySequence > previous) {
                            entry.factRetrySequences[page] = captured.retrySequence
                            owner.retryFacts()
                        }
                        if (owner.identity.content[page.index].contentVersion != page.encodedContentIdentity()) {
                            return@collect
                        }
                        owner.acceptFact(owner.requestFact(owner.identity.pageIds[page.index]), captured.fact)
                        (currentPage as? ReaderPage)?.takeIf { it.chapter === chapter }
                            ?.let { getPageHolder(it)?.beginPresentation() }
                    }
                }
            }
        }
        presentedViewports.keys.toList().filter { owner ->
            pairingStore.chapters.values.none { it.session === owner && it.pages.any(retained::contains) }
        }.forEach { owner -> presentedViewports.remove(owner)?.let(owner::releaseViewport) }
    }

    internal fun capturePresentation(page: ReaderPage): Pair<SharedDualPageSession, ReaderDualPageViewportTicket>? {
        // A split's offscreen second half must never issue or release the selected first half's scope.
        if (page is InsertPage || currentPage !== page ||
            adapter.items.getOrNull(pager.currentItem) !== page
        ) {
            return null
        }
        val owner = ensureSingleOwner(page) ?: return null
        if (owner.location?.pageId != owner.identity.pageIds[page.index]) return null
        return owner.captureViewport(preparingPageIds = emptySet())?.let {
            presentedViewports[owner] = it
            owner to it
        }
    }

    internal fun releasePresentation(presentation: Pair<SharedDualPageSession, ReaderDualPageViewportTicket>) {
        val (owner, ticket) = presentation
        owner.releaseViewport(ticket)
        if (presentedViewports[owner] === ticket) presentedViewports.remove(owner)
    }

    internal fun onHolderDisplayStateChanged(holder: PagerPageHolder) {
        if (holder.page === currentPage) reportSettledViewport()
    }

    private fun reportSettledViewport() {
        if (destroyed || !isIdle) return
        val page = adapter.items.getOrNull(pager.currentItem) as? ReaderPage ?: return
        if (page is InsertPage || currentPage !== page) return
        val holder = getPageHolder(page) ?: return
        if (!holder.hasRenderedPage()) return
        val (owner, ticket) = holder.presentationScope ?: return
        if (owner.settle(
                ReaderDualPageSettlement(
                    ticket.identity, ticket.layoutRevision, ticket.locationSequence, ticket.unitId,
                    setOf(ticket.identity.pageIds[page.index]), true, ReaderDualPageSettlementReason.USER_NAVIGATION,
                    ticket.locationSequence, ticket,
                ),
                page.chapter.chapter.read,
                "android-pager-${ticket.identity.generation}",
            ) == null
        ) {
            return
        }
        activity.onViewerViewportSettled(this, page, listOf(page))
    }

    /**
     * Moves to the next page.
     */
    open fun moveToNext() {
        moveRight()
    }

    /**
     * Moves to the previous page.
     */
    open fun moveToPrevious() {
        moveLeft()
    }

    /**
     * Moves to the page at the right.
     */
    protected open fun moveRight() {
        if (pager.currentItem != adapter.count - 1) {
            val holder = (currentPage as? ReaderPage)?.let(::getPageHolder)
            if (holder != null && config.navigateToPan && holder.canPanRight()) {
                holder.panRight()
            } else {
                pager.setCurrentItem(pager.currentItem + 1, config.usePageTransitions)
            }
        }
    }

    /**
     * Moves to the page at the left.
     */
    protected open fun moveLeft() {
        if (pager.currentItem != 0) {
            val holder = (currentPage as? ReaderPage)?.let(::getPageHolder)
            if (holder != null && config.navigateToPan && holder.canPanLeft()) {
                holder.panLeft()
            } else {
                pager.setCurrentItem(pager.currentItem - 1, config.usePageTransitions)
            }
        }
    }

    /**
     * Moves to the page at the top (or previous).
     */
    protected open fun moveUp() {
        moveToPrevious()
    }

    /**
     * Moves to the page at the bottom (or next).
     */
    protected open fun moveDown() {
        moveToNext()
    }

    /**
     * Resets the adapter in order to recreate all the views. Used when a image configuration is
     * changed.
     */
    private fun refreshAdapter() {
        val currentItem = pager.currentItem
        adapter.refresh()
        pager.adapter = adapter
        pager.setCurrentItem(currentItem, false)
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
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
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isUp) {
                    if (ctrlPressed) moveToNext() else moveRight()
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isUp) {
                    if (ctrlPressed) moveToPrevious() else moveLeft()
                }
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

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
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

    fun onPageSplit(currentPage: ReaderPage, newPage: InsertPage) {
        activity.runOnUiThread {
            // Need to insert on UI thread else images will go blank
            adapter.onPageSplit(currentPage, newPage)
        }
    }

    private fun cleanupPageSplit() {
        adapter.cleanupPageSplit()
    }
}
