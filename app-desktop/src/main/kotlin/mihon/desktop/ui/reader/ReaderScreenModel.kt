package mihon.desktop.ui.reader

import cafe.adriel.voyager.core.model.ScreenModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import mihon.desktop.reader.DesktopChapterPairingCoordinator
import mihon.desktop.reader.DesktopReaderChapterContext
import mihon.desktop.reader.DesktopReaderOptionalWorkPaused
import mihon.desktop.reader.DesktopReaderPresentationImageHolder
import mihon.desktop.reader.DesktopReaderRuntime
import mihon.desktop.reader.DesktopReaderSessionState
import mihon.desktop.reader.DesktopReaderSpreadSampler
import mihon.desktop.reader.ReaderBackgroundTheme
import mihon.desktop.reader.ReaderChapterRef
import mihon.desktop.reader.ReaderColorFilter
import mihon.desktop.reader.ReaderPreferences
import mihon.desktop.reader.ReadingMode
import mihon.desktop.reader.ScaleType
import mihon.desktop.reader.SkiaImageDecoder
import mihon.desktop.reader.VirtualPage
import mihon.desktop.reader.WebtoonSidePadding
import mihon.desktop.reader.ZoomState
import mihon.desktop.reader.dualPageFromViewerFlags
import mihon.desktop.reader.readingModeFromViewerFlags
import mihon.desktop.reader.viewerFlagsFollowingGlobal
import mihon.desktop.reader.viewerFlagsWithDualPage
import mihon.desktop.reader.viewerFlagsWithReadingMode
import mihon.desktop.ui.reader.presentation.DisplayUnitId
import mihon.desktop.ui.reader.presentation.VisiblePageSet
import mihon.desktop.ui.reader.presentation.WebtoonScrollAnchor
import mihon.desktop.ui.reader.presentation.WebtoonViewportUpdate
import mihon.desktop.ui.reader.presentation.dualDisplayUnitIndexForSourcePage
import mihon.desktop.ui.reader.presentation.firstDualPageIndex
import mihon.domain.reader.AdaptiveReaderLayout
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PageRotation
import mihon.domain.reader.ReaderAnalysisFailure
import mihon.domain.reader.ReaderChapterModel
import mihon.domain.reader.ReaderChapterState
import mihon.domain.reader.ReaderChapterTransitionModel
import mihon.domain.reader.ReaderDirection
import mihon.domain.reader.ReaderDualPageContentIdentity
import mihon.domain.reader.ReaderDualPageFactReadPermit
import mihon.domain.reader.ReaderDualPageFactRequest
import mihon.domain.reader.ReaderDualPageIdentity
import mihon.domain.reader.ReaderDualPageLocationIntent
import mihon.domain.reader.ReaderDualPageSettlement
import mihon.domain.reader.ReaderDualPageSettlementReason
import mihon.domain.reader.ReaderDualPageViewportTicket
import mihon.domain.reader.ReaderImageOrientation
import mihon.domain.reader.ReaderNavigationCommand
import mihon.domain.reader.ReaderOriginalAnalysisPublication
import mihon.domain.reader.ReaderPageAnalysisEvidence
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.ReaderPageFact
import mihon.domain.reader.ReaderPageFactState
import mihon.domain.reader.ReaderPageSideHint
import mihon.domain.reader.ReaderPairingCertification
import mihon.domain.reader.ReaderPairingFactKind
import mihon.domain.reader.ReaderPairingFactNeed
import mihon.domain.reader.ReaderPairingPersistenceNotice
import mihon.domain.reader.ReaderPairingPersistenceStatus
import mihon.domain.reader.ReaderPresentationMode
import mihon.domain.reader.ReaderTransitionDirection
import mihon.domain.reader.SharedDualPageSession
import mihon.domain.reader.StaleChapterPairingException
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.session.ReaderPageSession
import mihon.domain.reader.session.ReaderSessionSnapshot
import java.io.IOException
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
    private val onChapterActivated: (DesktopReaderChapterContext) -> DesktopReaderSessionState? =
        { null },
    private val onNextChapterPrefetchChanged: (DesktopReaderChapterContext?, Int) -> Unit = {
            _,
            _,
        ->
    },
    internal val runtime: DesktopReaderRuntime? = null,
    private val ownedRuntimeScope: CoroutineScope? = null,
    private val onProductionClosed: () -> Unit = {},
    private val pairingCoordinator: DesktopChapterPairingCoordinator? = null,
    private val readViewerFlags: (suspend (Long) -> Long?)? = null,
) : ScreenModel {
    private val globalPreferences = prefs
    private val _state = MutableStateFlow(buildInitialState(prefs, initialSessionState))
    val state: StateFlow<ReaderState> = _state.asStateFlow()
    private var lastSettledViewport: SettledViewportIdentity? = null
    private var lastLayoutScheduledViewport: SettledViewportIdentity? = null
    private var pendingResumePresentedViewport: SettledViewportIdentity? = null
    private var viewportSize: Pair<Int, Int>? = null
    private var adaptiveInitialized = false
    private var pendingLayout: Boolean? = null
    private var pendingLayoutJob: Job? = null
    private var webtoonRestoreAnchor: ReaderPageId? = null
    private var manualDualPage =
        dualPageFromViewerFlags(mangaViewerFlags) ?: dualPageOverride ?: prefs.isDualPage
    private val productionRuntimeLifecycleLock = Any()
    private val certificationTargets = linkedSetOf<ReaderPageId>()
    private var retainedCompositionCount = 0

    @Volatile private var disposeRequested = false
    private var productionRuntimeClosed = false

    @Volatile private var pairingEpoch = 0L

    @Volatile private var catalogActivationEpoch = 0L

    @Volatile private var pairingRequest: PairingRequest? = null

    private var dualSession: SharedDualPageSession? = null
    private var dualContentRevision = 0L
    private var dualMangaId: Long? = null
    private val dualContentStamps = mutableMapOf<ReaderPageId, DualContentStamp>()
    private val dualContentVersions = mutableMapOf<ReaderPageId, Long>()
    private var deferredContentState: DesktopReaderSessionState? = null

    init {
        synchronized(productionRuntimeLifecycleLock) { ensureDualSession() }
        beginPairingRestore(initialSessionState)
    }

    internal fun attachCatalog(
        refs: List<ReaderChapterRef>,
        opened: tachiyomi.domain.reader.model.ReaderOpenContext?,
        preparation: tachiyomi.domain.reader.interactor.ReaderCatalogPreparation?,
        map: (List<tachiyomi.domain.chapter.model.Chapter>) -> List<ReaderChapterRef>,
    ) {
        _state.update { it.copy(chapterRefs = refs) }
        if (opened == null || preparation == null) return
        val initial = state.value.context
        val epoch = catalogActivationEpoch
        val completion = tachiyomi.domain.reader.interactor.ReaderCatalogCompletion(
            opened,
            preparation,
        )
        ownedRuntimeScope?.launch {
            try {
                val chapters = completion.await() ?: return@launch
                val updated = map(chapters)
                synchronized(productionRuntimeLifecycleLock) {
                    if (disposeRequested || productionRuntimeClosed || catalogActivationEpoch !=
                        epoch
                    ) {
                        return@synchronized
                    }
                    _state.update { current ->
                        if (current.context.mangaId != initial.mangaId || current.context
                                .chapterId != initial
                                .chapterId ||
                            current.context.sourceId != initial.sourceId || current.context
                                .chapterUrl !=
                            initial.chapterUrl
                        ) {
                            current
                        } else {
                            val index = updated.indexOfFirst {
                                it.id == initial.chapterId && it.url == initial
                                    .chapterUrl
                            }
                            val activeRef = current.chapterRefs.firstOrNull {
                                it.id == initial.chapterId && it
                                    .url == initial.chapterUrl
                            }
                            val neighbors = updated.map {
                                if (it.id == initial.chapterId && activeRef !=
                                    null
                                ) {
                                    activeRef
                                } else {
                                    it
                                }
                            }
                            if (index < 0) {
                                current
                            } else {
                                current.copy(
                                    chapterRefs = neighbors,
                                    context =
                                    current.context.copy(chapterIndex = index),
                                )
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Neighbor metadata is optional; keep the already mounted pages and causal session.
            }
        }
    }

    /** All shared presentation operations run under the existing model lifecycle owner lock. */
    private fun ensureDualSession(): SharedDualPageSession? {
        if (disposeRequested) return null
        val current = _state.value
        val pages = current.session.activeChapter.pages
        if (pages.isEmpty()) {
            dualSession?.close()
            dualSession = null
            dualContentStamps.clear()
            dualContentVersions.clear()
            return null
        }
        val ids = pages.map { it.id }
        val previous = dualSession
        val sameOrder = previous?.identity?.pageIds == ids &&
            previous.identity.generation == current.session.generation && dualMangaId == current
                .context.mangaId
        if (!sameOrder) {
            dualContentStamps.clear()
            dualContentVersions.clear()
        }
        pages.forEach { page ->
            val stamp = DualContentStamp(
                page.url,
                page.imageUrl,
                page.encodedPageRef,
                page
                    .attemptGeneration,
            )
            val old = dualContentStamps[page.id]
            val replaced = old != null && (
                old.url != stamp.url || old.attempt != stamp.attempt ||
                    (old.imageUrl != null && old.imageUrl != stamp.imageUrl) ||
                    (old.encoded != null && old.encoded != stamp.encoded)
                )
            if (replaced) dualContentVersions[page.id] = (dualContentVersions[page.id] ?: 0L) + 1
            dualContentStamps[page.id] = stamp
        }
        val content = ids.map { id ->
            val version = "desktop:${dualContentVersions[id] ?: 0L}"
            ReaderDualPageContentIdentity(
                id,
                version,
                sourceOrientation = previous?.identity?.content?.firstOrNull {
                    it.pageId == id &&
                        it.contentVersion == version
                }
                    ?.sourceOrientation ?: ReaderImageOrientation.NORMAL,
            )
        }
        if (!sameOrder || previous?.identity?.content != content) {
            dualMangaId = current.context.mangaId
            val identity = ReaderDualPageIdentity(
                current.session.generation,
                ++dualContentRevision,
                content,
            )
            dualSession = if (sameOrder) {
                requireNotNull(previous).rebindContent(identity)
            } else {
                previous?.close()
                SharedDualPageSession(
                    identity,
                    dualDirection(current),
                    ids.getOrNull(current.currentPage) ?: ids.first(),
                    current.context.mangaId.takeIf {
                        pairingCoordinator != null &&
                            !current.context.isTemporaryReaderFile()
                    },
                )
            }
        }
        val owner = requireNotNull(dualSession)
        owner.configure(
            dualDirection(current),
            current.autoSpreadMatching,
        )
        owner.configurePresentationMode(
            if (current.dualPageMode) {
                ReaderPresentationMode.DUAL
            } else {
                ReaderPresentationMode.SINGLE
            },
        )
        publishDualSession(owner)
        return owner
    }

    private fun dualDirection(state: ReaderState) = if (state.readingMode == ReadingMode.RTL) {
        ReaderDirection.RTL
    } else {
        ReaderDirection.LTR
    }

    private fun publishDualSession(owner: SharedDualPageSession) = synchronized(productionRuntimeLifecycleLock) {
        if (disposeRequested || dualSession !== owner) return@synchronized
        val pairing = owner.pairingState
        val status = pairing.status
        _state.update { current ->
            val notice = when {
                status == ReaderPairingPersistenceStatus.SAVE_FAILED ||
                    status == ReaderPairingPersistenceStatus.CONFLICT -> PairingNotice.SAVE_FAILED
                pairing.notice == ReaderPairingPersistenceNotice.INVALID_RECORD ->
                    PairingNotice.INVALID
                status == ReaderPairingPersistenceStatus.TEMPORARY &&
                    pairingCoordinator != null -> PairingNotice.SESSION_ONLY
                else -> current.pairingNotice
            }
            current.copy(
                dualPageUnits = owner.units, dualPageIdentity = owner.identity,
                dualPageLayoutRevision = owner.layoutRevision,
                dualFactWorkRevision = owner
                    .factWorkRevision,
                dualPairingFailures = (certificationTargets + listOfNotNull(owner.location?.pageId))
                    .mapNotNull { page ->
                        (owner.certifyPairing(page) as? ReaderPairingCertification.RetryableBlocked)
                            ?.failures?.values?.firstOrNull()?.takeIf {
                                it != ReaderAnalysisFailure
                                    .PAIRING_RESTORE
                            }
                            ?.let { page to it }
                    }.toMap(),
                dualPageLocation = owner.location, dualPairingState = pairing,
                forcedSinglePages = pairing.committedForcedSinglePages,
                pairingRevision = pairing
                    .revision ?: 0,
                pairingSaving = status == ReaderPairingPersistenceStatus.SAVING,
                pairingSessionOnly = status == ReaderPairingPersistenceStatus.TEMPORARY,
                pairingLoad = when (status) {
                    ReaderPairingPersistenceStatus.WAITING_FOR_RESTORE,
                    ReaderPairingPersistenceStatus.RESTORING,
                    -> PairingLoad.LOADING
                    ReaderPairingPersistenceStatus.RESTORE_FAILED -> PairingLoad.ERROR
                    ReaderPairingPersistenceStatus.DEFAULT_UNVERIFIED ->
                        PairingLoad.DEFAULT_UNVERIFIED
                    else -> PairingLoad.READY
                },
            ).let {
                if (notice != null && notice != current.pairingNotice) {
                    it
                        .withPairingNotice(notice)
                } else {
                    it
                }
            }
        }
    }

    private data class DualContentStamp(
        val url: String,
        val imageUrl: String?,
        val encoded: Any?,
        val attempt: Long,
    )

    private var factPublisherJob: Job? = null
    private var spreadPublisherJob: Job? = null
    private val optionalWorkRevision = MutableStateFlow(0L)
    private var spreadPublisherOwner: SharedDualPageSession? = null
    private val failedSpreadReads = mutableSetOf<ReaderDualPageContentIdentity>()
    private val headerJobs = mutableMapOf<HeaderWork, Job>()
    private val pendingHeaderTickets = mutableMapOf<ReaderPageId, ReaderDualPageFactRequest>()
    private data class HeaderWork(
        val owner: SharedDualPageSession,
        val workRevision: Long,
        val request: ReaderPageContentOpenRequest,
    )

    internal fun bindDualFactPublisher(scope: CoroutineScope) {
        val runtime = runtime ?: return
        synchronized(productionRuntimeLifecycleLock) {
            if (factPublisherJob != null || disposeRequested) return
            val parallelism = Semaphore(1)
            factPublisherJob = scope.launch {
                kotlinx.coroutines.flow.combine(
                    state,
                    runtime.pageImagePipeline.foregroundWork,
                    runtime.session.physicalWorkCount,
                    runtime.session.foregroundRequestRevision,
                    kotlinx.coroutines.flow.combine(
                        optionalWorkRevision,
                        runtime.pageImagePipeline.optionalWork,
                        runtime.pageImagePipeline.memoryAuthority.snapshots.map {
                            it.overBudgetBytes > 0
                        }.distinctUntilChanged(),
                    ) { _, _, _ -> Unit },
                ) { current, _, _, _, _ ->
                    current
                }.collect { current ->
                    synchronized(productionRuntimeLifecycleLock) {
                        val owner = ensureDualSession() ?: return@synchronized
                        if (current.dualPageIdentity != owner.identity ||
                            current.dualFactWorkRevision != owner.factWorkRevision
                        ) {
                            return@synchronized
                        }
                        val nearby = certificationNeedsLocked(owner).flatMap {
                            listOfNotNull(
                                it
                                    .pageId,
                                it.secondPageId,
                            )
                        }.toSet() + owner.targetUnit?.pageIds
                            .orEmpty()
                        val work = current.session.activeChapter.pages.filter {
                            it.id in nearby && it.loadState is ReaderPageLoadState.Ready &&
                                (it.id == owner.location?.pageId || optionalWorkCanRun(runtime))
                        }
                            .mapNotNull { page ->
                                page.encodedPageRef?.let { ref ->
                                    HeaderWork(
                                        owner,
                                        owner.factWorkRevision,
                                        ReaderPageContentOpenRequest(
                                            page.id,
                                            current.session.generation,
                                            ref,
                                            page.attemptGeneration,
                                            page.encodedPageProvenance,
                                        ),
                                    )
                                }
                            }
                        headerJobs.keys.toList().filter { it !in work }.forEach {
                            headerJobs
                                .remove(it)?.cancel()
                        }
                        work.filter { it !in headerJobs }.forEach { item ->
                            val original = pendingHeaderTickets[item.request.pageId]?.takeIf {
                                owner.ownsFactRequest(it)
                            } ?: owner.requestFact(item.request.pageId)
                            pendingHeaderTickets[item.request.pageId] = original
                            headerJobs[item] = scope.launch {
                                parallelism.acquire()
                                try {
                                    if (item.request.pageId != owner.location?.pageId &&
                                        !optionalWorkCanRun(runtime)
                                    ) {
                                        throw DesktopReaderOptionalWorkPaused()
                                    }
                                    val lease = runtime.pageContentOwner.acquireHeader(item.request)
                                    val fact = lease?.use { originalHeader(it.content, original.content.rotation) }
                                        ?: ReaderPageFact(
                                            ReaderPageFactState.FAILED,
                                        )
                                    acceptDualFact(original, fact, item.request)
                                } catch (_: DesktopReaderOptionalWorkPaused) {
                                    synchronized(productionRuntimeLifecycleLock) {
                                        headerJobs
                                            .remove(item)
                                    }
                                    optionalWorkRevision.update { it + 1 }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    acceptDualFact(
                                        original,
                                        ReaderPageFact(
                                            ReaderPageFactState.FAILED,
                                        ),
                                        item.request,
                                    )
                                } finally {
                                    parallelism.release()
                                    synchronized(productionRuntimeLifecycleLock) {
                                        pendingHeaderTickets.remove(item.request.pageId, original)
                                    }
                                }
                            }
                        }
                        startSpreadSamplesLocked(owner, runtime, scope, parallelism)
                    }
                }
            }
        }
    }

    private fun originalGeometry(
        rawWidth: Int,
        rawHeight: Int,
        orientation: ReaderImageOrientation,
        rotation: PageRotation = PageRotation.NONE,
    ): ReaderPageFact {
        val size = orientation.orientedSize(rawWidth, rawHeight)
        return ReaderPageFact.dimensions(size.width, size.height, rotation, orientation)
    }

    private fun originalHeader(encoded: ByteArray, rotation: PageRotation): ReaderPageFact? {
        if (!mihon.domain.reader.hasCompleteImageOrientationMetadata(encoded)) return null
        val size = SkiaImageDecoder.peekSize(encoded) ?: return null
        return originalGeometry(size.first, size.second, mihon.domain.reader.readImageOrientation(encoded), rotation)
    }

    private fun matchesOriginalBinding(content: ReaderPageContentOpenRequest): Boolean {
        val current = _state.value.session
        val page = current.activeChapter.pages.firstOrNull { it.id == content.pageId } ?: return false
        return current.generation == content.generation &&
            page.attemptGeneration == content.attemptGeneration &&
            (
                page.encodedPageRef == null ||
                    (
                        page.encodedPageRef == content.encodedPageRef &&
                            page.encodedPageProvenance == content.encodedPageProvenance
                        )
                )
    }

    private fun optionalWorkCanRun(runtime: DesktopReaderRuntime): Boolean =
        runtime.session.foregroundRequestsIdle && runtime.pageImagePipeline.foregroundWork
            .value == 0 &&
            runtime.pageImagePipeline.memoryAuthority.snapshots.value.overBudgetBytes == 0L

    private fun certificationNeedsLocked(owner: SharedDualPageSession): Set<ReaderPairingFactNeed> {
        if (!_state.value.dualPageMode) return emptySet()
        owner.location?.pageId?.let { certificationTargets += it }
        certificationTargets.retainAll(owner.identity.pageIds.toSet())
        return certificationTargets.flatMap { page ->
            when (val result = owner.certifyPairing(page)) {
                is ReaderPairingCertification.NeedFacts -> result.facts
                is ReaderPairingCertification.RetryableBlocked -> result.facts.filter {
                    it.kind == ReaderPairingFactKind.DIMENSIONS &&
                        owner.originalAnalysis(it.pageId) is ReaderPageAnalysisEvidence.NotAnalyzed
                }
                is ReaderPairingCertification.Certified -> emptySet()
            }
        }.toSet()
    }

    /** Required preparation joins the same ordinary decode key used by mounted/native images. */
    private fun startSpreadSamplesLocked(
        owner: SharedDualPageSession,
        runtime: DesktopReaderRuntime,
        scope: CoroutineScope,
        parallelism: Semaphore,
    ) {
        if (spreadPublisherOwner !== owner) {
            spreadPublisherJob?.cancel()
            failedSpreadReads.clear()
            spreadPublisherOwner = owner
        }
        if (!_state.value.dualPageMode || spreadPublisherJob?.isCompleted == false ||
            certificationNeedsLocked(owner).isEmpty()
        ) {
            return
        }
        val sequence = owner.location?.sequence
        val budget = owner.factBudget
        val job = scope.launch(start = CoroutineStart.LAZY) {
            while (true) {
                val ids = synchronized(productionRuntimeLifecycleLock) {
                    if (disposeRequested || dualSession !== owner ||
                        owner.location?.sequence != sequence
                    ) {
                        return@synchronized emptyList()
                    }
                    certificationNeedsLocked(owner).filter {
                        it.kind != ReaderPairingFactKind
                            .PAIRING_RULES
                    }
                        .flatMap { listOfNotNull(it.pageId, it.secondPageId) }.distinct()
                }
                if (ids.isEmpty()) break
                val admitted = budget.reserve(ids, System.nanoTime() / 1_000_000)
                if (admitted.isEmpty()) {
                    synchronized(productionRuntimeLifecycleLock) {
                        if (dualSession === owner) {
                            ids.forEach { id ->
                                owner.requestOriginalAnalysis(id)?.let {
                                    owner
                                        .failOriginalAnalysis(it, ReaderAnalysisFailure.RESOURCE)
                                }
                            }
                            publishDualSession(owner)
                        }
                    }
                    break
                }
                val publications = mutableListOf<
                    Pair<
                        ReaderPageContentOpenRequest,
                        ReaderOriginalAnalysisPublication,
                        >,
                    >()
                try {
                    for (id in admitted) {
                        var sample = synchronized(productionRuntimeLifecycleLock) {
                            owner
                                .requestOriginalAnalysis(id)
                        } ?: continue
                        val permit = ReaderDualPageFactReadPermit(budget, id) {
                            System
                                .nanoTime() / 1_000_000
                        }
                        var acquired = false
                        try {
                            kotlinx.coroutines.withContext(Dispatchers.IO) {
                                kotlinx.coroutines.withTimeout(
                                    budget.remainingWaitMillis(
                                        System
                                            .nanoTime() / 1_000_000,
                                    ),
                                ) {
                                    parallelism.acquire()
                                    acquired = true
                                    if (!runtime.session.loadFactPage(id, permit)) {
                                        throw IOException("Page content is unavailable")
                                    }
                                    val content = synchronized(productionRuntimeLifecycleLock) {
                                        if (dualSession !== owner ||
                                            owner.location?.sequence != sequence
                                        ) {
                                            return@synchronized null
                                        }
                                        // Materialization publishes the core before the UI projection consumes it.
                                        val ready = runtime.session.state.value.snapshot
                                        if (ready.generation != owner.identity
                                                .generation
                                        ) {
                                            return@synchronized null
                                        }
                                        val page = ready.activeChapter.pages.firstOrNull {
                                            it
                                                .id == id
                                        } ?: return@synchronized null
                                        if (page.loadState !is ReaderPageLoadState
                                                .Ready
                                        ) {
                                            return@synchronized null
                                        }
                                        val ref = page.encodedPageRef ?: return@synchronized null
                                        ReaderPageContentOpenRequest(
                                            id,
                                            ready.generation,
                                            ref,
                                            page
                                                .attemptGeneration,
                                            page.encodedPageProvenance,
                                        ).takeIf(::matchesOriginalBinding)
                                    } ?: return@withTimeout
                                    if (!_state.value.autoSpreadMatching) {
                                        val request = synchronized(productionRuntimeLifecycleLock) {
                                            if (dualSession !== owner ||
                                                owner.location?.sequence != sequence
                                            ) {
                                                null
                                            } else {
                                                owner.requestFact(id)
                                            }
                                        } ?: return@withTimeout
                                        val header = runtime.pageContentOwner.acquireHeader(content)?.use {
                                            originalHeader(it.content, request.content.rotation)
                                        }
                                        if (header != null) {
                                            acceptDualFact(request, header, content)
                                            return@withTimeout
                                        }
                                    }
                                    val metadata = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                        // Original preparation can precede the next mounted holder after Retry.
                                        if (!runtime.pageImagePipeline.beginPageAttempt(
                                                content
                                                    .pageId,
                                                content.generation,
                                                content
                                                    .attemptGeneration,
                                            )
                                        ) {
                                            throw CancellationException("Original page attempt became stale")
                                        }
                                        runtime.pageImagePipeline
                                            .acquire(
                                                ReaderPageDecodeKey(
                                                    content,
                                                    PageDecodePurpose
                                                        .FULL_PAGE,
                                                    PRESENTATION_DECODE_BOUND,
                                                    PRESENTATION_DECODE_BOUND,
                                                ),
                                            )?.use {
                                                val asset = it.asset
                                                Triple(
                                                    originalGeometry(
                                                        asset.sourceWidth,
                                                        asset.sourceHeight,
                                                        asset.sourceOrientation,
                                                    ),
                                                    asset.originalSamples,
                                                    asset
                                                        .sourceOrientation,
                                                )
                                            }
                                    } ?: throw IOException("Normal page decode failed")
                                    val fact = synchronized(productionRuntimeLifecycleLock) {
                                        if (dualSession !== owner || owner.location?.sequence != sequence || !owner
                                                .ownsOriginalAnalysisRequest(sample)
                                        ) {
                                            return@synchronized null
                                        }
                                        val rotation = sample.content.rotation
                                        val original = ReaderPageFact.dimensions(
                                            metadata.first
                                                .width,
                                            metadata.first.height,
                                            rotation = rotation,
                                            sourceOrientation = metadata.third,
                                        )
                                        if (runtime.session
                                                .acceptCurrentPageContents(listOf(content)) {
                                                    if (owner.acceptOrientedFact(
                                                            owner.requestFact(id),
                                                            original,
                                                        ) == null
                                                    ) {
                                                        false
                                                    } else {
                                                        sample = owner
                                                            .requestOriginalAnalysis(id)
                                                            ?: return@acceptCurrentPageContents false
                                                        true
                                                    }
                                                }
                                        ) {
                                            original
                                        } else {
                                            null
                                        }
                                    } ?: return@withTimeout
                                    val originalSamples = metadata.second[
                                        sample.content
                                            .rotation,
                                    ] ?: kotlinx.coroutines.withContext(
                                        Dispatchers
                                            .IO,
                                    ) {
                                        runtime.pageImagePipeline.openRegionSession(content)
                                            .use { region ->
                                                val raw = sample.content.sourceOrientation
                                                    .orientedSize(fact.width, fact.height)
                                                DesktopReaderSpreadSampler.sampleOriginal(
                                                    runtime
                                                        .pageImagePipeline,
                                                    region,
                                                    raw.width,
                                                    raw.height,
                                                    sample.content.sourceOrientation,
                                                    sample.content
                                                        .rotation,
                                                    canContinue = {
                                                        budget
                                                            .remainingWaitMillis(
                                                                System
                                                                    .nanoTime() / 1_000_000,
                                                            ) > 0
                                                    },
                                                )
                                            }
                                    }
                                    synchronized(productionRuntimeLifecycleLock) {
                                        if (dualSession === owner && owner.location?.sequence == sequence && owner
                                                .ownsOriginalAnalysisRequest(sample)
                                        ) {
                                            if (originalSamples == null) {
                                                owner.failOriginalAnalysis(
                                                    sample,
                                                    ReaderAnalysisFailure.UNSUPPORTED,
                                                )
                                            } else {
                                                publications +=
                                                    content to
                                                    ReaderOriginalAnalysisPublication(sample, fact, originalSamples)
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (paused: DesktopReaderOptionalWorkPaused) {
                            synchronized(productionRuntimeLifecycleLock) {
                                if (dualSession ===
                                    owner
                                ) {
                                    owner.failOriginalAnalysis(sample, ReaderAnalysisFailure.RESOURCE)
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            if (kotlinx.coroutines.currentCoroutineContext().isActive && permit
                                    .exhausted
                            ) {
                                synchronized(productionRuntimeLifecycleLock) {
                                    if (dualSession ===
                                        owner
                                    ) {
                                        owner.failOriginalAnalysis(sample, ReaderAnalysisFailure.RESOURCE)
                                    }
                                }
                            } else {
                                throw cancelled
                            }
                        } catch (_: Exception) {
                            synchronized(productionRuntimeLifecycleLock) {
                                if (dualSession ===
                                    owner
                                ) {
                                    owner.failOriginalAnalysis(sample, ReaderAnalysisFailure.READ)
                                }
                            }
                        } finally {
                            budget.complete(id, 0, cacheHit = true)
                            if (acquired) parallelism.release()
                        }
                    }
                } finally {
                    // Every admitted capability exits, including pages skipped after a stale result.
                    admitted.forEach { budget.complete(it, 0, cacheHit = true) }
                }
                synchronized(productionRuntimeLifecycleLock) {
                    if (dualSession === owner && owner.location?.sequence == sequence) {
                        if (publications.isNotEmpty()) {
                            val physicalRequests = publications.map { it.first }
                            val accepted = runtime.session
                                .acceptCurrentPageContents(physicalRequests) {
                                    owner.acceptOriginalAnalyses(publications.map { it.second })
                                }
                            if (!accepted) return@launch
                        }
                        publishDualSession(owner)
                    }
                }
                if (publications.isEmpty()) break
            }
        }
        spreadPublisherJob = job
        job.invokeOnCompletion { if (!disposeRequested) optionalWorkRevision.update { it + 1 } }
        job.start()
    }

    private fun buildInitialState(
        prefs: ReaderPreferences,
        reader: DesktopReaderSessionState,
    ): ReaderState {
        val resolvedMode = when {
            isWebtoon -> ReadingMode.WEBTOON
            readingModeFromViewerFlags(mangaViewerFlags) == null && prefs.readingMode == ReadingMode
                .AUTO &&
                (dualPageFromViewerFlags(mangaViewerFlags) != null || dualPageOverride != null) ->
                ReadingMode.RTL
            else -> readingModeFromViewerFlags(mangaViewerFlags) ?: prefs.readingMode
        }
        return ReaderState(
            context = reader.context,
            session = reader.snapshot,
            currentPage = resolveInitialPage(
                reader.context.initialPage,
                reader.snapshot
                    .activeChapter.pages.size,
            ),
            resumePageUnavailable = invalidSavedPage(
                reader.context.initialPage,
                reader.snapshot.activeChapter.pages.size,
            ),
            readingMode = if (resolvedMode == ReadingMode.AUTO) ReadingMode.RTL else resolvedMode,
            automaticLayout = resolvedMode == ReadingMode.AUTO,
            followsGlobalReadingMode = !isWebtoon &&
                readingModeFromViewerFlags(mangaViewerFlags) == null &&
                dualPageFromViewerFlags(mangaViewerFlags) == null && dualPageOverride == null,
            dualPageMode = resolvedMode != ReadingMode.AUTO &&
                (dualPageFromViewerFlags(mangaViewerFlags) ?: dualPageOverride ?: prefs.isDualPage),
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
            pairingLoad = if (pairingCoordinator != null && !reader.context
                    .isTemporaryReaderFile()
            ) {
                PairingLoad.LOADING
            } else {
                PairingLoad.READY
            },
            pairingSessionOnly = pairingCoordinator != null && reader.context
                .isTemporaryReaderFile(),
            pairingNotice = if (pairingCoordinator != null && reader.context
                    .isTemporaryReaderFile()
            ) {
                PairingNotice.SESSION_ONLY
            } else {
                null
            },
        )
    }

    fun acceptSessionState(reader: DesktopReaderSessionState) =
        synchronized(productionRuntimeLifecycleLock) {
            val previous = _state.value
            val sameContentList = previous.session.generation == reader.snapshot.generation &&
                previous.context.chapterId == reader.context.chapterId &&
                previous.context.mangaId == reader.context.mangaId &&
                previous.session.activeChapter.pages.map { it.id } == reader.snapshot.activeChapter
                    .pages.map { it.id }
            val protected = if (sameContentList) {
                dualSession?.visibleContentPageIds
                    .orEmpty()
            } else {
                emptySet()
            }
            val originals = previous.session.activeChapter.pages.filter { it.id in protected }
                .associateBy { it.id }
            var deferred = false
            val acceptedPages = reader.snapshot.activeChapter.pages.map { incoming ->
                val original = originals[incoming.id]
                if (original != null &&
                    (
                        original.url != incoming.url || original.imageUrl != incoming.imageUrl ||
                            original.attemptGeneration != incoming.attemptGeneration ||
                            (
                                original.encodedPageRef != null && original.encodedPageRef != incoming
                                    .encodedPageRef
                                )
                        )
                ) {
                    deferred = true
                    original
                } else {
                    incoming
                }
            }
            deferredContentState = reader.takeIf { deferred }
            val accepted = if (deferred) {
                reader.copy(
                    snapshot = reader.snapshot.copy(
                        activeChapter = reader.snapshot.activeChapter.copy(pages = acceptedPages),
                    ),
                )
            } else {
                reader
            }
            var newIdentity = false
            _state.update { current ->
                val chapterChanged = current.context.chapterId != accepted.context.chapterId
                newIdentity = chapterChanged || current.context.mangaId != accepted.context.mangaId ||
                    current.session.generation != accepted.snapshot.generation ||
                    current.session.activeChapter.pages.size != accepted.snapshot.activeChapter.pages
                        .size
                val firstStablePageList = current.session.activeChapter.pages.isEmpty() &&
                    accepted.snapshot.activeChapter.pages.isNotEmpty()
                val pageCount = accepted.snapshot.activeChapter.pages.size
                val currentPage = if (chapterChanged || firstStablePageList) {
                    resolveInitialPage(accepted.context.initialPage, pageCount)
                } else {
                    current.currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                }
                current.copy(
                    context = accepted.context.copy(
                        chapterIndex = current.chapterRefs.indexOfFirst {
                            it.id == accepted.context
                                .chapterId
                        }
                            .takeIf { it >= 0 } ?: accepted.context.chapterIndex,
                    ),
                    session = accepted.snapshot,
                    currentPage = currentPage,
                    resumePageUnavailable = if (chapterChanged || firstStablePageList) {
                        invalidSavedPage(accepted.context.initialPage, pageCount)
                    } else {
                        current.resumePageUnavailable
                    },
                    currentDisplayUnitId = if (newIdentity) null else current.currentDisplayUnitId,
                    visiblePageIds = if (newIdentity) emptySet() else current.visiblePageIds,
                    webtoonScrollAnchor = if (newIdentity) null else current.webtoonScrollAnchor,
                    chapterTransition = if (newIdentity) null else current.chapterTransition,
                    forcedSinglePages = if (newIdentity) emptySet() else current.forcedSinglePages,
                    pairingLoad = if (newIdentity) {
                        if (pairingCoordinator != null &&
                            !accepted.context.isTemporaryReaderFile()
                        ) {
                            PairingLoad.LOADING
                        } else {
                            PairingLoad.READY
                        }
                    } else {
                        current.pairingLoad
                    },
                    pairingRevision = if (newIdentity) 0L else current.pairingRevision,
                    pairingSessionOnly = if (newIdentity) {
                        pairingCoordinator != null && accepted.context.isTemporaryReaderFile()
                    } else {
                        current.pairingSessionOnly
                    },
                    pairingSaving = if (newIdentity) false else current.pairingSaving,
                    pairingNotice = if (newIdentity) null else current.pairingNotice,
                    spreadPages = if (newIdentity) emptySet() else current.spreadPages,
                    virtualPages = if (newIdentity) null else current.virtualPages,
                )
            }
            if (newIdentity) {
                pendingResumePresentedViewport = null
                webtoonRestoreAnchor = null
                pairingEpoch++
                pairingRequest = null
            }
            ensureDualSession()
            beginPairingRestore(accepted)
        }

    private fun beginPairingRestore(reader: DesktopReaderSessionState) =
        synchronized(productionRuntimeLifecycleLock) {
            val coordinator = pairingCoordinator ?: return@synchronized
            val owner = ensureDualSession() ?: return@synchronized
            val context = reader.context
            if (context.isTemporaryReaderFile()) {
                publishDualSession(owner)
                return@synchronized
            }
            if (context.chapterId <= 0 || context.mangaId <= 0) {
                _state.update { it.copy(pairingLoad = PairingLoad.ERROR) }
                return@synchronized
            }
            if (owner.pairingState.status !in setOf(
                    ReaderPairingPersistenceStatus.WAITING_FOR_RESTORE,
                    ReaderPairingPersistenceStatus.RESTORE_FAILED,
                    ReaderPairingPersistenceStatus.CONFLICT,
                    ReaderPairingPersistenceStatus.DEFAULT_UNVERIFIED,
                )
            ) {
                return@synchronized
            }
            val request = owner.beginPairingRestore() ?: return@synchronized
            publishDualSession(owner)
            coordinator.submit(context.chapterId) {
                runCatching { load(context.chapterId, request.mangaId) }.fold(
                    onSuccess = { snapshot ->
                        synchronized(productionRuntimeLifecycleLock) {
                            if (!disposeRequested && dualSession === owner && owner
                                    .acceptPairingRestore(
                                        request,
                                        snapshot,
                                    )
                            ) {
                                publishDualSession(owner)
                            }
                        }
                    },
                    onFailure = {
                        synchronized(productionRuntimeLifecycleLock) {
                            if (!disposeRequested && dualSession === owner &&
                                owner.failPairingRestore(request)
                            ) {
                                publishDualSession(owner)
                            }
                        }
                    },
                )
            }
        }

    fun retryPairingRestore() = synchronized(productionRuntimeLifecycleLock) {
        val current = _state.value
        if (current.pairingLoad == PairingLoad.ERROR || current.pairingLoad == PairingLoad
                .DEFAULT_UNVERIFIED
        ) {
            beginPairingRestore(DesktopReaderSessionState(current.context, current.session))
        }
    }

    fun useDefaultPairingThisSession() = synchronized(productionRuntimeLifecycleLock) {
        dualSession?.let { if (it.useDefaultPairingForSession()) publishDualSession(it) }
    }

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

    fun confirmSyncResumePosition(page: Int): Boolean {
        val current = _state.value
        if (!current.resumePageUnavailable || page !in current.session.activeChapter.pages
                .indices
        ) {
            return false
        }
        webtoonRestoreAnchor = null
        _state.update { it.copy(resumePageUnavailable = false) }
        lastSettledViewport = null
        val id = current.session.activeChapter.pages[page].id
        val presented = pendingResumePresentedViewport
        pendingResumePresentedViewport = null
        if (current.currentPage == page && presented?.generation == current.session.generation &&
            presented.pageIds.isNotEmpty()
        ) {
            val actualAnchor = id.takeIf { it in presented.pageIds } ?: presented.activePageId
            lastSettledViewport = SettledViewportIdentity(
                current.session.generation,
                presented
                    .pageIds,
                actualAnchor,
            )
            onViewportSettled(presented.pageIds, actualAnchor)
        }
        return true
    }

    fun goToPage(page: Int) = synchronized(productionRuntimeLifecycleLock) {
        confirmSyncResumePosition(page)
        webtoonRestoreAnchor = null
        _state.update { state ->
            val max = (state.session.activeChapter.pages.size - 1).coerceAtLeast(0)
            state.copy(
                currentPage = page.coerceIn(0, max),
                currentDisplayUnitId = null,
                visiblePageIds = emptySet(),
                webtoonScrollAnchor = null,
            )
        }
        ensureDualSession()?.let { owner ->
            _state.value.session.activeChapter.pages.getOrNull(_state.value.currentPage)?.id?.let(owner::goToPage)
            publishDualSession(owner)
        }
    }

    /** Adjacent user turns follow actual shared presentation history, including missing partners
     . */
    internal fun navigateDualPage(forward: Boolean): Boolean =
        synchronized(productionRuntimeLifecycleLock) {
            if (!_state.value.dualPageMode ||
                _state.value.currentDisplayUnitId?.slots?.any { it.transitionDirection != null } == true
            ) {
                return@synchronized false
            }
            val owner = ensureDualSession() ?: return@synchronized false
            if (!owner.navigate(forward)) return@synchronized false
            confirmSyncResumePosition(owner.location!!.pageId.sourcePageIndex)
            _state.update {
                it.copy(
                    currentPage = owner.location!!.pageId.sourcePageIndex,
                    currentDisplayUnitId = null,
                    visiblePageIds = emptySet(),
                )
            }
            publishDualSession(owner)
            true
        }

    internal fun selectDisplayUnit(displayUnitId: DisplayUnitId) {
        webtoonRestoreAnchor = null
        _state.update { state ->
            state.copy(
                currentDisplayUnitId = displayUnitId,
                visiblePageIds = emptySet(),
                webtoonScrollAnchor = null,
            )
        }
    }

    internal fun settleWebtoon(update: WebtoonViewportUpdate) {
        settleWebtoonVisiblePages(update.visiblePages, update.anchor)
    }

    internal fun captureDualFact(pageId: ReaderPageId): ReaderDualPageFactRequest? =
        synchronized(productionRuntimeLifecycleLock) {
            ensureDualSession()?.takeIf { pageId in it.identity.pageIds }?.requestFact(pageId)
        }

    internal fun acceptDualFact(
        request: ReaderDualPageFactRequest,
        fact: ReaderPageFact,
        physicalContent: ReaderPageContentOpenRequest? = null,
    ): Boolean = synchronized(productionRuntimeLifecycleLock) {
        val owner = ensureDualSession() ?: return@synchronized false
        val commit = { owner.acceptOrientedFact(request, fact) != null }
        val accepted = if (runtime == null) {
            commit()
        } else {
            val physical = physicalContent ?: _state.value.session.activeChapter.pages
                .firstOrNull { it.id == request.content.pageId }?.let { page ->
                    page.encodedPageRef?.let { ref ->
                        ReaderPageContentOpenRequest(
                            page.id,
                            _state.value.session.generation,
                            ref,
                            page
                                .attemptGeneration,
                            page.encodedPageProvenance,
                        )
                    }
                } ?: return@synchronized false
            matchesOriginalBinding(physical) && runtime.session.acceptCurrentPageContents(listOf(physical), commit)
        }
        accepted.also { if (it) publishDualSession(owner) }
    }

    internal fun acceptDecodedOriginal(
        page: ReaderPageSession,
        holder: DesktopReaderPresentationImageHolder,
    ): Boolean = synchronized(productionRuntimeLifecycleLock) {
        if (!isCurrentDualContent(page)) return@synchronized false
        val ref = page.encodedPageRef ?: return@synchronized false
        val key = holder.decodeKey
        val current = ReaderPageContentOpenRequest(
            page.id,
            _state.value.session.generation,
            ref,
            page.attemptGeneration,
            page.encodedPageProvenance,
        )
        if (key.purpose != PageDecodePurpose.FULL_PAGE || key
                .contentKey != current
        ) {
            return@synchronized false
        }
        val lease = holder.retainReadyAssetForRender(holder.identity) ?: return@synchronized false
        lease.use {
            val asset = it.asset
            val owner = ensureDualSession() ?: return@use false
            val content = owner.identity.content.firstOrNull { content ->
                content.pageId == page
                    .id
            } ?: return@use false
            val samples = asset.originalSamples[content.rotation] ?: return@use false
            val fact = originalGeometry(
                asset.sourceWidth,
                asset.sourceHeight,
                asset.sourceOrientation,
                content.rotation,
            )
            val commit = {
                if (owner.originalAnalysis(page.id) is ReaderPageAnalysisEvidence.Computed) {
                    true
                } else {
                    val request = if (owner.acceptOrientedFact(
                            owner.requestFact(page.id),
                            fact,
                        ) == null
                    ) {
                        null
                    } else {
                        owner.requestOriginalAnalysis(page.id)
                    }
                    request != null && owner.acceptOriginalAnalysis(request, fact, samples)
                }
            }
            val accepted = runtime?.session?.acceptCurrentPageContents(
                listOf(current),
                commit,
            ) ?: commit()
            accepted.also { if (it) publishDualSession(owner) }
        }
    }

    /** Called at actual selected presentation start, before its image leases begin. */
    internal fun beginPagerViewport(
        visiblePages: VisiblePageSet,
        userNavigation: Boolean,
        logicalLocation: ReaderDualPageLocationIntent? = _state.value.dualPageLocation,
        selected: Boolean = true,
    ): ReaderDualPageViewportTicket? = synchronized(productionRuntimeLifecycleLock) {
        val owner = ensureDualSession() ?: return@synchronized null
        val currentUserNavigation = userNavigation && logicalLocation == owner.location
        if (visiblePages.transitionDirection != null) {
            // An outgoing native transition cannot replace a newer logical-page navigation.
            if (!currentUserNavigation && _state.value.currentDisplayUnitId != visiblePages
                    .displayUnitId
            ) {
                return@synchronized null
            }
            _state.update {
                it.copy(
                    currentDisplayUnitId = visiblePages.displayUnitId,
                    visiblePageIds = emptySet(),
                )
            }
            return@synchronized null
        }
        if (currentUserNavigation && owner.location?.pageId !in visiblePages.pageIds) {
            val anchor = visiblePages.activePageId ?: return@synchronized null
            val current = owner.location?.pageId ?: return@synchronized null
            if (!owner.navigate(
                    anchor.sourcePageIndex > current
                        .sourcePageIndex,
                )
            ) {
                return@synchronized null
            }
            _state.update {
                it.copy(
                    currentPage = owner.location!!.pageId.sourcePageIndex,
                    currentDisplayUnitId = null,
                    visiblePageIds = emptySet(),
                )
            }
            publishDualSession(owner)
        }
        if (!selected) {
            val anchor = visiblePages.activePageId ?: return@synchronized null
            certificationTargets += anchor
            optionalWorkRevision.update { it + 1 }
            return@synchronized owner.captureCertifiedViewport(anchor)
        }
        if (owner.targetUnit?.pageIds?.toSet() != visiblePages.pageIds) return@synchronized null
        _state.update {
            it.copy(
                currentPage = owner.location!!.pageId.sourcePageIndex,
                currentDisplayUnitId = visiblePages.displayUnitId,
                visiblePageIds = visiblePages.pageIds,
            )
        }
        // Pixel readiness gates progress, while selected normal image work starts immediately.
        val scheduled = SettledViewportIdentity(
            owner.identity.generation,
            visiblePages.pageIds,
            owner.location!!.pageId,
        )
        if (lastLayoutScheduledViewport != scheduled) {
            lastLayoutScheduledViewport = scheduled
            onViewportLayoutSettled(visiblePages.pageIds, owner.location!!.pageId)
        }
        owner.location?.pageId?.let { certificationTargets += it }
        optionalWorkRevision.update { it + 1 }
        owner.captureCertifiedViewport(owner.location!!.pageId)
    }

    internal fun prepareDualViewportPage(ticket: ReaderDualPageViewportTicket, page: ReaderPageId) =
        synchronized(productionRuntimeLifecycleLock) {
            dualSession?.preparePage(
                ticket,
                page,
            ) == true
        }

    internal fun isDualViewportPageLocked(
        ticket: ReaderDualPageViewportTicket,
        page: ReaderPageId,
    ) =
        synchronized(productionRuntimeLifecycleLock) { page in dualSession?.frame(ticket)?.lockedPageIds.orEmpty() }

    internal fun isCurrentDualContent(page: ReaderPageSession): Boolean =
        synchronized(productionRuntimeLifecycleLock) {
            val current = _state.value.session.activeChapter.pages.getOrNull(
                page.id
                    .sourcePageIndex,
            )
            current?.id == page.id && current.attemptGeneration == page.attemptGeneration &&
                current.encodedPageRef == page.encodedPageRef && current.url == page.url &&
                current.imageUrl == page.imageUrl
        }

    internal fun prepareDecodedDualViewportPage(
        ticket: ReaderDualPageViewportTicket,
        page: ReaderPageSession,
        width: Int,
        height: Int,
        sideHint: ReaderPageSideHint = ReaderPageSideHint.UNKNOWN,
    ): Boolean = synchronized(productionRuntimeLifecycleLock) {
        if (!isCurrentDualContent(page)) return@synchronized false
        val owner = dualSession ?: return@synchronized false
        if (owner.frame(ticket) == null) return@synchronized false
        val revision = owner.layoutRevision
        if (!owner.acceptDecodedFact(
                ticket,
                page.id,
                width,
                height,
                sideHint,
            )
        ) {
            return@synchronized false
        }
        if (revision != owner.layoutRevision) publishDualSession(owner)
        owner.preparePage(ticket, page.id)
    }

    internal fun isCurrentDualViewport(ticket: ReaderDualPageViewportTicket) =
        synchronized(productionRuntimeLifecycleLock) { dualSession?.frame(ticket) != null }

    internal fun drawDualViewportPage(ticket: ReaderDualPageViewportTicket, page: ReaderPageId) =
        synchronized(productionRuntimeLifecycleLock) {
            dualSession?.reportDrawn(
                ticket,
                setOf(page),
            ) == true
        }

    internal fun releaseDualViewport(ticket: ReaderDualPageViewportTicket) {
        synchronized(productionRuntimeLifecycleLock) {
            if (dualSession?.releaseViewport(ticket) == true) {
                deferredContentState?.let(::acceptSessionState)
            }
        }
    }

    internal fun settleDualPage(
        visiblePages: VisiblePageSet,
        ticket: ReaderDualPageViewportTicket,
    ) = synchronized(productionRuntimeLifecycleLock) {
        val owner = ensureDualSession() ?: return@synchronized
        val current = _state.value
        val event = ReaderDualPageSettlement(
            ticket.identity, ticket.layoutRevision,
            ticket.locationSequence, ticket.unitId, visiblePages.pageIds, true,
            ReaderDualPageSettlementReason.USER_NAVIGATION, ticket.locationSequence, ticket,
        )
        owner.settle(
            event,
            current.context.wasRead,
            "desktop:${current.context.chapterId}",
        ) ?: return@synchronized
        val presented = owner.frame(ticket)?.drawnPageIds ?: return@synchronized
        val target = owner.location?.pageId ?: return@synchronized
        _state.update {
            it.copy(
                currentPage = target.sourcePageIndex,
                currentDisplayUnitId = visiblePages.displayUnitId,
                visiblePageIds = presented,
            )
        }
        // The logical target may still be loading while its partner already painted.
        // Scheduling requires an actual visible anchor; source navigation retains the target.
        val actualAnchor = target.takeIf { it in presented } ?: presented.minBy {
            it
                .sourcePageIndex
        }
        if (current.resumePageUnavailable) {
            pendingResumePresentedViewport = SettledViewportIdentity(
                current.session.generation,
                presented,
                actualAnchor,
            )
        } else {
            onViewportSettled(presented, actualAnchor)
        }
    }

    private fun settleWebtoonVisiblePages(
        visiblePages: VisiblePageSet,
        webtoonScrollAnchor: WebtoonScrollAnchor?,
    ) {
        if (_state.value.pairingLoad == PairingLoad.LOADING || _state.value
                .pairingLoad == PairingLoad.ERROR
        ) {
            return
        }
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
        val layoutOnly = webtoonRestoreAnchor?.let { it in visiblePages.pageIds } == true
        val activePageId = visiblePages.activePageId ?: visiblePages.pageIds
            .maxByOrNull(ReaderPageId::sourcePageIndex)
            ?: return
        val current = _state.value
        val pages = current.session.activeChapter.pages
        if (activePageId.chapterId != current.session.activeChapter.id || pages.none {
                it
                    .id == activePageId
            }
        ) {
            return
        }
        webtoonRestoreAnchor = activePageId.takeIf { layoutOnly }
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
        if (layoutOnly || current.resumePageUnavailable) {
            if (current.resumePageUnavailable) pendingResumePresentedViewport = identity
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
        synchronized(productionRuntimeLifecycleLock) {
            spreadPublisherJob?.cancel()
            spreadPublisherJob = null
            failedSpreadReads.clear()
            ensureDualSession()?.let {
                val changedVisible = it.reloadContent(pageId)
                if (it.retryFacts()) publishDualSession(it)
                if (changedVisible) {
                    _state.update { state ->
                        state.copy(
                            dualGeometryRevision =
                            state.dualGeometryRevision + 1,
                        )
                    }
                }
            }
        }
        onPageRetry(pageId)
    }

    fun requestRetry() {
        val state = _state.value
        val chapter = state.session.activeChapter
        if (chapter.loadState is ReaderChapterLoadState.Error || chapter.pages.isEmpty()) {
            synchronized(productionRuntimeLifecycleLock) { dualSession?.retryFacts() }
            onChapterRetry()
            return
        }
        val page = chapter.pages.getOrNull(state.currentPage) ?: return
        retryPage(page.id)
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
        _state.update { it.copy(showSettings = !it.showSettings, settingsFeedback = null) }
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
        val next = width to height
        if (viewportSize == next) return
        val geometryChanged = viewportSize != null
        viewportSize = next
        if (geometryChanged) invalidateDualGeometry()
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
                if (_state.value.automaticLayout &&
                    pendingLayout == target
                ) {
                    applyAutomaticLayout(target)
                }
                pendingLayout = null
                pendingLayoutJob = null
            }
        }
    }

    private fun invalidateDualGeometry() = synchronized(productionRuntimeLifecycleLock) {
        if (!_state.value.dualPageMode) return@synchronized
        val owner = dualSession ?: return@synchronized
        owner.invalidateViewport()
        _state.update {
            it.copy(
                dualGeometryRevision = it.dualGeometryRevision + 1,
                currentDisplayUnitId = null,
                visiblePageIds = emptySet(),
            )
        }
        publishDualSession(owner)
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

    /** Resolve display position independently of whether a layout-only settle may report progress
     . */
    private fun changePresentation(
        preserveProgress: Boolean = true,
        transform: (ReaderState) ->
        ReaderState,
    ) =
        synchronized(productionRuntimeLifecycleLock) {
            _state.update { current ->
                val changed = transform(current)
                if (changed == current) return@update current
                webtoonRestoreAnchor =
                    changed.session.activeChapter.pages.getOrNull(changed.currentPage)?.id.takeIf {
                        preserveProgress &&
                            changed.readingMode == ReadingMode.WEBTOON
                    }
                changed.copy(
                    currentDisplayUnitId = null,
                    visiblePageIds = emptySet(),
                    webtoonScrollAnchor = null,
                )
            }
            ensureDualSession()
        }

    fun adjustSpread() = synchronized(productionRuntimeLifecycleLock) {
        val owner = ensureDualSession() ?: return@synchronized
        val previousLocation = owner.location
        val request = owner.adjustPairing()
        publishDualSession(owner)
        if (owner.location != previousLocation) {
            _state.update {
                it.copy(
                    currentPage = owner.location!!.pageId.sourcePageIndex,
                    currentDisplayUnitId = null,
                    visiblePageIds = emptySet(),
                )
            }
        }
        if (request == null) return@synchronized
        val coordinator = pairingCoordinator ?: return@synchronized
        val context = _state.value.context
        coordinator.submit(context.chapterId) {
            runCatching {
                replaceLayout(
                    context.chapterId,
                    request.mangaId,
                    request.expectedRevision,
                    request.identity.pageIds.size,
                    request.adjustment,
                )
            }.fold(
                onSuccess = { snapshot ->
                    synchronized(productionRuntimeLifecycleLock) {
                        if (!disposeRequested && dualSession === owner && owner
                                .acceptPairingSave(request, snapshot)
                        ) {
                            publishDualSession(owner)
                            _state.update {
                                it.copy(
                                    currentPage =
                                    owner.location?.pageId?.sourcePageIndex ?: it.currentPage,
                                )
                            }
                        }
                    }
                },
                onFailure = { failure ->
                    synchronized(productionRuntimeLifecycleLock) {
                        if (!disposeRequested && dualSession === owner &&
                            owner.failPairingSave(request, failure is StaleChapterPairingException)
                        ) {
                            publishDualSession(owner)
                            if (failure is StaleChapterPairingException) {
                                beginPairingRestore(
                                    DesktopReaderSessionState(
                                        _state.value.context,
                                        _state.value.session,
                                    ),
                                )
                            }
                        }
                    }
                },
            )
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
            viewportSize?.let { (width, height) ->
                AdaptiveReaderLayout.dualPage(
                    width,
                    height,
                )
            } ?: false
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
                state.copy(
                    autoSplitPages = on,
                    currentDisplayUnitId = null,
                    visiblePageIds =
                    emptySet(),
                )
            }
        }
        prefs?.autoSplitPages = on
    }

    fun setAutoSpreadMatching(on: Boolean, prefs: ReaderPreferences? = null) {
        changePresentation { it.copy(autoSpreadMatching = on) }
        prefs?.isAutoSpreadMatching = on
    }

    private fun changeDualPresentation(transform: (ReaderState) -> ReaderState) {
        if (_state.value.dualPageMode) {
            changePresentation(transform = transform)
        } else {
            // Metadata updates outside dual mode retain the Webtoon restoration protection.
            _state.update(transform)
        }
    }

    fun setSpreadPages(pages: Set<Int>) {
        changeDualPresentation { it.copy(spreadPages = pages) }
    }

    fun setVirtualPages(pages: List<VirtualPage>?) {
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
        if (_state.value.cropBordersPager != on) invalidateDualGeometry()
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
        if (_state.value.scaleType != type) invalidateDualGeometry()
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

    suspend fun persistViewerFlags(mangaId: Long, flags: Long): Boolean {
        if (mangaId == 0L) return true
        _state.update { it.copy(settingsFeedback = null) }
        return try {
            persistViewerFlags.invoke(mangaId, flags)
            true
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            try {
                val saved = readViewerFlags?.invoke(mangaId)
                if (saved != null && currentViewerFlags() == flags) {
                    manualDualPage = dualPageFromViewerFlags(saved) ?: globalPreferences.isDualPage
                    val mode = readingModeFromViewerFlags(saved)
                    if (mode == null) followGlobalReadingMode(globalPreferences) else setReadingMode(mode)
                }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Exception) {
                // Leave the user's attempted value visible when authority cannot be read.
            }
            _state.update {
                it.copy(
                    settingsFeedback = tachiyomi.i18n.MR.strings.desktop_detail_save_failed
                        .localized(),
                )
            }
            false
        }
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
            dualSession?.close()
            factPublisherJob?.cancel()
            spreadPublisherJob?.cancel()
            failedSpreadReads.clear()
            pendingHeaderTickets.clear()
            headerJobs.values.forEach(Job::cancel)
            headerJobs.clear()
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
        if (productionRuntimeClosed || !disposeRequested ||
            retainedCompositionCount != 0
        ) {
            return false
        }
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
        session.generation == request.generation && session.activeChapter.pages.size == request
            .pageCount

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
