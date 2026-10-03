package mihon.desktop.ui.reader

import cafe.adriel.voyager.core.model.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.desktop.reader.DesktopChapterPairingCoordinator
import mihon.desktop.reader.DesktopReaderChapterContext
import mihon.desktop.reader.DesktopReaderRuntime
import mihon.desktop.reader.DesktopReaderSessionState
import mihon.desktop.reader.ReaderBackgroundTheme
import mihon.desktop.reader.ReaderColorFilter
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.WebtoonSidePadding
import mihon.desktop.reader.ZoomState
import mihon.desktop.reader.dualPageFromViewerFlags
import mihon.desktop.reader.readingModeFromViewerFlags
import mihon.desktop.reader.viewerFlagsFollowingGlobal
import mihon.desktop.reader.viewerFlagsWithDualPage
import mihon.desktop.reader.viewerFlagsWithReadingMode
import mihon.desktop.ui.reader.presentation.DisplayUnitId
import mihon.desktop.ui.reader.presentation.VisiblePageSet
import mihon.desktop.ui.reader.presentation.WebtoonViewportUpdate
import mihon.desktop.ui.reader.presentation.dualDisplayUnitIndexForSourcePage
import mihon.desktop.ui.reader.presentation.firstDualPageIndex
import mihon.domain.reader.AdaptiveReaderLayout
import mihon.domain.reader.ReaderChapterModel
import mihon.domain.reader.ReaderChapterState
import mihon.domain.reader.ReaderChapterTransitionModel
import mihon.domain.reader.ReaderNavigationCommand
import mihon.domain.reader.ReaderTransitionDirection
import mihon.domain.reader.StaleChapterPairingException
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderSessionSnapshot
import java.util.concurrent.atomic.AtomicBoolean

