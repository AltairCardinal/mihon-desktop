package mihon.desktop.reader

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
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
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.scheduler.ReaderRequestKey
import mihon.domain.reader.scheduler.ReaderRequestScheduler
import mihon.domain.reader.scheduler.ReaderScheduledRequest
import mihon.domain.reader.scheduler.ReaderSchedulerPolicy
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId

/**
 * Desktop adapter for the shared preload-window contract.
 * Late results are generation-checked, stale jobs are cancelled, and decoded pages obey a byte budget.
 */
class PagePreloader(
    private val encodedPageReader: suspend (ref: EncodedPageRef) -> ByteArray?,
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
) {
    private data class Decoded(
        val index: Int,
        val result: PageDecodeResult<ImageBitmap>,
        val sourceWidth: Int,
        val sourceHeight: Int,
    )

    private data class SourceSize(val width: Int, val height: Int)

    private val lock = Any()
    private val cache = DesktopPageCache(maxCacheBytes)
    private val pageDecoder = SkiaPageDecoder()
    private val regionDecoder = SkiaRegionPageDecoder()
    private val activeJobs = mutableMapOf<ReaderRequestKey, Deferred<Decoded?>>()
    private val sourceSizes = mutableMapOf<Int, SourceSize>()

    val cacheRevision: StateFlow<Long> = cache.revision
    val cacheGeneration: StateFlow<Long> = cacheRevision

    init {
        require(maxDecodedWidth > 0 && maxDecodedHeight > 0) { "decoded bounds must be positive" }
        require(largeImagePixelThreshold > 0) { "largeImagePixelThreshold must be positive" }
    }

    suspend fun preloadEncoded(currentPage: Int, encodedPageRefs: List<EncodedPageRef?>) =
        preloadSources(currentPage, encodedPageRefs)

    private suspend fun preloadSources(currentPage: Int, sources: List<EncodedPageRef?>) = supervisorScope {
        val plan = synchronized(lock) {
            requestScheduler.moveTo(SCHEDULER_CHAPTER_ID, currentPage, sources.size).also {
                it.cancelRequests.forEach { jobKey -> activeJobs.remove(jobKey)?.cancel() }
                check(cache.beginGeneration(it.generation, it.evictPageIndices))
                sourceSizes.keys.retainAll(cache.snapshot().keys)
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
                            val job = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                                decodePage(request, sources[index])
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
                        job.await()
                    } catch (error: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw error
                        null
                    }
                    if (decoded != null) {
                        synchronized(lock) {
                            if (requestScheduler.accepts(request.jobKey) && decoded.index in plan.keepPageIndices) {
                                commitDecodedPage(decoded)
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
        }
    }

    fun cacheSize(): Int = cache.snapshot().keys.size

    fun cacheSnapshot(): PageCacheSnapshot = cache.snapshot()

    private suspend fun decodePage(request: ReaderScheduledRequest, source: EncodedPageRef?): Decoded? {
        val bytes = source?.let { encodedPageReader(it) } ?: return null
        val size = SkiaImageDecoder.peekSize(bytes) ?: return null
        val decodeRequest = PageDecodeRequest(
            pageIndex = request.pageIndex,
            generation = request.generation,
            maxWidth = maxDecodedWidth,
            maxHeight = maxDecodedHeight,
            region = PixelBounds(0, 0, size.first, size.second),
        )
        val pixelCount = size.first.toLong() * size.second
        val result = if (pixelCount > largeImagePixelThreshold) {
            regionDecoder.decodeRegion(bytes, decodeRequest)
        } else {
            pageDecoder.decode(bytes, decodeRequest.copy(region = null))
        }
        return Decoded(request.pageIndex, result, size.first, size.second)
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
                }
                sourceSizes.keys.retainAll(cache.snapshot().keys)
            }
            is PageDecodeResult.Failure -> Unit
        }
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
