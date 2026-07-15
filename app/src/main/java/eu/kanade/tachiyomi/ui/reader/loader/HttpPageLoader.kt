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
            registerPreloadLocked(page, PreloadJobKey(page.index, currentGeneration), priority = 2) != null
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
    private suspend fun internalLoadPage(page: ReaderPage, jobKey: PreloadJobKey) {
        try {
            if (page.imageUrl.isNullOrEmpty()) {
                page.status = Page.State.LoadPage
                val imageUrl = source.getImageUrl(page)
                if (!publishIfAccepted(jobKey) { page.imageUrl = imageUrl }) {
                    discardStaleResult(page, jobKey)
                    return
                }
            }
            val imageUrl = page.imageUrl!!

            if (!chapterCache.isImageInCache(imageUrl)) {
                page.status = Page.State.DownloadImage
                val imageResponse = source.getImage(page)
                chapterCache.putImageToCache(imageUrl, imageResponse)
            }

            if (!publishIfAccepted(jobKey) {
                    page.stream = { chapterCache.getImageFile(imageUrl).inputStream() }
                    page.status = Page.State.Ready
                }
            ) {
                discardStaleResult(page, jobKey)
                return
            }
        } catch (e: CancellationException) {
            discardStaleResult(page, jobKey)
            throw e
        } catch (e: Throwable) {
            if (!publishIfAccepted(jobKey) { page.status = Page.State.Error(e) }) {
                discardStaleResult(page, jobKey)
            }
        }
    }

    private fun runQueuedPage(queued: PriorityPage) {
        val loadJob = scope.launch(start = CoroutineStart.LAZY) { internalLoadPage(queued.page, queued.jobKey) }
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
    ): PriorityPage? {
        if (queuedPreloadJobs.containsKey(key) || activePreloadJobs.containsKey(key)) return null
        if (page.status == Page.State.Ready) return null
        if (activePreloadJobs.keys.any { it.pageIndex == key.pageIndex && it != key }) {
            page.status = Page.State.Queue
        }
        val queued = PriorityPage(page, priority, key)
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
