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
import mihon.domain.reader.PageCacheSnapshot
import mihon.domain.reader.PageCacheWrite
import mihon.domain.reader.PageDecodeRequest
import mihon.domain.reader.PageDecodeResult
import mihon.domain.reader.PixelBounds
import mihon.domain.reader.PreloadJobKey
import mihon.domain.reader.ReaderPreloadPlanner

/**
 * Desktop adapter for the shared preload-window contract.
 * Late results are generation-checked, stale jobs are cancelled, and decoded pages obey a byte budget.
 */
class PagePreloader(
    private val fetcher: suspend (url: String) -> ByteArray?,
    val windowSize: Int = 3,
    private val maxDecodedWidth: Int = 2048,
    private val maxDecodedHeight: Int = 2048,
    maxCacheBytes: Long = DEFAULT_CACHE_BYTES,
    private val largeImagePixelThreshold: Long = DEFAULT_LARGE_IMAGE_PIXELS,
) {
    private data class Decoded(val index: Int, val result: PageDecodeResult<ImageBitmap>)

    private val lock = Any()
    private val cache = DesktopPageCache(maxCacheBytes)
    private val planner = ReaderPreloadPlanner(windowSize)
    private val pageDecoder = SkiaPageDecoder()
    private val regionDecoder = SkiaRegionPageDecoder()
    private val activeJobs = mutableMapOf<PreloadJobKey, Deferred<Decoded?>>()

    val cacheRevision: StateFlow<Long> = cache.revision
    val cacheGeneration: StateFlow<Long> = cacheRevision

    init {
        require(maxDecodedWidth > 0 && maxDecodedHeight > 0) { "decoded bounds must be positive" }
        require(largeImagePixelThreshold > 0) { "largeImagePixelThreshold must be positive" }
    }

    suspend fun preload(currentPage: Int, pageUrls: List<String>) = supervisorScope {
        val plan = synchronized(lock) {
            planner.moveTo(currentPage, pageUrls.size).also {
                it.cancelRequests.forEach { jobKey -> activeJobs.remove(jobKey)?.cancel() }
                check(cache.beginGeneration(it.generation, it.evictPageIndices))
            }
        }
        val jobs = synchronized(lock) {
            if (cache.generation != plan.generation) {
                emptyList()
            } else {
                plan.requests
                    .filter { it.pageIndex in pageUrls.indices && cache.get(it.pageIndex) == null }
                    .map { preloadRequest ->
                        val index = preloadRequest.pageIndex
                        async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                            val bytes = fetcher(pageUrls[index]) ?: return@async null
                            val size = SkiaImageDecoder.peekSize(bytes) ?: return@async null
                            val request = PageDecodeRequest(
                                pageIndex = index,
                                generation = preloadRequest.generation,
                                maxWidth = maxDecodedWidth,
                                maxHeight = maxDecodedHeight,
                                region = PixelBounds(0, 0, size.first, size.second),
                            )
                            val pixelCount = size.first.toLong() * size.second
                            val result = if (pixelCount > largeImagePixelThreshold) {
                                regionDecoder.decodeRegion(bytes, request)
                            } else {
                                pageDecoder.decode(bytes, request.copy(region = null))
                            }
                            Decoded(index, result)
                        }.also { job ->
                            activeJobs[preloadRequest.jobKey] = job
                        }
                    }
                }
        }
        jobs.forEach { it.start() }

        jobs.forEach { job ->
            try {
                val decoded = try {
                    job.await()
                } catch (error: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw error
                    null
                }
                if (decoded == null) return@forEach
                synchronized(lock) {
                    if (decoded.index in plan.keepPageIndices) {
                        when (val result = decoded.result) {
                            is PageDecodeResult.Success -> {
                                cache.commit(
                                    PageCacheWrite(
                                        pageIndex = decoded.index,
                                        generation = result.generation,
                                        value = result.value,
                                        estimatedBytes = result.estimatedBytes,
                                    ),
                                )
                            }
                            is PageDecodeResult.Failure -> Unit
                        }
                    }
                }
            } finally {
                synchronized(lock) {
                    activeJobs.entries.removeAll { (_, activeJob) -> activeJob === job }
                }
            }
        }
    }

    fun get(pageIndex: Int): ImageBitmap? = cache.get(pageIndex)

    fun clear() {
        synchronized(lock) {
            val plan = planner.moveTo(currentPage = 0, pageCount = 0)
            plan.cancelRequests.forEach { jobKey -> activeJobs.remove(jobKey)?.cancel() }
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()
            check(cache.beginGeneration(plan.generation, plan.evictPageIndices))
            cache.clear()
        }
    }

    fun cacheSize(): Int = cache.snapshot().keys.size

    fun cacheSnapshot(): PageCacheSnapshot = cache.snapshot()

    companion object {
        const val DEFAULT_CACHE_BYTES: Long = 128L * 1024L * 1024L
        const val DEFAULT_LARGE_IMAGE_PIXELS: Long = 16_000_000L
    }
}
