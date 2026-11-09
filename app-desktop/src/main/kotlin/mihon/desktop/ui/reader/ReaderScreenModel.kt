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
import mihon.desktop.reader.DesktopReaderPairingDecodeDemand
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
import mihon.domain.reader.ReaderDualPageFactBudget
import mihon.domain.reader.ReaderDualPageFactLimits
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
import mihon.domain.reader.ReaderPairingPrefetchPlan
import mihon.domain.reader.ReaderPresentationMode
import mihon.domain.reader.ReaderSpreadSampleRequest
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
import java.util.concurrent.atomic.AtomicInteger

/** Temporary bounded-observer payload for the focused P5 certification-round diagnosis. */
internal data class ReaderSpreadPublisherRoundDiagnostic(
    val certificationRound: Int,
    val targetPageId: ReaderPageId?,
    val certificationKind: String?,
    val exactNeeds: Set<ReaderPairingFactNeed>,
    val needPageIds: Set<ReaderPageId>,
    val needStatesAtStart: List<ReaderSpreadNeedStateDiagnostic>,
    val needStatesAtCompletion: List<ReaderSpreadNeedStateDiagnostic>,
    val continuationFirstIndex: Int?,
    val scanSteps: Int?,
    val dependentPageIds: Set<ReaderPageId>,
    val failedPageIds: Set<ReaderPageId>,
    val factWorkRevisionBefore: Long?,
    val sampleWorkRevisionBefore: Long?,
    val factWorkRevisionAfter: Long?,
    val sampleWorkRevisionAfter: Long?,
    val admittedRequiredIds: Set<ReaderPageId>,
    val admittedSpeculativeIds: Set<ReaderPageId>,
    val admittedOptionalIds: Set<ReaderPageId>,
    val outstandingBefore: Set<ReaderPageId>,
    val consumedCompletionIds: Set<ReaderPageId>,
    val consumedCompletionRoles: Map<ReaderPageId, String>,
    val pendingCompletionIds: Set<ReaderPageId>,
    val outstandingAfter: Set<ReaderPageId>,
    val publishedCountBefore: Int,
    val publishedCountAfter: Int,
)

internal data class ReaderSpreadNeedSampleDiagnostic(
    val firstSampleCached: Boolean,
    val firstOriginalSampleCached: Boolean,
    val secondSampleCached: Boolean?,
    val secondOriginalSampleCached: Boolean?,
    val edgeScoreCached: Boolean?,
    val originalEdgeScoreCached: Boolean?,
)

internal data class ReaderSpreadNeedStateDiagnostic(
    val need: ReaderPairingFactNeed,
    val firstAnalysis: String,
    val secondAnalysis: String?,
    val samples: ReaderSpreadNeedSampleDiagnostic?,
)

/** Test-only capture runs synchronously under the lifecycle lock; production behavior never reads this. */
internal class ReaderSpreadPublisherRoundObserver(
    val captureSamples: (
        SharedDualPageSession,
        Set<ReaderPairingFactNeed>,
    ) -> Map<ReaderPairingFactNeed, ReaderSpreadNeedSampleDiagnostic>,
    val onRound: (ReaderSpreadPublisherRoundDiagnostic) -> Unit,
    /**
     * Called in the window where a capability reported its completion and the publisher has
     * not consumed it yet. The flag tells whether the page still occupies its logical slot.
     */
    val onCompletionReported: ((ReaderPageId, Boolean) -> Unit)? = null,
)

private fun ReaderPageAnalysisEvidence.p5DiagnosticSummary(): String = when (this) {
    ReaderPageAnalysisEvidence.NotAnalyzed -> "NotAnalyzed"
    is ReaderPageAnalysisEvidence.Computed -> "Computed(content=$content, algorithm=$algorithmVersion)"
    is ReaderPageAnalysisEvidence.RetryableFailure ->
        "RetryableFailure(content=$content, reason=$reason)"
}

/** Caller holds productionRuntimeLifecycleLock while reading owner analysis and sample snapshots. */
private fun captureP5NeedStates(
    owner: SharedDualPageSession,
    needs: Set<ReaderPairingFactNeed>,
    samples: Map<ReaderPairingFactNeed, ReaderSpreadNeedSampleDiagnostic>,
): List<ReaderSpreadNeedStateDiagnostic> = needs.map { need ->
    ReaderSpreadNeedStateDiagnostic(
        need = need,
        firstAnalysis = owner.originalAnalysis(need.pageId).p5DiagnosticSummary(),
        secondAnalysis = need.secondPageId?.let { owner.originalAnalysis(it).p5DiagnosticSummary() },
        samples = samples[need],
    )
}

private data class SpreadPublisherRoundStartDiagnostic(
    val certificationRound: Int,
    val targetPageId: ReaderPageId?,
    val certificationKind: String?,
    val exactNeeds: Set<ReaderPairingFactNeed>,
    val needPageIds: Set<ReaderPageId>,
    val needStates: List<ReaderSpreadNeedStateDiagnostic>,
    val continuationFirstIndex: Int?,
    val scanSteps: Int?,
    val dependentPageIds: Set<ReaderPageId>,
    val failedPageIds: Set<ReaderPageId>,
    val factWorkRevision: Long?,
    val sampleWorkRevision: Long?,
)

private data class SpreadPublisherConsumedCompletions(
    val pageIds: Set<ReaderPageId>,
    val roles: Map<ReaderPageId, String>,
)

/**
 * One finished pairing capability.
 *
 * The completion carries the demand class that won the capability, so the publisher
 * can tell a real completion from a speculative one and from optional work.
 */
