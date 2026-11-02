package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPageContentFact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import mihon.domain.error.AppError
import mihon.domain.network.AppErrorException
import mihon.domain.reader.ReaderDualPageFactReadPermit
import mihon.domain.reader.ReaderDualPageFrameId
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
import mihon.domain.reader.scheduler.ReaderVisiblePairAdmission
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
    val content: Pair<String, ReaderPageContentFact>,
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

    private data class NormalWindow(val anchor: ReaderPage, val pages: List<ReaderPage>)
    private data class NormalViewport(
        val lease: ReaderNormalViewportLease,
        val frameId: ReaderDualPageFrameId,
        val updateSequence: Long,
        val window: NormalWindow,
        val scheduledGeneration: Long? = null,
    )
    private var nextViewportSequence = 0L
    private var viewportLease: ReaderNormalViewportLease? = null
    private var normalViewport: NormalViewport? = null
    private var plannedNormalWindow: NormalWindow? = null
    private var plannedNormalGeneration: Long? = null
    private val foregroundRevision = MutableStateFlow(0L)
    private val scheduledPages = mutableMapOf<ReaderRequestKey, ReaderPage>()
    private val pageRunner = ReaderPageMaterializeRunner(
        scope = scope,
        maxConcurrentRequests = requestScheduler.snapshot().maxConcurrentRequests,
        maxStalePhysicalRequests = MAX_STALE_PHYSICAL_REQUESTS,
        materializeExecutor = materializeExecutor,
        port = AndroidPageMaterializeRunnerPort(),
    )

    override val hasForegroundWork: Boolean
        get() = synchronized(preloadJobLock) {
            val requests = requestScheduler.snapshot()
            requests.pendingRequests.isNotEmpty() || requests.activeRequests.isNotEmpty() ||
                pageRunner.physicalWorkCount.value != 0
        }

    override val foregroundWork = combine(foregroundRevision, pageRunner.physicalWorkCount) { _, _ ->
        hasForegroundWork
    }.distinctUntilChanged()

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
    override suspend fun loadPage(page: ReaderPage): Unit = withContext(dispatcher) {
        if (isRecycled || !isCurrentPage(page)) return@withContext
        val imageUrl = page.imageUrl

        // Check if the image has been deleted
        if (page.status == Page.State.Ready && imageUrl != null && !chapterCache.isImageInCache(imageUrl)) {
            page.status = Page.State.Queue
        }

        // Automatically retry failed pages when subscribed to this page
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }

        val viewportCancellations = mutableSetOf<ReaderRequestKey>()
        val enqueueResult = synchronized(preloadJobLock) {
            currentViewportLocked()?.takeIf { page in it.window.pages }?.let { viewport ->
                if (plannedNormalWindow != viewport.window ||
                    viewport.scheduledGeneration != plannedNormalGeneration
                ) {
                    viewportCancellations += planNormalWindowLocked(viewport.window).cancelRequests
                }
            }
            if (promoteBoundOrdinaryPageLocked(page)) {
                foregroundRevision.update { it + 1 }
                return@synchronized mihon.domain.reader.scheduler.ReaderEnqueueResult(request = null)
            }
            val result = requestScheduler.enqueue(
                pageId = readerPageId(page.index),
                kind = ReaderRequestKind.INTERACTIVE_VISIBLE,
            )
            registerEnqueueResultLocked(result, page.chapter.pages.orEmpty())
            result
        }
        pageRunner.cancel(viewportCancellations + enqueueResult.cancelRequests)
        pageRunner.pump()

        suspendCancellableCoroutine<Nothing> { continuation ->
            continuation.invokeOnCancellation {
                enqueueResult.request?.let(::removePendingRequest)
            }
        }
    }

    override suspend fun loadFactPage(page: ReaderPage, permit: ReaderDualPageFactReadPermit) = withContext(
        dispatcher,
    ) {
        check(!isRecycled)
        if (page.status == Page.State.Ready) {
            page.publishHeaderFact()
            return@withContext
        }
        val result = synchronized(preloadJobLock) {
            requestScheduler.enqueue(
                readerPageId(page.index),
                ReaderRequestKind.CURRENT_BACKGROUND,
                factPermit = permit,
            ).also {
                registerEnqueueResultLocked(it, page.chapter.pages.orEmpty())
            }
        }
        pageRunner.cancel(result.cancelRequests)
        pageRunner.pump()
        try {
            page.statusFlow.first { it == Page.State.Ready || it is Page.State.Error }
            Unit
        } finally {
            // A null request means the normal scheduler already owns this page; withdraw only our own ticket.
            result.request?.let { request ->
                synchronized(preloadJobLock) {
                    requestScheduler.cancelPending(request.jobKey)
                    requestScheduler.complete(request.jobKey)
                    scheduledPages.remove(request.jobKey)
                    foregroundRevision.update { it + 1 }
                }
                pageRunner.cancel(setOf(request.jobKey))
                pageRunner.pump()
            }
        }
    }

    override fun onPageSelected(page: ReaderPage) {
        val preloadPlan = synchronized(preloadJobLock) {
            if (isRecycled || !isCurrentPage(page)) return
            val viewport = currentViewportLocked()?.takeIf { it.window.anchor === page }
            if (viewport == null) {
                viewportLease = null
                normalViewport = null
            }
            planNormalWindowLocked(viewport?.window ?: NormalWindow(page, listOf(page)))
        }
        pageRunner.cancel(preloadPlan.cancelRequests)
        pageRunner.pump()
    }

    private fun planNormalWindowLocked(window: NormalWindow): mihon.domain.reader.scheduler.ReaderSchedulePlan {
        val pages = window.anchor.chapter.pages.orEmpty()
        val plan = requestScheduler.moveTo(
            readerChapterId(),
            window.pages.map { it.index },
            window.anchor.index,
            pages.size,
        )
        // Register replacements before cancellation can restore an overlapping page to Queue.
        plan.requests.forEach { request ->
            val candidate = pages[request.pageIndex]
            if (plan.cancelRequests.any { it.pageIndex == request.pageIndex } && candidate.status != Page.State.Ready) {
                candidate.status = Page.State.Queue
            }
            scheduledPages[request.jobKey] = candidate
        }
        plan.discardRequests.forEach(scheduledPages::remove)
        foregroundRevision.update { it + 1 }
        plannedNormalWindow = window
        plannedNormalGeneration = plan.generation
        normalViewport?.takeIf { it.lease === viewportLease && it.window == window }?.let {
            normalViewport = it.copy(scheduledGeneration = plan.generation)
        }
        return plan
    }

    internal override fun acquireNormalViewport(): ReaderNormalViewportLease? = synchronized(preloadJobLock) {
        if (isRecycled) return@synchronized null
        ReaderNormalViewportLease(this, ++nextViewportSequence).also {
            viewportLease = it
            normalViewport = null
        }
    }

    internal override fun updateNormalViewport(
        lease: ReaderNormalViewportLease,
        frameId: ReaderDualPageFrameId,
        updateSequence: Long,
        anchor: ReaderPage,
        pages: List<ReaderPage>,
    ): Boolean = synchronized(preloadJobLock) {
        if (isRecycled || lease.loader !== this || lease !== viewportLease || pages.size !in 1..2 ||
            pages.distinct().size != pages.size || anchor.chapter !== chapter || updateSequence <= 0 ||
            chapter.pages?.getOrNull(anchor.index) !== anchor ||
            pages.any { it.chapter !== chapter || chapter.pages?.getOrNull(it.index) !== it } ||
            (pages.size == 2 && anchor !in pages)
        ) {
            return@synchronized false
        }
        val previous = normalViewport
        if (previous != null && updateSequence <= previous.updateSequence) return@synchronized false
        val window = NormalWindow(anchor, pages.toList())
        // Frame IDs are opaque. A successfully fenced current-viewer notification may restore any prepared frame.
        val generation = plannedNormalGeneration.takeIf {
            plannedNormalWindow == window && it == requestScheduler.snapshot().generation
        }
        normalViewport = NormalViewport(lease, frameId, updateSequence, window, generation)
        true
    }

    internal override fun releaseNormalViewport(lease: ReaderNormalViewportLease) = synchronized(preloadJobLock) {
        if (viewportLease === lease) {
            viewportLease = null
            normalViewport = null
        }
    }

    private fun isCurrentPage(page: ReaderPage): Boolean =
        page.chapter === chapter && chapter.pages?.getOrNull(page.index) === page

    private fun currentViewportLocked(): NormalViewport? {
        val viewport = normalViewport ?: return null
        if (viewport.lease !== viewportLease || !isCurrentPage(viewport.window.anchor) ||
            viewport.window.pages.any { !isCurrentPage(it) }
        ) {
            viewportLease = null
            normalViewport = null
            return null
        }
        return viewport
    }

    private fun visiblePairAdmissionLocked(): ReaderVisiblePairAdmission? {
        val viewport = currentViewportLocked()?.takeIf { it.window == plannedNormalWindow } ?: return null
        val generation = viewport.scheduledGeneration ?: return null
        if (viewport.window.pages.size != 2 || generation != plannedNormalGeneration ||
            generation != requestScheduler.snapshot().generation
        ) {
            return null
        }
        return ReaderVisiblePairAdmission(
            generation,
            viewport.window.pages.mapTo(mutableSetOf()) { readerPageId(it.index) },
        )
    }

    /**
     * Retries a page. This method is only called from user interaction on the viewer.
     */
    override fun retryPage(page: ReaderPage) {
        super.retryPage(page)
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }
        val pages = page.chapter.pages.orEmpty()
        val retryPlan = synchronized(preloadJobLock) {
            val retryPlan = requestScheduler.retry(readerPageId(page.index), pages.size)
            val viewport = currentViewportLocked()
            if (viewport != null && page in viewport.window.pages && viewport.window == plannedNormalWindow) {
                plannedNormalGeneration = retryPlan.generation
                normalViewport = viewport.copy(scheduledGeneration = retryPlan.generation)
            } else if (viewport != null) {
                viewportLease = null
                normalViewport = null
            }
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
            foregroundRevision.update { it + 1 }
            retryPlan
        }
        pageRunner.cancel(retryPlan.cancelRequests)
        pageRunner.pump()
    }

    override fun recycle() {
        super.recycle()
        val closePlan = synchronized(preloadJobLock) {
            viewportLease = null
            normalViewport = null
            plannedNormalWindow = null
            plannedNormalGeneration = null
            requestScheduler.moveTo(chapterId = readerChapterId(), currentPage = 0, pageCount = 0).also {
                scheduledPages.clear()
                foregroundRevision.update { revision -> revision + 1 }
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
                    val request = requestScheduler.pollNext(visiblePairAdmissionLocked()) ?: return@synchronized null
                    foregroundRevision.update { it + 1 }
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
                            factReadPermit = request.factPermit,
                        ),
                        fetchPort = fetchPort,
                        binding = AndroidReaderPageBinding(page, fetchPort, page.captureContentFact()),
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
                foregroundRevision.update { it + 1 }
            }
        }
    }

    /** Only a kind change may reuse the already running ordinary source operation. */
    private fun promoteBoundOrdinaryPageLocked(page: ReaderPage): Boolean {
        val request = requestScheduler.snapshot().activeRequests.singleOrNull {
            it.pageId == readerPageId(page.index)
        } ?: return false
        if (request.forceRefresh || request.factPermit != null || scheduledPages[request.jobKey] !== page) return false
        val work = pageRunner.runningWork(request.jobKey) ?: return false
        if (work.binding.page !== page || work.request.forceRefresh || work.request.factPermit != null) return false
        val expected = ReaderPageFetchRequest(
            pageId = request.pageId,
            generation = request.generation,
            url = page.url,
            imageUrl = page.imageUrl,
        )
        if (work.fetchRequest != expected) return false
        var promoted = false
        // The original binding captures this loader's source/cache/store owner. The page's
        // existing content guard also excludes replacement and retry while preserving that binding.
        page.consumeContentFact(work.binding.content) {
            promoted = requestScheduler.promoteActive(request.jobKey, ReaderRequestKind.INTERACTIVE_VISIBLE)
        }
        return promoted
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
                publishHeaderFact()
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
        foregroundRevision.update { it + 1 }
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
                foregroundRevision.update { it + 1 }
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