/** UI preferences and presentation state for one long-lived Desktop reader session. */
class ReaderScreenModel(
    chapterTitle: String = "",
    initialPage: Int = 0,
    chapterId: Long = 0L,
    private val isWebtoon: Boolean = false,
    sourceId: Long = 0L,
    chapterUrl: String = "",
    mangaTitle: String = "",
    chapterNumber: Double = 0.0,
    chapterIndex: Int = 0,
    wasRead: Boolean = false,
    localChapterPath: String? = null,
    isDownloaded: Boolean = false,
    val mangaViewerFlags: Long = 0L,
    private val dualPageOverride: Boolean? = null,
    prefs: ReaderPreferences = ReaderPreferences(),
    initialSessionState: DesktopReaderSessionState = DesktopReaderSessionState(
        context = DesktopReaderChapterContext(
            chapterId = chapterId,
            sourceId = sourceId,
            chapterUrl = chapterUrl,
            mangaTitle = mangaTitle,
            chapterTitle = chapterTitle,
            chapterNumber = chapterNumber,
            chapterIndex = chapterIndex,
            initialPage = initialPage,
            wasRead = wasRead,
            localChapterPath = localChapterPath,
            isDownloaded = isDownloaded,
        ),
        snapshot = ReaderSessionSnapshot.initial(ReaderChapterId(chapterId)),
    ),
    private val persistViewerFlags: suspend (mangaId: Long, flags: Long) -> Unit = { _, _ -> },
    private val onViewportSettled: (Set<ReaderPageId>, ReaderPageId) -> Unit = { _, _ -> },
    private val onViewportLayoutSettled: (Set<ReaderPageId>, ReaderPageId) -> Unit = { _, _ -> },
    private val onPageRetry: (ReaderPageId) -> Unit = {},
    private val onChapterRetry: () -> Unit = {},
    private val onChapterActivated: (DesktopReaderChapterContext) -> DesktopReaderSessionState? = { null },
    private val onNextChapterPrefetchChanged: (DesktopReaderChapterContext?, Int) -> Unit = { _, _ -> },
    internal val runtime: DesktopReaderRuntime? = null,
    private val ownedRuntimeScope: CoroutineScope? = null,
    private val onProductionClosed: () -> Unit = {},
    private val pairingCoordinator: DesktopChapterPairingCoordinator? = null,
) : ScreenModel {
    private val globalPreferences = prefs
    private val _state = MutableStateFlow(buildInitialState(prefs, initialSessionState))
    val state: StateFlow<ReaderState> = _state.asStateFlow()
    private var lastSettledViewport: SettledViewportIdentity? = null
    private var lastLayoutScheduledViewport: SettledViewportIdentity? = null
    private var viewportSize: Pair<Int, Int>? = null
    private var adaptiveInitialized = false
    private var pendingLayout: Boolean? = null
    private var pendingLayoutJob: Job? = null
    private var layoutProgressAnchor: ReaderPageId? = null
    private var manualDualPage = dualPageFromViewerFlags(mangaViewerFlags) ?: dualPageOverride ?: prefs.isDualPage
    private val productionRuntimeLifecycleLock = Any()
    private var retainedCompositionCount = 0

    @Volatile private var disposeRequested = false
    private var productionRuntimeClosed = false

    @Volatile private var pairingEpoch = 0L

    @Volatile private var catalogActivationEpoch = 0L

    @Volatile private var pairingRequest: PairingRequest? = null

    init {
        beginPairingRestore(initialSessionState)
    }

    internal fun attachCatalog(
        refs: List<mihon.desktop.reader.ReaderChapterRef>,
        opened: tachiyomi.domain.reader.model.ReaderOpenContext?,
        preparation: tachiyomi.domain.reader.interactor.ReaderCatalogPreparation?,
        map: (List<tachiyomi.domain.chapter.model.Chapter>) -> List<mihon.desktop.reader.ReaderChapterRef>,
    ) {
        _state.update { it.copy(chapterRefs = refs) }
        if (opened == null || preparation == null) return
        val initial = state.value.context
        val epoch = catalogActivationEpoch
        val completion = tachiyomi.domain.reader.interactor.ReaderCatalogCompletion(opened, preparation)
        ownedRuntimeScope?.launch {
            try {
                val chapters = completion.await() ?: return@launch
                val updated = map(chapters)
                synchronized(productionRuntimeLifecycleLock) {
                    if (disposeRequested || productionRuntimeClosed || catalogActivationEpoch != epoch) return@synchronized
                    _state.update { current ->
                        if (current.context.mangaId != initial.mangaId || current.context.chapterId != initial.chapterId ||
                            current.context.sourceId != initial.sourceId || current.context.chapterUrl != initial.chapterUrl
                        ) {
                            current
                        } else {
                            val index = updated.indexOfFirst { it.id == initial.chapterId && it.url == initial.chapterUrl }
                            val activeRef = current.chapterRefs.firstOrNull { it.id == initial.chapterId && it.url == initial.chapterUrl }
                            val neighbors = updated.map { if (it.id == initial.chapterId && activeRef != null) activeRef else it }
                            if (index < 0) current else current.copy(chapterRefs = neighbors, context = current.context.copy(chapterIndex = index))
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Neighbor metadata is optional; keep the already mounted pages and causal session.
            }
        }
    }

    private fun buildInitialState(
        prefs: ReaderPreferences,
        reader: DesktopReaderSessionState,
    ): ReaderState {
        val resolvedMode = when {
            isWebtoon -> ReadingMode.WEBTOON
            readingModeFromViewerFlags(mangaViewerFlags) == null && prefs.readingMode == ReadingMode.AUTO &&
                (dualPageFromViewerFlags(mangaViewerFlags) != null || dualPageOverride != null) -> ReadingMode.RTL
            else -> readingModeFromViewerFlags(mangaViewerFlags) ?: prefs.readingMode
        }
        return ReaderState(
            context = reader.context,
            session = reader.snapshot,
            currentPage = resolveInitialPage(reader.context.initialPage, reader.snapshot.activeChapter.pages.size),
            resumePageUnavailable = invalidSavedPage(
                reader.context.initialPage,
                reader.snapshot.activeChapter.pages.size,
            ),
            readingMode = if (resolvedMode == ReadingMode.AUTO) ReadingMode.RTL else resolvedMode,
            automaticLayout = resolvedMode == ReadingMode.AUTO,
            followsGlobalReadingMode = !isWebtoon && readingModeFromViewerFlags(mangaViewerFlags) == null &&
                dualPageFromViewerFlags(mangaViewerFlags) == null && dualPageOverride == null,
            dualPageMode = resolvedMode != ReadingMode.AUTO && (dualPageFromViewerFlags(mangaViewerFlags) ?: dualPageOverride ?: prefs.isDualPage),
            autoSplitPages = prefs.autoSplitPages,
            autoSpreadMatching = prefs.isAutoSpreadMatching,
            backgroundTheme = prefs.backgroundTheme,
            navigationMode = prefs.navigationMode,
            cropBordersPager = prefs.cropBordersPager,
            cropBordersWebtoon = prefs.cropBordersWebtoon,
            webtoonSidePadding = prefs.webtoonSidePadding,
            webtoonAutoScroll = prefs.webtoonAutoScroll,
            webtoonAutoScrollSpeed = prefs.webtoonAutoScrollSpeed,
            scaleType = prefs.scaleType,
            colorFilter = prefs.loadColorFilter(),
            skipReadChapters = prefs.skipReadChapters,
            skipFilteredChapters = prefs.skipFilteredChapters,
            skipDuplicateChapters = prefs.skipDuplicateChapters,
            pairingLoad = if (pairingCoordinator != null && !reader.context.isTemporaryReaderFile()) {
                PairingLoad.LOADING
            } else {
                PairingLoad.READY
            },
            pairingSessionOnly = pairingCoordinator != null && reader.context.isTemporaryReaderFile(),
            pairingNotice = if (pairingCoordinator != null && reader.context.isTemporaryReaderFile()) {
                PairingNotice.SESSION_ONLY
            } else {
                null
            },
        )
    }

    fun acceptSessionState(reader: DesktopReaderSessionState) {
        var newIdentity = false
        _state.update { current ->
            val chapterChanged = current.context.chapterId != reader.context.chapterId
            newIdentity = chapterChanged || current.context.mangaId != reader.context.mangaId ||
                current.session.generation != reader.snapshot.generation ||
                current.session.activeChapter.pages.size != reader.snapshot.activeChapter.pages.size
            val firstStablePageList = current.session.activeChapter.pages.isEmpty() &&
                reader.snapshot.activeChapter.pages.isNotEmpty()
            val pageCount = reader.snapshot.activeChapter.pages.size
            val currentPage = if (chapterChanged || firstStablePageList) {
                resolveInitialPage(reader.context.initialPage, pageCount)
            } else {
                current.currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            }
            current.copy(
                context = reader.context.copy(
                    chapterIndex = current.chapterRefs.indexOfFirst { it.id == reader.context.chapterId }
                        .takeIf { it >= 0 } ?: reader.context.chapterIndex,
                ),
                session = reader.snapshot,
                currentPage = currentPage,
                resumePageUnavailable = if (chapterChanged || firstStablePageList) {
                    invalidSavedPage(reader.context.initialPage, pageCount)
                } else {
                    current.resumePageUnavailable
                },
                currentDisplayUnitId = if (newIdentity) null else current.currentDisplayUnitId,
                visiblePageIds = if (newIdentity) emptySet() else current.visiblePageIds,
                webtoonScrollAnchor = if (newIdentity) null else current.webtoonScrollAnchor,
                chapterTransition = if (newIdentity) null else current.chapterTransition,
                forcedSinglePages = if (newIdentity) emptySet() else current.forcedSinglePages,
                pairingLoad = if (newIdentity) {
                    if (pairingCoordinator != null && !reader.context.isTemporaryReaderFile()) PairingLoad.LOADING else PairingLoad.READY
                } else {
                    current.pairingLoad
                },
                pairingRevision = if (newIdentity) 0L else current.pairingRevision,
                pairingSessionOnly = if (newIdentity) {
                    pairingCoordinator != null && reader.context.isTemporaryReaderFile()
                } else {
                    current.pairingSessionOnly
                },
                pairingSaving = if (newIdentity) false else current.pairingSaving,
                pairingNotice = if (newIdentity) null else current.pairingNotice,
                spreadPages = if (newIdentity) emptySet() else current.spreadPages,
                matchedPairs = if (newIdentity) emptySet() else current.matchedPairs,
                virtualPages = if (newIdentity) null else current.virtualPages,
            )
        }
        if (newIdentity) {
            layoutProgressAnchor = null
            pairingEpoch++
            pairingRequest = null
        }
        beginPairingRestore(reader)
    }

    private fun beginPairingRestore(reader: DesktopReaderSessionState) {
        val coordinator = pairingCoordinator ?: return
        val context = reader.context
        if (context.isTemporaryReaderFile()) {
            _state.update { state ->
                state.copy(pairingLoad = PairingLoad.READY, pairingSessionOnly = true)
                    .let { if (it.pairingNotice == PairingNotice.SESSION_ONLY) it else it.withPairingNotice(PairingNotice.SESSION_ONLY) }
            }
            return
        }
        if (context.chapterId <= 0L || context.mangaId <= 0L) {
            _state.update { it.copy(pairingLoad = PairingLoad.ERROR) }
            return
        }
        val pageCount = reader.snapshot.activeChapter.pages.size
        if (pageCount == 0) return
        val request = PairingRequest(context.chapterId, context.mangaId, reader.snapshot.generation, pageCount, pairingEpoch)
        if (pairingRequest == request) return
        pairingRequest = request
        _state.update { it.copy(pairingLoad = PairingLoad.LOADING) }
        coordinator.submit(context.chapterId) {
            runCatching { load(context.chapterId, context.mangaId) }
                .onSuccess { snapshot ->
                    if (isCurrentPairingRequest(request)) {
                        val record = snapshot.record
                        val valid = record?.isValidFor(pageCount) != false
                        changePresentation { state ->
                            if (!isCurrentPairingRequest(request) || !state.matchesPairingRequest(request)) return@changePresentation state
                            state.copy(
                                forcedSinglePages = if (valid) record?.forcedSinglePages.orEmpty() else emptySet(),
                                pairingRevision = snapshot.revision,
                                pairingLoad = PairingLoad.READY,
                                pairingSaving = false,
                            ).let { updated ->
                                if (valid) updated else updated.withPairingNotice(PairingNotice.INVALID)
                            }
                        }
                    }
                }
                .onFailure {
                    if (isCurrentPairingRequest(request)) {
                        _state.update { state ->
                            if (isCurrentPairingRequest(request) && state.matchesPairingRequest(request)) {
                                state.copy(pairingLoad = PairingLoad.ERROR)
                            } else {
                                state
                            }
                        }
                    }
                }
        }
    }

    fun retryPairingRestore() {
        val current = _state.value
        if (current.pairingLoad != PairingLoad.ERROR && current.pairingLoad != PairingLoad.DEFAULT_UNVERIFIED) return
        pairingEpoch++
        pairingRequest = null
        _state.update { it.copy(pairingLoad = PairingLoad.LOADING) }
        beginPairingRestore(DesktopReaderSessionState(current.context, current.session))
    }

    fun useDefaultPairingThisSession() {
        _state.update { state ->
            if (state.pairingLoad == PairingLoad.ERROR) state.copy(pairingLoad = PairingLoad.DEFAULT_UNVERIFIED) else state
        }
    }

    private fun isCurrentPairingRequest(request: PairingRequest): Boolean =
        pairingRequest == request &&
            isCurrentPairingSession(request.chapterId, request.mangaId, request.generation, request.pageCount, request.epoch) &&
            _state.value.matchesPairingRequest(request)

    private fun isCurrentPairingSession(
        chapterId: Long,
        mangaId: Long,
        generation: Long,
        pageCount: Int,
        epoch: Long,
    ): Boolean =
        !disposeRequested && pairingEpoch == epoch && _state.value.context.chapterId == chapterId &&
            _state.value.context.mangaId == mangaId &&
            _state.value.session.generation == generation && _state.value.session.activeChapter.pages.size == pageCount

    fun activateChapter(context: DesktopReaderChapterContext) {
        catalogActivationEpoch++
        onChapterActivated(context)?.let(::acceptSessionState)
    }

    fun updateNextChapterPrefetch(
        context: DesktopReaderChapterContext?,
        firstViewportPageCount: Int,
    ) {
        onNextChapterPrefetchChanged(context, firstViewportPageCount)
    }

    fun goToPage(page: Int) {
        layoutProgressAnchor = null
        _state.update { state ->
            val max = (state.session.activeChapter.pages.size - 1).coerceAtLeast(0)
            state.copy(
                currentPage = page.coerceIn(0, max),
                currentDisplayUnitId = null,
                visiblePageIds = emptySet(),
                webtoonScrollAnchor = null,
            )
        }
    }

    internal fun selectDisplayUnit(displayUnitId: DisplayUnitId) {
        layoutProgressAnchor = null
        _state.update { state ->
            state.copy(
                currentDisplayUnitId = displayUnitId,
                visiblePageIds = emptySet(),
                webtoonScrollAnchor = null,
            )
        }
    }

    internal fun settleSinglePage(visiblePages: VisiblePageSet) {
        settleVisiblePages(visiblePages, webtoonScrollAnchor = null)
    }

    internal fun settleWebtoon(update: WebtoonViewportUpdate) {
        settleVisiblePages(update.visiblePages, update.anchor)
    }

    internal fun settleDualPage(visiblePages: VisiblePageSet) {
        settleVisiblePages(visiblePages, webtoonScrollAnchor = null)
    }

    private fun settleVisiblePages(
        visiblePages: VisiblePageSet,
        webtoonScrollAnchor: mihon.desktop.ui.reader.presentation.WebtoonScrollAnchor?,
    ) {
        if (_state.value.pairingLoad == PairingLoad.LOADING || _state.value.pairingLoad == PairingLoad.ERROR) return
        if (visiblePages.transitionDirection != null) {
            _state.update { state ->
                state.copy(
                    currentDisplayUnitId = visiblePages.displayUnitId,
                    visiblePageIds = emptySet(),
                    webtoonScrollAnchor = webtoonScrollAnchor,
                )
            }
            return
        }
        val layoutOnly = layoutProgressAnchor?.let { it in visiblePages.pageIds } == true
        val activePageId = visiblePages.activePageId ?: visiblePages.pageIds.maxByOrNull(ReaderPageId::sourcePageIndex)
            ?: return
        val current = _state.value
        val pages = current.session.activeChapter.pages
        if (activePageId.chapterId != current.session.activeChapter.id || pages.none { it.id == activePageId }) return
        layoutProgressAnchor = activePageId.takeIf { layoutOnly }
        _state.update { state ->
            state.copy(
                currentPage = activePageId.sourcePageIndex,
                currentDisplayUnitId = visiblePages.displayUnitId,
                visiblePageIds = visiblePages.pageIds,
                webtoonScrollAnchor = webtoonScrollAnchor,
            )
        }
        val identity = SettledViewportIdentity(
            generation = current.session.generation,
            pageIds = visiblePages.pageIds,
            activePageId = activePageId,
        )
        if (layoutOnly) {
            if (lastSettledViewport != identity && lastLayoutScheduledViewport != identity) {
                lastLayoutScheduledViewport = identity
                onViewportLayoutSettled(visiblePages.pageIds, activePageId)
            }
        } else if (lastSettledViewport != identity) {
            lastSettledViewport = identity
            onViewportSettled(visiblePages.pageIds, activePageId)
        }
    }

    fun retryPage(pageId: ReaderPageId) {
        onPageRetry(pageId)
    }

    fun requestRetry() {
        val state = _state.value
        val chapter = state.session.activeChapter
        if (chapter.loadState is ReaderChapterLoadState.Error || chapter.pages.isEmpty()) {
            onChapterRetry()
            return
        }
        val page = chapter.pages.getOrNull(state.currentPage) ?: return
        onPageRetry(page.id)
    }

    fun showChapterBoundary(
        direction: ReaderTransitionDirection,
        chapterId: Long,
        chapterUrl: String,
        chapterName: String,
        chapterNumber: Double,
    ) {
        _state.update {
            it.copy(
                chapterTransition = ReaderChapterTransitionModel(
                    direction = direction,
                    from = ReaderChapterModel(chapterId, chapterUrl, chapterName, chapterNumber),
                    to = null,
                    state = ReaderChapterState.Wait,
                ),
            )
        }
    }

    fun clearChapterTransition() {
        _state.update { it.copy(chapterTransition = null) }
    }

    fun chapterTransitionCommand(): ReaderNavigationCommand? = state.value.chapterTransition?.retryCommand()

    // ── UI visibility ─────────────────────────────────────────────────────────

    fun toggleSettings() {
        _state.update { it.copy(showSettings = !it.showSettings) }
    }

    fun closeSettings() {
        _state.update { it.copy(showSettings = false) }
    }

    fun toggleUI() {
        _state.update { it.copy(showUI = !it.showUI) }
    }

    // ── Reading mode ──────────────────────────────────────────────────────────

    fun updateViewportSize(width: Int, height: Int, scope: CoroutineScope) {
        if (disposeRequested) return
        if (width <= 0 || height <= 0) {
            viewportSize = null
            cancelPendingLayout()
            return
        }
        viewportSize = width to height
        val current = _state.value
        if (!current.automaticLayout) return
        val target = AdaptiveReaderLayout.dualPage(width, height, current.dualPageMode) ?: return
        if (!adaptiveInitialized) {
            adaptiveInitialized = true
            applyAutomaticLayout(target, preserveProgress = false)
        } else if (target == current.dualPageMode) {
            cancelPendingLayout()
        } else if (pendingLayout != target) {
            cancelPendingLayout()
            pendingLayout = target
            pendingLayoutJob = scope.launch {
                delay(AdaptiveReaderLayout.STABILITY_MILLIS)
                if (_state.value.automaticLayout && pendingLayout == target) applyAutomaticLayout(target)
                pendingLayout = null
                pendingLayoutJob = null
            }
        }
    }

    private fun cancelPendingLayout() {
        pendingLayoutJob?.cancel()
        pendingLayoutJob = null
        pendingLayout = null
    }

    private fun applyAutomaticLayout(dual: Boolean, preserveProgress: Boolean = true) {
        if (_state.value.dualPageMode == dual) return
        changePresentation(preserveProgress) { it.copy(dualPageMode = dual) }
    }

    /** Resolve display position independently of whether a layout-only settle may report progress. */
    private fun changePresentation(preserveProgress: Boolean = true, transform: (ReaderState) -> ReaderState) {
        _state.update { current ->
            val changed = transform(current)
            if (changed == current) return@update current
            val currentPage = if (changed.dualPageMode && changed.session.activeChapter.pages.isNotEmpty()) {
                val presentation = changed.dualPresentationSnapshot()
                val index = presentation.dualDisplayUnitIndexForSourcePage(changed.currentPage)
                if (index >= 0) presentation.firstDualPageIndex(index) else changed.currentPage
            } else {
                changed.currentPage
            }
            layoutProgressAnchor = changed.session.activeChapter.pages.getOrNull(currentPage)?.id.takeIf { preserveProgress }
            changed.copy(
                currentPage = currentPage,
                currentDisplayUnitId = null,
                visiblePageIds = emptySet(),
                webtoonScrollAnchor = null,
            )
        }
    }

    fun adjustSpread() {
        val current = _state.value
        if (current.pairingSaving || current.pairingLoad != PairingLoad.READY) return
        val adjustment = adjustedSpread(current)
        if (adjustment.forcedSinglePages == current.forcedSinglePages) return
        val coordinator = pairingCoordinator
        val context = current.context
        if (coordinator != null && !current.pairingSessionOnly && context.chapterId > 0L && context.mangaId > 0L) {
            val epoch = pairingEpoch
            val generation = current.session.generation
            val pageCount = current.session.activeChapter.pages.size
            _state.update { it.copy(pairingSaving = true) }
            coordinator.submit(context.chapterId) {
                runCatching {
                    replace(context.chapterId, context.mangaId, current.pairingRevision, pageCount, adjustment.forcedSinglePages)
                }.onSuccess { snapshot ->
                    if (isCurrentPairingSession(context.chapterId, context.mangaId, generation, pageCount, epoch)) {
                        changePresentation { state ->
                            if (!isCurrentPairingSession(context.chapterId, context.mangaId, generation, pageCount, epoch) ||
                                state.context.chapterId != context.chapterId || state.session.generation != generation ||
                                state.context.mangaId != context.mangaId || state.session.activeChapter.pages.size != pageCount
                            ) {
                                return@changePresentation state
                            }
                            state.copy(
                                forcedSinglePages = adjustment.forcedSinglePages,
                                currentPage = adjustment.currentPage,
                                pairingRevision = snapshot.revision,
                                pairingSaving = false,
                            )
                        }
                    }
                }.onFailure { failure ->
                    if (isCurrentPairingSession(context.chapterId, context.mangaId, generation, pageCount, epoch)) {
                        _state.update { state ->
                            if (isCurrentPairingSession(context.chapterId, context.mangaId, generation, pageCount, epoch) &&
                                state.context.chapterId == context.chapterId && state.session.generation == generation &&
                                state.context.mangaId == context.mangaId && state.session.activeChapter.pages.size == pageCount
                            ) {
                                state.copy(pairingSaving = false).withPairingNotice(PairingNotice.SAVE_FAILED)
                            } else {
                                state
                            }
                        }
                        if (failure is StaleChapterPairingException) {
                            pairingEpoch++
                            pairingRequest = null
                            beginPairingRestore(DesktopReaderSessionState(_state.value.context, _state.value.session))
                        }
                    }
                }
            }
            return
        }
        changePresentation { current ->
            current.copy(forcedSinglePages = adjustment.forcedSinglePages, currentPage = adjustment.currentPage)
        }
    }

    fun followGlobalReadingMode(prefs: ReaderPreferences) {
        if (isWebtoon) return
        manualDualPage = prefs.isDualPage
        setReadingMode(prefs.readingMode)
        _state.update { it.copy(followsGlobalReadingMode = true) }
    }

    fun currentViewerFlags(): Long {
        val current = _state.value
        if (current.followsGlobalReadingMode) return viewerFlagsFollowingGlobal(mangaViewerFlags)
        return viewerFlagsWithReadingMode(
            viewerFlagsWithDualPage(mangaViewerFlags, manualDualPage),
            if (current.automaticLayout) ReadingMode.AUTO else current.readingMode,
        )
    }

    fun setReadingMode(mode: ReadingMode, prefs: ReaderPreferences? = null) {
        if (isWebtoon) return
        if (mode == ReadingMode.DEFAULT) {
            followGlobalReadingMode(prefs ?: globalPreferences)
            return
        }
        cancelPendingLayout()
        adaptiveInitialized = false
        val automatic = mode == ReadingMode.AUTO
        val dual = if (automatic) {
            viewportSize?.let { (width, height) -> AdaptiveReaderLayout.dualPage(width, height) } ?: false
        } else {
            manualDualPage
        }
        if (automatic && viewportSize != null) adaptiveInitialized = true
        changePresentation {
            it.copy(
                readingMode = if (automatic) ReadingMode.RTL else mode,
                automaticLayout = automatic,
                followsGlobalReadingMode = false,
                dualPageMode = dual,
                currentDisplayUnitId = null,
                visiblePageIds = emptySet(),
                webtoonScrollAnchor = null,
            )
        }
    }

    // ── Dual-page & spread management ─────────────────────────────────────────

    fun setDualPageMode(on: Boolean, prefs: ReaderPreferences? = null) {
        if (_state.value.automaticLayout) return
        manualDualPage = on
        changePresentation { state ->
            state.copy(
                dualPageMode = on,
                followsGlobalReadingMode = false,
                forcedSinglePages = state.forcedSinglePages,
                currentDisplayUnitId = null,
                visiblePageIds = emptySet(),
                webtoonScrollAnchor = null,
            )
        }
        prefs?.isDualPage = on
    }

    fun setAutoSplitPages(on: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { state ->
            if (state.readingMode == ReadingMode.WEBTOON) {
                state.copy(autoSplitPages = on)
            } else {
                state.copy(autoSplitPages = on, currentDisplayUnitId = null, visiblePageIds = emptySet())
            }
        }
        prefs?.autoSplitPages = on
    }

    fun setAutoSpreadMatching(on: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(autoSpreadMatching = on) }
        prefs?.isAutoSpreadMatching = on
    }

    private fun changeDualPresentation(transform: (ReaderState) -> ReaderState) {
        if (_state.value.dualPageMode) {
            changePresentation(transform = transform)
        } else {
            // Single-page metadata updates must retain any pending layout progress protection.
            _state.update(transform)
        }
    }

    fun setSpreadPages(pages: Set<Int>) {
        changeDualPresentation { it.copy(spreadPages = pages) }
    }

    fun setForcedSinglePages(pages: Set<Int>) {
        changeDualPresentation { it.copy(forcedSinglePages = pages) }
    }

    fun setMatchedPairs(pairs: Set<Pair<Int, Int>>) {
        changeDualPresentation { it.copy(matchedPairs = pairs) }
    }

    fun setVirtualPages(pages: List<mihon.desktop.reader.VirtualPage>?) {
        _state.update { it.copy(virtualPages = pages) }
    }

    // ── Display settings ──────────────────────────────────────────────────────

    fun setBackgroundTheme(theme: ReaderBackgroundTheme, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(backgroundTheme = theme) }
        prefs?.backgroundTheme = theme
    }

    fun setNavigationMode(mode: NavigationMode, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(navigationMode = mode) }
        prefs?.navigationMode = mode
    }

    fun setCropBordersPager(on: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(cropBordersPager = on) }
        prefs?.cropBordersPager = on
    }

    fun setCropBordersWebtoon(on: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(cropBordersWebtoon = on) }
        prefs?.cropBordersWebtoon = on
    }

    fun setWebtoonSidePadding(padding: WebtoonSidePadding, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(webtoonSidePadding = padding) }
        prefs?.webtoonSidePadding = padding
    }

    fun setWebtoonAutoScroll(on: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(webtoonAutoScroll = on) }
        prefs?.webtoonAutoScroll = on
    }

    fun setWebtoonAutoScrollSpeed(speed: WebtoonAutoScrollSpeed, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(webtoonAutoScrollSpeed = speed) }
        prefs?.webtoonAutoScrollSpeed = speed
    }

    fun setScaleType(type: ScaleType, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(scaleType = type) }
        prefs?.scaleType = type
    }

    fun setColorFilter(filter: ReaderColorFilter, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(colorFilter = filter) }
        prefs?.saveColorFilter(filter)
    }

    fun setZoomState(zoom: ZoomState) {
        _state.update { it.copy(zoomState = zoom) }
    }

    fun setSkipReadChapters(skip: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(skipReadChapters = skip) }
        prefs?.skipReadChapters = skip
    }

    fun setSkipFilteredChapters(skip: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(skipFilteredChapters = skip) }
        prefs?.skipFilteredChapters = skip
    }

    fun setSkipDuplicateChapters(skip: Boolean, prefs: ReaderPreferences? = null) {
        _state.update { it.copy(skipDuplicateChapters = skip) }
        prefs?.skipDuplicateChapters = skip
    }

    suspend fun persistViewerFlags(mangaId: Long, flags: Long) {
        if (mangaId == 0L) return
        persistViewerFlags.invoke(mangaId, flags)
    }

    internal fun retainProductionRuntimeForComposition(): AutoCloseable {
        if (runtime == null) return AutoCloseable {}
        synchronized(productionRuntimeLifecycleLock) {
            check(!disposeRequested) { "Reader screen model is already disposed" }
            retainedCompositionCount += 1
        }
        return ProductionRuntimeCompositionLease(::releaseProductionRuntimeComposition)
    }

    override fun onDispose() {
        cancelPendingLayout()
        val closeRuntime = synchronized(productionRuntimeLifecycleLock) {
            disposeRequested = true
            markProductionRuntimeClosedIfReady()
        }
        if (closeRuntime) closeProductionRuntime()
    }

    private fun releaseProductionRuntimeComposition() {
        val closeRuntime = synchronized(productionRuntimeLifecycleLock) {
            check(retainedCompositionCount > 0) { "Reader composition lease was released without an owner" }
            retainedCompositionCount -= 1
            markProductionRuntimeClosedIfReady()
        }
        if (closeRuntime) closeProductionRuntime()
    }

    private fun markProductionRuntimeClosedIfReady(): Boolean {
        if (productionRuntimeClosed || !disposeRequested || retainedCompositionCount != 0) return false
        productionRuntimeClosed = true
        return true
    }

    private fun closeProductionRuntime() {
        try {
            runtime?.close()
        } finally {
            ownedRuntimeScope?.cancel()
        }
        if (runtime != null) onProductionClosed()
    }
}

