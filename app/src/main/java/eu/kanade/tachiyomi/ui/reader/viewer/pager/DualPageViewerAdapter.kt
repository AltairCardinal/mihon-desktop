package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import android.view.ViewGroup
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPageContentFact
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.model.loadedEntryPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mihon.domain.reader.ChapterPairingSnapshot
import mihon.domain.reader.ReaderAnalysisFailure
import mihon.domain.reader.ReaderChapterBoundary
import mihon.domain.reader.ReaderDualPageFactLimits
import mihon.domain.reader.ReaderDualPageFactReadPermit
import mihon.domain.reader.ReaderDualPageFactRequest
import mihon.domain.reader.ReaderOriginalAnalysisPublication
import mihon.domain.reader.ReaderPageFact
import mihon.domain.reader.ReaderPageFactState
import mihon.domain.reader.ReaderPairingAdjustment
import mihon.domain.reader.ReaderPairingCertification
import mihon.domain.reader.ReaderPairingFactKind
import mihon.domain.reader.ReaderPairingPrefetchPlan
import mihon.domain.reader.ReaderPortraitSingleSlot
import mihon.domain.reader.SharedDualPageSession
import mihon.domain.reader.readerChapterBoundary
import mihon.domain.reader.session.ReaderPageId
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
    private data class FactProjectionInput(
        val layout: Long,
        val facts: Long,
        val samples: Long,
        val headers: Long,
    )

    private val projectedFactInputs = mutableMapOf<SharedDualPageSession, FactProjectionInput>()

    private fun SharedDualPageSession.factProjectionInput() =
        FactProjectionInput(layoutRevision, factWorkRevision, sampleWorkRevision, originalHeaderRevision)

    private fun recordProjectedFacts(inputs: Map<SharedDualPageSession, FactProjectionInput>) {
        projectedFactInputs.keys.retainAll(inputs.keys)
        projectedFactInputs.putAll(inputs)
    }

    private fun projectAcceptedFact(owner: SharedDualPageSession) {
        if (projectedFactInputs[owner] != owner.factProjectionInput()) rebuildItems()
    }

    private val scope = MainScope()
    private val foregroundCollectors = mutableMapOf<PageLoader, Job>()

    // Main owns subscriptions; IO checks an immutable publication plus each loader's real getter.
    @Volatile
    private var foregroundLoaders: Set<PageLoader> = emptySet()
    private val factCollectors = mutableMapOf<ReaderPage, Job>()
    private var matchingJob: Job? = null
    private var matchingRequested = false
    private var matchingSession: SharedDualPageSession? = null
    private var matchingSequence: Long? = null
    var matchingRounds: Int = 0
        internal set
    private val failedSamples = mutableSetOf<mihon.domain.reader.ReaderDualPageContentIdentity>()
    private var speculativeFailures = mutableSetOf<ReaderPageId>()
    private val preparationTargets = mutableMapOf<ReaderChapter, MutableSet<ReaderPageId>>()
    private data class OriginalCandidate(
        val page: ReaderPage,
        val captured: Pair<String, ReaderPageContentFact>,
        val publication: ReaderOriginalAnalysisPublication,
    )

    private fun publishOriginalBatch(owner: SharedDualPageSession, candidates: List<OriginalCandidate>): Boolean {
        val ordered = candidates.sortedBy { it.page.index }
        fun publish(index: Int): Boolean {
            if (index == ordered.size) return owner.acceptOriginalAnalyses(ordered.map { it.publication })
            val candidate = ordered[index]
            var accepted = false
            // Two admitted pages at most; lock in story order until the complete batch is published.
            return candidate.page.consumeContentFact(candidate.captured) { accepted = publish(index + 1) } && accepted
        }
        return candidates.isNotEmpty() && publish(0)
    }
    internal var spreadDecoderFactory = eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.nativeDecoder
    private val samplingMutex = Mutex()

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
                if (count == 0 && matchingRequested) startMatching()
            }
        }
    }

    internal fun matchingSettingChanged() {
        matchingJob?.cancel()
        failedSamples.clear()
        speculativeFailures = mutableSetOf()
        chapterPairings.values.forEach {
            it.session.configure(
                viewer.direction,
                automaticMatching = viewer.config.automaticSpreadMatching,
            )
        }
        rebuildItems()
    }

    internal fun session(chapter: ReaderChapter): SharedDualPageSession? = chapterPairings[chapter]?.session

    internal fun ownsSession(
        owner: SharedDualPageSession,
    ): Boolean = chapterPairings.values.any { it.session === owner }

    internal fun ownsPresentationLineage(owner: SharedDualPageSession): Boolean =
        chapterPairings.values.any { it.session.inheritsPresentationFrom(owner) }

    internal fun selectPage(page: ReaderPage, publishLayout: Boolean = false) {
        val entry = chapterPairings[page.chapter] ?: return
        if (entry.pages.getOrNull(page.index) !== page) return
        if (entry.session.goToPage(entry.session.identity.pageIds[page.index])) {
            preparationTargets.remove(page.chapter)
            if (matchingSession === entry.session) matchingJob?.cancel()
        }
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
        preparationTargets.remove(chapter)
        if (matchingSession === entry.session) matchingJob?.cancel()
        val target = entry.session.location?.pageId?.sourcePageIndex?.let(entry.pages::getOrNull) ?: return null
        rebuildItems(target)
        return target
    }

    /** A drag submits the same source intent as keys/taps; native slots reconcile when idle. */
    internal fun selectGesturePage(displayPage: DisplayPage, forward: Boolean, origin: DisplayPage?) {
        val chapter = displayPage.firstPage.chapter
        val entry = chapterPairings[chapter] ?: return
        if (displayPage.visiblePages.any { entry.pages.getOrNull(it.index) !== it }) return
        val owner = entry.session
        if (origin?.firstPage?.chapter === chapter) {
            if (!owner.navigate(forward)) return
        } else {
            // Entering another chapter still uses its native boundary entry rather than turning
            // again inside the newly selected chapter.
            val indices = displayPage.visiblePages.map { it.index }
            if (owner.location?.pageId?.sourcePageIndex !in indices) {
                val anchor = if (forward) indices.min() else indices.max()
                if (!owner.goToPage(owner.identity.pageIds[anchor], forward)) return
            }
        }
        preparationTargets.remove(chapter)
        if (matchingSession === owner) matchingJob?.cancel()
        pendingPairing = true
        startMatching()
    }

    internal fun close() {
        scope.cancel()
        projectedFactInputs.clear()
        presentationRefreshOwners.clear()
        chapterPairings.values.forEach {
            it.session.configurePresentationMode(mihon.domain.reader.ReaderPresentationMode.SINGLE)
        }
        matchingJob = null
        matchingSession = null
        failedSamples.clear()
        speculativeFailures = mutableSetOf()
        factCollectors.clear()
        foregroundCollectors.clear()
        foregroundLoaders = emptySet()
        preparationTargets.clear()
    }

    /** Keep decode facts and manual pairing while the same chapter page identities remain loaded. */
    fun setChapters(chapters: ViewerChapters, @Suppress("UNUSED_PARAMETER") forceTransition: Boolean) {
        this.chapters = chapters
        currentChapter = chapters.currChapter
        val window = listOfNotNull(chapters.prevChapter, chapters.currChapter, chapters.nextChapter)
        chapterPairings.keys.toList().filter { it !in window }.forEach { chapter ->
            chapterPairings.remove(chapter)?.session?.close()
            preparationTargets.remove(chapter)
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
            val admitted = store.ensure(
                chapter,
                pages,
            )
            admitted.session.apply {
                configure(
                    viewer.direction,
                    automaticMatching = viewer.config.automaticSpreadMatching,
                )
                configurePresentationMode(mihon.domain.reader.ReaderPresentationMode.DUAL)
            }
            pages.filter { it !in factCollectors }.forEach { page ->
                val admittedSnapshot = admitted.cachedFacts[page]
                factCollectors[page] = scope.launch {
                    var firstEmission = true
                    page.contentFact.collect { captured ->
                        viewer.traceStartup("ReaderR2.dualFact", chapter) {
                            val initial = firstEmission
                            firstEmission = false
                            if (chapter.pages !== pages || chapterPairings[chapter]?.pages !== pages) {
                                return@traceStartup
                            }
                            if (initial && admittedSnapshot == captured && admitted.pages === pages &&
                                chapterPairings[chapter] === admitted && pages.getOrNull(page.index) === page &&
                                admitted.cachedFacts[page] == captured && page.contentFact.value == captured &&
                                admitted.factRetrySequences[page] == captured.retrySequence &&
                                admitted.session.identity.content.getOrNull(page.index)?.contentVersion ==
                                page.encodedContentIdentity()
                            ) {
                                return@traceStartup
                            }
                            val entry = store.ensure(chapter, pages)
                            entry.session.configure(
                                viewer.direction,
                                automaticMatching = viewer.config.automaticSpreadMatching,
                            )
                            entry.session.configurePresentationMode(mihon.domain.reader.ReaderPresentationMode.DUAL)
                            if (captured.version != page.contentFact.value.version ||
                                captured.retrySequence != page.contentFact.value.retrySequence ||
                                captured.encodedVersion != page.contentFact.value.encodedVersion
                            ) {
                                return@traceStartup
                            }
                            val previousRetry = entry.factRetrySequences[page] ?: captured.retrySequence
                            if (captured.retrySequence > previousRetry) {
                                entry.factRetrySequences[page] = captured.retrySequence
                                matchingJob?.cancel()
                                failedSamples.clear()
                                speculativeFailures = mutableSetOf()
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
        projectAcceptedFact(owner)
        return true
    }

    private fun acceptFact(page: ReaderPage, fact: ReaderPageFact) {
        val pairing = chapterPairings[page.chapter] ?: return
        if (pairing.pages.getOrNull(page.index) !== page) return
        if (pairing.session.identity.content[page.index].contentVersion != page.encodedContentIdentity()) return
        val request = pairing.session.requestFact(pairing.session.identity.pageIds[page.index])
        if (!pairing.session.acceptFact(request, fact)) return
        projectAcceptedFact(pairing.session)
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

    private var presentationRefreshScheduled = false
    private val presentationRefreshOwners = mutableSetOf<SharedDualPageSession>()

    internal fun presentationReleased(owner: SharedDualPageSession) {
        if (!ownsPresentationLineage(owner)) return
        presentationRefreshOwners += owner
        if (presentationRefreshScheduled) return
        presentationRefreshScheduled = true
        scope.launch {
            kotlinx.coroutines.yield()
            presentationRefreshScheduled = false
            val released = presentationRefreshOwners.toSet()
            presentationRefreshOwners.clear()
            val refresh = chapterPairings.entries.mapNotNull { (chapter, entry) ->
                if (released.none(entry.session::inheritsPresentationFrom)) return@mapNotNull null
                chapter.pages?.takeIf { it === entry.pages }?.let { chapter to it }
            }
            if (refresh.isNotEmpty()) {
                // A fact snapshot deferred by the old native lease may have no later fact event.
                // Reuse the same store gate; other still-visible content remains protected.
                refresh.forEach { (chapter, pages) -> store.ensure(chapter, pages) }
                rebuildItems()
            }
        }
    }

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

    /** A boundary page may belong to both outgoing and incoming frames. */
    internal fun positionForPage(page: ReaderPage): Int {
        val owner = session(page.chapter)
        if (owner != null && owner.location?.pageId?.sourcePageIndex == page.index) {
            owner.targetFrameId?.let { frame ->
                return items.indexOfFirst {
                    it is DisplayPage && it.frameId == frame &&
                        it.firstPage.chapter === page.chapter
                }
            }
            val target = owner.targetUnit?.id
            return items.indexOfFirst {
                it is DisplayPage && it.firstPage.chapter === page.chapter && it.sharedUnitId(owner) == target
            }
        }
        return items.indexOfFirst { it is DisplayPage && it.containsPage(page) }
    }

    internal fun refreshPresentationFrames() = scheduleMatchingRefresh()

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

    /** Assemble in story order; RTL reverses the entire window, including its boundaries. */
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
        val replacement = (if (viewer.isRtl) logical.asReversed() else logical).toMutableList()
        // Capture the inputs actually assembled, before native refresh/notify can admit newer facts.
        // A gesture return above never consumes its pending inputs.
        val consumedInputs = window.mapNotNull { chapter ->
            chapterPairings[chapter]?.session?.let { it to it.factProjectionInput() }
        }.toMap()
        if (items == replacement) {
            recordProjectedFacts(consumedInputs)
            // Metadata can change certainty and the presentation capability without changing physical slots.
            viewer.refreshPresentation()
            return
        }
        // Explicit jumps and source turns carry user reading intent through the rebuilt group.
        val layoutOnly = targetPage == null && selected is DisplayPage && selected.firstPage.chapter === currentChapter
        viewer.replacePairing(anchor, layoutOnly, retainedTransition) {
            items = replacement
            recordProjectedFacts(consumedInputs)
            notifyDataSetChanged()
        }
        viewer.logNavigationState("rebuild")
    }

    internal fun publishPendingPairing() {
        if (pendingPairing) rebuildItems()
    }

    internal fun requirePairing(displayPage: DisplayPage): ReaderPairingCertification? {
        val owner = chapterPairings[displayPage.firstPage.chapter]?.session ?: return null
        val ids = displayPage.visiblePages.map { owner.identity.pageIds[it.index] }
        val target = owner.location?.pageId?.takeIf { it in ids } ?: ids.firstOrNull() ?: return null
        preparationTargets.getOrPut(displayPage.firstPage.chapter) { linkedSetOf() } += target
        val result = owner.certifyPairing(target)
        if (result !is ReaderPairingCertification.Certified) startMatching()
        return result
    }

    internal fun retryPairing(page: ReaderPage) {
        chapterPairings[page.chapter]?.session?.retryFacts()
        matchingJob?.cancel()
        failedSamples.clear()
        speculativeFailures = mutableSetOf()
        startMatching()
        rebuildItems()
    }

    private fun targetNeeds(
        chapter: ReaderChapter,
        owner: SharedDualPageSession,
    ): Set<mihon.domain.reader.ReaderPairingFactNeed> {
        if (owner.isClosed) return emptySet()
        val target = owner.location?.pageId ?: return emptySet()
        return when (val result = owner.certifyPairing(target)) {
            is ReaderPairingCertification.NeedFacts -> result.facts.toSet()
            is ReaderPairingCertification.RetryableBlocked -> result.facts.filter { need ->
                need.kind == ReaderPairingFactKind.DIMENSIONS &&
                    owner.originalAnalysis(
                        need.pageId,
                    ) is mihon.domain.reader.ReaderPageAnalysisEvidence.NotAnalyzed &&
                    owner.identity.content.first { it.pageId == need.pageId } !in failedSamples
            }.toSet()
            is ReaderPairingCertification.Certified -> emptySet()
        }
    }

    private fun preparationNeeds(
        chapter: ReaderChapter,
        owner: SharedDualPageSession,
    ): Set<mihon.domain.reader.ReaderPairingFactNeed> {
        val targets = preparationTargets.getOrPut(chapter) { linkedSetOf() }
        owner.location?.pageId?.let { targets += it }
        val failedIds = failedSamples.map { it.pageId }.toSet()
        val certNeeds = targets.flatMap { target ->
            when (val result = owner.certifyPairing(target)) {
                is ReaderPairingCertification.NeedFacts -> result.facts
                is ReaderPairingCertification.RetryableBlocked -> result.facts.filter { need ->
                    need.kind == ReaderPairingFactKind.DIMENSIONS &&
                        owner.originalAnalysis(
                            need.pageId,
                        ) is mihon.domain.reader.ReaderPageAnalysisEvidence.NotAnalyzed &&
                        need.pageId !in failedIds
                }
                is ReaderPairingCertification.Certified -> emptySet()
            }
        }
        val spreadNeeds = owner.spreadSampleDependencies()
            .filter {
                it !in failedIds &&
                    owner.originalAnalysis(it) is mihon.domain.reader.ReaderPageAnalysisEvidence.NotAnalyzed
            }
            .map { mihon.domain.reader.ReaderPairingFactNeed(it, ReaderPairingFactKind.ORIGINAL_ANALYSIS) }
        return (certNeeds + spreadNeeds).toSet()
    }

    /** Required evidence consumes the normal producer and the same explicit-location budget. */
    private fun startMatching() {
        if (matchingJob?.isCompleted == false ||
            eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value > 0
        ) {
            matchingRequested = true
            return
        }
        matchingRequested = false
        val ordered = listOfNotNull(currentChapter) + preparationTargets.keys.filter { it !== currentChapter }
        val selected = ordered.firstNotNullOfOrNull { chapter ->
            val owner = chapterPairings[chapter]?.session ?: return@firstNotNullOfOrNull null
            val excluded = if (matchingSession === owner && matchingSequence == owner.location?.sequence) {
                speculativeFailures
            } else {
                emptySet()
            }
            val real = targetNeeds(chapter, owner).flatMap { listOfNotNull(it.pageId, it.secondPageId) }.toSet()
            preparationNeeds(chapter, owner).takeIf { needs ->
                needs.any {
                    it.kind != ReaderPairingFactKind.PAIRING_RULES &&
                        (it.pageId !in excluded || it.pageId in real || it.secondPageId in real)
                }
            }
                ?.let { Triple(chapter, owner, it) }
        } ?: return
        val (chapter, owner, _) = selected
        val sequence = owner.location?.sequence
        if (matchingSession !== owner || matchingSequence != sequence) {
            matchingSession = owner
            matchingSequence = sequence
            matchingRounds = 0
            speculativeFailures = mutableSetOf()
        }
        val excludedSpeculative = speculativeFailures
        val budget = owner.factBudget
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val completed = kotlinx.coroutines.channels.Channel<Pair<ReaderPageId, OriginalCandidate?>>(
                ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS,
            )
            val active = mutableMapOf<ReaderPageId, Job>()
            val speculative = mutableSetOf<ReaderPageId>()
            while (chapterPairings[chapter]?.session === owner && owner.location?.sequence == sequence) {
                val allNeeds = preparationNeeds(chapter, owner)
                val target = if (chapter === currentChapter) owner.location?.pageId else null
                val targetCert = target?.let { owner.certifyPairing(it) }
                val failedIds = failedSamples.map { it.pageId }.toSet() + excludedSpeculative
                val plan = if (targetCert != null && targetCert !is ReaderPairingCertification.Certified) {
                    ReaderPairingPrefetchPlan.classify(
                        targetCert,
                        owner.identity.pageIds,
                        owner::originalAnalysis,
                        ReaderDualPageFactLimits.REQUIRED_LOOKAHEAD_K,
                        failedIds,
                    )
                } else {
                    ReaderPairingPrefetchPlan.ClassifiedPlan(emptyList(), emptyList())
                }
                val requiredPages = plan.realMissing + plan.speculative
                val optionalPages = allNeeds.filter { it.kind != ReaderPairingFactKind.PAIRING_RULES }
                    .flatMap { listOfNotNull(it.pageId, it.secondPageId) }
                    .distinct()
                    .filter { it !in requiredPages && it !in excludedSpeculative }
                if (requiredPages.isEmpty() && optionalPages.isEmpty() && active.isEmpty()) break

                val isForegroundBusy = foregroundLoaders.any { it.hasForegroundWork } ||
                    eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.foregroundWork.value > 0
                val isTemporaryPaused = viewer.activity.optionalWorkUnderMemoryPressure.value || isForegroundBusy
                if (isTemporaryPaused) {
                    if (active.isEmpty()) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (budget.remainingWaitMillis(now) <= 0) {
                            for (id in plan.realMissing + optionalPages) {
                                owner.requestOriginalAnalysis(id)?.let {
                                    owner.failOriginalAnalysis(it, ReaderAnalysisFailure.RESOURCE)
                                }
                            }
                            viewer.refreshPresentation()
                        }
                        break
                    }
                    val (id, candidate) = completed.receive()
                    active.remove(id)
                    speculative.remove(id)
                    chapter.pages?.let { store.ensure(chapter, it) }
                    if (chapterPairings[chapter]?.session !== owner || owner.location?.sequence != sequence) {
                        active.values.forEach { it.cancel() }
                        matchingRequested = true
                        scheduleMatchingRefresh()
                        break
                    }
                    if (candidate != null && publishOriginalBatch(owner, listOf(candidate))) {
                        scheduleMatchingRefresh()
                    }
                    if (target != null && owner.certifyPairing(target) is ReaderPairingCertification.Certified) {
                        excludedSpeculative += speculative
                        speculative.mapNotNull(active::get).forEach { it.cancel() }
                    }
                    continue
                }

                val now = android.os.SystemClock.uptimeMillis()
                val available = (ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS - active.size).coerceAtLeast(0)
                speculative.removeAll(plan.realMissing.toSet())
                val admittedReal = budget.reserve(
                    plan.realMissing.filter { it !in active }.take(available),
                    now,
                    required = true,
                )
                val admittedSpeculative = budget.reserve(
                    plan.speculative.filter { it !in active }.take(available - admittedReal.size),
                    now,
                    required = true,
                    speculative = true,
                )
                val admittedRequired = admittedReal + admittedSpeculative
                val admittedOptional = if (optionalPages.isNotEmpty()) {
                    budget.reserve(
                        optionalPages.filter {
                            it !in active
                        }.take(available - admittedRequired.size),
                        now,
                        required = false,
                    )
                } else {
                    emptyList()
                }
                val admitted = admittedRequired + admittedOptional
                if (admitted.isNotEmpty() && targetCert != null &&
                    targetCert !is ReaderPairingCertification.Certified
                ) {
                    matchingRounds++
                }
                if (admitted.isEmpty() && active.isEmpty()) {
                    if (isTemporaryPaused &&
                        budget.remainingWaitMillis(now) > 0
                    ) {
                        break
                    }
                    for (id in plan.realMissing + optionalPages) {
                        owner.requestOriginalAnalysis(id)?.let {
                            owner.failOriginalAnalysis(it, ReaderAnalysisFailure.RESOURCE)
                        }
                    }
                    viewer.refreshPresentation()
                    break
                }
                suspend fun readOriginal(
                    id: ReaderPageId,
                    onDeferred: () -> Unit = {},
                ): OriginalCandidate? {
                    if (chapterPairings[chapter]?.session !== owner || owner.location?.sequence != sequence) {
                        return null
                    }
                    val speculativeRequest = id in admittedSpeculative
                    if (speculativeRequest && target != null &&
                        owner.certifyPairing(target) is ReaderPairingCertification.Certified
                    ) {
                        return null
                    }
                    val page = chapter.pages?.getOrNull(id.sourcePageIndex) ?: return null
                    val permit = ReaderDualPageFactReadPermit(budget, id, android.os.SystemClock::uptimeMillis)
                    var request = owner.requestOriginalAnalysis(id) ?: return null
                    fun failAnalysis(
                        request: mihon.domain.reader.ReaderSpreadSampleRequest,
                        reason: ReaderAnalysisFailure,
                    ) {
                        if (owner.isClosed || chapterPairings[chapter]?.session !== owner) return
                        val realNow = targetNeeds(chapter, owner).any { it.pageId == id || it.secondPageId == id }
                        if (speculativeRequest && !realNow) {
                            excludedSpeculative += id
                        } else {
                            owner.failOriginalAnalysis(request, reason)
                        }
                    }
                    var pausedByMemoryPressure = false
                    var pausedByForeground = false
                    try {
                        return kotlinx.coroutines.withTimeout(
                            budget.remainingWaitMillis(android.os.SystemClock.uptimeMillis()),
                        ) {
                            if (page.status != eu.kanade.tachiyomi.source.model.Page.State.Ready) {
                                chapter.pageLoader?.loadFactPage(page, permit)
                            }
                            withContext(Dispatchers.IO) {
                                page.publishHeaderFact(readPermit = permit)
                                if (page.pageFact.value.state ==
                                    ReaderPageFactState.FAILED
                                ) {
                                    page.publishCompleteHeaderFact(permit)
                                }
                            }
                            if (chapterPairings[chapter]?.session !== owner ||
                                owner.location?.sequence != sequence
                            ) {
                                return@withTimeout null
                            }
                            store.ensure(chapter, requireNotNull(chapter.pages))
                            if (chapterPairings[chapter]?.session !== owner) return@withTimeout null
                            val captured = page.captureContentFact()
                            request = owner.requestOriginalAnalysis(id) ?: return@withTimeout null
                            val current = request.content
                            val header = captured.second.fact
                            if (captured.first != current.contentVersion || header.width <= 0 ||
                                header.height <= 0
                            ) {
                                failAnalysis(request, ReaderAnalysisFailure.READ)
                                if (!speculativeRequest) failedSamples += current
                                return@withTimeout null
                            }
                            val fact = ReaderPageFact.dimensions(
                                header.width,
                                header.height,
                                rotation = current.rotation,
                                sourceOrientation = current.sourceOrientation,
                            )
                            val raw = current.sourceOrientation.orientedSize(fact.width, fact.height)
                            val samples = samplingMutex.withLock {
                                withContext(Dispatchers.IO) {
                                    eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.sampleOriginal(
                                        page,
                                        request,
                                        raw.width,
                                        raw.height,
                                        spreadDecoderFactory,
                                        canContinue = {
                                            val pressured = viewer.activity.optionalWorkUnderMemoryPressure.value
                                            val foregroundBusy = foregroundLoaders.any { it.hasForegroundWork } ||
                                                ReaderPageImageView.foregroundWork.value > 0
                                            val timeRemaining =
                                                budget.remainingWaitMillis(android.os.SystemClock.uptimeMillis()) > 0
                                            if (pressured) {
                                                pausedByMemoryPressure = true
                                            }
                                            if (foregroundBusy) {
                                                pausedByForeground = true
                                            }
                                            !pressured && !foregroundBusy && timeRemaining
                                        },
                                        readPermit = permit,
                                    )
                                }
                            }
                            if (samples == null) {
                                failAnalysis(
                                    request,
                                    if (permit.exhausted) {
                                        ReaderAnalysisFailure.RESOURCE
                                    } else {
                                        ReaderAnalysisFailure.UNSUPPORTED
                                    },
                                )
                                null
                            } else if (owner.ownsOriginalAnalysisRequest(request)) {
                                OriginalCandidate(
                                    page,
                                    captured,
                                    ReaderOriginalAnalysisPublication(request, fact, samples),
                                )
                            } else {
                                null
                            }
                        }
                    } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                        if (kotlinx.coroutines.currentCoroutineContext().isActive &&
                            owner.location?.sequence == sequence
                        ) {
                            failAnalysis(request, ReaderAnalysisFailure.RESOURCE)
                            return null
                        } else {
                            throw error
                        }
                    } catch (error: eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderOptionalWorkPaused) {
                        val remaining = budget.remainingWaitMillis(android.os.SystemClock.uptimeMillis())
                        val seqMatches = (owner.location?.sequence ?: 0L) == sequence
                        val isTemporaryPause = pausedByMemoryPressure || pausedByForeground
                        if (isTemporaryPause && remaining > 0 && !permit.exhausted && seqMatches) {
                            onDeferred()
                            return null
                        }
                        failAnalysis(request, ReaderAnalysisFailure.RESOURCE)
                        return null
                    } catch (error: CancellationException) {
                        if (kotlinx.coroutines.currentCoroutineContext().isActive && permit.exhausted &&
                            owner.location?.sequence == sequence
                        ) {
                            failAnalysis(request, ReaderAnalysisFailure.RESOURCE)
                            return null
                        } else {
                            throw error
                        }
                    } catch (_: Exception) {
                        failAnalysis(
                            request,
                            if (permit.exhausted) ReaderAnalysisFailure.RESOURCE else ReaderAnalysisFailure.READ,
                        )
                        return null
                    }
                }
                for (id in admitted) {
                    var candidate: OriginalCandidate? = null
                    var isDeferred = false
                    val child = launch { candidate = readOriginal(id, onDeferred = { isDeferred = true }) }
                    active[id] = child
                    child.invokeOnCompletion {
                        // Includes cancellation before launch. A running JNI call must really exit first.
                        if (isDeferred) {
                            budget.defer(id)
                        } else {
                            budget.complete(id, 0, cacheHit = true)
                        }
                        completed.trySend(id to candidate)
                    }
                    if (id in plan.speculative) speculative += id
                }
                val (id, candidate) = completed.receive()
                active.remove(id)
                speculative.remove(id)
                // Another completed page may already have been admitted before its physical bytes changed.
                // Reuse the chapter's content gate before extending that owner's evidence.
                chapter.pages?.let { store.ensure(chapter, it) }
                if (chapterPairings[chapter]?.session !== owner || owner.location?.sequence != sequence) {
                    active.values.forEach { it.cancel() }
                    matchingRequested = true
                    scheduleMatchingRefresh()
                    break
                }
                if (candidate != null && publishOriginalBatch(owner, listOf(candidate))) {
                    scheduleMatchingRefresh()
                }
                if (target != null && owner.certifyPairing(target) is ReaderPairingCertification.Certified) {
                    // Cancellation stops queued/next regions. A running JNI call keeps its child alive.
                    excludedSpeculative += speculative
                    speculative.mapNotNull(active::get).forEach { it.cancel() }
                }
            }
        }
        matchingJob = job
        eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.observe(job)
        job.invokeOnCompletion {
            scope.launch {
                if (matchingJob === job) {
                    matchingJob = null
                    if (matchingRequested) startMatching()
                }
            }
        }
        job.start()
    }

    private var matchingRefreshScheduled = false

    private fun scheduleMatchingRefresh() {
        if (matchingRefreshScheduled) return
        matchingRefreshScheduled = true
        scope.launch {
            kotlinx.coroutines.yield()
            matchingRefreshScheduled = false
            rebuildItems()
        }
    }

    private fun isReadyForDisplay(chapter: ReaderChapter?): Boolean =
        chapter?.state is ReaderChapter.State.Loaded

    private fun buildDisplayPages(pages: List<ReaderPage>, session: SharedDualPageSession): List<DisplayPage> {
        session.preparePresentationFrame()
        return session.presentationFrames.map { frame ->
            val unit = frame.unit
            if (unit.pageIds.size == 1) {
                DisplayPage.Single(
                    pages[unit.pageIds.single().sourcePageIndex],
                    unit.slots.single().position,
                    frame.frameId,
                )
            } else {
                DisplayPage.Double(
                    rightPage = pages[
                        unit.slots.single {
                            it.position == mihon.domain.reader.ReaderPortraitSingleSlot.RIGHT
                        }.pageId.sourcePageIndex,
                    ],
                    leftPage = pages[
                        unit.slots.single {
                            it.position == mihon.domain.reader.ReaderPortraitSingleSlot.LEFT
                        }.pageId.sourcePageIndex,
                    ],
                    frameId = frame.frameId,
                )
            }
        }
    }
}
