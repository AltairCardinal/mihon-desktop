package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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
) : PageLoader() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * A queue used to manage requests one by one while allowing priorities.
     */
    private val queue = PriorityBlockingQueue<PriorityPage>()

    private val preloadSize = 4
    private val preloadPlanner = ReaderPreloadPlanner(windowSize = preloadSize, backwardWindowSize = 0)
    private val activePreloadJobLock = Any()
    private var activePreloadJob: ActivePreloadJob? = null

    init {
        scope.launchIO {
            flow {
                while (true) {
                    emit(runInterruptible { queue.take() })
                }
            }
                .filter { it.page.status == Page.State.Queue }
                .collect(::runQueuedPage)
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

        val pages = page.chapter.pages.orEmpty()
        val preloadPlan = synchronized(preloadPlanner) {
            preloadPlanner.moveTo(page.index, pages.size)
        }
        queue.removeIf { queued ->
            queued.jobKey in preloadPlan.cancelRequests && queued.page.status == Page.State.Queue
        }
        synchronized(activePreloadJobLock) {
            activePreloadJob?.let { active ->
                cancelAndroidPreloadJob(active.key, active.job, preloadPlan.cancelRequests)
            }
        }
        val queuedPages = mutableListOf<PriorityPage>()
        queuedPages += preloadPlan.requests
            .asSequence()
            .mapNotNull { request ->
                val candidate = pages[request.pageIndex]
                if (candidate.status == Page.State.Queue) {
                    PriorityPage(
                        page = candidate,
                        priority = if (request.pageIndex == page.index) 1 else 0,
                        jobKey = request.jobKey,
                    ).also(queue::offer)
                } else {
                    null
                }
            }
            .toList()

        suspendCancellableCoroutine<Nothing> { continuation ->
            continuation.invokeOnCancellation {
                queuedPages.forEach {
                    if (it.page.status == Page.State.Queue) {
                        queue.remove(it)
                    }
                }
            }
        }
    }

    /**
     * Retries a page. This method is only called from user interaction on the viewer.
     */
    override fun retryPage(page: ReaderPage) {
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }
        queue.offer(PriorityPage(page, 2, jobKey = null))
    }

    override fun recycle() {
        super.recycle()
        scope.cancel()
        queue.clear()

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
    private suspend fun internalLoadPage(page: ReaderPage) {
        try {
            if (page.imageUrl.isNullOrEmpty()) {
                page.status = Page.State.LoadPage
                page.imageUrl = source.getImageUrl(page)
            }
            val imageUrl = page.imageUrl!!

            if (!chapterCache.isImageInCache(imageUrl)) {
                page.status = Page.State.DownloadImage
                val imageResponse = source.getImage(page)
                chapterCache.putImageToCache(imageUrl, imageResponse)
            }

            page.stream = { chapterCache.getImageFile(imageUrl).inputStream() }
            page.status = Page.State.Ready
        } catch (e: CancellationException) {
            page.status = Page.State.Queue
            throw e
        } catch (e: Throwable) {
            page.status = Page.State.Error(e)
        }
    }

    private suspend fun runQueuedPage(queued: PriorityPage) {
        val loadJob = scope.launch(start = CoroutineStart.LAZY) { internalLoadPage(queued.page) }
        val active = queued.jobKey?.let { ActivePreloadJob(it, loadJob) }
        if (active != null) {
            synchronized(activePreloadJobLock) { activePreloadJob = active }
        }
        try {
            loadJob.start()
            loadJob.join()
        } finally {
            synchronized(activePreloadJobLock) {
                if (activePreloadJob === active) activePreloadJob = null
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

private data class ActivePreloadJob(val key: PreloadJobKey, val job: Job)

/**
 * Data class used to keep ordering of pages in order to maintain priority.
 */
@OptIn(ExperimentalAtomicApi::class)
private class PriorityPage(
    val page: ReaderPage,
    val priority: Int,
    val jobKey: PreloadJobKey?,
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
