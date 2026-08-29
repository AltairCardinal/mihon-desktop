package mihon.desktop.reader

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import mihon.domain.reader.PageCacheCommitResult
import mihon.domain.reader.PageCacheSnapshot
import mihon.domain.reader.PageCacheWrite
import mihon.domain.reader.PageDecodeRequest
import mihon.domain.reader.PageDecodeResult
import mihon.domain.reader.PageDecoder
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.RegionDecoder
import mihon.domain.reader.content.ReaderPageContentLease
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.scheduler.ReaderRequestKey
import mihon.domain.reader.scheduler.ReaderRequestScheduler
import mihon.domain.reader.scheduler.ReaderScheduledRequest
import mihon.domain.reader.scheduler.ReaderSchedulerPolicy
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId

/**
 * Desktop adapter for the shared preload-window contract.
 * Late results are generation-checked, stale jobs are cancelled, and decoded pages obey a byte budget.
 */
class PagePreloader private constructor(
    private val contentSource: ContentSource,
    val windowSize: Int = 3,
    private val maxDecodedWidth: Int = 2048,
    private val maxDecodedHeight: Int = 2048,
    maxCacheBytes: Long = DEFAULT_CACHE_BYTES,
    private val largeImagePixelThreshold: Long = DEFAULT_LARGE_IMAGE_PIXELS,
    private val requestScheduler: ReaderRequestScheduler = ReaderRequestScheduler(
        ReaderSchedulerPolicy(
            nearbyForward = windowSize,
            nearbyBackward = windowSize,
            maxConcurrentRequests = DEFAULT_CONCURRENT_REQUESTS,
        ),
    ),
    private val ioGate: ReaderIoGate = ReaderIoGate.None,
    private val pageDecoder: PageDecoder<ByteArray, ImageBitmap> = SkiaPageDecoder(),
    private val regionDecoder: RegionDecoder<ByteArray, ImageBitmap> = SkiaRegionPageDecoder(),
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        encodedPageReader: suspend (ref: EncodedPageRef) -> ByteArray?,
        windowSize: Int = 3,
        maxDecodedWidth: Int = 2048,
        maxDecodedHeight: Int = 2048,
        maxCacheBytes: Long = DEFAULT_CACHE_BYTES,
        largeImagePixelThreshold: Long = DEFAULT_LARGE_IMAGE_PIXELS,
        requestScheduler: ReaderRequestScheduler = ReaderRequestScheduler(
            ReaderSchedulerPolicy(
                nearbyForward = windowSize,
                nearbyBackward = windowSize,
                maxConcurrentRequests = DEFAULT_CONCURRENT_REQUESTS,
            ),
        ),
        ioGate: ReaderIoGate = ReaderIoGate.None,
        pageDecoder: PageDecoder<ByteArray, ImageBitmap> = SkiaPageDecoder(),
        regionDecoder: RegionDecoder<ByteArray, ImageBitmap> = SkiaRegionPageDecoder(),
        decodeDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        contentSource = ContentSource.Legacy(encodedPageReader),
        windowSize = windowSize,
        maxDecodedWidth = maxDecodedWidth,
        maxDecodedHeight = maxDecodedHeight,
        maxCacheBytes = maxCacheBytes,
        largeImagePixelThreshold = largeImagePixelThreshold,
        requestScheduler = requestScheduler,
        ioGate = ioGate,
        pageDecoder = pageDecoder,
        regionDecoder = regionDecoder,
        decodeDispatcher = decodeDispatcher,
    )

    internal constructor(
        pageContentOwner: DesktopReaderPageContentOwner,
        windowSize: Int = 3,
        maxDecodedWidth: Int = 2048,
        maxDecodedHeight: Int = 2048,
        maxCacheBytes: Long = DEFAULT_CACHE_BYTES,
        largeImagePixelThreshold: Long = DEFAULT_LARGE_IMAGE_PIXELS,
        requestScheduler: ReaderRequestScheduler = ReaderRequestScheduler(
            ReaderSchedulerPolicy(
                nearbyForward = windowSize,
                nearbyBackward = windowSize,
                maxConcurrentRequests = DEFAULT_CONCURRENT_REQUESTS,
            ),
        ),
        ioGate: ReaderIoGate = ReaderIoGate.None,
        pageDecoder: PageDecoder<ByteArray, ImageBitmap> = SkiaPageDecoder(),
        regionDecoder: RegionDecoder<ByteArray, ImageBitmap> = SkiaRegionPageDecoder(),
        decodeDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        contentSource = ContentSource.Owned(pageContentOwner),
        windowSize = windowSize,
        maxDecodedWidth = maxDecodedWidth,
        maxDecodedHeight = maxDecodedHeight,
        maxCacheBytes = maxCacheBytes,
        largeImagePixelThreshold = largeImagePixelThreshold,
        requestScheduler = requestScheduler,
        ioGate = ioGate,
        pageDecoder = pageDecoder,
        regionDecoder = regionDecoder,
        decodeDispatcher = decodeDispatcher,
    )

    private sealed interface ContentSource {
        class Legacy(val reader: suspend (EncodedPageRef) -> ByteArray?) : ContentSource
        class Owned(val owner: DesktopReaderPageContentOwner) : ContentSource
    }

    private data class Decoded(
        val index: Int,
        val result: PageDecodeResult<ImageBitmap>,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val contentLease: ReaderPageContentLease<ByteArray>?,
        val contentLeaseHandoff: ContentLeaseHandoff,
    )

    private class ContentLeaseHandoff {
        private val lock = Any()
        private var contentLease: ReaderPageContentLease<ByteArray>? = null
        private var claimed = false

        fun attach(lease: ReaderPageContentLease<ByteArray>) {
            val releaseImmediately = synchronized(lock) {
                if (claimed) {
                    true
                } else {
                    check(contentLease == null) { "Content lease is already attached" }
                    contentLease = lease
                    false
                }
            }
            if (releaseImmediately) lease.close()
        }

        fun claim() = synchronized(lock) {
            claimed = true
        }

        fun closeIfUnclaimed() {
            val lease = synchronized(lock) {
                if (claimed) {
                    null
                } else {
                    claimed = true
                    contentLease
                }
            }
            lease?.close()
        }
    }

    private data class SourceSize(val width: Int, val height: Int)

    private val lock = Any()
    private val cache = DesktopPageCache(maxCacheBytes)
    private val activeJobs = mutableMapOf<ReaderRequestKey, Deferred<Decoded?>>()
    private val sourceSizes = mutableMapOf<Int, SourceSize>()
    private val contentLeases = mutableMapOf<Int, ReaderPageContentLease<ByteArray>>()

    val cacheRevision: StateFlow<Long> = cache.revision
    val cacheGeneration: StateFlow<Long> = cacheRevision

    init {
        require(maxDecodedWidth > 0 && maxDecodedHeight > 0) { "decoded bounds must be positive" }
        require(largeImagePixelThreshold > 0) { "largeImagePixelThreshold must be positive" }
    }

    suspend fun preloadEncoded(
        currentPage: Int,
        encodedPageRefs: List<EncodedPageRef?>,
        pageIds: List<ReaderPageId>? = null,
        observer: ReaderPageIoObserver? = null,
        sessionGeneration: Long? = null,
    ) = preloadSources(currentPage, encodedPageRefs, pageIds, observer, sessionGeneration)

    private suspend fun preloadSources(
        currentPage: Int,
        sources: List<EncodedPageRef?>,
        pageIds: List<ReaderPageId>?,
        observer: ReaderPageIoObserver?,
        sessionGeneration: Long?,
    ) = supervisorScope {
        require(pageIds == null || pageIds.size == sources.size) { "pageIds must match sources" }
        val preloadJob = currentCoroutineContext()[Job]
        val plan = synchronized(lock) {
            requestScheduler.moveTo(SCHEDULER_CHAPTER_ID, currentPage, sources.size).also {
                it.cancelRequests.forEach { jobKey -> activeJobs.remove(jobKey)?.cancel() }
                check(cache.beginGeneration(it.generation, it.evictPageIndices))
                sourceSizes.keys.retainAll(cache.snapshot().keys)
                releaseContentLeasesExcept(cache.snapshot().keys)
            }
        }
        while (true) {
            val jobs = synchronized(lock) {
                if (cache.generation != plan.generation || !requestScheduler.acceptsGeneration(plan.generation)) {
                    emptyList()
                } else {
                    buildList {
                        while (true) {
                            val request = requestScheduler.pollNext() ?: break
                            val index = request.pageIndex
                            if (index !in sources.indices || cache.get(index) != null) {
                                requestScheduler.complete(request.jobKey)
                                continue
                            }
                            val contentLeaseHandoff = ContentLeaseHandoff()
                            preloadJob?.invokeOnCompletion { cause ->
                                if (cause != null) contentLeaseHandoff.closeIfUnclaimed()
                            }
                            val job = async(decodeDispatcher, start = CoroutineStart.LAZY) {
                                decodePage(
                                    request,
                                    sources[index],
                                    pageIds?.get(index),
                                    observer,
                                    sessionGeneration,
                                    contentLeaseHandoff,
                                )
                            }
                            activeJobs[request.jobKey] = job
                            add(request to job)
                        }
                    }
                }
            }
            if (jobs.isEmpty()) break
            jobs.forEach { (_, job) -> job.start() }

            try {
                jobs.forEach { (request, job) ->
                    val decoded = try {
                        job.await()?.also { it.contentLeaseHandoff.claim() }
                    } catch (error: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw error
                        null
                    }
                    if (decoded != null) {
                        synchronized(lock) {
                            if (requestScheduler.accepts(request.jobKey) && decoded.index in plan.keepPageIndices) {
                                commitDecodedPage(decoded)
                            } else {
                                decoded.contentLease?.close()
                            }
                        }
                    }
                }
            } finally {
                synchronized(lock) {
                    jobs.forEach { (request, job) ->
                        if (activeJobs[request.jobKey] === job) {
                            activeJobs.remove(request.jobKey)
                        }
                        requestScheduler.complete(request.jobKey)
                    }
                }
            }
        }
    }

    fun get(pageIndex: Int): ImageBitmap? = getCachedPage(pageIndex)?.bitmap

    fun getCachedPage(pageIndex: Int): PreloadedPageBitmap? = synchronized(lock) {
        val bitmap = cache.get(pageIndex) ?: return@synchronized null
        val sourceSize = checkNotNull(sourceSizes[pageIndex]) {
            "Missing source dimensions for cached page $pageIndex"
        }
        PreloadedPageBitmap(bitmap, sourceSize.width, sourceSize.height)
    }

    fun clear() {
        synchronized(lock) {
            val plan = requestScheduler.moveTo(
                chapterId = SCHEDULER_CHAPTER_ID,
                currentPage = 0,
                pageCount = 0,
            )
            plan.cancelRequests.forEach { jobKey -> activeJobs.remove(jobKey)?.cancel() }
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()
            check(cache.beginGeneration(plan.generation, plan.evictPageIndices))
            cache.clear()
            sourceSizes.clear()
            releaseContentLeasesExcept(emptySet())
        }
    }

    fun cacheSize(): Int = cache.snapshot().keys.size

    fun cacheSnapshot(): PageCacheSnapshot = cache.snapshot()

    private suspend fun decodePage(
        request: ReaderScheduledRequest,
        source: EncodedPageRef?,
        pageId: ReaderPageId?,
        observer: ReaderPageIoObserver?,
        sessionGeneration: Long?,
        contentLeaseHandoff: ContentLeaseHandoff,
    ): Decoded? {
        val observedGeneration = sessionGeneration ?: request.generation
        request.gatePoint(isAdjacentPrefetch = false)?.let { ioGate.await(it) }
        val opened = openContent(source, pageId, observer, observedGeneration, contentLeaseHandoff) ?: return null
        try {
            val size = SkiaImageDecoder.peekSize(opened.bytes)
            if (size == null) {
                opened.lease?.close()
                return null
            }
            val decodeRequest = PageDecodeRequest(
                pageIndex = request.pageIndex,
                generation = request.generation,
                maxWidth = maxDecodedWidth,
                maxHeight = maxDecodedHeight,
                region = PixelBounds(0, 0, size.first, size.second),
            )
            val pixelCount = size.first.toLong() * size.second
            val result = if (pixelCount > largeImagePixelThreshold) {
                regionDecoder.decodeRegion(opened.bytes, decodeRequest)
            } else {
                pageDecoder.decode(opened.bytes, decodeRequest.copy(region = null))
            }
            pageId?.let { observer?.pageDecoded(it, observedGeneration) }
            return Decoded(
                request.pageIndex,
                result,
                size.first,
                size.second,
                opened.lease,
                contentLeaseHandoff,
            )
        } catch (error: Throwable) {
            opened.lease?.close()
            throw error
        }
    }

    private data class OpenedContent(
        val bytes: ByteArray,
        val lease: ReaderPageContentLease<ByteArray>?,
    )

    private suspend fun openContent(
        source: EncodedPageRef?,
        pageId: ReaderPageId?,
        observer: ReaderPageIoObserver?,
        generation: Long,
        contentLeaseHandoff: ContentLeaseHandoff,
    ): OpenedContent? {
        source ?: return null
        return when (val sourceOwner = contentSource) {
            is ContentSource.Legacy -> {
                pageId?.let { observer?.pageOpened(it, generation) }
                sourceOwner.reader(source)?.let { OpenedContent(it, null) }
            }
            is ContentSource.Owned -> {
                val resolvedPageId = requireNotNull(pageId) { "Owned page content requires a stable page id" }
                val lease = sourceOwner.owner.acquire(
                    ReaderPageContentOpenRequest(resolvedPageId, generation, source),
                ) ?: return null
                contentLeaseHandoff.attach(lease)
                currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                    if (cause != null) contentLeaseHandoff.closeIfUnclaimed()
                }
                OpenedContent(lease.content, lease)
            }
        }
    }

    private fun commitDecodedPage(decoded: Decoded) {
        when (val result = decoded.result) {
            is PageDecodeResult.Success -> {
                val commitResult = cache.commit(
                    PageCacheWrite(
                        pageIndex = decoded.index,
                        generation = result.generation,
                        value = result.value,
                        estimatedBytes = result.estimatedBytes,
                    ),
                )
                if (commitResult == PageCacheCommitResult.STORED) {
                    sourceSizes[decoded.index] = SourceSize(decoded.sourceWidth, decoded.sourceHeight)
                    decoded.contentLease?.let { lease -> contentLeases.put(decoded.index, lease)?.close() }
                } else {
                    decoded.contentLease?.close()
                }
                sourceSizes.keys.retainAll(cache.snapshot().keys)
                releaseContentLeasesExcept(cache.snapshot().keys)
            }
            is PageDecodeResult.Failure -> decoded.contentLease?.close()
        }
    }

    private fun releaseContentLeasesExcept(retainedPageIndices: Set<Int>) {
        val evicted = contentLeases.keys - retainedPageIndices
        evicted.forEach { pageIndex -> contentLeases.remove(pageIndex)?.close() }
    }

    companion object {
        const val DEFAULT_CACHE_BYTES: Long = 128L * 1024L * 1024L
        const val DEFAULT_LARGE_IMAGE_PIXELS: Long = 16_000_000L
        const val DEFAULT_CONCURRENT_REQUESTS: Int = 3
        private val SCHEDULER_CHAPTER_ID = ReaderChapterId(0)
    }
}

data class PreloadedPageBitmap(
    val bitmap: ImageBitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
) {
    init {
        require(sourceWidth > 0 && sourceHeight > 0) { "source dimensions must be positive" }
    }
}
