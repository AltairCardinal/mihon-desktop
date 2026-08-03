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
import mihon.domain.reader.progress.ReaderProgressEffect
import mihon.domain.reader.scheduler.ReaderEnqueueResult
import mihon.domain.reader.scheduler.ReaderRequestCancellation
import mihon.domain.reader.scheduler.ReaderRequestKey
import mihon.domain.reader.scheduler.ReaderSchedulePlan
import mihon.domain.reader.scheduler.ReaderScheduledRequest
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageDescriptor
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.session.ReaderPageLoadState
import mihon.domain.reader.session.ReaderSessionCore
import mihon.domain.reader.session.ReaderSessionSnapshot

fun interface DesktopReaderChapterContentPortFactory {
    fun create(context: DesktopReaderChapterContext): ReaderChapterContentPort
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

data class DesktopReaderSessionState(
    val context: DesktopReaderChapterContext,
    val snapshot: ReaderSessionSnapshot,
)

private data class DesktopReaderScheduledPage(
    val context: DesktopReaderChapterContext,
    val descriptor: ReaderPageDescriptor,
    val isAdjacentPrefetch: Boolean,
    val adjacentSequence: Long? = null,
)

/** Executes Desktop I/O around the generation-checked shared reader core. */
class DesktopReaderSession(
    private val initialContext: DesktopReaderChapterContext,
    val core: ReaderSessionCore,
    private val encodedPageStore: DesktopReaderEncodedPageStore,
    private val chapterContentPortFactory: DesktopReaderChapterContentPortFactory,
    private val pageFetchPortFactory: DesktopReaderPageFetchPortFactory,
    private val progressPort: DesktopReaderProgressPort,
    parentScope: CoroutineScope,
    private val materializeExecutor: ReaderMaterializeExecutor = CanonicalReaderMaterializeExecutor,
    initialNextChapterPrefetchMode: NextChapterPrefetchMode = NextChapterPrefetchMode.FULL_NEXT_CHAPTER,
) : AutoCloseable {
    private val lock = Any()
    private val storeMutex = Mutex()
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    private val progressSupervisor: CompletableJob = SupervisorJob()
    private val progressScope = CoroutineScope(parentScope.coroutineContext.minusKey(Job) + progressSupervisor)
    private val physicalRequestPermits = Semaphore(
        core.schedulerSnapshot().maxConcurrentRequests + MAX_STALE_PHYSICAL_REQUESTS,
    )
    private val activePageJobs = mutableMapOf<ReaderRequestKey, Job>()
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
    private var adjacentContext: DesktopReaderChapterContext? = null
    private var adjacentPages: MutableList<ReaderPageDescriptor>? = null
    private val adjacentFailedPageIds = mutableSetOf<ReaderPageId>()
    private var adjacentPageListFailed = false
    private var adjacentQuotaBlocked = false
    private var adjacentFirstViewportPageCount = 1
    private var nextChapterPrefetchMode = initialNextChapterPrefetchMode
    private var lastSettledPageIndex: Int? = null
    private var storeStarted = false
    private var started = false
    private var closed = false

    private val _state = MutableStateFlow(DesktopReaderSessionState(initialContext, core.snapshot))
    val state: StateFlow<DesktopReaderSessionState> = _state.asStateFlow()
    internal val currentNextChapterPrefetchMode: NextChapterPrefetchMode
        get() = synchronized(lock) { nextChapterPrefetchMode }

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
        synchronized(lock) {
            check(!closed) { "Reader session is closed" }
            cachedAdjacentPages = adjacentPages
                ?.takeIf { adjacentContext?.chapterId == target.chapterId }
                ?.toList()
            clearAdjacentPrefetchLocked()
            context = target.copy(wasRead = target.wasRead || target.chapterId in readChapterIds)
            activationSequence++
            sequence = activationSequence
            lastSettledPageIndex = null
            chapterJob?.cancel()
            val update = core.openChapter(ReaderChapterId(target.chapterId))
            applySchedulePlanLocked(update.schedulePlan)
            publishStateLocked()
        }
        chapterJob = scope.launch {
            ensureStoreStarted()
            val opening = synchronized(lock) {
                if (closed || activationSequence != sequence) return@launch
                core.snapshot
            }
            val request = ReaderChapterContentRequest(
                chapterId = ReaderChapterId(target.chapterId),
                generation = opening.generation,
            )
            val result = cachedAdjacentPages
                ?.let(ReaderChapterMaterializeResult::Loaded)
                ?: physicalRequestPermits.withPermit {
                    materializeExecutor.materializeChapter(
                        request,
                        chapterContentPortFactory.create(target),
                    )
                }
            val adjacentPageListJob = synchronized(lock) {
                if (closed || activationSequence != sequence) return@launch
                core.acceptChapterMaterialization(request.chapterId, request.generation, result)
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
        synchronized(lock) {
            if (closed) return
            val sameTarget = target != null && target.chapterId == adjacentContext?.chapterId
            if (!sameTarget) {
                clearAdjacentPrefetchLocked()
                adjacentContext = target
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
        synchronized(lock) {
            if (closed) return
            closed = true
            chapterJob?.cancel()
            chapterJob = null
            clearAdjacentPrefetchLocked()
            applySchedulePlanLocked(core.close().schedulePlan)
            activePageJobs.values.forEach(Job::cancel)
            activePageJobs.clear()
            if (storeStarted) encodedPageStore.endSession()
            progressSupervisor.complete()
        }
        scope.cancel()
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

    private suspend fun ensureStoreStarted() {
        storeMutex.withLock {
            if (!storeStarted) {
                encodedPageStore.beginSession(emptySet())
                storeStarted = true
            }
        }
    }

    private fun pumpPageRequests() {
        val jobs = synchronized(lock) {
            if (closed) return
            buildList {
                while (true) {
                    val request = core.pollNextPageRequest() ?: break
                    val scheduledPage = scheduledPageLocked(request)
                    if (
                        scheduledPage == null ||
                        (scheduledPage.descriptor.initialLoadState is ReaderPageLoadState.Ready && !request.forceRefresh)
                    ) {
                        core.completePageRequest(request.jobKey)
                        continue
                    }
                    val job = scope.launch(start = CoroutineStart.LAZY) {
                        materializePage(scheduledPage, request)
                    }
                    activePageJobs[request.jobKey] = job
                    add(job)
                }
            }
        }
        jobs.forEach(Job::start)
    }

    private suspend fun materializePage(
        scheduledPage: DesktopReaderScheduledPage,
        request: ReaderScheduledRequest,
    ) {
        var terminalResult: ReaderPageMaterializeResult? = null
        var acceptedAdjacentStorageFailure = false
        var adjacentPageListJob: Job? = null
        try {
            physicalRequestPermits.withPermit {
                terminalResult = materializeExecutor.materializePage(
                    request = ReaderPageFetchRequest(
                        pageId = request.pageId,
                        generation = request.generation,
                        url = scheduledPage.descriptor.url,
                        imageUrl = scheduledPage.descriptor.imageUrl,
                    ),
                    port = pageFetchPortFactory.create(scheduledPage.context, scheduledPage.descriptor),
                    forceRefresh = request.forceRefresh,
                    publish = { event ->
                        synchronized(lock) {
                            if (closed) {
                                false
                            } else if (scheduledPage.isAdjacentPrefetch) {
                                acceptAdjacentMaterializationLocked(scheduledPage, request, event).also { accepted ->
                                    if (accepted && event is ReaderPageMaterializeEvent.Failed && event.error is AppError.Storage) {
                                        acceptedAdjacentStorageFailure = true
                                    }
                                }
                            } else {
                                core.acceptPageMaterialization(request, event).also { accepted ->
                                    if (accepted) publishStateLocked()
                                }
                            }
                        }
                    },
                )
            }
        } finally {
            synchronized(lock) {
                val blocksCurrentAdjacentTarget =
                    acceptedAdjacentStorageFailure &&
                        (terminalResult as? ReaderPageMaterializeResult.Failed)?.error is AppError.Storage &&
                        scheduledPage.adjacentSequence == adjacentSequence &&
                        adjacentContext?.chapterId == scheduledPage.context.chapterId &&
                        core.acceptsPageRequest(request.jobKey)
                activePageJobs.remove(request.jobKey)
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
            pumpPageRequests()
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
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            val result = physicalRequestPermits.withPermit {
                materializeExecutor.materializeChapter(
                    ReaderChapterContentRequest(ReaderChapterId(target.chapterId), generation),
                    chapterContentPortFactory.create(target),
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
        adjacentPages = null
        adjacentFailedPageIds.clear()
        adjacentPageListFailed = false
        adjacentQuotaBlocked = false
    }

    private fun applyEnqueueResultLocked(result: ReaderEnqueueResult) {
        result.cancelRequests.forEach { requestKey -> activePageJobs.remove(requestKey)?.cancel() }
    }

    private fun applyRequestCancellationLocked(cancellation: ReaderRequestCancellation) {
        cancellation.cancelRequests.forEach { requestKey -> activePageJobs.remove(requestKey)?.cancel() }
    }

    private fun applySchedulePlanLocked(plan: ReaderSchedulePlan?) {
        plan ?: return
        plan.cancelRequests.forEach { requestKey -> activePageJobs.remove(requestKey)?.cancel() }
    }

    private fun publishStateLocked() {
        _state.value = DesktopReaderSessionState(context, core.snapshot)
    }

    private companion object {
        const val MAX_STALE_PHYSICAL_REQUESTS = 1
        const val ORIGINAL_PAGE_LIST_PRELOAD_WINDOW = 5
    }
}
