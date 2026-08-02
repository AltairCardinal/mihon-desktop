package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import mihon.domain.reader.materialize.CanonicalReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderPageFetchRequest
import mihon.domain.reader.materialize.ReaderPageMaterializeEvent
import mihon.domain.reader.materialize.ReaderPageMaterializeResult
import mihon.domain.reader.scheduler.ReaderRequestKey
import mihon.domain.reader.scheduler.ReaderRequestKind
import mihon.domain.reader.scheduler.ReaderRequestScheduler
import mihon.domain.reader.scheduler.ReaderScheduledRequest
import mihon.domain.reader.scheduler.ReaderSchedulerPolicy
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import mihon.domain.reader.storage.ReaderEncodedPageStore
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Loader used to load chapters from an online source.
 */
internal class HttpPageLoader(
    private val chapter: ReaderChapter,
    private val source: HttpSource,
    private val chapterCache: ChapterCache = Injekt.get(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val materializeExecutor: ReaderMaterializeExecutor = CanonicalReaderMaterializeExecutor,
    private val requestScheduler: ReaderRequestScheduler = ReaderRequestScheduler(
        ReaderSchedulerPolicy.originalMihon(),
    ),
    private val encodedPageStore: ReaderEncodedPageStore = AndroidReaderEncodedPageStore(chapterCache),
) : PageLoader() {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val queueSignal = Channel<Unit>(Channel.CONFLATED)
    private val physicalRequestPermits = Semaphore(
        requestScheduler.snapshot().maxConcurrentRequests + MAX_STALE_PHYSICAL_REQUESTS,
    )

    private val preloadJobLock = Any()
    private val scheduledPages = mutableMapOf<ReaderRequestKey, ReaderPage>()
    private val activePreloadJobs = mutableMapOf<ReaderRequestKey, Job>()

    init {
        scope.launch {
            for (signal in queueSignal) {
                while (true) {
                    val scheduled = synchronized(preloadJobLock, ::pollNextStartableLocked) ?: break
                    runScheduledPage(scheduled.first, scheduled.second)
                }
            }
        }
    }

    override var isLocal: Boolean = false

    /**
     * Returns the page list for a chapter. It tries to return the page list from the local cache,
     * otherwise fallbacks to network.
     */
    override suspend fun getPages(): List<ReaderPage> {
        val pages = try {
            chapterCache.getPageListFromCache(chapter.chapter.toDomainChapter()!!)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            source.getPageList(chapter.chapter)
        }
        return pages.mapIndexed { index, page ->
            // Don't trust sources and use our own indexing
            ReaderPage(index, page.url, page.imageUrl)
        }.also { readerPages ->
            try {
                encodedPageStore.beginSession(
                    readerPages.mapNotNullTo(mutableSetOf()) { readerPage ->
                        readerPage.imageUrl?.takeIf(String::isNotBlank)?.let(::EncodedPageRef)
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: AppErrorException) {
                throw error
            } catch (error: Throwable) {
                throw AppErrorException(AppError.Storage(error))
            }
        }
    }

    /**
     * Loads a page through the queue. Handles re-enqueueing pages if they were evicted from the cache.
     */
    override suspend fun loadPage(page: ReaderPage) = withContext(dispatcher) {
        val imageUrl = page.imageUrl

        // Check if the image has been deleted
        if (page.status == Page.State.Ready && imageUrl != null && !chapterCache.isImageInCache(imageUrl)) {
            page.status = Page.State.Queue
        }

        // Automatically retry failed pages when subscribed to this page
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }

        val scheduledRequest = synchronized(preloadJobLock) {
            val result = requestScheduler.enqueue(
                pageId = readerPageId(page.index),
                kind = ReaderRequestKind.INTERACTIVE_VISIBLE,
            )
            registerEnqueueResultLocked(result, page.chapter.pages.orEmpty())
            result.request
        }
        if (scheduledRequest != null) queueSignal.trySend(Unit)

        suspendCancellableCoroutine<Nothing> { continuation ->
            continuation.invokeOnCancellation {
                scheduledRequest?.let(::removePendingRequest)
            }
        }
    }

    override fun onPageSelected(page: ReaderPage) {
        val pages = page.chapter.pages.orEmpty()
        val registered = synchronized(preloadJobLock) {
            val preloadPlan = requestScheduler.moveTo(readerChapterId(), page.index, pages.size)

            // Register replacements before cancellation can restore an overlapping page to Queue.
            preloadPlan.requests.forEach { request ->
                val candidate = pages[request.pageIndex]
                if (preloadPlan.cancelRequests.any { it.pageIndex == request.pageIndex } &&
                    candidate.status != Page.State.Ready
                ) {
                    candidate.status = Page.State.Queue
                }
                scheduledPages[request.jobKey] = candidate
            }
            preloadPlan.discardRequests.forEach(scheduledPages::remove)
            cancelActiveRequestsLocked(preloadPlan.cancelRequests)
            preloadPlan.requests.isNotEmpty()
        }
        if (registered) queueSignal.trySend(Unit)
    }

    /**
     * Retries a page. This method is only called from user interaction on the viewer.
     */
    override fun retryPage(page: ReaderPage) {
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }
        val pages = page.chapter.pages.orEmpty()
        val registered = synchronized(preloadJobLock) {
            val retryPlan = requestScheduler.retry(readerPageId(page.index), pages.size)
            retryPlan.requests.forEach { request ->
                val candidate = pages.getOrNull(request.pageIndex) ?: page.takeIf { it.index == request.pageIndex }
                if (candidate != null) {
                    if (retryPlan.cancelRequests.any { it.pageIndex == request.pageIndex } &&
                        candidate.status != Page.State.Ready
                    ) {
                        candidate.status = Page.State.Queue
                    }
                    scheduledPages[request.jobKey] = candidate
                }
            }
            retryPlan.discardRequests.forEach(scheduledPages::remove)
            cancelActiveRequestsLocked(retryPlan.cancelRequests)
            retryPlan.requests.isNotEmpty()
        }
        if (registered) queueSignal.trySend(Unit)
    }

    override fun recycle() {
        super.recycle()
        queueSignal.close()
        scope.cancel()
        synchronized(preloadJobLock) {
            requestScheduler.moveTo(chapterId = readerChapterId(), currentPage = 0, pageCount = 0)
            scheduledPages.clear()
            activePreloadJobs.clear()
        }
        encodedPageStore.endSession()

        // Cache current page list progress for online chapters to allow a faster reopen
        chapter.pages?.let { pages ->
            launchIO {
                try {
                    // Convert to pages without reader information
                    val pagesToSave = pages.map { Page(it.index, it.url, it.imageUrl) }
                    chapterCache.putPageListToCache(chapter.chapter.toDomainChapter()!!, pagesToSave)
                } catch (e: Throwable) {
                    if (e is CancellationException) {
                        throw e
                    }
                }
            }
        }
    }

    /**
     * Loads the page, retrieving the image URL and downloading the image if necessary.
     * Downloaded images are stored in the chapter cache.
     *
     * @param page the page whose source image has to be downloaded.
     */
    private suspend fun internalLoadPage(
        page: ReaderPage,
        jobKey: ReaderRequestKey,
        forceRefresh: Boolean,
    ) {
        val port = AndroidReaderPageFetchPort(page, source, chapterCache, encodedPageStore)
        val request = ReaderPageFetchRequest(
            pageId = ReaderPageId(
                chapterId = ReaderChapterId(checkNotNull(chapter.chapter.id)),
                sourcePageIndex = page.index,
            ),
            generation = jobKey.generation,
            url = page.url,
            imageUrl = page.imageUrl,
        )
        try {
            val result = materializeExecutor.materializePage(
                request = request,
                port = port,
                forceRefresh = forceRefresh,
                publish = { event -> publishIfAccepted(jobKey) { page.applyMaterializeEvent(event, port) } },
            )
            if (result is ReaderPageMaterializeResult.Rejected) {
                discardStaleResult(page, jobKey)
            }
        } catch (e: CancellationException) {
            discardStaleResult(page, jobKey)
            throw e
        }
    }

    private fun ReaderPage.applyMaterializeEvent(
        event: ReaderPageMaterializeEvent,
        port: AndroidReaderPageFetchPort,
    ) {
        when (event) {
            ReaderPageMaterializeEvent.ResolvingImage -> status = Page.State.LoadPage
            is ReaderPageMaterializeEvent.Downloading -> {
                imageUrl = event.imageUrl
                status = Page.State.DownloadImage
            }
            is ReaderPageMaterializeEvent.Ready -> {
                imageUrl = event.imageUrl
                encodedPageRef = event.encodedPageRef
                stream = { port.openEncodedPage(event.encodedPageRef) }
                status = Page.State.Ready
            }
            is ReaderPageMaterializeEvent.Failed -> {
                failMaterialization(event.error, event.cause ?: IllegalStateException(event.error.toString()))
            }
        }
    }

    private fun runScheduledPage(request: ReaderScheduledRequest, page: ReaderPage) {
        val loadJob = scope.launch(start = CoroutineStart.LAZY) {
            physicalRequestPermits.withPermit {
                internalLoadPage(page, request.jobKey, request.forceRefresh)
            }
        }
        synchronized(preloadJobLock) { activePreloadJobs[request.jobKey] = loadJob }
        loadJob.invokeOnCompletion {
            synchronized(preloadJobLock) {
                if (activePreloadJobs[request.jobKey] === loadJob) {
                    activePreloadJobs.remove(request.jobKey)
                }
                requestScheduler.complete(request.jobKey)
                scheduledPages.remove(request.jobKey)
            }
            queueSignal.trySend(Unit)
        }
        loadJob.start()
    }

    private fun pollNextStartableLocked(): Pair<ReaderScheduledRequest, ReaderPage>? {
        while (true) {
            val request = requestScheduler.pollNext() ?: return null
            val page = scheduledPages[request.jobKey]
            if (page == null || page.status != Page.State.Queue) {
                requestScheduler.complete(request.jobKey)
                scheduledPages.remove(request.jobKey)
                continue
            }
            return request to page
        }
    }

    private fun registerEnqueueResultLocked(
        result: mihon.domain.reader.scheduler.ReaderEnqueueResult,
        pages: List<ReaderPage>,
    ) {
        result.replacedRequests.forEach(scheduledPages::remove)
        result.request?.let { request ->
            pages.getOrNull(request.pageIndex)?.let { scheduledPages[request.jobKey] = it }
        }
        val pendingRequests = requestScheduler.snapshot().pendingRequests
        pendingRequests.forEach { request ->
            pages.getOrNull(request.pageIndex)?.let { scheduledPages[request.jobKey] = it }
        }
        result.cancelRequests.forEach { cancelledKey ->
            val cancelledPage = scheduledPages[cancelledKey] ?: return@forEach
            val hasReplacement = pendingRequests.any { it.pageId == cancelledKey.pageId }
            if (hasReplacement && cancelledPage.status != Page.State.Ready) {
                cancelledPage.status = Page.State.Queue
            }
        }
        cancelActiveRequestsLocked(result.cancelRequests)
    }

    private fun removePendingRequest(request: ReaderScheduledRequest) {
        synchronized(preloadJobLock) {
            if (requestScheduler.cancelPending(request.jobKey)) {
                scheduledPages.remove(request.jobKey)
            }
        }
    }

    private fun cancelActiveRequestsLocked(cancelRequests: Set<ReaderRequestKey>) {
        cancelRequests.forEach { jobKey -> activePreloadJobs[jobKey]?.cancel() }
    }

    private fun publishIfAccepted(jobKey: ReaderRequestKey, publish: () -> Unit): Boolean =
        synchronized(preloadJobLock) {
            if (!requestScheduler.accepts(jobKey) || !activePreloadJobs.containsKey(jobKey)) {
                return@synchronized false
            }
            publish()
            true
        }

    private fun discardStaleResult(page: ReaderPage, jobKey: ReaderRequestKey) {
        synchronized(preloadJobLock) {
            val hasCurrentReplacement = requestScheduler.hasCurrentReplacement(readerPageId(page.index), jobKey)
            if (!hasCurrentReplacement && page.status != Page.State.Ready && page.status !is Page.State.Error) {
                page.status = Page.State.Queue
            }
        }
    }

    private fun readerChapterId() = ReaderChapterId(checkNotNull(chapter.chapter.id))

    private fun readerPageId(pageIndex: Int) = ReaderPageId(readerChapterId(), pageIndex)

    private companion object {
        const val MAX_STALE_PHYSICAL_REQUESTS = 1
    }
}
