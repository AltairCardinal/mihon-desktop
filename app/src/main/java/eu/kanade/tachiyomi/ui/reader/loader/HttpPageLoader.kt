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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import mihon.domain.reader.materialize.CanonicalReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderPageFetchRequest
import mihon.domain.reader.materialize.ReaderPageMaterializeEvent
import mihon.domain.reader.materialize.ReaderPageMaterializeResult
import mihon.domain.reader.scheduler.ReaderPageMaterializeCompletion
import mihon.domain.reader.scheduler.ReaderPageMaterializeRunner
import mihon.domain.reader.scheduler.ReaderPageMaterializeRunnerPort
import mihon.domain.reader.scheduler.ReaderPageMaterializeRunnerSnapshot
import mihon.domain.reader.scheduler.ReaderPageMaterializeWork
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

private data class AndroidReaderPageBinding(
    val page: ReaderPage,
    val fetchPort: AndroidReaderPageFetchPort,
)

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

    private val preloadJobLock = Any()
    private val scheduledPages = mutableMapOf<ReaderRequestKey, ReaderPage>()
    private val pageRunner = ReaderPageMaterializeRunner(
        scope = scope,
        maxConcurrentRequests = requestScheduler.snapshot().maxConcurrentRequests,
        maxStalePhysicalRequests = MAX_STALE_PHYSICAL_REQUESTS,
        materializeExecutor = materializeExecutor,
        port = AndroidPageMaterializeRunnerPort(),
    )

    internal fun pageRunnerSnapshot(): ReaderPageMaterializeRunnerSnapshot = pageRunner.snapshot()

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

        val enqueueResult = synchronized(preloadJobLock) {
            val result = requestScheduler.enqueue(
                pageId = readerPageId(page.index),
                kind = ReaderRequestKind.INTERACTIVE_VISIBLE,
            )
            registerEnqueueResultLocked(result, page.chapter.pages.orEmpty())
            result
        }
        pageRunner.cancel(enqueueResult.cancelRequests)
        pageRunner.pump()

        suspendCancellableCoroutine<Nothing> { continuation ->
            continuation.invokeOnCancellation {
                enqueueResult.request?.let(::removePendingRequest)
            }
        }
    }

    override fun onPageSelected(page: ReaderPage) {
        val pages = page.chapter.pages.orEmpty()
        val preloadPlan = synchronized(preloadJobLock) {
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
            preloadPlan
        }
        pageRunner.cancel(preloadPlan.cancelRequests)
        pageRunner.pump()
    }

    /**
     * Retries a page. This method is only called from user interaction on the viewer.
     */
    override fun retryPage(page: ReaderPage) {
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }
        val pages = page.chapter.pages.orEmpty()
        val retryPlan = synchronized(preloadJobLock) {
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
            retryPlan
        }
        pageRunner.cancel(retryPlan.cancelRequests)
        pageRunner.pump()
    }

    override fun recycle() {
        super.recycle()
        val closePlan = synchronized(preloadJobLock) {
            requestScheduler.moveTo(chapterId = readerChapterId(), currentPage = 0, pageCount = 0).also {
                scheduledPages.clear()
            }
        }
        pageRunner.cancel(closePlan.cancelRequests)
        pageRunner.close()
        scope.cancel()
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

    private inner class AndroidPageMaterializeRunnerPort :
        ReaderPageMaterializeRunnerPort<AndroidReaderPageBinding> {

        override fun pollNext(): ReaderPageMaterializeWork<AndroidReaderPageBinding>? =
            synchronized(preloadJobLock) {
                while (true) {
                    val request = requestScheduler.pollNext() ?: return@synchronized null
                    val page = scheduledPages[request.jobKey]
                    if (page == null || page.status != Page.State.Queue) {
                        requestScheduler.complete(request.jobKey)
                        scheduledPages.remove(request.jobKey)
                        continue
                    }
                    val fetchPort = AndroidReaderPageFetchPort(page, source, chapterCache, encodedPageStore)
                    return@synchronized ReaderPageMaterializeWork(
                        request = request,
                        fetchRequest = ReaderPageFetchRequest(
                            pageId = request.pageId,
                            generation = request.generation,
                            url = page.url,
                            imageUrl = page.imageUrl,
                        ),
                        fetchPort = fetchPort,
                        binding = AndroidReaderPageBinding(page, fetchPort),
                    )
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }

        override fun accepts(work: ReaderPageMaterializeWork<AndroidReaderPageBinding>): Boolean =
            synchronized(preloadJobLock) { requestScheduler.accepts(work.request.jobKey) }

        override fun publish(
            work: ReaderPageMaterializeWork<AndroidReaderPageBinding>,
            event: ReaderPageMaterializeEvent,
        ): Boolean = synchronized(preloadJobLock) {
            if (!requestScheduler.accepts(work.request.jobKey)) return@synchronized false
            work.binding.page.applyMaterializeEvent(event, work.binding.fetchPort)
            true
        }

        override fun complete(
            work: ReaderPageMaterializeWork<AndroidReaderPageBinding>,
            completion: ReaderPageMaterializeCompletion,
        ) {
            synchronized(preloadJobLock) {
                val rejected =
                    completion is ReaderPageMaterializeCompletion.Cancelled ||
                        completion is ReaderPageMaterializeCompletion.Failed ||
                        (completion as? ReaderPageMaterializeCompletion.Completed)?.result is
                            ReaderPageMaterializeResult.Rejected
                if (rejected) discardStaleResultLocked(work.binding.page, work.request.jobKey)
                requestScheduler.complete(work.request.jobKey)
                scheduledPages.remove(work.request.jobKey)
            }
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
    }

    private fun removePendingRequest(request: ReaderScheduledRequest) {
        synchronized(preloadJobLock) {
            if (requestScheduler.cancelPending(request.jobKey)) {
                scheduledPages.remove(request.jobKey)
            }
        }
        pageRunner.pump()
    }

    private fun discardStaleResultLocked(page: ReaderPage, jobKey: ReaderRequestKey) {
        val hasCurrentReplacement = requestScheduler.hasCurrentReplacement(readerPageId(page.index), jobKey)
        if (!hasCurrentReplacement && page.status != Page.State.Ready && page.status !is Page.State.Error) {
            page.status = Page.State.Queue
        }
    }

    private fun readerChapterId() = ReaderChapterId(checkNotNull(chapter.chapter.id))

    private fun readerPageId(pageIndex: Int) = ReaderPageId(readerChapterId(), pageIndex)

    private companion object {
        const val MAX_STALE_PHYSICAL_REQUESTS = 1
    }
}
