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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import mihon.domain.reader.ChapterPairingSnapshot
import mihon.domain.reader.ReaderChapterBoundary
import mihon.domain.reader.ReaderDualPageFactReadPermit
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
    private val scope = MainScope()
    private val factCollectors = mutableMapOf<ReaderPage, Job>()
    private var supplementSession: SharedDualPageSession? = null
    private var supplementSequence: Long? = null
    private var supplementWorkRevision: Long? = null
    private var supplementJob: Job? = null
    private var matchingJob: Job? = null
    private var matchingSession: SharedDualPageSession? = null
    private val failedSamples = mutableSetOf<mihon.domain.reader.ReaderDualPageContentIdentity>()
    internal var spreadDecoderFactory = eu.kanade.tachiyomi.ui.reader.viewer.AndroidReaderSpreadSampler.nativeDecoder

    internal fun matchingSettingChanged() {
        matchingJob?.cancel()
        matchingJob = null
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

    internal fun selectPage(page: ReaderPage) {
        val entry = chapterPairings[page.chapter] ?: return
        if (entry.pages.getOrNull(page.index) !== page) return
        entry.session.goToPage(entry.session.identity.pageIds[page.index])
        startSupplement()
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
        supplementJob = null
        supplementSession = null
    }

    /** Keep decode facts and manual pairing while the same chapter page identities remain loaded. */
    fun setChapters(chapters: ViewerChapters, @Suppress("UNUSED_PARAMETER") forceTransition: Boolean) {
        this.chapters = chapters
        currentChapter = chapters.currChapter
        val window = listOfNotNull(chapters.prevChapter, chapters.currChapter, chapters.nextChapter)
        chapterPairings.keys.toList().filter { it !in window }.forEach { chapter ->
            chapterPairings.remove(chapter)?.session?.close()
        }
        for (chapter in window) {
            val pages = chapter.pages
            if (pages == null) {
                chapterPairings.remove(chapter)?.session?.close()
                continue
            }
            if (store.requiresRestoration && chapterPairings[chapter]?.pages !== pages) {
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
                            matchingJob = null
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

    fun refreshPairing() = rebuildItems()

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
        startSupplement()
        startMatching()
        val selected = items.getOrNull(viewer.pager.currentItem)
        val currentPairing = chapterPairings[chapters.currChapter]
        val logicalTarget = currentPairing?.session?.location?.pageId?.sourcePageIndex
            ?.let { currentPairing.pages.getOrNull(it) }
        val anchor = targetPage ?: logicalTarget ?: when (selected) {
            is DisplayPage -> selected.firstPage
            is ChapterTransition -> selected.loadedEntryPage()
            else -> null
        }
        val logical = buildList<Any> {
            chapters.prevChapter?.let { chapter ->
                chapterPairings[chapter]?.let { addAll(buildDisplayPages(it.pages, it.session)) }
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
                chapterPairings[chapter]?.let { addAll(buildDisplayPages(it.pages, it.session)) }
            }
        }
        val replacement = logical.asReversed().toMutableList()
        if (items == replacement) {
            // Metadata can change certainty and the presentation capability without changing physical slots.
            viewer.refreshPresentation()
            return
        }
        val layoutOnly = selected is DisplayPage && selected.firstPage.chapter === currentChapter
        viewer.replacePairing(anchor, layoutOnly) {
            items = replacement
            notifyDataSetChanged()
        }
    }

    /** No materialization here: required adjacent edges consume only already-ready original streams. */
    private fun startMatching() {
        val chapter = currentChapter ?: return
        val owner = chapterPairings[chapter]?.session ?: return
        if (matchingSession !== owner || !viewer.config.automaticSpreadMatching) {
            matchingJob?.cancel()
            matchingJob = null
            failedSamples.clear()
            matchingSession = owner
        }
        if (!viewer.config.automaticSpreadMatching || matchingJob?.isActive == true ||
            owner.spreadSampleDependencies().isEmpty()
        ) {
            return
        }
        matchingJob = scope.launch {
            var pendingContent = owner.spreadSampleDependencies().firstOrNull()?.let { id ->
                owner.identity.content.single { it.pageId == id }
            }
            try {
                kotlinx.coroutines.withTimeout(owner.factBudget.remainingWaitMillis(System.nanoTime() / 1_000_000L)) {
                    while (chapterPairings[chapter]?.session === owner) {
                        val pageId = owner.spreadSampleDependencies().firstOrNull() ?: break
                        val content = owner.identity.content.single { it.pageId == pageId }
                        pendingContent = content
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
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                if (chapterPairings[chapter]?.session === owner && matchingJob === coroutineContext[Job]) {
                    pendingContent?.let { failedSamples += it }
                }
            }
        }
    }

    /** The shared owner decides dependencies and budget; this adapter only leases the existing loader. */
    private fun startSupplement() {
        val chapter = currentChapter ?: return
        val owner = chapterPairings[chapter]?.session ?: return
        val sequence = owner.location?.sequence ?: return
        val workRevision = owner.factWorkRevision
        if (supplementSession === owner && supplementSequence == sequence &&
            supplementWorkRevision == workRevision && supplementJob?.isActive == true
        ) {
            return
        }
        supplementJob?.cancel()
        supplementSession = owner
        supplementSequence = sequence
        supplementWorkRevision = workRevision
        val loader = chapter.pageLoader ?: return
        val budget = owner.factBudget
        val clock = { System.nanoTime() / 1_000_000L }
        supplementJob = scope.launch {
            try {
                val wait = budget.remainingWaitMillis(clock())
                if (wait == 0L) return@launch
                withTimeout(wait) {
                    while (chapterPairings[chapter]?.session === owner && owner.location?.sequence == sequence &&
                        owner.factWorkRevision == workRevision
                    ) {
                        val admitted = budget.reserve(owner.factDependencies(), clock())
                        if (admitted.isEmpty()) break
                        coroutineScope {
                            admitted.forEach { pageId ->
                                launch {
                                    try {
                                        if (chapterPairings[chapter]?.session !== owner ||
                                            owner.location?.sequence != sequence
                                        ) {
                                            return@launch
                                        }
                                        val request = owner.requestFact(pageId)
                                        val page = chapter.pages?.getOrNull(pageId.sourcePageIndex) ?: return@launch
                                        loader.loadFactPage(page, ReaderDualPageFactReadPermit(budget, pageId, clock))
                                        val captured = page.contentFact.value
                                        if (chapterPairings[chapter]?.session === owner &&
                                            owner.identity.content[page.index].contentVersion ==
                                            page.encodedContentIdentity()
                                        ) {
                                            owner.acceptOrientedFact(request, captured.fact)
                                        }
                                    } finally {
                                        // Actual network bytes were metered by the permit, never inferred from file
                                        // length.
                                        budget.complete(pageId, 0, cacheHit = true)
                                    }
                                }
                            }
                        }
                        rebuildItems()
                    }
                }
            } catch (_: TimeoutCancellationException) {
                budget.close()
            } catch (error: CancellationException) {
                throw error
            }
        }
    }

    private fun isReadyForDisplay(chapter: ReaderChapter?): Boolean =
        chapter?.state is ReaderChapter.State.Loaded &&
            (!store.requiresRestoration || chapterPairings[chapter]?.pages === chapter.pages)

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
