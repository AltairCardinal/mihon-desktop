package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import android.view.ViewGroup
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.model.loadedEntryPage
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mihon.domain.reader.ChapterPairingSnapshot
import mihon.domain.reader.ReaderChapterBoundary
import mihon.domain.reader.ReaderDualPageFactRequest
import mihon.domain.reader.ReaderPageFact
import mihon.domain.reader.ReaderPageFactState
import mihon.domain.reader.ReaderPairingAdjustment
import mihon.domain.reader.ReaderPortraitSingleSlot
import mihon.domain.reader.SharedDualPageSession
import mihon.domain.reader.readerChapterBoundary
import tachiyomi.core.common.util.system.logcat

/**
 * ViewPager adapter for [DualPageR2LPagerViewer].
 *
 * Items list contains:
 * - [DisplayPage]       → mapped to a [DualPagerPageHolder]
 * - [ChapterTransition] → mapped to a [PagerTransitionHolder] (reused from single-page viewer)
 *
 * Pairings are managed by [SharedDualPageSession] and rebuilt whenever page dimensions
 * are decoded or the user presses "adjust pairing".
 */
class DualPageViewerAdapter(
    private val viewer: DualPageR2LPagerViewer,
    private val store: DualPagePairingStore = DualPagePairingStore(),
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
    private var pendingPairing = false
    private val scope = MainScope()
    private val foregroundCollectors = mutableMapOf<PageLoader, Job>()

    // Main owns subscriptions; IO checks an immutable publication plus each loader's real getter.
    @Volatile
    private var foregroundLoaders: Set<PageLoader> = emptySet()
    private val factCollectors = mutableMapOf<ReaderPage, Job>()
    private var matchingJob: Job? = null
    private var matchingSession: SharedDualPageSession? = null
    private val failedSamples = mutableSetOf<mihon.domain.reader.ReaderDualPageContentIdentity>()
    internal var spreadDecoderFactory = eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.nativeDecoder

    init {
        scope.launch {
            eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.foregroundWork
                .map { it > 0 }.distinctUntilChanged().collect { busy ->
                    if (!busy) {
                        viewer.activity.refreshOptionalReaderMemoryPressure()
                        startMatching()
                    }
                }
        }
        scope.launch {
            viewer.activity.optionalWorkUnderMemoryPressure.collect { pressured ->
                if (!pressured) startMatching()
            }
        }
        scope.launch {
            eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.collect { count ->
                if (count == 0) startMatching()
            }
        }
    }

    internal fun matchingSettingChanged() {
        matchingJob?.cancel()
        failedSamples.clear()
        chapterPairings.values.forEach {
            it.session.configure(
                mihon.domain.reader.ReaderDirection.RTL,
                automaticMatching = viewer.config.automaticSpreadMatching,
            )
        }
        rebuildItems()
    }

    internal fun session(chapter: ReaderChapter): SharedDualPageSession? = chapterPairings[chapter]?.session

    internal fun ownsSession(
        owner: SharedDualPageSession,
    ): Boolean = chapterPairings.values.any { it.session === owner }

    internal fun selectPage(page: ReaderPage, publishLayout: Boolean = false) {
        val entry = chapterPairings[page.chapter] ?: return
        if (entry.pages.getOrNull(page.index) !== page) return
        entry.session.goToPage(entry.session.identity.pageIds[page.index])
        if (publishLayout) {
            // An explicit jump can choose a different group from the old offscreen projection.
            rebuildItems(page)
        } else {
            startMatching()
        }
    }

    internal fun navigate(chapter: ReaderChapter, forward: Boolean): ReaderPage? {
        val entry = chapterPairings[chapter] ?: return null
        if (!entry.session.navigate(forward)) return null
        val target = entry.session.location?.pageId?.sourcePageIndex?.let(entry.pages::getOrNull) ?: return null
        rebuildItems(target)
        return target
    }

    /** Native slots stay frozen during dragging; only their logical source anchor advances. */
    internal fun selectGesturePage(displayPage: DisplayPage, forward: Boolean) {
        val entry = chapterPairings[displayPage.firstPage.chapter] ?: return
        val indices = displayPage.visiblePages.map { it.index }
        if (displayPage.visiblePages.any { entry.pages.getOrNull(it.index) !== it }) return
        val owner = entry.session
        // Only the selected native group was entered; skipped slots must not acquire shared ownership.
        if (owner.location?.pageId?.sourcePageIndex !in indices) {
            val anchor = if (forward) indices.min() else indices.max()
            if (!owner.goToPage(owner.identity.pageIds[anchor], forward)) return
        }
        pendingPairing = true
        startMatching()
    }

    internal fun close() {
        scope.cancel()
        chapterPairings.values.forEach {
            it.session.configurePresentationMode(mihon.domain.reader.ReaderPresentationMode.SINGLE)
        }
        matchingJob = null
        matchingSession = null
        failedSamples.clear()
        factCollectors.clear()
        foregroundCollectors.clear()
        foregroundLoaders = emptySet()
    }

    /** Keep decode facts and manual pairing while the same chapter page identities remain loaded. */
    fun setChapters(chapters: ViewerChapters, @Suppress("UNUSED_PARAMETER") forceTransition: Boolean) {
        this.chapters = chapters
        currentChapter = chapters.currChapter
        val window = listOfNotNull(chapters.prevChapter, chapters.currChapter, chapters.nextChapter)
        chapterPairings.keys.toList().filter { it !in window }.forEach { chapter ->
            chapterPairings.remove(chapter)?.session?.close()
        }
        val activeLoaders = window.mapNotNull { it.pageLoader }.toSet()
        // A removed chapter loses presentation ownership immediately, but cancelled physical work
        // still consumes the original decoder/network resources until its actual completion.
        foregroundCollectors.keys.toList().filter { it !in activeLoaders && !it.hasForegroundWork }
            .forEach { foregroundCollectors.remove(it)?.cancel() }
        for (loader in activeLoaders) {
            if (loader !in foregroundCollectors) {
                foregroundCollectors[loader] = scope.launch {
                    loader.foregroundWork.collect { busy ->
                        if (!busy) {
                            viewer.activity.refreshOptionalReaderMemoryPressure()
                            startMatching()
                            val currentWindow = chapters?.let {
                                listOfNotNull(it.prevChapter, it.currChapter, it.nextChapter)
                            }.orEmpty()
                            if (currentWindow.none { it.pageLoader === loader }) {
                                foregroundCollectors.remove(loader)?.cancel()
                                foregroundLoaders = foregroundCollectors.keys.toSet()
                            }
                        }
                    }
                }
            }
        }
        foregroundLoaders = foregroundCollectors.keys.toSet()
        for (chapter in window) {
            val pages = chapter.pages
            if (pages == null) {
                chapterPairings.remove(chapter)?.session?.close()
                continue
            }
            store.ensure(
                chapter,
                pages,
            ).session.apply {
                configure(
                    mihon.domain.reader.ReaderDirection.RTL,
                    automaticMatching = viewer.config.automaticSpreadMatching,
                )
                configurePresentationMode(mihon.domain.reader.ReaderPresentationMode.DUAL)
            }
            pages.filter { it !in factCollectors }.forEach { page ->
                factCollectors[page] = scope.launch {
                    page.contentFact.collect { captured ->
                        if (chapter.pages !== pages || chapterPairings[chapter]?.pages !== pages) return@collect
                        val entry = store.ensure(chapter, pages)
                        entry.session.configure(
                            mihon.domain.reader.ReaderDirection.RTL,
                            automaticMatching = viewer.config.automaticSpreadMatching,
                        )
                        entry.session.configurePresentationMode(mihon.domain.reader.ReaderPresentationMode.DUAL)
                        if (captured.version != page.contentFact.value.version ||
                            captured.retrySequence != page.contentFact.value.retrySequence ||
                            captured.encodedVersion != page.contentFact.value.encodedVersion
                        ) {
                            return@collect
                        }
                        val previousRetry = entry.factRetrySequences[page] ?: captured.retrySequence
                        if (captured.retrySequence > previousRetry) {
                            entry.factRetrySequences[page] = captured.retrySequence
                            matchingJob?.cancel()
                            failedSamples.clear()
                            entry.session.retryFacts()
                        }
                        val fact = captured.fact
                        if (fact.state != ReaderPageFactState.UNKNOWN) {
                            acceptFact(page, fact)
                        } else {
                            rebuildItems()
                        }
                    }
                }
            }
        }
        val retainedPages = window.flatMap { it.pages.orEmpty() }.toSet()
        factCollectors.keys.toList().filter { it !in retainedPages }.forEach { page ->
            factCollectors.remove(page)?.cancel()
        }
        rebuildItems()
    }

    /** The chapter identity is part of every decode fact; page ordinals alone are not unique. */
    fun updatePageDimensions(page: ReaderPage, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        acceptDecodedDimensions(page, captureDimensions(page), width, height)
    }

    internal fun captureDimensions(page: ReaderPage): Pair<SharedDualPageSession, ReaderDualPageFactRequest>? {
        val entry = chapterPairings[page.chapter] ?: return null
        if (entry.pages.getOrNull(page.index) !== page) return null
        return entry.session to entry.session.requestFact(entry.session.identity.pageIds[page.index])
    }

    internal fun acceptDecodedDimensions(
        page: ReaderPage,
        captured: Pair<SharedDualPageSession, ReaderDualPageFactRequest>?,
        width: Int,
        height: Int,
        orientation: mihon.domain.reader.ReaderImageOrientation = mihon.domain.reader.ReaderImageOrientation.NORMAL,
    ): Boolean {
        if (captured == null || width <= 0 || height <= 0) return false
        val (owner, request) = captured
        if (chapterPairings[page.chapter]?.session !== owner ||
            owner.identity.content[page.index].contentVersion != page.encodedContentIdentity()
        ) {
            return false
        }
        if (!owner.acceptFact(
                request,
                ReaderPageFact.dimensions(width, height, sourceOrientation = orientation),
            )
        ) {
            return false
        }
        rebuildItems()
        return true
    }

    private fun acceptFact(page: ReaderPage, fact: ReaderPageFact) {
        val pairing = chapterPairings[page.chapter] ?: return
        if (pairing.pages.getOrNull(page.index) !== page) return
        if (pairing.session.identity.content[page.index].contentVersion != page.encodedContentIdentity()) return
        val request = pairing.session.requestFact(pairing.session.identity.pageIds[page.index])
        if (!pairing.session.acceptFact(request, fact)) return
        rebuildItems()
    }

    fun adjustPairing() {
        currentChapter?.let(chapterPairings::get)?.session?.adjustPairing()
        rebuildItems()
    }

    fun beginPairingAdjustment(): mihon.domain.reader.ReaderPairingPersistenceRequest.Save? {
        val owner = currentChapter?.let(chapterPairings::get)?.session ?: return null
        return owner.adjustPairing().also { if (it == null) rebuildItems() }
    }

    fun appliedForcedSinglePages(): Set<Int> =
        currentChapter?.let(chapterPairings::get)?.session?.pairingState?.committedForcedSinglePages.orEmpty()

    internal fun refreshDecodedSide(page: ReaderPage, owner: SharedDualPageSession) {
        if (chapterPairings[page.chapter]?.session === owner &&
            owner.identity.content[page.index].contentVersion == page.encodedContentIdentity()
        ) {
            rebuildItems()
        }
    }

    fun refreshPairing() = rebuildItems()

    internal fun releaseContent(chapter: ReaderChapter) {
        val pages = chapter.pages ?: return
        val previous = chapterPairings[chapter]?.session ?: return
        if (store.ensure(chapter, pages).session !== previous) rebuildItems()
    }

    fun refresh() {
        readerThemedContext = viewer.activity.createReaderThemeContext()
    }

    // ── ViewPagerAdapter implementation ─────────────────────────────────────

    override fun getCount(): Int = items.size

    /** Shared transition equality does not distinguish its native direction. */
    internal fun positionOf(item: Any?): Int = items.indexOfFirst { candidate ->
        if (item is ChapterTransition) {
            candidate is ChapterTransition && candidate.javaClass == item.javaClass &&
                candidate.from === item.from && candidate.to === item.to
        } else {
            candidate == item
        }
    }

    override fun createView(container: ViewGroup, position: Int): View {
        return when (val item = items[position]) {
            is DisplayPage -> DualPagerPageHolder(readerThemedContext, viewer, item)
            is ChapterTransition -> PagerTransitionHolder(readerThemedContext, viewer, item)
            else -> throw NotImplementedError("Holder for ${item.javaClass} not implemented")
        }
    }

    override fun getItemPosition(view: Any): Int {
        if (view is DualPagerPageHolder) {
            val unchanged = items.indexOf(view.displayPage)
            if (unchanged != -1) return unchanged
            items.forEachIndexed { position, replacement ->
                if (replacement is DisplayPage && view.updateDisplayPage(replacement)) return position
            }
        }
        if (view is ViewPagerAdapter.PositionableView) {
            val position = positionOf(view.item)
            if (position != -1) return position
            logcat { "Position for ${view.item} not found" }
        }
        return POSITION_NONE
    }

    /** Assemble in story order and reverse the entire window once, including its boundaries. */
    private fun rebuildItems(targetPage: ReaderPage? = null) {
        if (targetPage == null && viewer.hasActiveGesture) {
            // Facts update the owner immediately, while native slots retain their held positions.
            pendingPairing = true
            return
        }
        pendingPairing = false
        val chapters = chapters ?: return
        startMatching()
        val selected = items.getOrNull(viewer.pager.currentItem)
        val currentPairing = chapterPairings[chapters.currChapter]
        val logicalTarget = currentPairing?.session?.location?.pageId?.sourcePageIndex
            ?.let { currentPairing.pages.getOrNull(it) }
        val window = listOfNotNull(chapters.prevChapter, chapters.currChapter, chapters.nextChapter)
        val selectedTransition = (selected as? ChapterTransition)?.takeIf { transition ->
            window.any { it === transition.from } &&
                (transition.to == null || window.any { it === transition.to })
        }
        val transitionEntry = selectedTransition?.takeIf { isReadyForDisplay(it.to) }?.loadedEntryPage()
        // A loading/terminal boundary is itself the native selection, not the old owner's anchor.
        val retainedTransition = selectedTransition?.takeIf { targetPage == null && transitionEntry == null }
        val anchor = targetPage ?: transitionEntry ?: if (retainedTransition != null) {
            null
        } else {
            logicalTarget ?: (selected as? DisplayPage)?.firstPage
        }
        val logical = buildList<Any> {
            chapters.prevChapter?.let { chapter ->
                if (isReadyForDisplay(chapter)) {
                    chapterPairings[chapter]?.let { addAll(buildDisplayPages(it.pages, it.session)) }
                }
            }
            if (readerChapterBoundary(
                    chapters.prevChapter != null,
                    isReadyForDisplay(chapters.prevChapter),
                ) != ReaderChapterBoundary.NONE
            ) {
                add(ChapterTransition.Prev(chapters.currChapter, chapters.prevChapter))
            }
            chapterPairings[chapters.currChapter]?.let { addAll(buildDisplayPages(it.pages, it.session)) }
            nextTransition = ChapterTransition.Next(chapters.currChapter, chapters.nextChapter)
            if (readerChapterBoundary(
                    chapters.nextChapter != null,
                    isReadyForDisplay(chapters.nextChapter),
                ) != ReaderChapterBoundary.NONE
            ) {
                add(requireNotNull(nextTransition))
            }
            chapters.nextChapter?.let { chapter ->
                if (isReadyForDisplay(chapter)) {
                    chapterPairings[chapter]?.let { addAll(buildDisplayPages(it.pages, it.session)) }
                }
            }
        }
        val replacement = logical.asReversed().toMutableList()
        if (items == replacement) {
            // Metadata can change certainty and the presentation capability without changing physical slots.
            viewer.refreshPresentation()
            return
        }
        val layoutOnly = selected is DisplayPage && selected.firstPage.chapter === currentChapter
        viewer.replacePairing(anchor, layoutOnly, retainedTransition) {
            items = replacement
            notifyDataSetChanged()
        }
        viewer.logNavigationState("rebuild")
    }

    internal fun publishPendingPairing() {
        if (pendingPairing) rebuildItems()
    }

    /** No materialization here: required adjacent edges consume only already-ready original streams. */
    private fun startMatching() {
        val chapter = currentChapter ?: return
        val owner = chapterPairings[chapter]?.session ?: return
        if (matchingSession !== owner || !viewer.config.automaticSpreadMatching) {
            matchingJob?.cancel()
            failedSamples.clear()
            matchingSession = owner
        }
        if (!viewer.config.automaticSpreadMatching ||
            viewer.activity.optionalWorkUnderMemoryPressure.value || foregroundLoaders.any { it.hasForegroundWork } ||
            eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.foregroundWork.value > 0 ||
            matchingJob?.isCompleted == false ||
            eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value > 0
        ) {
            return
        }
        val first = owner.spreadSampleDependencies().firstOrNull() ?: return
        val firstContent = owner.identity.content.single { it.pageId == first }
        if (firstContent in failedSamples ||
            chapter.pages?.getOrNull(first.sourcePageIndex)?.status != eu.kanade.tachiyomi.source.model.Page.State.Ready
        ) {
            return
        }
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            while (chapterPairings[chapter]?.session === owner) {
                val pageId = owner.spreadSampleDependencies().firstOrNull() ?: break
                val content = owner.identity.content.single { it.pageId == pageId }
                if (content in failedSamples) break
                val page = chapter.pages?.getOrNull(pageId.sourcePageIndex)
                    ?.takeIf { it.status is eu.kanade.tachiyomi.source.model.Page.State.Ready } ?: break
                val captured = page.captureContentFact()
                if (captured.first != content.contentVersion ||
                    captured.second.fact.sourceOrientation != content.sourceOrientation
                ) {
                    store.ensure(chapter, requireNotNull(chapter.pages))
                    if (chapterPairings[chapter]?.session !== owner ||
                        owner.identity.content.single { it.pageId == pageId } == content
                    ) {
                        break
                    }
                    continue
                }
                val request = owner.requestSpreadSample(pageId) ?: break
                val fact = captured.second.fact
                val rawSize = request.content.sourceOrientation.orientedSize(fact.width, fact.height)
                val samples = try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.sample(
                            page,
                            request,
                            rawSize.width,
                            rawSize.height,
                            spreadDecoderFactory,
                            canContinue = {
                                !viewer.activity.optionalWorkUnderMemoryPressure.value &&
                                    foregroundLoaders.none { it.hasForegroundWork } &&
                                    eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.foregroundWork.value == 0
                            },
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                if (chapterPairings[chapter]?.session !== owner || !owner.ownsSpreadSampleRequest(request) ||
                    page.encodedContentIdentity() != request.content.contentVersion
                ) {
                    break
                }
                if (samples == null) {
                    failedSamples += request.content
                    break
                }
                var accepted = false
                if (!page.consumeContentFact(captured) {
                        accepted = owner.acceptSpreadSample(request, samples)
                    }
                ) {
                    store.ensure(chapter, requireNotNull(chapter.pages))
                    continue
                }
                if (!accepted) break
                rebuildItems()
            }
        }
        // Main serializes admission, registering this LAZY job, and start across viewer replacements.
        // Cancellation revokes publication, not a native/IO task still executing in the old job.
        matchingJob = job
        eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.observe(job)
        job.invokeOnCompletion {
            scope.launch {
                if (matchingJob === job) {
                    matchingJob = null
                    startMatching()
                }
            }
        }
        job.start()
    }

    private fun isReadyForDisplay(chapter: ReaderChapter?): Boolean =
        chapter?.state is ReaderChapter.State.Loaded

    private fun buildDisplayPages(pages: List<ReaderPage>, session: SharedDualPageSession): List<DisplayPage> {
        return session.units.map { unit ->
            if (unit.pageIds.size == 1) {
                DisplayPage.Single(pages[unit.pageIds.single().sourcePageIndex], unit.slots.single().position)
            } else {
                DisplayPage.Double(
                    rightPage = pages[unit.pageIds[0].sourcePageIndex],
                    leftPage = pages[unit.pageIds[1].sourcePageIndex],
                )
            }
        }
    }
}
