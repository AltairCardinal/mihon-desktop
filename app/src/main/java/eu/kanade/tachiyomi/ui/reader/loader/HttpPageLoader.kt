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
import mihon.domain.reader.PreloadJobKey
import mihon.domain.reader.ReaderPreloadPlanner
import mihon.domain.reader.materialize.CanonicalReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderMaterializeExecutor
import mihon.domain.reader.materialize.ReaderPageFetchRequest
import mihon.domain.reader.materialize.ReaderPageMaterializeEvent
import mihon.domain.reader.materialize.ReaderPageMaterializeResult
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.PriorityBlockingQueue
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Loader used to load chapters from an online source.
 */
internal class HttpPageLoader(
    private val chapter: ReaderChapter,
    private val source: HttpSource,
    private val chapterCache: ChapterCache = Injekt.get(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val materializeExecutor: ReaderMaterializeExecutor = CanonicalReaderMaterializeExecutor,
) : PageLoader() {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * A queue used to manage requests one by one while allowing priorities.
     */
    private val queue = PriorityBlockingQueue<PriorityPage>()
    private val queueSignal = Channel<Unit>(Channel.CONFLATED)

    private val preloadSize = 4
    private val preloadPlanner = ReaderPreloadPlanner(windowSize = preloadSize, backwardWindowSize = 0)
    private val preloadJobLock = Any()
    private val queuedPreloadJobs = mutableMapOf<PreloadJobKey, PriorityPage>()
    private val activePreloadJobs = mutableMapOf<PreloadJobKey, Job>()
    private var currentGeneration = 0L

    init {
        scope.launch {
            for (signal in queueSignal) {
                while (true) {
                    val queued = synchronized(preloadJobLock, ::pollNextStartableLocked) ?: break
                    runQueuedPage(queued)
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
        }
    }

    /**
     * Loads a page through the queue. Handles re-enqueueing pages if they were evicted from the cache.
     */
    override suspend fun loadPage(page: ReaderPage) = withIOContext {
        val imageUrl = page.imageUrl

        // Check if the image has been deleted
        if (page.status == Page.State.Ready && imageUrl != null && !chapterCache.isImageInCache(imageUrl)) {
            page.status = Page.State.Queue
        }

        // Automatically retry failed pages when subscribed to this page
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }

        val queuedPage = synchronized(preloadJobLock) {
            registerPreloadLocked(
                page = page,
                key = PreloadJobKey(page.index, currentGeneration),
                priority = 2,
                forceRefresh = false,
            )
        }
        if (queuedPage != null) queueSignal.trySend(Unit)

        suspendCancellableCoroutine<Nothing> { continuation ->
            continuation.invokeOnCancellation {
                queuedPage?.let(::removeQueuedPreload)
            }
        }
    }

    override fun onPageSelected(page: ReaderPage) {
        val pages = page.chapter.pages.orEmpty()
        val preloadPlan = synchronized(preloadPlanner) {
            preloadPlanner.moveTo(page.index, pages.size)
        }
        val registered = synchronized(preloadJobLock) {
            currentGeneration = preloadPlan.generation

            // Register the replacement generation before cancellation can restore the old page to Queue.
            val replacements = preloadPlan.requests.mapNotNull { request ->
                val candidate = pages[request.pageIndex]
                registerPreloadLocked(
                    page = candidate,
                    key = request.jobKey,
                    priority = if (request.pageIndex == page.index) 1 else 0,
                    forceRefresh = false,
                )
            }

            val staleQueued = queuedPreloadJobs
                .filterKeys { it.generation != preloadPlan.generation }
                .values
                .toList()
            staleQueued.forEach { queued ->
                queuedPreloadJobs.remove(queued.jobKey)
                queue.remove(queued)
            }

            activePreloadJobs
                .filterKeys { it.generation != preloadPlan.generation }
                .values
                .forEach(Job::cancel)
            replacements.isNotEmpty()
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
        val registered = synchronized(preloadJobLock) {
            registerPreloadLocked(
                page = page,
                key = PreloadJobKey(page.index, currentGeneration),
                priority = 2,
                forceRefresh = true,
            ) != null
        }
        if (registered) queueSignal.trySend(Unit)
    }

    override fun recycle() {
        super.recycle()
        queueSignal.close()
        scope.cancel()
        queue.clear()
        synchronized(preloadJobLock) {
            queuedPreloadJobs.clear()
            activePreloadJobs.clear()
        }

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
        jobKey: PreloadJobKey,
        forceRefresh: Boolean,
    ) {
        val port = AndroidReaderPageFetchPort(page, source, chapterCache)
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

    private fun runQueuedPage(queued: PriorityPage) {
        val loadJob = scope.launch(start = CoroutineStart.LAZY) {
            internalLoadPage(queued.page, queued.jobKey, queued.forceRefresh)
        }
        synchronized(preloadJobLock) { activePreloadJobs[queued.jobKey] = loadJob }
        loadJob.invokeOnCompletion {
            synchronized(preloadJobLock) {
                if (activePreloadJobs[queued.jobKey] === loadJob) {
                    activePreloadJobs.remove(queued.jobKey)
                }
            }
            queueSignal.trySend(Unit)
        }
        loadJob.start()
    }

    private fun pollNextStartableLocked(): PriorityPage? {
        while (true) {
            val queued = queue.peek() ?: return null
            val isRegistered = queuedPreloadJobs[queued.jobKey] === queued &&
                queued.jobKey.generation == currentGeneration
            if (!isRegistered || queued.page.status != Page.State.Queue) {
                queue.poll()
                if (queuedPreloadJobs[queued.jobKey] === queued) {
                    queuedPreloadJobs.remove(queued.jobKey)
                }
                continue
            }
            if (activePreloadJobs.keys.any { it.generation == queued.jobKey.generation }) {
                return null
            }
            queue.poll()
            queuedPreloadJobs.remove(queued.jobKey)
            return queued
        }
    }

    private fun registerPreloadLocked(
        page: ReaderPage,
        key: PreloadJobKey,
        priority: Int,
        forceRefresh: Boolean,
    ): PriorityPage? {
        if (queuedPreloadJobs.containsKey(key) || activePreloadJobs.containsKey(key)) return null
        if (page.status == Page.State.Ready) return null
        if (activePreloadJobs.keys.any { it.pageIndex == key.pageIndex && it != key }) {
            page.status = Page.State.Queue
        }
        val queued = PriorityPage(page, priority, key, forceRefresh)
        queuedPreloadJobs[key] = queued
        queue.offer(queued)
        return queued
    }

    private fun removeQueuedPreload(queued: PriorityPage) {
        synchronized(preloadJobLock) {
            if (queuedPreloadJobs[queued.jobKey] === queued) {
                queuedPreloadJobs.remove(queued.jobKey)
                queue.remove(queued)
            }
        }
    }

    private fun publishIfAccepted(jobKey: PreloadJobKey, publish: () -> Unit): Boolean =
        synchronized(preloadJobLock) {
            if (jobKey.generation != currentGeneration || !activePreloadJobs.containsKey(jobKey)) {
                return@synchronized false
            }
            publish()
            true
        }

    private fun discardStaleResult(page: ReaderPage, jobKey: PreloadJobKey) {
        synchronized(preloadJobLock) {
            val hasCurrentReplacement = queuedPreloadJobs.keys.any {
                it.pageIndex == jobKey.pageIndex && it.generation == currentGeneration && it != jobKey
            } || activePreloadJobs.keys.any {
                it.pageIndex == jobKey.pageIndex && it.generation == currentGeneration && it != jobKey
            }
            if (!hasCurrentReplacement && page.status != Page.State.Ready && page.status !is Page.State.Error) {
                page.status = Page.State.Queue
            }
        }
    }
}

internal fun cancelAndroidPreloadJob(
    activeKey: PreloadJobKey,
    activeJob: Job,
    cancelRequests: Set<PreloadJobKey>,
): Boolean {
    if (activeKey !in cancelRequests) return false
    activeJob.cancel()
    return true
}

/**
 * Data class used to keep ordering of pages in order to maintain priority.
 */
@OptIn(ExperimentalAtomicApi::class)
private class PriorityPage(
    val page: ReaderPage,
    val priority: Int,
    val jobKey: PreloadJobKey,
    val forceRefresh: Boolean,
) : Comparable<PriorityPage> {
    companion object {
        private val idGenerator = AtomicInt(0)
    }

    private val identifier = idGenerator.incrementAndFetch()

    override fun compareTo(other: PriorityPage): Int {
        val p = other.priority.compareTo(priority)
        return if (p != 0) p else identifier.compareTo(other.identifier)
    }
}
