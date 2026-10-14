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
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.domain.reader.ChapterPairingSnapshot
import mihon.domain.reader.ReaderAnalysisFailure
import mihon.domain.reader.ReaderChapterBoundary
import mihon.domain.reader.ReaderDualPageFactReadPermit
import mihon.domain.reader.ReaderDualPageFactRequest
import mihon.domain.reader.ReaderOriginalAnalysisPublication
import mihon.domain.reader.ReaderPageFact
import mihon.domain.reader.ReaderPageFactState
import mihon.domain.reader.ReaderPairingAdjustment
import mihon.domain.reader.ReaderPairingCertification
import mihon.domain.reader.ReaderPairingFactKind
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
    private val scope = MainScope()
    private val foregroundCollectors = mutableMapOf<PageLoader, Job>()

    // Main owns subscriptions; IO checks an immutable publication plus each loader's real getter.
    @Volatile
    private var foregroundLoaders: Set<PageLoader> = emptySet()
    private val factCollectors = mutableMapOf<ReaderPage, Job>()
    private var matchingJob: Job? = null
    private var matchingSession: SharedDualPageSession? = null
    private val failedSamples = mutableSetOf<mihon.domain.reader.ReaderDualPageContentIdentity>()
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
            preparationTargets.remove(displayPage.firstPage.chapter)
            if (matchingSession === owner) matchingJob?.cancel()
        }
        pendingPairing = true
        startMatching()
    }

    internal fun close() {
        scope.cancel()
        presentationRefreshOwners.clear()
        chapterPairings.values.forEach {
            it.session.configurePresentationMode(mihon.domain.reader.ReaderPresentationMode.SINGLE)
        }
        matchingJob = null
        matchingSession = null
        failedSamples.clear()
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
            store.ensure(
                chapter,
                pages,
            ).session.apply {
                configure(
                    viewer.direction,
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
                            viewer.direction,
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
        startMatching()
        rebuildItems()
    }

    private fun preparationNeeds(
        chapter: ReaderChapter,
        owner: SharedDualPageSession,
    ): Set<mihon.domain.reader.ReaderPairingFactNeed> {
        val targets = preparationTargets.getOrPut(chapter) { linkedSetOf() }
        owner.location?.pageId?.let { targets += it }
        return targets.flatMap { target ->
            when (val result = owner.certifyPairing(target)) {
                is ReaderPairingCertification.NeedFacts -> result.facts
                is ReaderPairingCertification.RetryableBlocked -> result.facts.filter { need ->
                    need.kind == ReaderPairingFactKind.DIMENSIONS &&
                        owner.originalAnalysis(
                            need.pageId,
                        ) is mihon.domain.reader.ReaderPageAnalysisEvidence.NotAnalyzed &&
                        owner.identity.content.first { it.pageId == need.pageId } !in failedSamples
                }
                is ReaderPairingCertification.Certified -> emptySet()
            }
        }.toSet()
    }

    /** Required evidence consumes the normal producer and the same explicit-location budget. */
    private fun startMatching() {
        if (matchingJob?.isCompleted == false ||
            eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.optionalWork.value > 0
        ) {
            return
        }
        val ordered = listOfNotNull(currentChapter) + preparationTargets.keys.filter { it !== currentChapter }
        val selected = ordered.firstNotNullOfOrNull { chapter ->
            val owner = chapterPairings[chapter]?.session ?: return@firstNotNullOfOrNull null
            preparationNeeds(chapter, owner).takeIf { needs ->
                needs.any {
                    it.kind !=
                        ReaderPairingFactKind.PAIRING_RULES
                }
            }
                ?.let { Triple(chapter, owner, it) }
        } ?: return
        val (chapter, owner, _) = selected
        matchingSession = owner
        val sequence = owner.location?.sequence
        val budget = owner.factBudget
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            while (chapterPairings[chapter]?.session === owner && owner.location?.sequence == sequence) {
                val needs = preparationNeeds(chapter, owner)
                val neededPages = needs.filter { it.kind != ReaderPairingFactKind.PAIRING_RULES }
                    .flatMap { listOfNotNull(it.pageId, it.secondPageId) }.distinct()
                if (neededPages.isEmpty()) break
                val admitted = budget.reserve(neededPages, android.os.SystemClock.uptimeMillis())
                if (admitted.isEmpty()) {
                    for (id in neededPages) {
                        owner.requestOriginalAnalysis(id)?.let {
                            owner.failOriginalAnalysis(it, ReaderAnalysisFailure.RESOURCE)
                        }
                    }
                    viewer.refreshPresentation()
                    break
                }
                val results = admitted.map { id ->
                    async {
                        val page = chapter.pages?.getOrNull(id.sourcePageIndex) ?: return@async null
                        val permit = ReaderDualPageFactReadPermit(budget, id, android.os.SystemClock::uptimeMillis)
                        var request = owner.requestOriginalAnalysis(id) ?: return@async null
                        try {
                            kotlinx.coroutines.withTimeout(
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
                                    owner.failOriginalAnalysis(request, ReaderAnalysisFailure.READ)
                                    failedSamples += current
                                    return@withTimeout null
                                }
                                val fact = ReaderPageFact.dimensions(
                                    header.width,
                                    header.height,
                                    rotation = current.rotation,
                                    sourceOrientation = current.sourceOrientation,
                                )
                                val raw = current.sourceOrientation.orientedSize(fact.width, fact.height)
                                val samples = withContext(Dispatchers.IO) {
                                    eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.sampleOriginal(
                                        page,
                                        request,
                                        raw.width,
                                        raw.height,
                                        spreadDecoderFactory,
                                        canContinue = {
                                            !viewer.activity.optionalWorkUnderMemoryPressure.value &&
                                                budget.remainingWaitMillis(android.os.SystemClock.uptimeMillis()) > 0
                                        },
                                        readPermit = permit,
                                    )
                                }
                                if (samples == null) {
                                    owner.failOriginalAnalysis(
                                        request,
                                        if (permit.exhausted) {
                                            ReaderAnalysisFailure.RESOURCE
                                        } else {
                                            ReaderAnalysisFailure.UNSUPPORTED
                                        },
                                    )
                                    null
                                } else if (owner.ownsOriginalAnalysisRequest(request) &&
                                    page.consumeContentFact(captured) {}
                                ) {
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
                                owner.failOriginalAnalysis(request, ReaderAnalysisFailure.RESOURCE)
                                null
                            } else {
                                throw error
                            }
                        } catch (error: eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderOptionalWorkPaused) {
                            owner.failOriginalAnalysis(request, ReaderAnalysisFailure.RESOURCE)
                            null
                        } catch (error: CancellationException) {
                            if (kotlinx.coroutines.currentCoroutineContext().isActive && permit.exhausted &&
                                owner.location?.sequence == sequence
                            ) {
                                owner.failOriginalAnalysis(request, ReaderAnalysisFailure.RESOURCE)
                                null
                            } else {
                                throw error
                            }
                        } catch (_: Exception) {
                            owner.failOriginalAnalysis(
                                request,
                                if (permit.exhausted) ReaderAnalysisFailure.RESOURCE else ReaderAnalysisFailure.READ,
                            )
                            null
                        } finally {
                            budget.complete(id, 0, cacheHit = true)
                        }
                    }
                }.awaitAll().filterNotNull()
                if (chapterPairings[chapter]?.session !== owner || owner.location?.sequence != sequence) break
                if (results.isNotEmpty()) publishOriginalBatch(owner, results)
                rebuildItems()
                if (results.isEmpty()) break
            }
        }
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
        return session.presentationFrameProjection.map { unit ->
            if (unit.pageIds.size == 1) {
                DisplayPage.Single(pages[unit.pageIds.single().sourcePageIndex], unit.slots.single().position)
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
                )
            }
        }
    }
}
