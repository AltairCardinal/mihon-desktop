package mihon.desktop.ui.reader

import cafe.adriel.voyager.core.model.ScreenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
import mihon.domain.reader.session.ReaderPageLoadState
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
    private var webtoonRestoreAnchor: ReaderPageId? = null
    private var manualDualPage = dualPageFromViewerFlags(mangaViewerFlags) ?: dualPageOverride ?: prefs.isDualPage
    private val productionRuntimeLifecycleLock = Any()
    private var retainedCompositionCount = 0

    @Volatile private var disposeRequested = false
    private var productionRuntimeClosed = false

    @Volatile private var pairingEpoch = 0L

    @Volatile private var pairingRequest: PairingRequest? = null

    private var dualSession: mihon.domain.reader.SharedDualPageSession? = null
    private var dualContentRevision = 0L
    private var dualMangaId: Long? = null
    private val dualContentStamps = mutableMapOf<ReaderPageId, DualContentStamp>()
    private val dualContentVersions = mutableMapOf<ReaderPageId, Long>()
    private var deferredContentState: DesktopReaderSessionState? = null

    init {
        synchronized(productionRuntimeLifecycleLock) { ensureDualSession() }
        beginPairingRestore(initialSessionState)
    }

    /** All shared presentation operations run under the existing model lifecycle owner lock. */
    private fun ensureDualSession(): mihon.domain.reader.SharedDualPageSession? {
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
            previous.identity.generation == current.session.generation && dualMangaId == current.context.mangaId
        if (!sameOrder) {
            dualContentStamps.clear()
            dualContentVersions.clear()
        }
        pages.forEach { page ->
            val stamp = DualContentStamp(page.url, page.imageUrl, page.encodedPageRef, page.attemptGeneration)
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
            mihon.domain.reader.ReaderDualPageContentIdentity(
                id,
                version,
                sourceOrientation = previous?.identity?.content?.firstOrNull {
                    it.pageId == id &&
                        it.contentVersion == version
                }
                    ?.sourceOrientation ?: mihon.domain.reader.ReaderImageOrientation.NORMAL,
            )
        }
        if (!sameOrder || previous?.identity?.content != content) {
            dualMangaId = current.context.mangaId
            val identity = mihon.domain.reader.ReaderDualPageIdentity(
                current.session.generation,
                ++dualContentRevision,
                content,
            )
            dualSession = if (sameOrder) {
                requireNotNull(previous).rebindContent(identity)
            } else {
                previous?.close()
                mihon.domain.reader.SharedDualPageSession(
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
                mihon.domain.reader.ReaderPresentationMode.DUAL
            } else {
                mihon.domain.reader.ReaderPresentationMode.SINGLE
            },
        )
        publishDualSession(owner)
        return owner
    }

    private fun dualDirection(state: ReaderState) = if (state.readingMode == ReadingMode.RTL) {
        mihon.domain.reader.ReaderDirection.RTL
    } else {
        mihon.domain.reader.ReaderDirection.LTR
    }

    private fun publishDualSession(owner: mihon.domain.reader.SharedDualPageSession) {
        val pairing = owner.pairingState
        val status = pairing.status
        _state.update { current ->
            val notice = when {
                status == mihon.domain.reader.ReaderPairingPersistenceStatus.SAVE_FAILED ||
                    status == mihon.domain.reader.ReaderPairingPersistenceStatus.CONFLICT -> PairingNotice.SAVE_FAILED
                pairing.notice == mihon.domain.reader.ReaderPairingPersistenceNotice.INVALID_RECORD ->
                    PairingNotice.INVALID
                status == mihon.domain.reader.ReaderPairingPersistenceStatus.TEMPORARY &&
                    pairingCoordinator != null -> PairingNotice.SESSION_ONLY
                else -> current.pairingNotice
            }
            current.copy(
                dualPageUnits = owner.units, dualPageIdentity = owner.identity,
                dualPageLayoutRevision = owner.layoutRevision, dualFactWorkRevision = owner.factWorkRevision,
                dualPageLocation = owner.location, dualPairingState = pairing,
                forcedSinglePages = pairing.committedForcedSinglePages, pairingRevision = pairing.revision ?: 0,
                pairingSaving = status == mihon.domain.reader.ReaderPairingPersistenceStatus.SAVING,
                pairingSessionOnly = status == mihon.domain.reader.ReaderPairingPersistenceStatus.TEMPORARY,
                pairingLoad = when (status) {
                    mihon.domain.reader.ReaderPairingPersistenceStatus.WAITING_FOR_RESTORE,
                    mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORING,
                    -> PairingLoad.LOADING
                    mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORE_FAILED -> PairingLoad.ERROR
                    mihon.domain.reader.ReaderPairingPersistenceStatus.DEFAULT_UNVERIFIED ->
                        PairingLoad.DEFAULT_UNVERIFIED
                    else -> PairingLoad.READY
                },
            ).let { if (notice != null && notice != current.pairingNotice) it.withPairingNotice(notice) else it }
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
    private val optionalWorkRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private var spreadPublisherOwner: mihon.domain.reader.SharedDualPageSession? = null
    private val failedSpreadReads = mutableSetOf<mihon.domain.reader.ReaderDualPageContentIdentity>()
    private val headerJobs = mutableMapOf<HeaderWork, Job>()
    private val pendingHeaderTickets = mutableMapOf<ReaderPageId, mihon.domain.reader.ReaderDualPageFactRequest>()
    private data class HeaderWork(
        val owner: mihon.domain.reader.SharedDualPageSession,
        val workRevision: Long,
        val request: mihon.domain.reader.content.ReaderPageContentOpenRequest,
    )

    internal fun bindDualFactPublisher(scope: CoroutineScope) {
        val runtime = runtime ?: return
        synchronized(productionRuntimeLifecycleLock) {
            if (factPublisherJob != null || disposeRequested) return
            val parallelism = kotlinx.coroutines.sync.Semaphore(1)
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
                        val nearby = owner.factDependencies().toSet() + owner.targetUnit?.pageIds.orEmpty()
                        val work = current.session.activeChapter.pages.filter {
                            it.id in nearby && it.loadState is mihon.domain.reader.session.ReaderPageLoadState.Ready &&
                                (it.id == owner.location?.pageId || optionalWorkCanRun(runtime))
                        }
                            .mapNotNull { page ->
                                page.encodedPageRef?.let { ref ->
                                    HeaderWork(
                                        owner,
                                        owner.factWorkRevision,
                                        mihon.domain.reader.content.ReaderPageContentOpenRequest(
                                            page.id,
                                            current.session.generation,
                                            ref,
                                            page.attemptGeneration,
                                            page.encodedPageProvenance,
                                        ),
                                    )
                                }
                            }
                        headerJobs.keys.toList().filter { it !in work }.forEach { headerJobs.remove(it)?.cancel() }
                        work.filter { it !in headerJobs }.forEach { item ->
                            val original = pendingHeaderTickets[item.request.pageId]?.takeIf {
                                owner.ownsFactRequest(it)
                            } ?: owner.requestFact(item.request.pageId)
                            pendingHeaderTickets[item.request.pageId] = original
                            headerJobs[item] = scope.launch {
                                parallelism.acquire()
                                try {
                                    if (item.request.pageId != owner.location?.pageId && !optionalWorkCanRun(runtime)) {
                                        throw mihon.desktop.reader.DesktopReaderOptionalWorkPaused()
                                    }
                                    val lease = runtime.pageContentOwner.acquireHeader(item.request)
                                    val fact = lease?.use {
                                        mihon.desktop.reader.SkiaImageDecoder.peekSize(it.content)?.let { size ->
                                            mihon.domain.reader.ReaderPageFact.dimensions(
                                                size.first,
                                                size.second,
                                                sourceOrientation =
                                                mihon.domain.reader.readImageOrientation(it.content),
                                            )
                                        }
                                    }
                                        ?: mihon.domain.reader.ReaderPageFact(
                                            mihon.domain.reader.ReaderPageFactState.FAILED,
                                        )
                                    acceptDualFact(original, fact)
                                } catch (_: mihon.desktop.reader.DesktopReaderOptionalWorkPaused) {
                                    synchronized(productionRuntimeLifecycleLock) { headerJobs.remove(item) }
                                    optionalWorkRevision.update { it + 1 }
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    acceptDualFact(
                                        original,
                                        mihon.domain.reader.ReaderPageFact(
                                            mihon.domain.reader.ReaderPageFactState.FAILED,
                                        ),
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

    private fun optionalWorkCanRun(runtime: DesktopReaderRuntime): Boolean =
        runtime.session.foregroundRequestsIdle && runtime.pageImagePipeline.foregroundWork.value == 0 &&
            runtime.pageImagePipeline.memoryAuthority.snapshots.value.overBudgetBytes == 0L

    /** One optional pass; resource deferral is distinct from failed evidence. */
    private fun startSpreadSamplesLocked(
        owner: mihon.domain.reader.SharedDualPageSession,
        runtime: DesktopReaderRuntime,
        scope: CoroutineScope,
        parallelism: kotlinx.coroutines.sync.Semaphore,
    ) {
        val state = _state.value
        if (!state.autoSpreadMatching || !state.dualPageMode || spreadPublisherOwner !== owner) {
            spreadPublisherJob?.cancel()
            failedSpreadReads.clear()
            spreadPublisherOwner = owner
        }
        if (!state.autoSpreadMatching || !state.dualPageMode || spreadPublisherJob?.isCompleted == false ||
            owner.spreadSampleDependencies().isEmpty() || !optionalWorkCanRun(runtime) ||
            runtime.pageImagePipeline.optionalWork.value != 0
        ) {
            return
        }
        val sequence = owner.location?.sequence
        var paused = false
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                while (true) {
                    val work = synchronized(productionRuntimeLifecycleLock) {
                        if (disposeRequested || dualSession !== owner || owner.location?.sequence != sequence) {
                            return@synchronized null
                        }
                        if (!optionalWorkCanRun(runtime)) throw mihon.desktop.reader.DesktopReaderOptionalWorkPaused()
                        val id = owner.spreadSampleDependencies().firstOrNull() ?: return@synchronized null
                        val content = owner.identity.content.single { it.pageId == id }
                        if (content in failedSpreadReads) return@synchronized null
                        val page = _state.value.session.activeChapter.pages.firstOrNull { it.id == id }
                            ?.takeIf { it.loadState is ReaderPageLoadState.Ready } ?: return@synchronized null
                        val encoded = page.encodedPageRef ?: return@synchronized null
                        val sample = owner.requestSpreadSample(id) ?: return@synchronized null
                        sample to mihon.domain.reader.content.ReaderPageContentOpenRequest(
                            id,
                            _state.value.session.generation,
                            encoded,
                            page.attemptGeneration,
                            page.encodedPageProvenance,
                        )
                    } ?: break
                    val (sample, content) = work
                    parallelism.acquire()
                    val samples = try {
                        if (!optionalWorkCanRun(runtime)) throw mihon.desktop.reader.DesktopReaderOptionalWorkPaused()
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            // Settings can consume a retried encoded page before a new presentation
                            // mounts. Advance only the attempt captured from our accepted snapshot.
                            if (!runtime.pageImagePipeline.beginPageAttempt(
                                    content.pageId,
                                    content.generation,
                                    content.attemptGeneration,
                                )
                            ) {
                                throw mihon.desktop.reader.DesktopReaderOptionalWorkPaused()
                            }
                            runtime.pageImagePipeline.openRegionSession(content).use { region ->
                                if (!optionalWorkCanRun(runtime)) {
                                    throw mihon.desktop.reader.DesktopReaderOptionalWorkPaused()
                                }
                                val size = region.withContent(mihon.desktop.reader.SkiaImageDecoder::peekSize)
                                    ?: return@use null
                                val raw = sample.content.sourceOrientation.orientedSize(size.first, size.second)
                                mihon.desktop.reader.DesktopReaderSpreadSampler.sample(
                                    runtime.pageImagePipeline,
                                    region,
                                    raw.width,
                                    raw.height,
                                    sample.content.sourceOrientation,
                                    canContinue = { optionalWorkCanRun(runtime) },
                                )
                            }
                        }
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    } finally {
                        parallelism.release()
                    }
                    val progressed = synchronized(productionRuntimeLifecycleLock) {
                        if (dualSession === owner && !owner.ownsSpreadSampleRequest(sample) &&
                            owner.identity.content.any { it.pageId == sample.content.pageId && it != sample.content }
                        ) {
                            true // Same-byte metadata changed; re-enter using the renewed original capability.
                        } else if (dualSession !== owner || !owner.ownsSpreadSampleRequest(sample)) {
                            false
                        } else if (samples == null) {
                            failedSpreadReads += sample.content
                            false
                        } else {
                            owner.acceptSpreadSample(sample, samples).also { if (it) publishDualSession(owner) }
                        }
                    }
                    if (!progressed) break
                }
            } catch (_: mihon.desktop.reader.DesktopReaderOptionalWorkPaused) {
                paused = true
            }
        }
        spreadPublisherJob = job
        job.invokeOnCompletion { cause ->
            // Idle can arrive while a final region lease is still closing. Re-evaluate after the
            // original job actually completes, rather than relying on that earlier single signal.
            if (!disposeRequested && (
                    paused || cause != null || dualSession !== owner ||
                        owner.location?.sequence != sequence || _state.value.readingMode != state.readingMode
                    )
            ) {
                optionalWorkRevision.update { it + 1 }
            }
        }
        job.start()
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

    fun acceptSessionState(reader: DesktopReaderSessionState) = synchronized(productionRuntimeLifecycleLock) {
        val previous = _state.value
        val sameContentList = previous.session.generation == reader.snapshot.generation &&
            previous.context.chapterId == reader.context.chapterId &&
            previous.context.mangaId == reader.context.mangaId &&
            previous.session.activeChapter.pages.map { it.id } == reader.snapshot.activeChapter.pages.map { it.id }
        val protected = if (sameContentList) dualSession?.visibleContentPageIds.orEmpty() else emptySet()
        val originals = previous.session.activeChapter.pages.filter { it.id in protected }.associateBy { it.id }
        var deferred = false
        val acceptedPages = reader.snapshot.activeChapter.pages.map { incoming ->
            val original = originals[incoming.id]
            if (original != null &&
                (
                    original.url != incoming.url || original.imageUrl != incoming.imageUrl ||
                        original.attemptGeneration != incoming.attemptGeneration ||
                        (original.encodedPageRef != null && original.encodedPageRef != incoming.encodedPageRef)
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
                current.session.activeChapter.pages.size != accepted.snapshot.activeChapter.pages.size
            val firstStablePageList = current.session.activeChapter.pages.isEmpty() &&
                accepted.snapshot.activeChapter.pages.isNotEmpty()
            val pageCount = accepted.snapshot.activeChapter.pages.size
            val currentPage = if (chapterChanged || firstStablePageList) {
                resolveInitialPage(accepted.context.initialPage, pageCount)
            } else {
                current.currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            }
            current.copy(
                context = accepted.context,
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
            webtoonRestoreAnchor = null
            pairingEpoch++
            pairingRequest = null
        }
        ensureDualSession()
        beginPairingRestore(accepted)
    }

    private fun beginPairingRestore(reader: DesktopReaderSessionState) = synchronized(productionRuntimeLifecycleLock) {
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
                mihon.domain.reader.ReaderPairingPersistenceStatus.WAITING_FOR_RESTORE,
                mihon.domain.reader.ReaderPairingPersistenceStatus.RESTORE_FAILED,
                mihon.domain.reader.ReaderPairingPersistenceStatus.CONFLICT,
                mihon.domain.reader.ReaderPairingPersistenceStatus.DEFAULT_UNVERIFIED,
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
                        if (!disposeRequested && dualSession === owner && owner.acceptPairingRestore(
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
        if (current.pairingLoad == PairingLoad.ERROR || current.pairingLoad == PairingLoad.DEFAULT_UNVERIFIED) {
            beginPairingRestore(DesktopReaderSessionState(current.context, current.session))
        }
    }

    fun useDefaultPairingThisSession() = synchronized(productionRuntimeLifecycleLock) {
        dualSession?.let { if (it.useDefaultPairingForSession()) publishDualSession(it) }
    }

    fun activateChapter(context: DesktopReaderChapterContext) {
        onChapterActivated(context)?.let(::acceptSessionState)
    }

    fun updateNextChapterPrefetch(
        context: DesktopReaderChapterContext?,
        firstViewportPageCount: Int,
    ) {
        onNextChapterPrefetchChanged(context, firstViewportPageCount)
    }

    fun goToPage(page: Int) = synchronized(productionRuntimeLifecycleLock) {
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

    /** Adjacent user turns follow actual shared presentation history, including missing partners. */
    internal fun navigateDualPage(forward: Boolean): Boolean = synchronized(productionRuntimeLifecycleLock) {
        if (!_state.value.dualPageMode ||
            _state.value.currentDisplayUnitId?.slots?.any { it.transitionDirection != null } == true
        ) {
            return@synchronized false
        }
        val owner = ensureDualSession() ?: return@synchronized false
        if (!owner.navigate(forward)) return@synchronized false
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

    internal fun captureDualFact(pageId: ReaderPageId): mihon.domain.reader.ReaderDualPageFactRequest? =
        synchronized(productionRuntimeLifecycleLock) {
            ensureDualSession()?.takeIf { pageId in it.identity.pageIds }?.requestFact(pageId)
        }

    internal fun acceptDualFact(
        request: mihon.domain.reader.ReaderDualPageFactRequest,
        fact: mihon.domain.reader.ReaderPageFact,
    ): Boolean = synchronized(productionRuntimeLifecycleLock) {
        val owner = ensureDualSession() ?: return@synchronized false
        (owner.acceptOrientedFact(request, fact) != null).also { if (it) publishDualSession(owner) }
    }

    /** Called at actual selected presentation start, before its image leases begin. */
    internal fun beginPagerViewport(
        visiblePages: VisiblePageSet,
        userNavigation: Boolean,
        logicalLocation: mihon.domain.reader.ReaderDualPageLocationIntent? = _state.value.dualPageLocation,
        selected: Boolean = true,
    ): mihon.domain.reader.ReaderDualPageViewportTicket? = synchronized(productionRuntimeLifecycleLock) {
        val owner = ensureDualSession() ?: return@synchronized null
        val currentUserNavigation = userNavigation && logicalLocation == owner.location
        if (visiblePages.transitionDirection != null) {
            // An outgoing native transition cannot replace a newer logical-page navigation.
            if (!currentUserNavigation && _state.value.currentDisplayUnitId != visiblePages.displayUnitId) {
                return@synchronized null
            }
            _state.update { it.copy(currentDisplayUnitId = visiblePages.displayUnitId, visiblePageIds = emptySet()) }
            return@synchronized null
        }
        if (currentUserNavigation && owner.location?.pageId !in visiblePages.pageIds) {
            val anchor = visiblePages.activePageId ?: return@synchronized null
            val current = owner.location?.pageId ?: return@synchronized null
            if (!owner.navigate(anchor.sourcePageIndex > current.sourcePageIndex)) return@synchronized null
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
            return@synchronized owner.captureViewport(anchor, preparingPageIds = emptySet())
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
        owner.captureViewport(preparingPageIds = emptySet())
    }

    internal fun prepareDualViewportPage(ticket: mihon.domain.reader.ReaderDualPageViewportTicket, page: ReaderPageId) =
        synchronized(productionRuntimeLifecycleLock) { dualSession?.preparePage(ticket, page) == true }

    internal fun isDualViewportPageLocked(
        ticket: mihon.domain.reader.ReaderDualPageViewportTicket,
        page: ReaderPageId,
    ) =
        synchronized(productionRuntimeLifecycleLock) { page in dualSession?.frame(ticket)?.lockedPageIds.orEmpty() }

    internal fun isCurrentDualContent(page: mihon.domain.reader.session.ReaderPageSession): Boolean =
        synchronized(productionRuntimeLifecycleLock) {
            val current = _state.value.session.activeChapter.pages.getOrNull(page.id.sourcePageIndex)
            current?.id == page.id && current.attemptGeneration == page.attemptGeneration &&
                current.encodedPageRef == page.encodedPageRef && current.url == page.url &&
                current.imageUrl == page.imageUrl
        }

    internal fun prepareDecodedDualViewportPage(
        ticket: mihon.domain.reader.ReaderDualPageViewportTicket,
        page: mihon.domain.reader.session.ReaderPageSession,
        width: Int,
        height: Int,
        sideHint: mihon.domain.reader.ReaderPageSideHint = mihon.domain.reader.ReaderPageSideHint.UNKNOWN,
    ): Boolean = synchronized(productionRuntimeLifecycleLock) {
        if (!isCurrentDualContent(page)) return@synchronized false
        val owner = dualSession ?: return@synchronized false
        if (owner.frame(ticket) == null) return@synchronized false
        val revision = owner.layoutRevision
        if (!owner.acceptDecodedFact(ticket, page.id, width, height, sideHint)) return@synchronized false
        if (revision != owner.layoutRevision) publishDualSession(owner)
        owner.preparePage(ticket, page.id)
    }

    internal fun isCurrentDualViewport(ticket: mihon.domain.reader.ReaderDualPageViewportTicket) =
        synchronized(productionRuntimeLifecycleLock) { dualSession?.frame(ticket) != null }

    internal fun drawDualViewportPage(ticket: mihon.domain.reader.ReaderDualPageViewportTicket, page: ReaderPageId) =
        synchronized(productionRuntimeLifecycleLock) { dualSession?.reportDrawn(ticket, setOf(page)) == true }

    internal fun releaseDualViewport(ticket: mihon.domain.reader.ReaderDualPageViewportTicket) {
        synchronized(productionRuntimeLifecycleLock) {
            if (dualSession?.releaseViewport(ticket) == true) {
                deferredContentState?.let(::acceptSessionState)
            }
        }
    }

    internal fun settleDualPage(
        visiblePages: VisiblePageSet,
        ticket: mihon.domain.reader.ReaderDualPageViewportTicket,
    ) = synchronized(productionRuntimeLifecycleLock) {
        val owner = ensureDualSession() ?: return@synchronized
        val current = _state.value
        val event = mihon.domain.reader.ReaderDualPageSettlement(
            ticket.identity, ticket.layoutRevision,
            ticket.locationSequence, ticket.unitId, visiblePages.pageIds, true,
            mihon.domain.reader.ReaderDualPageSettlementReason.USER_NAVIGATION, ticket.locationSequence, ticket,
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
        val actualAnchor = target.takeIf { it in presented } ?: presented.minBy { it.sourcePageIndex }
        onViewportSettled(presented, actualAnchor)
    }

    private fun settleWebtoonVisiblePages(
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
        val layoutOnly = webtoonRestoreAnchor?.let { it in visiblePages.pageIds } == true
        val activePageId = visiblePages.activePageId ?: visiblePages.pageIds.maxByOrNull(ReaderPageId::sourcePageIndex)
            ?: return
        val current = _state.value
        val pages = current.session.activeChapter.pages
        if (activePageId.chapterId != current.session.activeChapter.id || pages.none { it.id == activePageId }) return
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
                if (_state.value.automaticLayout && pendingLayout == target) applyAutomaticLayout(target)
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

    /** Resolve display position independently of whether a layout-only settle may report progress. */
    private fun changePresentation(preserveProgress: Boolean = true, transform: (ReaderState) -> ReaderState) =
        synchronized(productionRuntimeLifecycleLock) {
            _state.update { current ->
                val changed = transform(current)
                if (changed == current) return@update current
                webtoonRestoreAnchor =
                    changed.session.activeChapter.pages.getOrNull(changed.currentPage)?.id.takeIf {
                        preserveProgress &&
                            changed.readingMode == ReadingMode.WEBTOON
                    }
                changed.copy(currentDisplayUnitId = null, visiblePageIds = emptySet(), webtoonScrollAnchor = null)
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
                replace(
                    context.chapterId,
                    request.mangaId,
                    request.expectedRevision,
                    request.identity.pageIds.size,
                    request.adjustment.forcedSinglePages,
                )
            }.fold(
                onSuccess = { snapshot ->
                    synchronized(productionRuntimeLifecycleLock) {
                        if (!disposeRequested && dualSession === owner && owner.acceptPairingSave(request, snapshot)) {
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
