package mihon.desktop.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mihon.domain.error.AppError
import mihon.domain.reader.materialize.CanonicalReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderChapterContentPort
import mihon.domain.reader.materialize.ReaderChapterContentRequest
import mihon.domain.reader.materialize.ReaderChapterMaterializeResult
import mihon.domain.reader.materialize.ReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderPageFetchPort
import mihon.domain.reader.materialize.ReaderPageFetchRequest
import mihon.domain.reader.materialize.ReaderPageMaterializeEvent
import mihon.domain.reader.materialize.ReaderPageMaterializeResult
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoPurpose
import mihon.domain.reader.observability.ReaderIoReporter
import mihon.domain.reader.observability.ReaderMonotonicClock
import mihon.domain.reader.observability.ReaderIoProbe
import mihon.domain.reader.progress.ReaderProgressEffect
import mihon.domain.reader.scheduler.ReaderEnqueueResult
import mihon.domain.reader.scheduler.ReaderPageMaterializeCompletion
import mihon.domain.reader.scheduler.ReaderPageMaterializeRunner
import mihon.domain.reader.scheduler.ReaderPageMaterializeRunnerPort
import mihon.domain.reader.scheduler.ReaderPageMaterializeRunnerSnapshot
import mihon.domain.reader.scheduler.ReaderPageMaterializeWork
import mihon.domain.reader.scheduler.ReaderRequestCancellation
import mihon.domain.reader.scheduler.ReaderSchedulePlan
import mihon.domain.reader.scheduler.ReaderScheduledRequest
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderChapterLoadState
import mihon.domain.reader.session.ReaderPageDescriptor
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.session.ReaderSessionCore
import mihon.domain.reader.session.ReaderSessionSnapshot

fun interface DesktopReaderChapterContentPortFactory {
    fun create(
        context: DesktopReaderChapterContext,
        leaseGeneration: Long,
    ): ReaderChapterContentPort
}

fun interface DesktopReaderPageFetchPortFactory {
    fun create(
        context: DesktopReaderChapterContext,
        descriptor: ReaderPageDescriptor,
    ): ReaderPageFetchPort
}

fun interface DesktopReaderProgressPort {
    suspend fun record(
        context: DesktopReaderChapterContext,
        effect: ReaderProgressEffect,
    )
}

interface DesktopReaderChapterLeasePort {
    fun reserveChapter(chapterId: Long, leaseGeneration: Long)

    fun releaseChapter(chapterId: Long, leaseGeneration: Long)

    companion object {
        val None = object : DesktopReaderChapterLeasePort {
            override fun reserveChapter(chapterId: Long, leaseGeneration: Long) = Unit
            override fun releaseChapter(chapterId: Long, leaseGeneration: Long) = Unit
        }
    }
}

data class DesktopReaderSessionState(
    val context: DesktopReaderChapterContext,
    val snapshot: ReaderSessionSnapshot,
)

private data class DesktopReaderScheduledPage(
    val context: DesktopReaderChapterContext,
    val descriptor: ReaderPageDescriptor,
    val isAdjacentPrefetch: Boolean,
    val attemptGeneration: Long,
    val adjacentSequence: Long? = null,
    var acceptedStorageFailure: Boolean = false,
) {
    init {
        require(attemptGeneration >= 0L) { "attemptGeneration must be non-negative" }
        require(!isAdjacentPrefetch || attemptGeneration == 0L) {
            "Adjacent prefetch must use the initial content attempt"
        }
    }
}

private data class DesktopReaderChapterLeaseOwner(
    val chapterId: Long,
    val leaseGeneration: Long,
)

