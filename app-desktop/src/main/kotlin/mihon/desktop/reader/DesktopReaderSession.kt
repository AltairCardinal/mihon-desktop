package mihon.desktop.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mihon.domain.reader.materialize.CanonicalReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderChapterContentPort
import mihon.domain.reader.materialize.ReaderChapterContentRequest
import mihon.domain.reader.materialize.ReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderPageFetchPort
import mihon.domain.reader.materialize.ReaderPageFetchRequest
import mihon.domain.reader.progress.ReaderProgressEffect
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
) : AutoCloseable {
    private val lock = Any()
    private val storeMutex = Mutex()
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    private val progressSupervisor: CompletableJob = SupervisorJob()
    private val progressScope = CoroutineScope(parentScope.coroutineContext.minusKey(Job) + progressSupervisor)
    private val activePageJobs = mutableMapOf<ReaderRequestKey, Job>()
    private val progressJobs = mutableSetOf<Job>()
    private val readChapterIds = mutableSetOf<Long>().apply {
        if (initialContext.wasRead) add(initialContext.chapterId)
    }
    private var chapterJob: Job? = null
    private var progressTail: Job? = null
    private var context = initialContext
    private var activationSequence = 0L
    private var storeStarted = false
    private var started = false
    private var closed = false

    private val _state = MutableStateFlow(DesktopReaderSessionState(initialContext, core.snapshot))
    val state: StateFlow<DesktopReaderSessionState> = _state.asStateFlow()

    fun start() {
        synchronized(lock) {
            if (started || closed) return
            started = true
        }
        activate(initialContext)
    }

    fun activate(target: DesktopReaderChapterContext) {
        val sequence: Long
        synchronized(lock) {
            check(!closed) { "Reader session is closed" }
            context = target.copy(wasRead = target.wasRead || target.chapterId in readChapterIds)
            activationSequence++
            sequence = activationSequence
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
            val result = materializeExecutor.materializeChapter(
                request,
                chapterContentPortFactory.create(target),
            )
            synchronized(lock) {
                if (closed || activationSequence != sequence) return@synchronized
                core.acceptChapterMaterialization(request.chapterId, request.generation, result)
                publishStateLocked()
            }
        }
    }

    fun settleViewport(
        visiblePageIds: Set<ReaderPageId>,
        anchorPageId: ReaderPageId,
    ) {
        val progressJob: Job?
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
            progressJob = progress?.let { enqueueProgressLocked(context, it) }
        }
        progressJob?.start()
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
                    val page = core.snapshot.activeChapter.pages.firstOrNull { it.id == request.pageId }
                    if (page == null || (page.loadState is ReaderPageLoadState.Ready && !request.forceRefresh)) {
                        core.completePageRequest(request.jobKey)
                        continue
                    }
                    val requestContext = context
                    val descriptor = ReaderPageDescriptor(
                        sourcePageIndex = page.id.sourcePageIndex,
                        url = page.url,
                        imageUrl = page.imageUrl,
                        encodedPageRef = page.encodedPageRef,
                        initialLoadState = page.loadState,
                    )
                    val job = scope.launch(start = CoroutineStart.LAZY) {
                        materializePage(requestContext, descriptor, request)
                    }
                    activePageJobs[request.jobKey] = job
                    add(job)
                }
            }
        }
        jobs.forEach(Job::start)
    }

    private suspend fun materializePage(
        requestContext: DesktopReaderChapterContext,
        descriptor: ReaderPageDescriptor,
        request: ReaderScheduledRequest,
    ) {
        try {
            val sessionGeneration = synchronized(lock) { core.snapshot.generation }
            materializeExecutor.materializePage(
                request = ReaderPageFetchRequest(
                    pageId = request.pageId,
                    generation = sessionGeneration,
                    url = descriptor.url,
                    imageUrl = descriptor.imageUrl,
                ),
                port = pageFetchPortFactory.create(requestContext, descriptor),
                forceRefresh = request.forceRefresh,
                publish = { event ->
                    synchronized(lock) {
                        if (closed) {
                            false
                        } else {
                            core.acceptPageMaterialization(request, event).also { accepted ->
                                if (accepted) publishStateLocked()
                            }
                        }
                    }
                },
            )
        } finally {
            synchronized(lock) {
                activePageJobs.remove(request.jobKey)
                core.completePageRequest(request.jobKey)
            }
            pumpPageRequests()
        }
    }

    private fun applySchedulePlanLocked(plan: ReaderSchedulePlan?) {
        plan ?: return
        plan.cancelRequests.forEach { requestKey -> activePageJobs.remove(requestKey)?.cancel() }
    }

    private fun publishStateLocked() {
        _state.value = DesktopReaderSessionState(context, core.snapshot)
    }
}