private data class PairingCompletion(
    val pageId: ReaderPageId,
    val role: String,
    val published: Boolean,
    val deferred: Boolean = false,
)

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
    private val _state =
        MutableStateFlow(
            initialReaderState(
                prefs,
                initialSessionState,
                isWebtoon,
                mangaViewerFlags,
                dualPageOverride,
                pairingCoordinator != null,
            ),
        )
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
    private val _certificationRounds = AtomicInteger(0)
    val certificationRounds: Int get() = _certificationRounds.get()

    /** Temporary focused P5 diagnostic; observers must be bounded, immutable, and nonblocking. */
    @Volatile
    internal var p5SpreadPublisherRoundObserver: ReaderSpreadPublisherRoundObserver? = null

    private data class PagerViewportDemand(
        var owner: SharedDualPageSession,
        val anchor: ReaderPageId,
        val content: List<ReaderDualPageContentIdentity>,
    )
    private val pagerViewportDemands = mutableMapOf<Any, PagerViewportDemand>()
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
    private val acceptedPhysicalContents = mutableMapOf<ReaderPageId, AcceptedPhysicalContent>()
    private var deferredContentState: DesktopReaderSessionState? = null

    init {
        synchronized(productionRuntimeLifecycleLock) {
            ensureDualSession()?.let { beginPairingRestore(initialSessionState, it) }
        }
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
            certificationTargets.clear()
            acceptedPhysicalContents.clear()
            return null
        }
        mihon.desktop.reader.ReaderStartupTrace.mark("DUAL_IDENTITY_BEGIN", pages.getOrNull(current.currentPage)?.id, current.session.generation)
        val ids = pages.map { it.id }
        val previous = dualSession
        val sameOrder = previous?.identity?.pageIds == ids &&
            previous.identity.generation == current.session.generation && dualMangaId == current
                .context.mangaId
        if (!sameOrder) {
            dualContentStamps.clear()
            dualContentVersions.clear()
            certificationTargets.clear()
            acceptedPhysicalContents.clear()
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
        val content = ids.mapIndexed { index, id ->
            val version = "desktop:${dualContentVersions[id] ?: 0L}"
            val previousContent = if (sameOrder) {
                previous?.identity?.content?.getOrNull(index)?.takeIf {
                    it.pageId == id && it.contentVersion == version
                }
            } else {
                previous?.identity?.content?.firstOrNull {
                    it.pageId == id && it.contentVersion == version
                }
            }
            ReaderDualPageContentIdentity(
                id,
                version,
                sourceOrientation = previousContent?.sourceOrientation ?: ReaderImageOrientation.NORMAL,
            )
        }
        val contentByPage = content.associateBy { it.pageId }
        acceptedPhysicalContents.entries.removeAll { (pageId, accepted) ->
            accepted.request.generation != current.session.generation ||
                contentByPage[pageId]?.contentVersion != accepted.identity.contentVersion
        }
        if (!sameOrder || previous?.identity?.content != content) {
            dualMangaId = current.context.mangaId
            val identity = ReaderDualPageIdentity(
                current.session.generation,
                ++dualContentRevision,
                content,
            )
            externalInputEpoch++
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
        mihon.desktop.reader.ReaderStartupTrace.mark("DUAL_IDENTITY_END", pages.getOrNull(current.currentPage)?.id, current.session.generation)
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
        mihon.desktop.reader.ReaderStartupTrace.mark("DUAL_PROJECTION_BEGIN", owner.location?.pageId, owner.identity.generation)
        refreshCertificationTargetsLocked(owner)
        val pairing = owner.pairingState
        val status = pairing.status
        certificationTargets.retainAll(owner.identity.pageIds.toSet())
        val validCertificationTargets = (certificationTargets + listOfNotNull(owner.location?.pageId))
            .filter { it in owner.identity.pageIds }
        val certifications = validCertificationTargets.associateWith(owner::certifyPairing)
        owner.preparePresentationFrame()
        val frames = owner.presentationFrames
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
                dualPageFrames = frames,
                dualPageTargetFrameId = owner.targetFrameId,
                dualPageLayoutRevision = owner.layoutRevision,
                dualFactWorkRevision = owner
                    .factWorkRevision,
                dualPairingFailures = validCertificationTargets
                    .mapNotNull { page ->
                        (certifications[page] as? ReaderPairingCertification.RetryableBlocked)
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
        mihon.desktop.reader.ReaderStartupTrace.mark("DUAL_PROJECTION_END", owner.location?.pageId, owner.identity.generation)
    }

    private data class DualContentStamp(
        val url: String,
        val imageUrl: String?,
        val encoded: Any?,
        val attempt: Long,
    )

    private data class AcceptedPhysicalContent(
        val identity: ReaderDualPageContentIdentity,
        val request: ReaderPageContentOpenRequest,
    )

    private class PairingRegistration(
        val owner: SharedDualPageSession,
        val locationSequence: Long?,
        val demand: mihon.desktop.reader.DesktopReaderPairingDecodeDemand,
        var job: Job?,
    ) {
        @Volatile
        var content: ReaderPageContentOpenRequest? = null
    }

    private data class PairingPromotion(
        val pageId: ReaderPageId,
        val registration: PairingRegistration,
        val content: ReaderPageContentOpenRequest?,
    )

    private class SpreadPublisherTransition(
        private val oldJob: Job?,
        private val newJob: Job?,
    ) {
        fun applyOutsideLock() {
            oldJob?.cancel()
            newJob?.start()
        }
    }

    companion object {
        private val processPublisherOwnerIdGenerator = java.util.concurrent.atomic.AtomicLong(0)
    }

    private var factPublisherJob: Job? = null
    private var spreadPublisherJob: Job? = null
    // Protected by productionRuntimeLifecycleLock; captures inputs arriving during a terminating round.
    private var publisherInputRevision = 0L

    /**
     * Whether the publisher that is currently registered in [spreadPublisherJob] has run.
     *
     * The publisher is registered before it is started, because the start happens
     * outside the lifecycle lock. A publisher that was registered but never started
     * is a lazy job that never completes, and the next round refuses to start a new
     * publisher while it is alive. The flag lets the recovery recognise that case.
     */
    private val spreadPublisherStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * The failure of the last publisher round that died.
     *
     * A publisher round that dies must not be lost: the binding keeps observing, but
     * the failure is the observable trace that a round was aborted instead of
     * completing its analysis.
     */
    @Volatile
    internal var publisherBindingFailure: Throwable? = null

    private var consecutivePublisherBindingFailures = 0
    private var lastBindingAttemptLocation: ReaderPageId? = null
    private var externalInputEpoch = 0L
    private var lastBindingAttemptEpoch = -1L
    private val abortedPublisherJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()

    private val optionalWorkRevision = MutableStateFlow(0L)
    private var spreadPublisherOwner: SharedDualPageSession? = null
    private var spreadPublisherSequence: Long? = null
    private val failedSpreadReads = mutableSetOf<ReaderDualPageContentIdentity>()
    private val headerJobs = mutableMapOf<HeaderWork, Job>()

    /**
     * Live pairing demands keyed by page, so a real certification need can promote an already queued or
     * running speculative decode in place. Promotion must not restart the decode, reset the shared
     * deadline or bytes, or change any content identity: it only raises the existing demand's priority.
     */
    private val pairingRegistrations = java.util.concurrent.ConcurrentHashMap<ReaderPageId, PairingRegistration>()

    /**
     * A budget admission belongs to the owner and the budget that reserved it. A capability of
     * an older owner must never release the admission a newer owner already holds for the same
     * page, so the admission is recorded next to the slot and compared before it is freed.
     */
    private data class PairingBudgetAdmission(
        val owner: SharedDualPageSession,
        val budget: ReaderDualPageFactBudget,
        val publisher: Long,
    )

    private val pairingBudgetOwners = java.util.concurrent.ConcurrentHashMap<ReaderPageId, PairingBudgetAdmission>()

    private fun releasePairingBudget(
        admission: PairingBudgetAdmission,
        pageId: ReaderPageId,
        deferred: Boolean = false,
    ) {
        if (pairingBudgetOwners.remove(pageId, admission)) {
            if (deferred) {
                admission.budget.defer(pageId)
            } else {
                admission.budget.complete(pageId, 0, cacheHit = true)
            }
        }
    }

    /**
     * A replaced publisher's logical admissions are freed at once. Its capabilities are
     * cancelled, and their physical work stays busy until its actual exit; only the logical
     * capacity is returned to the new publisher.
     */
    private fun releasePairingBudgets(publisher: Long) {
        pairingBudgetOwners.entries.removeIf { (pageId, admission) ->
            if (admission.publisher == publisher) {
                admission.budget.complete(pageId, 0, cacheHit = true)
                true
            } else {
                false
            }
        }
    }

    private val pendingHeaderTickets = mutableMapOf<ReaderPageId, ReaderDualPageFactRequest>()

    /** Identifies one publisher run, so its clean-up never touches a newer publisher. */
    private var spreadPublisherToken: Long = 0

    /**
     * Test-only gate, invoked after the publisher started the capabilities of a round and before
     * it consumes their completions. It lets a focused test fix a queue backlog: the completions
     * of the running capabilities stay in the queue while the publisher cannot consume them.
     */
    @Volatile
    internal var p5SpreadPublisherCompletionGate: (suspend (Set<ReaderPageId>) -> Unit)? = null
    private data class HeaderWork(
        val owner: SharedDualPageSession,
        val workRevision: Long,
        val request: ReaderPageContentOpenRequest,
    )

    internal fun bindDualFactPublisher(scope: CoroutineScope) {
        val runtime = runtime ?: return
        synchronized(productionRuntimeLifecycleLock) {
            if (factPublisherJob != null || disposeRequested) return
            val parallelism = Semaphore(ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS)
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
                        // The busy state of the pairing is released by the real exit of the
                        // work that blocked it. The publisher waits for that event instead
                        // of restarting itself, so the counter is part of the observed
                        // state: when it drops, the publisher is armed again.
                        runtime.session.blockingPhysicalWorkCounts,
                    ) { _, _, _, _ -> Unit },
                ) { current, _, _, _, _ ->
                    current
                }.collect { current ->
                    mihon.desktop.reader.ReaderStartupTrace.mark("PUBLISHER_LOCK_WAIT", generation = current.session.generation)
                    val (transition, stuckToCancel, shouldRetry) = synchronized(productionRuntimeLifecycleLock) {
                        mihon.desktop.reader.ReaderStartupTrace.mark("PUBLISHER_LOCKED", generation = current.session.generation)
                        publisherInputRevision++
                        val owner = ensureDualSession() ?: return@synchronized Triple(null, null, false)
                        if (owner.location?.pageId != lastBindingAttemptLocation ||
                            lastBindingAttemptEpoch != externalInputEpoch
                        ) {
                            lastBindingAttemptLocation = owner.location?.pageId
                            lastBindingAttemptEpoch = externalInputEpoch
                            consecutivePublisherBindingFailures = 0
                        }
                        if (current.dualPageIdentity != owner.identity ||
                            current.dualFactWorkRevision != owner.factWorkRevision
                        ) {
                            return@synchronized Triple(null, null, false)
                        }
                        if (consecutivePublisherBindingFailures >= 2) {
                            return@synchronized Triple(null, null, false)
                        }
                        if (spreadPublisherJob?.isCompleted != false) admitCachedOriginalsLocked(owner, runtime)
                        val nearby = certificationNeedsLocked(owner).flatMap {
                            listOfNotNull(
                                it
                                    .pageId,
                                it.secondPageId,
                            )
                        }.toSet() + owner.targetUnit?.pageIds
                            .orEmpty() + owner.factDependencies()
                        // Normal Ready neighbour facts are ordinary preparation, not spread matching.
                        // The domain decision window already bounds this to at most five pages around
                        // the location, and only pages that are still UNKNOWN are returned by it, so
                        // this neither scans the chapter nor re-reads pages that already have facts.
                        val work = current.session.activeChapter.pages.filter {
                            it.id in nearby && it.loadState is ReaderPageLoadState.Ready &&
                                needsOriginalHeader(owner, it.id) &&
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
                            mihon.desktop.reader.ReaderStartupTrace.mark("HEADER_ENQUEUE", item.request.pageId, item.request.generation)
                            headerJobs[item] = scope.launch {
                                mihon.desktop.reader.ReaderStartupTrace.mark("HEADER_SEMAPHORE_WAIT", item.request.pageId, item.request.generation)
                                parallelism.acquire()
                                mihon.desktop.reader.ReaderStartupTrace.mark("HEADER_ADMITTED", item.request.pageId, item.request.generation)
                                try {
                                    if (item.request.pageId != owner.location?.pageId &&
                                        !optionalWorkCanRun(runtime)
                                    ) {
                                        throw DesktopReaderOptionalWorkPaused()
                                    }
                                    val lease = runtime.pageContentOwner.acquireHeader(
                                        item.request,
                                        mihon.domain.reader.observability.ReaderIoPurpose.NORMAL_NEIGHBOUR_HEADER,
                                    )
                                    mihon.desktop.reader.ReaderStartupTrace.mark("HEADER_PARSE_BEGIN", item.request.pageId, item.request.generation)
                                    val fact = lease?.use { originalHeader(it.content, original.content.rotation) }
                                        ?: ReaderPageFact(
                                            ReaderPageFactState.FAILED,
                                        )
                                    mihon.desktop.reader.ReaderStartupTrace.mark("HEADER_PARSE_END", item.request.pageId, item.request.generation)
                                    acceptDualFact(original, fact, item.request)
                                    mihon.desktop.reader.ReaderStartupTrace.mark("HEADER_ACCEPTED", item.request.pageId, item.request.generation)
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
                        try {
                            val trans = startSpreadSamplesLocked(owner, runtime, scope, parallelism)
                            consecutivePublisherBindingFailures = 0
                            publisherBindingFailure = null
                            Triple(trans, null, false)
                        } catch (error: Throwable) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            publisherBindingFailure = error
                            consecutivePublisherBindingFailures++
                            System.err.println(
                                "ReaderScreenModel publisher binding caught error: $error " +
                                    "(failures=$consecutivePublisherBindingFailures)",
                            )
                            val stuck = if (spreadPublisherJob?.isCompleted == false && !spreadPublisherStarted.get()) {
                                val j = spreadPublisherJob
                                spreadPublisherJob = null
                                j
                            } else {
                                null
                            }
                            if (stuck != null) {
                                abortedPublisherJobs.add(stuck)
                            }
                            val retry = consecutivePublisherBindingFailures == 1
                            Triple(null, stuck, retry)
                        }
                    }
                    mihon.desktop.reader.ReaderStartupTrace.mark("PUBLISHER_UNLOCKED", generation = current.session.generation)
                    stuckToCancel?.cancel()
                    transition?.applyOutsideLock()
                    if (shouldRetry) {
                        optionalWorkRevision.update { it + 1 }
                    }
                }
            }
        }
    }

    /**
     * Reports one publisher round to the bounded observer.
     *
     * The completion-queue fields are the real ones of this publisher: the admitted
     * capabilities are split by their demand class, and the consumed completions are
     * the ones the publisher took out of the queue in this round.
     */
    private fun emitP5RoundDiagnostic(
        owner: SharedDualPageSession,
        target: ReaderPageId?,
        certification: ReaderPairingCertification?,
        admittedRequired: Set<ReaderPageId>,
        admittedSpeculative: Set<ReaderPageId>,
        admittedOptional: Set<ReaderPageId>,
        outstandingBefore: Set<ReaderPageId>,
        consumedCompletionIds: Set<ReaderPageId>,
        consumedCompletionRoles: Map<ReaderPageId, String>,
        pendingCompletionIds: Set<ReaderPageId>,
        outstandingAfter: Set<ReaderPageId>,
        publishedCountAfter: Int,
    ) {
        val observer = p5SpreadPublisherRoundObserver ?: return
        val facts = when (certification) {
            is ReaderPairingCertification.NeedFacts -> certification.facts
            is ReaderPairingCertification.RetryableBlocked -> certification.facts
            else -> emptySet()
        }
        val samples = observer.captureSamples(owner, facts)
        val states = captureP5NeedStates(owner, facts, samples)
        val trace = target?.let { owner.certifyPairingWithDependencies(it) }
        observer.onRound(
            ReaderSpreadPublisherRoundDiagnostic(
                certificationRound = _certificationRounds.get(),
                targetPageId = target,
                certificationKind = when (certification) {
                    is ReaderPairingCertification.Certified -> "Certified"
                    is ReaderPairingCertification.NeedFacts -> "NeedFacts"
                    is ReaderPairingCertification.RetryableBlocked -> "RetryableBlocked"
                    null -> null
                },
                exactNeeds = facts,
                needPageIds = facts.flatMapTo(linkedSetOf()) { listOfNotNull(it.pageId, it.secondPageId) },
                needStatesAtStart = states,
                needStatesAtCompletion = states,
                continuationFirstIndex = when (certification) {
                    is ReaderPairingCertification.NeedFacts -> certification.continuationFirstIndex
                    is ReaderPairingCertification.RetryableBlocked -> certification.continuationFirstIndex
                    else -> null
                },
                scanSteps = when (certification) {
                    is ReaderPairingCertification.Certified -> certification.segment.scanSteps
                    is ReaderPairingCertification.NeedFacts -> certification.scanSteps
                    is ReaderPairingCertification.RetryableBlocked -> certification.scanSteps
                    null -> null
                },
                dependentPageIds = trace?.dependentPageIds.orEmpty(),
                failedPageIds = (certification as? ReaderPairingCertification.RetryableBlocked)
                    ?.failures?.keys.orEmpty(),
                factWorkRevisionBefore = owner.factWorkRevision,
                sampleWorkRevisionBefore = owner.sampleWorkRevision,
                factWorkRevisionAfter = owner.factWorkRevision,
                sampleWorkRevisionAfter = owner.sampleWorkRevision,
                admittedRequiredIds = admittedRequired,
                admittedSpeculativeIds = admittedSpeculative,
                admittedOptionalIds = admittedOptional,
                outstandingBefore = outstandingBefore,
                consumedCompletionIds = consumedCompletionIds,
                consumedCompletionRoles = consumedCompletionRoles,
                pendingCompletionIds = pendingCompletionIds,
                outstandingAfter = outstandingAfter,
                publishedCountBefore = 0,
                publishedCountAfter = publishedCountAfter,
            ),
        )
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

    /** Caller holds the model lifecycle lock; physical validation and the scalar commit share the core lock. */
    private fun acceptCurrentPhysicalContentsLocked(
        owner: SharedDualPageSession,
        runtime: DesktopReaderRuntime,
        requests: List<ReaderPageContentOpenRequest>,
        accept: () -> Boolean,
    ): Boolean {
        if (requests.isEmpty() || requests.any {
                it.generation != owner.identity.generation || it.pageId !in owner.identity.pageIds
            }
        ) {
            return false
        }
        refreshCertificationTargetsLocked(owner)
        // G1: the gate validates every physical reference this owner can still certify from,
        // plus the request being published. The read set of the pre-acceptance certification
        // is not a complete superset: the certifier's outer entry comparison walks further
        // left (first--) once a new analysis is admitted, so the accepted set can grow after
        // the commit. The complete source set is collected here instead of intersecting a
        // read set with the admitted records, so a missing record is never dropped silently.
        val identityByPage = owner.identity.content.associateBy { it.pageId }
        val stillValidReferences = acceptedPhysicalContents.mapNotNull { (pageId, accepted) ->
            val identity = identityByPage[pageId] ?: return@mapNotNull null
            accepted.takeIf {
                it.request.generation == owner.identity.generation &&
                    it.identity.contentVersion == identity.contentVersion
            }?.request
        }
        val readPages = certificationTargets.flatMap { target ->
            owner.certifyPairingWithDependencies(target).dependentPageIds
        }.toSet()
        val sourceMissing = readPages.any { pageId ->
            owner.pageFact(pageId) != null && acceptedPhysicalContents[pageId] == null
        }
        if (sourceMissing) {
            // A fact without a physical source must not be certified: it cannot be checked
            // against the core, so the publication waits until the source is known again.
            return false
        }
        val checkedRequests = (requests + stillValidReferences).distinct()
        return runtime.session.acceptCurrentPageContents(checkedRequests) {
            if (!accept()) return@acceptCurrentPageContents false
            requests.forEach { request ->
                val previous = acceptedPhysicalContents[request.pageId]
                // One content identity keeps the physical content its facts were derived
                // from. A different attempt under the same identity would certify the old
                // facts against the new bytes, so the admitted content stays the one the
                // facts came from; the identity itself changes when the facts are dropped.
                if (previous == null || previous.request == request) {
                    acceptedPhysicalContents[request.pageId] = AcceptedPhysicalContent(
                        checkNotNull(owner.identity.content.firstOrNull { it.pageId == request.pageId }),
                        request,
                    )
                }
            }
            true
        }
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

    /**
     * The busy state the pairing has to respect.
     *
     * Two things hold the native decoder and the image memory away from the pairing:
     *
     * - a user visible decode, which the pipeline counts as visible foreground work;
     * - the physical work that is still running. It is process wide, so the work of a
     *   closed session stays visible, and it is released only when the work actually
     *   completes, so the non cooperative physical read of a closed session keeps the
     *   reader busy until its real exit.
     *
     * The pairing's own work is not part of this state: a publisher never waits for
     * the work it started itself, whether that work is a real target or a guess. The
     * work of every *other* publisher is, including the pairing work of a publisher
     * that was replaced, because that work holds the resources the next capability
     * would use - and that is exactly the case a closed session leaves behind.
     */
    private fun pairingBusyState(runtime: DesktopReaderRuntime, ownerKey: Long): Boolean =
        runtime.pageImagePipeline.visibleForegroundWork.value > 0 ||
            runtime.session.blockingPhysicalWorkCountExcept(ownerKey) > 0

    private fun refreshCertificationTargetsLocked(owner: SharedDualPageSession) {
        pagerViewportDemands.entries.removeAll { (_, demand) ->
            val valid = demand.owner.identity.generation == owner.identity.generation &&
                demand.owner.identity.pageIds == owner.identity.pageIds && demand.content.all { captured ->
                    owner.identity.content.any {
                        it.pageId == captured.pageId && it.contentVersion == captured.contentVersion &&
                            it.rotation == captured.rotation
                    }
                }
            if (valid) demand.owner = owner
            !valid
        }
        certificationTargets.clear()
        owner.location?.pageId?.let { certificationTargets += it }
        pagerViewportDemands.values.mapTo(certificationTargets) { it.anchor }
    }

    private fun targetNeedsLocked(owner: SharedDualPageSession): Set<ReaderPairingFactNeed> {
        if (!_state.value.dualPageMode) return emptySet()
        val target = owner.location?.pageId ?: return emptySet()
        return when (val result = owner.certifyPairing(target)) {
            is ReaderPairingCertification.NeedFacts -> result.facts.toSet()
            is ReaderPairingCertification.RetryableBlocked -> result.facts.filter {
                it.kind == ReaderPairingFactKind.DIMENSIONS &&
                    owner.originalAnalysis(it.pageId) is ReaderPageAnalysisEvidence.NotAnalyzed
            }.toSet()
            is ReaderPairingCertification.Certified -> emptySet()
        }
    }

    private fun certificationNeedsLocked(owner: SharedDualPageSession): Set<ReaderPairingFactNeed> {
        if (!_state.value.dualPageMode) return emptySet()
        refreshCertificationTargetsLocked(owner)
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

    /** Admit only exact cached scalar evidence, atomically, before any new work is reserved. */
    private fun admitCachedOriginalsLocked(owner: SharedDualPageSession, runtime: DesktopReaderRuntime) {
        // The pages of the visible pairing still owe their edge samples, and the cached
        // full page of such a page carries them. Admitting those cached originals is a
        // cache hit and not new work, so it must not reserve an attempt either.
        val spreadDependencies = owner.spreadSampleDependencies().filter {
            owner.originalAnalysis(it) !is ReaderPageAnalysisEvidence.Computed
        }
        val needs = (
            certificationNeedsLocked(owner).filter { it.kind != ReaderPairingFactKind.PAIRING_RULES }
                .flatMap { listOfNotNull(it.pageId, it.secondPageId) } + spreadDependencies
            ).distinct()
        val publications = needs.mapNotNull { id ->
            val page = _state.value.session.activeChapter.pages.firstOrNull { it.id == id }
                ?.takeIf { it.loadState is ReaderPageLoadState.Ready } ?: return@mapNotNull null
            val ref = page.encodedPageRef ?: return@mapNotNull null
            val content = ReaderPageContentOpenRequest(
                id,
                _state.value.session.generation,
                ref,
                page.attemptGeneration,
                page.encodedPageProvenance,
            )
            val binding = owner.identity.content.firstOrNull { it.pageId == id } ?: return@mapNotNull null
            val key = ReaderPageDecodeKey(
                content,
                PageDecodePurpose.FULL_PAGE,
                PRESENTATION_DECODE_BOUND,
                PRESENTATION_DECODE_BOUND,
            )
            runtime.pageImagePipeline.acquireCachedForAnalysis(key)?.use { lease ->
                val asset = lease.asset
                if (asset.sourceOrientation != binding.sourceOrientation) return@use null
                val samples = asset.originalSamples[binding.rotation] ?: return@use null
                val fact =
                    originalGeometry(asset.sourceWidth, asset.sourceHeight, asset.sourceOrientation, binding.rotation)
                val known = owner.pageFact(id)
                if (known != null && known.width > 0 && known.height > 0 &&
                    (
                        known.width != fact.width || known.height != fact.height || known.rotation != fact.rotation ||
                            known.sourceOrientation != fact.sourceOrientation
                        )
                ) {
                    return@use null
                }
                val request = owner.requestOriginalAnalysis(id) ?: return@use null
                content to ReaderOriginalAnalysisPublication(request, fact, samples)
            }
        }
        if (publications.isEmpty()) return
        if (acceptCurrentPhysicalContentsLocked(owner, runtime, publications.map { it.first }) {
                owner.acceptOriginalAnalyses(publications.map { it.second })
            }
        ) {
            publishDualSession(owner)
        }
    }

    private fun needsOriginalHeader(owner: SharedDualPageSession, pageId: ReaderPageId): Boolean {
        val content = owner.identity.content.firstOrNull { it.pageId == pageId } ?: return false
        val fact = owner.pageFact(pageId) ?: return true
        return fact.state !in setOf(ReaderPageFactState.PORTRAIT, ReaderPageFactState.SPREAD) ||
            fact.width <= 0 || fact.height <= 0 || fact.rotation != content.rotation ||
            fact.sourceOrientation != content.sourceOrientation
    }

    /** Required preparation joins the same ordinary decode key used by mounted/native images. */
    private fun promotePairingEntries(
        runtime: DesktopReaderRuntime,
        promotions: List<PairingPromotion>,
    ) {
        promotions.forEach { promotion ->
            val current = synchronized(productionRuntimeLifecycleLock) {
                val registration = promotion.registration
                !disposeRequested && pairingRegistrations[promotion.pageId] === registration &&
                    dualSession === registration.owner &&
                    registration.owner.location?.sequence == registration.locationSequence &&
                    registration.job?.isActive == true
            }
            if (current) {
                promotion.content?.let { content ->
                    runtime.pageImagePipeline.promotePairing(content, promotion.registration.demand)
                }
            }
        }
    }

    private fun promotePairingDemandsLocked(
        owner: SharedDualPageSession,
        pageIds: Set<ReaderPageId>,
    ): List<PairingPromotion> = pageIds.mapNotNull { pageId ->
        val registration = pairingRegistrations[pageId] ?: return@mapNotNull null
        if (registration.owner !== owner || registration.locationSequence != owner.location?.sequence ||
            registration.job?.isActive != true || !registration.demand.speculative
        ) {
            return@mapNotNull null
        }
        registration.demand.promote()
        PairingPromotion(pageId, registration, registration.content)
    }

    /**
     * Starts the spread publisher the way the production caller does.
     *
     * [startSpreadSamplesLocked] only prepares the transition; the caller applies
     * it outside the lifecycle lock, so that the lock never holds a cancel or a
     * start. A caller that drives the publisher directly has to apply the same
     * transition, otherwise the lazily created publisher job is never started
     * and no pairing work runs at all.
     */
    internal fun startSpreadPublisher(
        owner: SharedDualPageSession,
        runtime: DesktopReaderRuntime,
        scope: CoroutineScope,
        parallelism: Semaphore,
    ) {
        startSpreadSamplesLocked(owner, runtime, scope, parallelism)?.applyOutsideLock()
    }

    private fun startSpreadSamplesLocked(
        owner: SharedDualPageSession,
        runtime: DesktopReaderRuntime,
        scope: CoroutineScope,
        parallelism: Semaphore,
    ): SpreadPublisherTransition? {
        var oldJob: Job? = null
        if (spreadPublisherOwner !== owner || spreadPublisherSequence != owner.location?.sequence) {
            oldJob = spreadPublisherJob
            spreadPublisherJob = null
            failedSpreadReads.clear()
            // The replaced publisher's logical admissions are freed before the new
            // publisher plans its first round, so it does not wait for a capacity
            // that only a cancelled publisher still holds.
            releasePairingBudgets(spreadPublisherToken)
            // The queued consumers of the previous location are withdrawn before a new
            // target can use the freed slots.
            pairingRegistrations.entries.removeAll { (_, registration) ->
                registration.owner !== owner || registration.locationSequence != owner.location?.sequence
            }
            spreadPublisherOwner = owner
            spreadPublisherSequence = owner.location?.sequence
            _certificationRounds.set(0)
        }
        // A certified target produces no certification needs any more, but the pages
        // of the visible pairing still owe their edge samples, and the publisher is
        // the only producer of those samples. A dependency whose analysis already
        // failed is a recorded failure rather than pending work, so it never starts
        // a publisher: that would be a restart loop which can never succeed.
        val spreadWorkPending = owner.spreadSampleDependencies().any { id ->
            owner.originalAnalysis(id) !is ReaderPageAnalysisEvidence.RetryableFailure
        }
        if (!_state.value.dualPageMode || spreadPublisherJob?.isCompleted == false ||
            (certificationNeedsLocked(owner).isEmpty() && !spreadWorkPending)
        ) {
            return oldJob?.let { SpreadPublisherTransition(it, null) }
        }
        val sequence = owner.location?.sequence
        val budget = owner.factBudget
        // Every publisher owns the reservations it made until the publisher is gone, so a
        // cancellation cannot leave a capacity that the next owner would inherit.
        val publisherToken = ++spreadPublisherToken
        val publisherOwnerKey = processPublisherOwnerIdGenerator.incrementAndGet()
        // The publisher is registered before it is started, because the start happens
        // outside the lifecycle lock. Until it runs, the field holds a publisher that
        // was never started.
        spreadPublisherStarted.set(false)
        // A publisher that stops because a real event has to happen first must not ask the
        // flow to re-arm it. The flow already observes that event, and an immediate re-arm
        // restarts the publisher in an unbounded loop while it waits.
        val waitsForRealEvent = java.util.concurrent.atomic.AtomicBoolean(false)
        // Whether the publisher stopped because the reader was busy. That state is
        // released by the actual exit of the work that blocked it, and that exit can
        // happen while this publisher is still finishing, so the publisher arms itself
        // again when the blocking work is already gone.
        val waitsForBusyState = java.util.concurrent.atomic.AtomicBoolean(false)
        val noProgressInputRevision = java.util.concurrent.atomic.AtomicLong(-1L)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // The publisher has run: the field no longer holds a publisher that was
            // registered but never started.
            spreadPublisherStarted.set(true)
            // The capabilities are children of the publisher, not of one round. The
            // publisher consumes one completion per round and rebuilds its plan from
            // it, so a capability that is still running must not hold the round, and
            // a cancelled publisher must cancel the capabilities that hold a lease.
            // The publisher scope is captured here because the round scope below
            // would otherwise own the capabilities and wait for all of them.
            val publisherScope = this
            // The completion queue carries the finished capabilities back to the publisher.
            // The publisher consumes one completion per round and rebuilds its plan from
            // it, so a slow or gated speculation cannot hold the real target back. The
            // queue is bounded, so a completion that waits to be consumed still occupies
            // its slot and the batch is not silently collapsed into one page per round.
            val completed = kotlinx.coroutines.channels.Channel<PairingCompletion>(
                ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS,
            )
            val taskJobs = java.util.concurrent.ConcurrentHashMap<ReaderPageId, kotlinx.coroutines.Job>()
            val consumedCompletionIds = linkedSetOf<ReaderPageId>()
            val consumedCompletionRoles = mutableMapOf<ReaderPageId, String>()
            // A finished capability keeps its budget slot until the publisher consumes its
            // completion. A completion that is already reported but not consumed still occupies
            // the logical capacity, so the next round cannot reuse that slot.
            val pendingCompletionIds = java.util.concurrent.ConcurrentHashMap.newKeySet<ReaderPageId>()
            // The pages whose sampling was deferred because a user visible decode owned the
            // native decoder. They are not admitted again while that decode is in flight: a
            // second attempt would only pause again.
            val deferredPages = java.util.concurrent.ConcurrentHashMap.newKeySet<ReaderPageId>()
            while (true) {
                val promotions = mutableListOf<PairingPromotion>()
                var observedTarget: ReaderPageId? = null
                var observedCertification: ReaderPairingCertification? = null
                val outstandingBefore = taskJobs.keys.filter { taskJobs[it]?.isActive == true }.toSet()
                // The foreground gate is captured for the round, so the publisher can
                // also tell whether it leaves because a user visible decode owns the
                // native decoder.
                var foregroundBusy = false
                // The reader's busy state is captured for the round as well: the
                // publisher leaves because of it and has to be armed again when the
                // work that blocked it really exits.
                var readerBusy = false
                var classifiedInputRevision = -1L
                val classification =
                    synchronized(productionRuntimeLifecycleLock) {
                        if (disposeRequested || dualSession !== owner ||
                            owner.location?.sequence != sequence
                        ) {
                            return@synchronized Triple(
                                emptyList<ReaderPageId>(),
                                emptyList<ReaderPageId>(),
                                emptyList<ReaderPageId>(),
                            )
                        }
                        classifiedInputRevision = publisherInputRevision
                        admitCachedOriginalsLocked(owner, runtime)
                        val allNeeds = certificationNeedsLocked(owner).filter {
                            it.kind != ReaderPairingFactKind.PAIRING_RULES
                        }
                        val target = owner.location?.pageId
                        val targetCert = target?.let { owner.certifyPairing(it) }
                        observedTarget = target
                        observedCertification = targetCert
                        if (targetCert !is ReaderPairingCertification.Certified ||
                            spreadWorkPending || allNeeds.isNotEmpty()
                        ) {
                            _certificationRounds.incrementAndGet()
                        }
                        // Real missing demand, pure lookahead and mounted-neighbour demand are three different
                        // responsibilities. Real demand and speculation are admitted by ONE capacity
                        // allocation: two sequential reserves share `REQUIRED_CONCURRENT_REQUESTS - active.size`,
                        // so real demand would fill the pool first and speculation would be admitted zero times
                        // per round, collapsing the K-page batch prefetch (measured: rounds 29 vs the guarded 9).
                        val classified = if (targetCert != null) {
                            ReaderPairingPrefetchPlan.classify(
                                targetCert,
                                owner.identity.pageIds,
                                owner::originalAnalysis,
                                ReaderDualPageFactLimits.REQUIRED_LOOKAHEAD_K,
                                failedSpreadReads.map { it.pageId }.toSet(),
                            )
                        } else {
                            null
                        }
                        // A user visible decode owns the native decoder and the image memory.
                        // The speculation and the optional preparation would compete with it, and
                        // a page whose sampling was already deferred would only pause again, so
                        // those are held back. The real demand is admitted as before: the pairing
                        // of a long chapter needs its facts even while the visible pages are
                        // being decoded.
                        foregroundBusy = runtime.pageImagePipeline.visibleForegroundWork.value > 0
                        // The reader's busy state is wider than the visible work: the physical
                        // work that is still running holds the resources the sampling needs,
                        // and the work of a closed session is still running after its session
                        // is gone. A page whose sampling was deferred for that reason would
                        // only pause again, so it is held back until the work really exits.
                        val busy = pairingBusyState(runtime, publisherOwnerKey)
                        readerBusy = busy
                        // A page is deferred because the reader was busy. That is a
                        // property of the current state, not a latch: once the work
                        // that blocked it really exited, the page must be admitted
                        // again. A deferred page that stays deferred for ever owes its
                        // samples to nobody, and the derived dependency of the visible
                        // pair never clears.
                        if (!busy) {
                            deferredPages.clear()
                        }
                        // A page whose sampling was deferred would only pause again while the
                        // reader is busy, so it waits for the real exit of the work that
                        // blocked it. That holds every role back, not only the real demand:
                        // the optional preparation and the speculation would otherwise be
                        // admitted again and again, and the round would burn the shared
                        // wait of the budget until the demand is recorded as a resource
                        // failure.
                        val deferredWhileBusy = if (busy) deferredPages else emptySet()
                        val real = classified?.realMissing.orEmpty()
                            .let { ids -> ids.filter { it !in deferredWhileBusy } }
                        val speculative = if (foregroundBusy) {
                            emptyList()
                        } else {
                            // The lookahead guesses what the scan will need next, and the scan
                            // moves left. An unbounded guess drags the request chain further
                            // left than the reader's own neighbourhood, so the guess stays
                            // inside that neighbourhood. The canonical demand is untouched:
                            // the certification may read as far left as it proves.
                            val windowStart = owner.identity.pageIds.indexOf(
                                owner.location?.pageId,
                            ).takeIf { it >= 0 }?.minus(
                                ReaderDualPageFactLimits.DECISION_WINDOW_RADIUS,
                            ) ?: Int.MIN_VALUE
                            classified?.speculative.orEmpty().filter {
                                it.sourcePageIndex >= windowStart && it !in deferredWhileBusy
                            }
                        }
                        val neededPages = allNeeds.flatMap { listOfNotNull(it.pageId, it.secondPageId) }
                            .distinct()
                        // A page that was admitted as lookahead and has since become needed -
                        // by the target or by a mounted neighbour - keeps its physical decode
                        // and is promoted in place instead of being decoded again.
                        promotions += promotePairingDemandsLocked(owner, neededPages.toSet())
                        // The visible spread pair owes its edge samples to the pages of
                        // the pair itself, and those samples arrive with the original
                        // analysis of the page. A certified target produces no
                        // certification needs any more, so the pair's pages are prepared
                        // here instead.
                        val spreadSamplePages = owner.spreadSampleDependencies().filter {
                            owner.originalAnalysis(it) !is ReaderPageAnalysisEvidence.Computed
                        }
                        val optionalPages = (neededPages + spreadSamplePages).distinct()
                        val optional = if (foregroundBusy) {
                            emptyList()
                        } else {
                            optionalPages.filter {
                                it !in real && it !in speculative && it !in deferredWhileBusy
                            }
                        }
                        Triple(real, speculative, optional)
                    }
                val (requiredIds, speculativeIds, optionalIds) = classification
                promotePairingEntries(runtime, promotions)
                if (requiredIds.isEmpty() && speculativeIds.isEmpty() && optionalIds.isEmpty()) {
                    // Nothing can be admitted while a user visible decode owns the
                    // native decoder. The flow that observes that decode re-arms the
                    // publisher when the real event is gone, so this publisher must
                    // not re-arm itself: an unbounded restart loop would never make
                    // progress.
                    if (readerBusy) {
                        waitsForRealEvent.set(true)
                        waitsForBusyState.set(true)
                    }
                    break
                }
                if (runtime.pageImagePipeline.memoryAuthority.snapshots.value.overBudgetBytes > 0L) {
                    // The image memory is over its soft budget: a new capability would have its
                    // decode refused again, so the publisher waits for the real event - the over
                    // budget state flipping back - instead of recording a resource failure.
                    waitsForRealEvent.set(true)
                    break
                }

                val now = System.nanoTime() / 1_000_000
                val classifiedAdmitted = budget.reserveClassified(requiredIds, speculativeIds, now)
                val admittedRequired = classifiedAdmitted.filterNot { it.speculative }.map { it.pageId }
                val speculativeAdmitted = classifiedAdmitted.filter { it.speculative }.map { it.pageId }
                val admittedOptional = if (optionalIds.isNotEmpty()) {
                    budget.reserve(optionalIds, now, required = false)
                } else {
                    emptyList()
                }
                val admitted = admittedRequired + speculativeAdmitted + admittedOptional
                admitted.forEach {
                    pairingBudgetOwners[it] = PairingBudgetAdmission(owner, budget, publisherToken)
                }
                if (admitted.isEmpty()) {
                    // A round without free capacity is not a resource failure while a
                    // capability of an earlier round is still running: that work is in
                    // flight and will publish its result. A completion that is already
                    // reported but not consumed is still pending work of this publisher,
                    // so it is consumed by this round instead of ending the publisher.
                    // Only a publisher with nothing running and nothing pending reports
                    // the remaining needs as RESOURCE.
                    if (taskJobs.values.none { it.isActive } && pendingCompletionIds.isEmpty()) {
                        if (readerBusy) {
                            // A busy reader is not a resource failure. The demand waits for
                            // the real exit of the work that blocks it - the publisher is
                            // armed again when that exit happens - and the page keeps its
                            // pending analysis instead of a recorded failure.
                            waitsForRealEvent.set(true)
                            waitsForBusyState.set(true)
                            break
                        }
                        synchronized(productionRuntimeLifecycleLock) {
                            if (dualSession === owner) {
                                // Speculation is not a certification need: an exhausted round only
                                // ends this round's speculation. Recording RESOURCE for it would
                                // present a page that was merely waiting for a free capability
                                // as a genuine resource failure.
                                (requiredIds + optionalIds).forEach { id ->
                                    owner.requestOriginalAnalysis(id)?.let {
                                        owner.failOriginalAnalysis(it, ReaderAnalysisFailure.RESOURCE)
                                    }
                                }
                                publishDualSession(owner)
                            }
                        }
                        break
                    }
                }
                val publishedCount = java.util.concurrent.atomic.AtomicInteger(0)
                try {
                    kotlinx.coroutines.coroutineScope {
                        admitted.forEach { id ->
                            // Every reserved capability is registered before its content key
                            // exists, so a real need can promote the running capability in
                            // place instead of decoding the same page a second time.
                            val pairingDemand = DesktopReaderPairingDecodeDemand(
                                speculative = id in speculativeAdmitted,
                            )
                            val registration = PairingRegistration(
                                owner,
                                owner.location?.sequence,
                                pairingDemand,
                                null,
                            )
                            val taskJob = publisherScope.launch(start = CoroutineStart.LAZY) {
                                val role = if (id in admittedRequired) {
                                    "required"
                                } else if (id in speculativeAdmitted) {
                                    "speculative"
                                } else {
                                    "optional"
                                }
                                val publishedBefore = publishedCount.get()
                                val isRequired = id in admittedRequired
                                if (synchronized(productionRuntimeLifecycleLock) {
                                        if (dualSession !== owner || owner.isClosed ||
                                            owner.location?.sequence != sequence
                                        ) {
                                            true
                                        } else if (isRequired) {
                                            val target = owner.location?.pageId
                                            val spreadDependency = id in owner.spreadSampleDependencies()
                                            !spreadDependency && target != null &&
                                                owner.certifyPairing(target) is ReaderPairingCertification.Certified
                                        } else {
                                            val target = owner.location?.pageId
                                            val targetCert = target?.let { owner.certifyPairing(it) }
                                            // A page that still owes its edge samples is not a
                                            // certification need, but it is the preparation of
                                            // the visible pairing. Dropping it here would
                                            // leave the pair without samples for ever, because
                                            // the certified target produces no certification
                                            // needs any more.
                                            val spreadDependency = id in owner.spreadSampleDependencies()
                                            !spreadDependency && (
                                                targetCert is ReaderPairingCertification.Certified ||
                                                    id !in certificationNeedsLocked(owner).flatMap {
                                                        listOfNotNull(it.pageId, it.secondPageId)
                                                    }
                                                )
                                        }
                                    }
                                ) {
                                    // The capability has nothing to produce for this page
                                    // right now. The page still owes its one shot attempt
                                    // only to this attempt, and a page that owes its edge
                                    // samples later - the visible pair does, once the facts
                                    // settle - must stay admissible. The attempt is
                                    // therefore released instead of spent.
                                    pendingCompletionIds.add(id)
                                    p5SpreadPublisherRoundObserver?.onCompletionReported?.invoke(
                                        id,
                                        budget.holds(id),
                                    )
                                    completed.send(PairingCompletion(id, role, false, deferred = true))
                                    return@launch
                                }
                                var currentSample = synchronized(productionRuntimeLifecycleLock) {
                                    owner.requestOriginalAnalysis(id)
                                } ?: run {
                                    pendingCompletionIds.add(id)
                                    p5SpreadPublisherRoundObserver?.onCompletionReported?.invoke(
                                        id,
                                        budget.holds(id),
                                    )
                                    completed.send(PairingCompletion(id, role, false, deferred = true))
                                    return@launch
                                }
                                var sample = currentSample
                                val permit = ReaderDualPageFactReadPermit(
                                    budget,
                                    id,
                                    { System.nanoTime() / 1_000_000 },
                                    publisherOwnerKey,
                                )
                                var acquired = false
                                // A sampling that stops because a user visible decode owns the
                                // decoder is deferred, not failed: the analysis stays pending.
                                var deferredByForeground = false
                                var isDeferred = false
                                try {
                                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                                        kotlinx.coroutines.withTimeout(
                                            budget.remainingWaitMillis(System.nanoTime() / 1_000_000),
                                        ) {
                                            parallelism.acquire()
                                            acquired = true
                                            if (!runtime.session.loadFactPage(id, permit)) {
                                                throw IOException("Page content is unavailable")
                                            }
                                            val content = synchronized(productionRuntimeLifecycleLock) {
                                                if (dualSession !== owner ||
                                                    owner.isClosed ||
                                                    owner.location?.sequence != sequence
                                                ) {
                                                    return@synchronized null
                                                }
                                                // Materialization publishes the core before the UI
                                                // projection consumes it.
                                                val ready = runtime.session.state.value.snapshot
                                                if (ready.generation != owner.identity.generation) {
                                                    return@synchronized null
                                                }
                                                val page = ready.activeChapter.pages.firstOrNull {
                                                    it.id == id
                                                } ?: return@synchronized null
                                                if (page.loadState !is ReaderPageLoadState.Ready) {
                                                    return@synchronized null
                                                }
                                                val ref = page.encodedPageRef ?: return@synchronized null
                                                ReaderPageContentOpenRequest(
                                                    id,
                                                    ready.generation,
                                                    ref,
                                                    page.attemptGeneration,
                                                    page.encodedPageProvenance,
                                                ).takeIf(::matchesOriginalBinding)
                                            } ?: run {
                                                // Nothing was published, so the page still owes
                                                // its samples. Its one shot slot must be released
                                                // for a later attempt, otherwise the page can
                                                // never be prepared again.
                                                budget.defer(id)
                                                return@withTimeout
                                            }
                                            val ownsRegistration = synchronized(productionRuntimeLifecycleLock) {
                                                if (pairingRegistrations[id] !== registration ||
                                                    dualSession !== owner || owner.isClosed ||
                                                    owner.location?.sequence != sequence
                                                ) {
                                                    false
                                                } else {
                                                    registration.content = content
                                                    true
                                                }
                                            }
                                            if (!ownsRegistration) {
                                                budget.defer(id)
                                                return@withTimeout
                                            }
                                            if (!_state.value.autoSpreadMatching) {
                                                val request = synchronized(productionRuntimeLifecycleLock) {
                                                    if (dualSession !== owner ||
                                                        owner.isClosed ||
                                                        owner.location?.sequence != sequence
                                                    ) {
                                                        null
                                                    } else {
                                                        owner.requestFact(id)
                                                    }
                                                } ?: run {
                                                    budget.defer(id)
                                                    return@withTimeout
                                                }
                                                val header = runtime.pageContentOwner.acquireHeader(content)?.use {
                                                    originalHeader(it.content, request.content.rotation)
                                                }
                                                if (header != null) {
                                                    acceptDualFact(request, header, content)
                                                    publishedCount.incrementAndGet()
                                                    return@withTimeout
                                                }
                                            }
                                            val metadata = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                                // Original preparation can precede the next mounted holder after Retry.
                                                if (!runtime.pageImagePipeline.beginPageAttempt(
                                                        content.pageId,
                                                        content.generation,
                                                        content.attemptGeneration,
                                                    )
                                                ) {
                                                    throw CancellationException("Original page attempt became stale")
                                                }
                                                runtime.pageImagePipeline.acquireForPairing(
                                                    ReaderPageDecodeKey(
                                                        content,
                                                        PageDecodePurpose.FULL_PAGE,
                                                        PRESENTATION_DECODE_BOUND,
                                                        PRESENTATION_DECODE_BOUND,
                                                    ),
                                                    pairingDemand,
                                                )?.use {
                                                    val asset = it.asset
                                                    Triple(
                                                        originalGeometry(
                                                            asset.sourceWidth,
                                                            asset.sourceHeight,
                                                            asset.sourceOrientation,
                                                        ),
                                                        asset.originalSamples,
                                                        asset.sourceOrientation,
                                                    )
                                                }
                                            } ?: throw IOException("Normal page decode failed")
                                            val fact = synchronized(productionRuntimeLifecycleLock) {
                                                if (dualSession !== owner || owner.isClosed ||
                                                    owner.location?.sequence != sequence ||
                                                    pairingRegistrations[id] !== registration ||
                                                    !owner.ownsOriginalAnalysisRequest(currentSample)
                                                ) {
                                                    return@synchronized null
                                                }
                                                val rotation = currentSample.content.rotation
                                                val original = ReaderPageFact.dimensions(
                                                    metadata.first.width,
                                                    metadata.first.height,
                                                    rotation = rotation,
                                                    sourceOrientation = metadata.third,
                                                )
                                                if (acceptCurrentPhysicalContentsLocked(
                                                        owner,
                                                        runtime,
                                                        listOf(content),
                                                    ) {
                                                        if (owner.acceptOrientedFact(
                                                                owner.requestFact(id),
                                                                original,
                                                            ) == null
                                                        ) {
                                                            false
                                                        } else {
                                                            currentSample = owner.requestOriginalAnalysis(id)
                                                                ?: return@acceptCurrentPhysicalContentsLocked false
                                                            sample = currentSample
                                                            true
                                                        }
                                                    }
                                                ) {
                                                    original
                                                } else {
                                                    null
                                                }
                                            } ?: run {
                                                isDeferred = true
                                                return@withTimeout
                                            }
                                            val originalSamples = metadata.second[sample.content.rotation]
                                                ?: kotlinx.coroutines.withContext(Dispatchers.IO) {
                                                    runtime.pageImagePipeline.openRegionSession(content).use { region ->
                                                        val raw = sample.content.sourceOrientation
                                                            .orientedSize(fact.width, fact.height)
                                                        DesktopReaderSpreadSampler.sampleOriginal(
                                                            runtime.pageImagePipeline,
                                                            region,
                                                            raw.width,
                                                            raw.height,
                                                            sample.content.sourceOrientation,
                                                            sample.content.rotation,
                                                            canContinue = {
                                                                val remaining = budget.remainingWaitMillis(
                                                                    System.nanoTime() / 1_000_000,
                                                                ) > 0
                                                                val busy = pairingBusyState(runtime, publisherOwnerKey)
                                                                // A busy reader pauses the sampling, it does
                                                                // not fail it: the page keeps its pending
                                                                // analysis and waits for the real exit of
                                                                // the work that blocks it.
                                                                deferredByForeground = busy
                                                                remaining && !busy
                                                            },
                                                            pairingDemand = pairingDemand,
                                                        )
                                                    }
                                                }
                                            synchronized(productionRuntimeLifecycleLock) {
                                                if (dualSession === owner &&
                                                    !owner.isClosed &&
                                                    owner.location?.sequence == sequence &&
                                                    owner.ownsOriginalAnalysisRequest(sample)
                                                ) {
                                                    if (originalSamples == null) {
                                                        owner.failOriginalAnalysis(
                                                            sample,
                                                            ReaderAnalysisFailure.UNSUPPORTED,
                                                        )
                                                    } else {
                                                        val publication = ReaderOriginalAnalysisPublication(
                                                            sample,
                                                            fact,
                                                            originalSamples,
                                                        )
                                                        // The publication closes the whole admitted
                                                        // physical identity, not only its own page: a
                                                        // dependency that the real core replaced after
                                                        // the size gate must not be certified together
                                                        // with this late analysis.
                                                        val accepted = acceptCurrentPhysicalContentsLocked(
                                                            owner,
                                                            runtime,
                                                            listOf(content),
                                                        ) {
                                                            owner.acceptOriginalAnalyses(listOf(publication))
                                                        }
                                                        if (accepted) {
                                                            publishedCount.incrementAndGet()
                                                            publishDualSession(owner)
                                                        } else {
                                                            // The physical content was replaced
                                                            // before the publication, so the page
                                                            // still owes its samples and its
                                                            // one shot slot must be released for
                                                            // the new content.
                                                            isDeferred = true
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (paused: DesktopReaderOptionalWorkPaused) {
                                    if (deferredByForeground) {
                                        // The page was deferred, not failed. Its analysis stays
                                        // pending and its attempt slot is released, so the
                                        // publisher can admit it again once the user visible
                                        // decode has finished. The publisher does not admit new
                                        // work while that decode is in flight, so this cannot
                                        // become a busy loop.
                                        deferredPages.add(id)
                                        isDeferred = true
                                    } else {
                                        synchronized(productionRuntimeLifecycleLock) {
                                            if (dualSession === owner) {
                                                owner.failOriginalAnalysis(
                                                    sample,
                                                    ReaderAnalysisFailure.RESOURCE,
                                                )
                                            }
                                        }
                                    }
                                } catch (cancelled: CancellationException) {
                                    if (kotlinx.coroutines.currentCoroutineContext().isActive &&
                                        permit.exhausted
                                    ) {
                                        synchronized(productionRuntimeLifecycleLock) {
                                            if (dualSession === owner) {
                                                owner.failOriginalAnalysis(sample, ReaderAnalysisFailure.RESOURCE)
                                            }
                                        }
                                    } else {
                                        // The capability was cancelled before it published, so the
                                        // page still owes its samples. Its one shot slot must be
                                        // released, otherwise the page can never be prepared again.
                                        isDeferred = true
                                        throw cancelled
                                    }
                                } catch (_: Exception) {
                                    synchronized(productionRuntimeLifecycleLock) {
                                        if (dualSession === owner) {
                                            owner.failOriginalAnalysis(sample, ReaderAnalysisFailure.READ)
                                        }
                                    }
                                } finally {
                                    if (acquired) parallelism.release()
                                    // The capability exits here: its registration is
                                    // withdrawn and its completion is consumed by the
                                    // publisher, which re-plans the round from it.
                                    pairingRegistrations.remove(id, registration)
                                    try {
                                        // The budget slot stays reserved until the publisher
                                        // consumes this completion.
                                        pendingCompletionIds.add(id)
                                        p5SpreadPublisherRoundObserver?.onCompletionReported?.invoke(
                                            id,
                                            budget.holds(id),
                                        )
                                        completed.send(
                                            PairingCompletion(
                                                id,
                                                role,
                                                publishedCount.get() > publishedBefore,
                                                isDeferred,
                                            ),
                                        )
                                    } catch (closed: kotlinx.coroutines.channels.ClosedSendChannelException) {
                                        // The publisher stopped before this capability reported.
                                        if (isDeferred) {
                                            synchronized(productionRuntimeLifecycleLock) {
                                                if (dualSession === owner) {
                                                    budget.defer(id)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            registration.job = taskJob
                            pairingRegistrations[id] = registration
                            taskJobs[id] = taskJob
                            taskJob.start()
                        }
                    }
                    // One completion is consumed per round and the plan is rebuilt from
                    // it, so the publisher keeps advancing while another capability of
                    // the same round is still running.
                    p5SpreadPublisherCompletionGate?.invoke(taskJobs.keys.toSet())
                    val completion = completed.receive()
                    consumedCompletionIds += completion.pageId
                    consumedCompletionRoles[completion.pageId] = completion.role
                    // The consumed completion finally frees the logical capacity it held.
                    pendingCompletionIds.remove(completion.pageId)
                    releasePairingBudget(
                        PairingBudgetAdmission(owner, budget, publisherToken),
                        completion.pageId,
                        completion.deferred,
                    )
                    while (taskJobs.values.none { it.isActive } && pendingCompletionIds.isNotEmpty()) {
                        val next = completed.tryReceive().getOrNull() ?: break
                        consumedCompletionIds += next.pageId
                        consumedCompletionRoles[next.pageId] = next.role
                        pendingCompletionIds.remove(next.pageId)
                        releasePairingBudget(
                            PairingBudgetAdmission(owner, budget, publisherToken),
                            next.pageId,
                            next.deferred,
                        )
                    }
                } finally {
                    // A capability that was cancelled before it started releases its
                    // reservation here. A capability that already reported keeps its
                    // reservation until the publisher consumes its completion.
                    admitted.filter { id ->
                        taskJobs[id]?.isCompleted == true && id !in consumedCompletionIds &&
                            id !in pendingCompletionIds
                    }.forEach {
                        pendingCompletionIds.remove(it)
                        releasePairingBudget(PairingBudgetAdmission(owner, budget, publisherToken), it)
                    }
                }
                emitP5RoundDiagnostic(
                    owner = owner,
                    target = observedTarget,
                    certification = observedCertification,
                    admittedRequired = admittedRequired.toSet(),
                    admittedSpeculative = speculativeAdmitted.toSet(),
                    admittedOptional = admittedOptional.toSet(),
                    outstandingBefore = outstandingBefore,
                    consumedCompletionIds = consumedCompletionIds.toSet(),
                    consumedCompletionRoles = consumedCompletionRoles.toMap(),
                    pendingCompletionIds = pendingCompletionIds.toSet(),
                    outstandingAfter = taskJobs.keys.filter { taskJobs[it]?.isActive == true }.toSet(),
                    publishedCountAfter = publishedCount.get(),
                )
                if (publishedCount.get() == 0 && taskJobs.values.none { it.isActive } &&
                    pendingCompletionIds.isEmpty()
                ) {
                    // A capability may become unnecessary or unavailable without publishing.
                    // With no work left, only a changed input can make the next plan useful.
                    waitsForRealEvent.set(true)
                    noProgressInputRevision.set(classifiedInputRevision)
                    break
                }
            }
            // The publisher stops: every capability that still holds a reservation
            // releases it and the running ones are cancelled, so a new location can
            // use the freed slots.
            taskJobs.keys.forEach {
                pendingCompletionIds.remove(it)
                releasePairingBudget(PairingBudgetAdmission(owner, budget, publisherToken), it)
            }
            taskJobs.values.forEach { it.cancel() }
            completed.close()
        }
        spreadPublisherJob = job
        job.invokeOnCompletion {
            try {
                // The whole publisher returns its logical capacity, including a cancellation
                // that happened before its capabilities were registered as tasks. The physical
                // work of a cancelled capability stays busy until its actual exit.
                releasePairingBudgets(publisherToken)
                val aborted = abortedPublisherJobs.remove(job)
                // A publisher that waits for a real event is re-armed by the flow that observes
                // that event. Re-arming it here would restart it in an unbounded loop. The busy
                // state is the one exception: the work that blocks it exits on its own, and
                // that exit can land between this publisher's stop and its completion, so the
                // publisher arms itself again when the blocking work is already gone.
                // An aborted publisher whose binding failed must only clean up and not self re-arm:
                // bounded retry is controlled solely by the binding layer.
                val canRearm = synchronized(productionRuntimeLifecycleLock) {
                    !waitsForRealEvent.get() ||
                        (noProgressInputRevision.get() >= 0 && publisherInputRevision != noProgressInputRevision.get()) ||
                        (waitsForBusyState.get() && !pairingBusyState(runtime, publisherOwnerKey))
                }
                if (!disposeRequested && !aborted && canRearm) {
                    optionalWorkRevision.update { it + 1 }
                }
            } finally {
                abortedPublisherJobs.remove(job)
            }
        }
        // The previous publisher is cancelled and the new one is started outside the
        // lifecycle lock: the lock must not hold a cancel or a start.
        return SpreadPublisherTransition(oldJob, job)
    }

    fun acceptSessionState(reader: DesktopReaderSessionState) {
        mihon.desktop.reader.ReaderStartupTrace.mark("SESSION_ACCEPT_WAIT", generation = reader.snapshot.generation)
        synchronized(productionRuntimeLifecycleLock) {
            mihon.desktop.reader.ReaderStartupTrace.mark("SESSION_ACCEPT_LOCKED", generation = reader.snapshot.generation)
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
            val owner = ensureDualSession()
            if (owner?.pairingState?.status == ReaderPairingPersistenceStatus.WAITING_FOR_RESTORE) {
                beginPairingRestore(accepted, owner)
            }
        }
        mihon.desktop.reader.ReaderStartupTrace.mark("SESSION_ACCEPT_END", generation = reader.snapshot.generation)
    }

    private fun beginPairingRestore(
        reader: DesktopReaderSessionState,
        preparedOwner: SharedDualPageSession? = null,
    ) =
        synchronized(productionRuntimeLifecycleLock) {
            val coordinator = pairingCoordinator ?: return@synchronized
            // Supplied only by an entry that just prepared this owner under the same lifecycle lock.
            // Independent retry/recovery calls must still prepare the current session here.
            val owner = preparedOwner ?: ensureDualSession() ?: return@synchronized
            if (disposeRequested || dualSession !== owner || owner.isClosed) return@synchronized
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
            matchesOriginalBinding(physical) &&
                acceptCurrentPhysicalContentsLocked(owner, runtime, listOf(physical), commit)
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
            val samples = asset.originalSamples[content.rotation]
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
                    val geometryAccepted = owner.acceptOrientedFact(owner.requestFact(page.id), fact) != null
                    geometryAccepted && (
                        samples == null || owner.requestOriginalAnalysis(page.id)?.let { request ->
                            owner.acceptOriginalAnalysis(request, fact, samples)
                        } == true
                        )
                }
            }
            val accepted = runtime?.let {
                acceptCurrentPhysicalContentsLocked(owner, it, listOf(current), commit)
            } ?: commit()
            accepted.also { if (it) publishDualSession(owner) }
        }
    }

    internal fun registerPagerViewportDemand(
        token: Any,
        visiblePages: VisiblePageSet,
        logicalLocation: ReaderDualPageLocationIntent?,
    ): Boolean {
        var promotions = emptyList<PairingPromotion>()
        val accepted = synchronized(productionRuntimeLifecycleLock) {
            val owner = ensureDualSession() ?: return@synchronized false
            certificationNeedsLocked(owner)
            val existing = pagerViewportDemands[token]
            if (existing != null && existing.anchor == visiblePages.activePageId &&
                existing.content.map { it.pageId }.toSet() == visiblePages.pageIds
            ) {
                return@synchronized true
            }
            if (logicalLocation != owner.location || visiblePages.transitionDirection != null) {
                return@synchronized false
            }
            val anchor = visiblePages.activePageId ?: return@synchronized false
            val content = owner.identity.content.filter { it.pageId in visiblePages.pageIds }
            if (content.map { it.pageId }.toSet() != visiblePages.pageIds) return@synchronized false
            pagerViewportDemands[token] = PagerViewportDemand(owner, anchor, content)
            promotions = promotePairingDemandsLocked(owner, visiblePages.pageIds)
            optionalWorkRevision.update { it + 1 }
            true
        }
        if (accepted) runtime?.let { promotePairingEntries(it, promotions) }
        return accepted
    }

    internal fun releasePagerViewportDemand(token: Any) = synchronized(productionRuntimeLifecycleLock) {
        if (pagerViewportDemands.remove(token) != null) optionalWorkRevision.update { it + 1 }
    }

    /** Called at actual selected presentation start, before its image leases begin. */
    internal fun beginPagerViewport(
        visiblePages: VisiblePageSet,
        userNavigation: Boolean,
        logicalLocation: ReaderDualPageLocationIntent? = _state.value.dualPageLocation,
        selected: Boolean = true,
        demandToken: Any? = null,
        canDraw: Boolean = true,
    ): ReaderDualPageViewportTicket? = synchronized(productionRuntimeLifecycleLock) {
        val owner = ensureDualSession() ?: return@synchronized null
        certificationNeedsLocked(owner)
        val mounted = demandToken?.let { pagerViewportDemands[it] }?.let {
            it.owner === owner && it.anchor == visiblePages.activePageId &&
                it.content.map { content -> content.pageId }.toSet() == visiblePages.pageIds
        } == true
        if (logicalLocation != owner.location && !mounted) return@synchronized null
        if (visiblePages.displayUnitId.sharedUnitId != null &&
            logicalLocation != owner.location
        ) {
            return@synchronized null
        }
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
            val shared = visiblePages.displayUnitId
            if (shared.sharedUnitId != null && shared.frameId == null) {
                val proof = owner.certifyPairing(anchor) as? ReaderPairingCertification.Certified
                owner.preparePresentationFrame(
                    anchor,
                    proof?.segment?.unit?.id ?: shared.sharedUnitId,
                    activate = false,
                )
                publishDualSession(owner)
                return@synchronized null
            }
            if (!canDraw) return@synchronized null
            return@synchronized owner.capturePresentableViewport(
                anchor,
                shared.sharedUnitId,
                shared.frameId,
                activate = false,
                leaseOwner = demandToken,
            )
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
        val actualAnchor = owner.location!!.pageId.takeIf { it in visiblePages.pageIds }
            ?: visiblePages.pageIds.minBy { it.sourcePageIndex }
        val scheduled = SettledViewportIdentity(
            owner.identity.generation,
            visiblePages.pageIds,
            actualAnchor,
        )
        if (lastLayoutScheduledViewport != scheduled) {
            lastLayoutScheduledViewport = scheduled
            onViewportLayoutSettled(visiblePages.pageIds, actualAnchor)
        }
        owner.location?.pageId?.let { certificationTargets += it }
        optionalWorkRevision.update { it + 1 }
        if (visiblePages.displayUnitId.sharedUnitId != null && visiblePages.displayUnitId.frameId == null) {
            owner.preparePresentationFrame()
            publishDualSession(owner)
            return@synchronized null
        }
        if (!canDraw) return@synchronized null
        owner.capturePresentableViewport(
            owner.location!!.pageId,
            visiblePages.displayUnitId.sharedUnitId,
            visiblePages.displayUnitId.frameId,
            leaseOwner = demandToken,
        )
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
        mihon.desktop.reader.ReaderStartupTrace.mark("DUAL_PREPARE_LOCKED", page.id)
        if (!isCurrentDualContent(page)) return@synchronized false
        val owner = dualSession ?: return@synchronized false
        if (owner.frame(ticket) == null) return@synchronized false
        val revision = owner.layoutRevision
        mihon.desktop.reader.ReaderStartupTrace.mark("DUAL_FACT_BEGIN", page.id)
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
        mihon.desktop.reader.ReaderStartupTrace.mark("DUAL_FACT_END", page.id)
        if (revision != owner.layoutRevision) publishDualSession(owner)
        owner.preparePage(ticket, page.id).also {
            mihon.desktop.reader.ReaderStartupTrace.mark("DUAL_PREPARE_END", page.id, detail = if (it) 1 else 0)
        }
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
            val owner = dualSession
            if (owner?.releaseViewport(ticket) == true) {
                deferredContentState?.let(::acceptSessionState)
                if (dualSession === owner && !owner.closed) publishDualSession(owner)
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
        val publisherToCancel = synchronized(productionRuntimeLifecycleLock) {
            val previousPublisher = spreadPublisherJob
            spreadPublisherJob = null
            failedSpreadReads.clear()
            externalInputEpoch++
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
            previousPublisher
        }
        publisherToCancel?.cancel()
        onPageRetry(pageId)
    }

    fun requestRetry() {
        val state = _state.value
        val chapter = state.session.activeChapter
        if (chapter.loadState is ReaderChapterLoadState.Error || chapter.pages.isEmpty()) {
            synchronized(productionRuntimeLifecycleLock) {
                externalInputEpoch++
                dualSession?.retryFacts()
            }
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

    fun toggleFullscreen() {
        _state.update { current ->
            val nextFullscreen = !current.isFullscreen
            current.copy(
                isFullscreen = nextFullscreen,
                showUI = if (nextFullscreen) false else true,
            )
        }
    }

    fun setFullscreen(fullscreen: Boolean) {
        _state.update { current ->
            if (current.isFullscreen == fullscreen) {
                current
            } else {
                current.copy(
                    isFullscreen = fullscreen,
                    showUI = if (fullscreen) false else true,
                )
            }
        }
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
            var stateChanged = false
            _state.update { current ->
                val changed = transform(current)
                if (changed == current) return@update current
                stateChanged = true
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
            if (stateChanged) {
                externalInputEpoch++
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
        synchronized(productionRuntimeLifecycleLock) {
            var stateChanged = false
            _state.update { state ->
                if (state.autoSplitPages == on) return@update state
                stateChanged = true
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
            if (stateChanged) {
                externalInputEpoch++
            }
        }
        prefs?.autoSplitPages = on
    }

    fun setAutoSpreadMatching(on: Boolean, prefs: ReaderPreferences? = null) {
        changePresentation { it.copy(autoSpreadMatching = on) }
        prefs?.isAutoSpreadMatching = on
    }

    fun setSpreadPages(pages: Set<Int>) {
        // A decoded neighbor can publish metadata without changing the active physical frame.
        // Keep its selected viewport and progress; shared original facts own any later regrouping.
        _state.update { it.copy(spreadPages = pages) }
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
        var publisherToCancel: Job? = null
        val closeRuntime = synchronized(productionRuntimeLifecycleLock) {
            disposeRequested = true
            dualSession?.close()
            factPublisherJob?.cancel()
            publisherToCancel = spreadPublisherJob
            spreadPublisherJob = null
            certificationTargets.clear()
            failedSpreadReads.clear()
            pendingHeaderTickets.clear()
            headerJobs.values.forEach(Job::cancel)
            headerJobs.clear()
            markProductionRuntimeClosedIfReady()
        }
        publisherToCancel?.cancel()
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

internal fun resolveInitialPage(requestedPage: Int, pageCount: Int): Int = when {
    pageCount <= 0 -> 0
    requestedPage == ReaderInitialPage.LAST -> pageCount - 1
    else -> requestedPage.takeIf { it in 0 until pageCount } ?: 0
}

internal fun invalidSavedPage(requestedPage: Int, pageCount: Int): Boolean =
    pageCount > 0 && requestedPage != ReaderInitialPage.LAST && requestedPage !in 0 until pageCount

internal fun initialReaderMode(
    prefs: ReaderPreferences,
    isWebtoon: Boolean,
    mangaViewerFlags: Long,
    dualPageOverride: Boolean?,
): ReadingMode =
    when {
        isWebtoon -> ReadingMode.WEBTOON
        readingModeFromViewerFlags(mangaViewerFlags) == null && prefs.readingMode == ReadingMode
            .AUTO &&
            (dualPageFromViewerFlags(mangaViewerFlags) != null || dualPageOverride != null) ->
            ReadingMode.RTL
        else -> readingModeFromViewerFlags(mangaViewerFlags) ?: prefs.readingMode
    }

internal fun initialReaderState(
    prefs: ReaderPreferences,
    reader: DesktopReaderSessionState,
    isWebtoon: Boolean,
    mangaViewerFlags: Long,
    dualPageOverride: Boolean?,
    hasPairingCoordinator: Boolean,
): ReaderState {
    val resolvedMode = initialReaderMode(prefs, isWebtoon, mangaViewerFlags, dualPageOverride)
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
        pairingLoad = if (hasPairingCoordinator && !reader.context
                .isTemporaryReaderFile()
        ) {
            PairingLoad.LOADING
        } else {
            PairingLoad.READY
        },
        pairingSessionOnly = hasPairingCoordinator && reader.context
            .isTemporaryReaderFile(),
        pairingNotice = if (hasPairingCoordinator && reader.context
                .isTemporaryReaderFile()
        ) {
            PairingNotice.SESSION_ONLY
        } else {
            null
        },
    )
}