/** Executes Desktop I/O around the generation-checked shared reader core. */
class DesktopReaderSession(
    private val initialContext: DesktopReaderChapterContext,
    val core: ReaderSessionCore,
    private val encodedPageStore: DesktopReaderEncodedPageStore,
    private val chapterContentPortFactory: DesktopReaderChapterContentPortFactory,
    private val pageFetchPortFactory: DesktopReaderPageFetchPortFactory,
    private val progressPort: DesktopReaderProgressPort,
    private val chapterLeasePort: DesktopReaderChapterLeasePort = DesktopReaderChapterLeasePort.None,
    parentScope: CoroutineScope,
    private val materializeExecutor: ReaderMaterializeExecutor = CanonicalReaderMaterializeExecutor,
    initialNextChapterPrefetchMode: NextChapterPrefetchMode = NextChapterPrefetchMode.FULL_NEXT_CHAPTER,
    private val ioReporter: ReaderIoReporter = ReaderIoReporter(
        ReaderIoProbe.None,
        ReaderMonotonicClock { 0L },
    ),
    private val ioGate: ReaderIoGate = ReaderIoGate.None,
    private val onGenerationPublished: (Long) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val storeMutex = Mutex()
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    private val progressSupervisor: CompletableJob = SupervisorJob()
    private val progressScope = CoroutineScope(parentScope.coroutineContext.minusKey(Job) + progressSupervisor)
    private val chapterContentPermits = Semaphore(
        core.schedulerSnapshot().maxConcurrentRequests + MAX_STALE_PHYSICAL_REQUESTS,
    )
    private val progressJobs = mutableSetOf<Job>()
    private val readChapterIds = mutableSetOf<Long>().apply {
        if (initialContext.wasRead) add(initialContext.chapterId)
    }
    private var chapterJob: Job? = null
    private var adjacentChapterJob: Job? = null
    private var progressTail: Job? = null
    private var context = initialContext
    private var activationSequence = 0L
    private var adjacentSequence = 0L
    private var contentLeaseGeneration = 0L
    private var activeContentLeaseGeneration = 0L
    private var adjacentContext: DesktopReaderChapterContext? = null
    private var adjacentContentLeaseGeneration: Long? = null
    private var adjacentPages: MutableList<ReaderPageDescriptor>? = null
    private val adjacentFailedPageIds = mutableSetOf<ReaderPageId>()
    private var adjacentPageListFailed = false
    private var adjacentQuotaBlocked = false
    private var adjacentFirstViewportPageCount = 1
    private var nextChapterPrefetchMode = initialNextChapterPrefetchMode
    private var lastSettledPageIndex: Int? = null
    private var storeStarted = false
    private var storeReconcileStarted = false
    private var started = false
    private var closed = false

    private val pageRunner = ReaderPageMaterializeRunner(
        scope = scope,
        maxConcurrentRequests = core.schedulerSnapshot().maxConcurrentRequests,
        maxStalePhysicalRequests = MAX_STALE_PHYSICAL_REQUESTS,
        materializeExecutor = materializeExecutor,
        port = DesktopPageMaterializeRunnerPort(),
    )

    private val _state = MutableStateFlow(DesktopReaderSessionState(initialContext, core.snapshot))
    val state: StateFlow<DesktopReaderSessionState> = _state.asStateFlow()
    private var publishedGeneration = core.snapshot.generation
    internal val currentNextChapterPrefetchMode: NextChapterPrefetchMode
        get() = synchronized(lock) { nextChapterPrefetchMode }
    internal fun pageRunnerSnapshot(): ReaderPageMaterializeRunnerSnapshot = pageRunner.snapshot()

    fun start() {
        synchronized(lock) {
            if (started || closed) return
            started = true
        }
        activate(initialContext)
    }

    fun activate(target: DesktopReaderChapterContext) {
        val sequence: Long
        val cachedAdjacentPages: List<ReaderPageDescriptor>?
        val materializationContext: DesktopReaderChapterContext
        val materializationLeaseGeneration: Long
        val leaseOwnersToRelease: Set<DesktopReaderChapterLeaseOwner>
        val reserveMaterializationLease: Boolean
        synchronized(lock) {
            check(!closed) { "Reader session is closed" }
            val cachedAdjacentContext = adjacentContext
                ?.takeIf { it.chapterId == target.chapterId && adjacentPages != null }
            cachedAdjacentPages = adjacentPages
                ?.takeIf { cachedAdjacentContext != null }
                ?.toList()
            materializationContext = target
            materializationLeaseGeneration = adjacentContentLeaseGeneration
                ?.takeIf { cachedAdjacentContext != null }
                ?: nextContentLeaseGenerationLocked()
            val materializationOwner = DesktopReaderChapterLeaseOwner(
                materializationContext.chapterId,
                materializationLeaseGeneration,
            )
            leaseOwnersToRelease = buildSet {
                activeLeaseOwnerOrNull()?.takeIf { it != materializationOwner }?.let(::add)
                adjacentLeaseOwnerOrNull()?.takeIf { it != materializationOwner }?.let(::add)
            }
            reserveMaterializationLease = cachedAdjacentContext == null
            clearAdjacentPrefetchLocked()
            context = materializationContext.copy(
                wasRead = target.wasRead || target.chapterId in readChapterIds,
            )
            activeContentLeaseGeneration = materializationLeaseGeneration
            activationSequence++
            sequence = activationSequence
            lastSettledPageIndex = null
            chapterJob?.cancel()
            val update = core.openChapter(ReaderChapterId(target.chapterId))
            ioReporter.report(
                ReaderIoEventType.OPEN_READER_INTENT,
                update.snapshot.activeChapter.id,
                generation = update.snapshot.generation,
                purpose = ReaderIoPurpose.READER_OPEN,
            )
            applySchedulePlanLocked(update.schedulePlan)
            publishStateLocked()
        }
        leaseOwnersToRelease.forEach { owner ->
            chapterLeasePort.releaseChapter(owner.chapterId, owner.leaseGeneration)
        }
        if (reserveMaterializationLease) {
            chapterLeasePort.reserveChapter(
                materializationContext.chapterId,
                materializationLeaseGeneration,
            )
        }
        chapterJob = scope.launch {
            val opening = synchronized(lock) {
                if (closed || activationSequence != sequence) return@launch
                core.snapshot
            }
            val request = ReaderChapterContentRequest(
                chapterId = ReaderChapterId(materializationContext.chapterId),
                generation = opening.generation,
            )
            val result = cachedAdjacentPages
                ?.let(ReaderChapterMaterializeResult::Loaded)
                ?: chapterContentPermits.withPermit {
                    materializeExecutor.materializeChapter(
                        request,
                        chapterContentPortFactory.create(materializationContext, materializationLeaseGeneration),
                    )
                }
            val adjacentPageListJob = synchronized(lock) {
                if (closed || activationSequence != sequence) return@launch
                val update = core.acceptChapterMaterialization(request.chapterId, request.generation, result)
                val acceptedLoadedPageList =
                    result is ReaderChapterMaterializeResult.Loaded &&
                        update.snapshot.activeChapter.id == request.chapterId &&
                        update.snapshot.generation == request.generation &&
                        update.snapshot.activeChapter.loadState is ReaderChapterLoadState.Loaded
                if (acceptedLoadedPageList) {
                    ioReporter.report(
                        ReaderIoEventType.PAGE_LIST_READY,
                        request.chapterId,
                        generation = request.generation,
                        purpose = ReaderIoPurpose.PAGE_LIST,
                    )
                }
                publishStateLocked()
                enqueueAdjacentImagesLocked()
                maybeStartAdjacentPageListLocked()
            }
            adjacentPageListJob?.start()
            pumpPageRequests()
        }
    }

    fun updateNextChapter(
        target: DesktopReaderChapterContext?,
        firstViewportPageCount: Int,
    ) {
        require(firstViewportPageCount > 0) { "firstViewportPageCount must be positive" }
        val pageListJob: Job?
        var leaseOwnerToRelease: DesktopReaderChapterLeaseOwner? = null
        var leaseOwnerToReserve: DesktopReaderChapterLeaseOwner? = null
        synchronized(lock) {
            if (closed) return
            val sameTarget = target != null && target.chapterId == adjacentContext?.chapterId
            if (!sameTarget) {
                leaseOwnerToRelease = adjacentLeaseOwnerOrNull()
                    ?.takeIf { it != activeLeaseOwnerOrNull() }
                clearAdjacentPrefetchLocked()
                adjacentContext = target
                adjacentContentLeaseGeneration = target?.let { nextContentLeaseGenerationLocked() }
                leaseOwnerToReserve = adjacentLeaseOwnerOrNull()
                adjacentPages = null
                adjacentFailedPageIds.clear()
                adjacentPageListFailed = false
                adjacentQuotaBlocked = false
            } else {
                adjacentContext = target
            }
            if (adjacentFirstViewportPageCount != firstViewportPageCount && sameTarget) {
                adjacentContext?.let { chapter ->
                    applyRequestCancellationLocked(core.cancelChapterPageRequests(ReaderChapterId(chapter.chapterId)))
                }
            }
            adjacentFirstViewportPageCount = firstViewportPageCount
            pageListJob = maybeStartAdjacentPageListLocked()
            enqueueAdjacentImagesLocked()
        }
        leaseOwnerToRelease?.let { owner ->
            chapterLeasePort.releaseChapter(owner.chapterId, owner.leaseGeneration)
        }
        leaseOwnerToReserve?.let { owner ->
            chapterLeasePort.reserveChapter(owner.chapterId, owner.leaseGeneration)
        }
        pageListJob?.start()
        pumpPageRequests()
    }

    fun setNextChapterPrefetchMode(mode: NextChapterPrefetchMode) {
        val pageListJob: Job?
        synchronized(lock) {
            if (closed || nextChapterPrefetchMode == mode) return
            nextChapterPrefetchMode = mode
            adjacentContext?.let { chapter ->
                applyRequestCancellationLocked(core.cancelChapterPageRequests(ReaderChapterId(chapter.chapterId)))
            }
            if (mode == NextChapterPrefetchMode.OFF && !isWithinOriginalMetadataWindowLocked()) {
                adjacentSequence++
                adjacentChapterJob?.cancel()
                adjacentChapterJob = null
                adjacentPageListFailed = false
            }
            if (mode != NextChapterPrefetchMode.OFF) adjacentQuotaBlocked = false
            pageListJob = maybeStartAdjacentPageListLocked()
            enqueueAdjacentImagesLocked()
        }
        pageListJob?.start()
        pumpPageRequests()
    }

    fun settleViewport(
        visiblePageIds: Set<ReaderPageId>,
        anchorPageId: ReaderPageId,
    ) {
        val progressJob: Job?
        val adjacentPageListJob: Job?
        synchronized(lock) {
            if (closed) return
            val update = core.settleViewport(
                visiblePageIds = visiblePageIds,
                anchorPageId = anchorPageId,
                wasRead = context.wasRead,
            )
            applySchedulePlanLocked(update.schedulePlan)
            val progress = update.progressEffect
            if (progress?.isRead == true) {
                readChapterIds += context.chapterId
                context = context.copy(wasRead = true)
            }
            lastSettledPageIndex = visiblePageIds.maxOf(ReaderPageId::sourcePageIndex)
            progressJob = progress?.let { enqueueProgressLocked(context, it) }
            adjacentPageListJob = maybeStartAdjacentPageListLocked()
            enqueueAdjacentImagesLocked()
        }
        progressJob?.start()
        adjacentPageListJob?.start()
        pumpPageRequests()
    }

    fun retryPage(pageId: ReaderPageId) {
        synchronized(lock) {
            if (closed) return
            val update = core.retryPage(pageId)
            applySchedulePlanLocked(update.schedulePlan)
            publishStateLocked()
        }
        pumpPageRequests()
    }

    fun retryChapter() {
        val retryContext = synchronized(lock) { context }
        activate(retryContext)
    }

    override fun close() {
        val leaseOwnersToRelease: Set<DesktopReaderChapterLeaseOwner>
        synchronized(lock) {
            if (closed) return
            closed = true
            leaseOwnersToRelease = buildSet {
                activeLeaseOwnerOrNull()?.let(::add)
                adjacentLeaseOwnerOrNull()?.let(::add)
            }
            chapterJob?.cancel()
            chapterJob = null
            clearAdjacentPrefetchLocked()
            applySchedulePlanLocked(core.close().schedulePlan)
            if (storeStarted) encodedPageStore.endSession()
            progressSupervisor.complete()
        }
        pageRunner.close()
        leaseOwnersToRelease.forEach { owner ->
            chapterLeasePort.releaseChapter(owner.chapterId, owner.leaseGeneration)
        }
        scope.cancel()
    }

    fun onFirstPagePresented(pageId: ReaderPageId, generation: Long) {
        synchronized(lock) {
            val snapshot = core.snapshot
            if (
                closed ||
                storeReconcileStarted ||
                snapshot.generation != generation ||
                snapshot.activeChapter.id != pageId.chapterId
            ) {
                return
            }
            storeReconcileStarted = true
        }
        scope.launch {
            if (!ensureStoreWritable()) return@launch
            ioGate.await(ReaderIoGatePoint.CACHE_SCAN)
            val canReconcile = synchronized(lock) { !closed && storeStarted }
            if (!canReconcile) return@launch
            ioReporter.report(
                ReaderIoEventType.CACHE_RECONCILE,
                pageId.chapterId,
                generation = generation,
                purpose = ReaderIoPurpose.CACHE_MAINTENANCE,
            )
            encodedPageStore.reconcileSession()
        }
    }

    /** Serializes durable progress effects and lets already accepted writes drain after reader disposal. */
    private fun enqueueProgressLocked(
        progressContext: DesktopReaderChapterContext,
        effect: ReaderProgressEffect,
    ): Job {
        val predecessor = progressTail
        lateinit var job: Job
        job = progressScope.launch(start = CoroutineStart.LAZY) {
            predecessor?.join()
            progressPort.record(progressContext, effect)
        }
        progressTail = job
        progressJobs += job
        job.invokeOnCompletion {
            synchronized(lock) {
                progressJobs.remove(job)
                if (progressTail === job) progressTail = null
            }
        }
        return job
    }

    private suspend fun ensureStoreWritable(): Boolean = storeMutex.withLock {
        synchronized(lock) {
            if (closed) return@withLock false
            if (storeStarted) return@withLock true
        }
        encodedPageStore.beginSessionFast(emptySet())
        val closeImmediately = synchronized(lock) {
            if (closed) {
                true
            } else {
                storeStarted = true
                false
            }
        }
        if (closeImmediately) encodedPageStore.endSession()
        !closeImmediately
    }

    private fun pumpPageRequests() = pageRunner.pump()

    private inner class DesktopPageMaterializeRunnerPort :
        ReaderPageMaterializeRunnerPort<DesktopReaderScheduledPage> {

        override fun pollNext(): ReaderPageMaterializeWork<DesktopReaderScheduledPage>? = synchronized(lock) {
            if (closed) return@synchronized null
            while (true) {
                val request = core.pollNextPageRequest() ?: return@synchronized null
                val scheduledPage = scheduledPageLocked(request)
                if (
                    scheduledPage == null ||
                    (scheduledPage.descriptor.initialLoadState is ReaderPageLoadState.Ready && !request.forceRefresh)
                ) {
                    core.completePageRequest(request.jobKey)
                    continue
                }
                return@synchronized ReaderPageMaterializeWork(
                    request = request,
                    fetchRequest = ReaderPageFetchRequest(
                        pageId = request.pageId,
                        generation = request.generation,
                        url = scheduledPage.descriptor.url,
                        imageUrl = scheduledPage.descriptor.imageUrl,
                        attemptGeneration = scheduledPage.attemptGeneration,
                    ),
                    fetchPort = pageFetchPortFactory.create(scheduledPage.context, scheduledPage.descriptor),
                    binding = scheduledPage,
                )
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

        override fun accepts(work: ReaderPageMaterializeWork<DesktopReaderScheduledPage>): Boolean =
            synchronized(lock) {
                acceptsLocked(work)
            }

        override suspend fun prepare(work: ReaderPageMaterializeWork<DesktopReaderScheduledPage>): Boolean {
            val scheduledPage = work.binding
            val request = work.request
            request.gatePoint(scheduledPage.isAdjacentPrefetch)?.let { ioGate.await(it) }
            if (!accepts(work)) return false
            if (scheduledPage.descriptor.encodedPageRef == null && !ensureStoreWritable()) return false
            return synchronized(lock) {
                if (!acceptsLocked(work)) return@synchronized false
                if (scheduledPage.isAdjacentPrefetch) {
                    ioReporter.report(
                        ReaderIoEventType.ADJACENT_IO,
                        request.pageId.chapterId,
                        request.pageId,
                        request.generation,
                        ReaderIoPurpose.ADJACENT_PREFETCH,
                    )
                } else {
                    ioReporter.report(
                        ReaderIoEventType.OPEN_PAGE,
                        request.pageId.chapterId,
                        request.pageId,
                        request.generation,
                        ReaderIoPurpose.CURRENT_PAGE,
                    )
                }
                true
            }
        }

        private fun acceptsLocked(work: ReaderPageMaterializeWork<DesktopReaderScheduledPage>): Boolean {
            if (closed || !core.acceptsPageRequest(work.request.jobKey)) return false
            val scheduledPage = work.binding
            if (work.fetchRequest.attemptGeneration != scheduledPage.attemptGeneration) return false
            return if (scheduledPage.isAdjacentPrefetch) {
                scheduledPage.adjacentSequence == adjacentSequence &&
                    adjacentContext?.chapterId == scheduledPage.context.chapterId
            } else {
                core.snapshot.activeChapter.pages.any { page ->
                    page.id == work.request.pageId &&
                        page.attemptGeneration == scheduledPage.attemptGeneration
                }
            }
        }

        override fun publish(
            work: ReaderPageMaterializeWork<DesktopReaderScheduledPage>,
            event: ReaderPageMaterializeEvent,
        ): Boolean = synchronized(lock) {
            if (!acceptsLocked(work)) {
                false
            } else if (work.binding.isAdjacentPrefetch) {
                acceptAdjacentMaterializationLocked(work.binding, work.request, event).also { accepted ->
                    if (accepted && event is ReaderPageMaterializeEvent.Failed && event.error is AppError.Storage) {
                        work.binding.acceptedStorageFailure = true
                    }
                }
            } else {
                core.acceptPageMaterialization(work.request, event).also { accepted ->
                    if (accepted) publishStateLocked()
                }
            }
        }

        override fun complete(
            work: ReaderPageMaterializeWork<DesktopReaderScheduledPage>,
            completion: ReaderPageMaterializeCompletion,
        ) {
            var adjacentPageListJob: Job? = null
            synchronized(lock) {
                val terminalResult = (completion as? ReaderPageMaterializeCompletion.Completed)?.result
                val scheduledPage = work.binding
                val request = work.request
                val blocksCurrentAdjacentTarget =
                    scheduledPage.acceptedStorageFailure &&
                        (terminalResult as? ReaderPageMaterializeResult.Failed)?.error is AppError.Storage &&
                        scheduledPage.adjacentSequence == adjacentSequence &&
                        adjacentContext?.chapterId == scheduledPage.context.chapterId &&
                        core.acceptsPageRequest(request.jobKey)
                core.completePageRequest(request.jobKey)
                if (blocksCurrentAdjacentTarget) {
                    adjacentQuotaBlocked = true
                    adjacentContext?.let { chapter ->
                        applyRequestCancellationLocked(
                            core.cancelChapterPageRequests(ReaderChapterId(chapter.chapterId)),
                        )
                    }
                }
                if (!scheduledPage.isAdjacentPrefetch) {
                    adjacentPageListJob = maybeStartAdjacentPageListLocked()
                    enqueueAdjacentImagesLocked()
                }
            }
            adjacentPageListJob?.start()
        }
    }

    private fun scheduledPageLocked(request: ReaderScheduledRequest): DesktopReaderScheduledPage? {
        val activePage = core.snapshot.activeChapter.pages.firstOrNull { it.id == request.pageId }
        if (activePage != null) {
            return DesktopReaderScheduledPage(
                context = context,
                descriptor = ReaderPageDescriptor(
                    sourcePageIndex = activePage.id.sourcePageIndex,
                    url = activePage.url,
                    imageUrl = activePage.imageUrl,
                    encodedPageRef = activePage.encodedPageRef,
                    initialLoadState = activePage.loadState,
                ),
                isAdjacentPrefetch = false,
                attemptGeneration = activePage.attemptGeneration,
            )
        }
        val prefetchContext = adjacentContext?.takeIf { it.chapterId == request.pageId.chapterId.value }
            ?: return null
        val descriptor = adjacentPages?.firstOrNull { it.sourcePageIndex == request.pageId.sourcePageIndex }
            ?: return null
        return DesktopReaderScheduledPage(
            context = prefetchContext,
            descriptor = descriptor,
            isAdjacentPrefetch = true,
            attemptGeneration = 0L,
            adjacentSequence = adjacentSequence,
        )
    }

    private fun acceptAdjacentMaterializationLocked(
        scheduledPage: DesktopReaderScheduledPage,
        request: ReaderScheduledRequest,
        event: ReaderPageMaterializeEvent,
    ): Boolean {
        if (!core.acceptsPageRequest(request.jobKey)) return false
        if (scheduledPage.adjacentSequence != adjacentSequence) return false
        if (adjacentContext?.chapterId != request.pageId.chapterId.value) return false
        val pages = adjacentPages ?: return false
        val index = pages.indexOfFirst { it.sourcePageIndex == request.pageId.sourcePageIndex }
        if (index < 0) return false
        when (event) {
            is ReaderPageMaterializeEvent.Ready -> {
                pages[index] = pages[index].copy(
                    imageUrl = event.imageUrl,
                    encodedPageRef = event.encodedPageRef,
                    initialLoadState = ReaderPageLoadState.Ready,
                )
                adjacentFailedPageIds.remove(request.pageId)
            }
            is ReaderPageMaterializeEvent.Failed -> adjacentFailedPageIds += request.pageId
            ReaderPageMaterializeEvent.ResolvingImage,
            is ReaderPageMaterializeEvent.Downloading,
            -> Unit
        }
        return true
    }

    private fun maybeStartAdjacentPageListLocked(): Job? {
        val target = adjacentContext ?: return null
        if (adjacentPages != null || adjacentChapterJob != null || adjacentPageListFailed) return null
        val isWithinOriginalMetadataWindow = isWithinOriginalMetadataWindowLocked()
        val fullImagePolicyNeedsPageList = nextChapterPrefetchMode != NextChapterPrefetchMode.OFF &&
            activeChapterAllReadyLocked()
        if (!isWithinOriginalMetadataWindow && !fullImagePolicyNeedsPageList) return null

        val sequence = adjacentSequence
        val generation = core.snapshot.generation
        val leaseGeneration = requireNotNull(adjacentContentLeaseGeneration)
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            ioGate.await(ReaderIoGatePoint.ADJACENT_IO)
            ioReporter.report(
                ReaderIoEventType.ADJACENT_IO,
                ReaderChapterId(target.chapterId),
                generation = generation,
                purpose = ReaderIoPurpose.ADJACENT_PREFETCH,
            )
            val result = chapterContentPermits.withPermit {
                materializeExecutor.materializeChapter(
                    ReaderChapterContentRequest(ReaderChapterId(target.chapterId), generation),
                    chapterContentPortFactory.create(target, leaseGeneration),
                )
            }
            val accepted = synchronized(lock) {
                if (
                    closed || adjacentSequence != sequence ||
                    adjacentContext?.chapterId != target.chapterId || adjacentChapterJob !== job
                ) {
                    false
                } else {
                    adjacentChapterJob = null
                    when (result) {
                        is ReaderChapterMaterializeResult.Loaded -> adjacentPages = result.pages.toMutableList()
                        is ReaderChapterMaterializeResult.Failed -> adjacentPageListFailed = true
                    }
                    enqueueAdjacentImagesLocked()
                    true
                }
            }
            if (accepted) pumpPageRequests()
        }
        adjacentChapterJob = job
        return job
    }

    private fun enqueueAdjacentImagesLocked() {
        if (!activeChapterAllReadyLocked() || adjacentQuotaBlocked) return
        val target = adjacentContext ?: return
        val pages = adjacentPages ?: return
        val selectedPages = when (nextChapterPrefetchMode) {
            NextChapterPrefetchMode.OFF -> return
            NextChapterPrefetchMode.FIRST_VIEWPORT -> pages.take(adjacentFirstViewportPageCount)
            NextChapterPrefetchMode.FULL_NEXT_CHAPTER -> pages
        }
        selectedPages.forEach { descriptor ->
            val pageId = ReaderPageId(ReaderChapterId(target.chapterId), descriptor.sourcePageIndex)
            if (
                descriptor.initialLoadState is ReaderPageLoadState.Ready ||
                pageId in adjacentFailedPageIds
            ) {
                return@forEach
            }
            applyEnqueueResultLocked(core.enqueueAdjacentPage(pageId))
        }
    }

    private fun activeChapterAllReadyLocked(): Boolean {
        val pages = core.snapshot.activeChapter.pages
        return pages.isNotEmpty() && pages.all { it.loadState is ReaderPageLoadState.Ready }
    }

    private fun isWithinOriginalMetadataWindowLocked(): Boolean {
        val chapter = core.snapshot.activeChapter
        return lastSettledPageIndex?.let { pageIndex ->
            chapter.pages.isNotEmpty() && chapter.pages.size - pageIndex <= ORIGINAL_PAGE_LIST_PRELOAD_WINDOW
        } == true
    }

    private fun clearAdjacentPrefetchLocked() {
        adjacentSequence++
        adjacentChapterJob?.cancel()
        adjacentChapterJob = null
        adjacentContext?.let { chapter ->
            applyRequestCancellationLocked(core.cancelChapterPageRequests(ReaderChapterId(chapter.chapterId)))
        }
        adjacentContext = null
        adjacentContentLeaseGeneration = null
        adjacentPages = null
        adjacentFailedPageIds.clear()
        adjacentPageListFailed = false
        adjacentQuotaBlocked = false
    }

    private fun nextContentLeaseGenerationLocked(): Long = ++contentLeaseGeneration

    private fun activeLeaseOwnerOrNull(): DesktopReaderChapterLeaseOwner? =
        activeContentLeaseGeneration.takeIf { it > 0L }
            ?.let { DesktopReaderChapterLeaseOwner(context.chapterId, it) }

    private fun adjacentLeaseOwnerOrNull(): DesktopReaderChapterLeaseOwner? =
        adjacentContext?.let { chapter ->
            adjacentContentLeaseGeneration?.let { generation ->
                DesktopReaderChapterLeaseOwner(chapter.chapterId, generation)
            }
        }

    private fun applyEnqueueResultLocked(result: ReaderEnqueueResult) {
        pageRunner.cancel(result.cancelRequests)
    }

    private fun applyRequestCancellationLocked(cancellation: ReaderRequestCancellation) {
        pageRunner.cancel(cancellation.cancelRequests)
    }

    private fun applySchedulePlanLocked(plan: ReaderSchedulePlan?) {
        plan ?: return
        pageRunner.cancel(plan.cancelRequests)
    }

    private fun publishStateLocked() {
        val state = DesktopReaderSessionState(context, core.snapshot)
        if (state.snapshot.generation != publishedGeneration) {
            onGenerationPublished(state.snapshot.generation)
            publishedGeneration = state.snapshot.generation
        }
        _state.value = state
    }

    private companion object {
        const val MAX_STALE_PHYSICAL_REQUESTS = 1
        const val ORIGINAL_PAGE_LIST_PRELOAD_WINDOW = 5
    }
}