private data class PairingRequest(
    val chapterId: Long,
    val mangaId: Long,
    val generation: Long,
    val pageCount: Int,
    val epoch: Long,
)

private fun ReaderState.matchesPairingRequest(request: PairingRequest): Boolean =
    context.chapterId == request.chapterId && context.mangaId == request.mangaId &&
        session.generation == request.generation && session.activeChapter.pages.size == request.pageCount

private fun DesktopReaderChapterContext.isTemporaryReaderFile(): Boolean =
    localChapterPath != null && mangaId == 0L

private fun ReaderState.withPairingNotice(notice: PairingNotice): ReaderState =
    copy(pairingNotice = notice, pairingNoticeSerial = pairingNoticeSerial + 1)

private class ProductionRuntimeCompositionLease(
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

private data class SettledViewportIdentity(
    val generation: Long,
    val pageIds: Set<ReaderPageId>,
    val activePageId: ReaderPageId,
)

private fun resolveInitialPage(requestedPage: Int, pageCount: Int): Int = when {
    pageCount <= 0 -> 0
    requestedPage == ReaderInitialPage.LAST -> pageCount - 1
    else -> requestedPage.takeIf { it in 0 until pageCount } ?: 0
}

private fun invalidSavedPage(requestedPage: Int, pageCount: Int): Boolean =
    pageCount > 0 && requestedPage != ReaderInitialPage.LAST && requestedPage !in 0 until pageCount
