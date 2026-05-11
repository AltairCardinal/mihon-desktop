package mihon.desktop.reader

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Decodes adjacent manga pages in the background so they are immediately available
 * when the user navigates.
 *
 * The cache holds at most `windowSize * 2 + 1` pages centred on [currentPage].
 * Pages outside that window are evicted on each [preload] call.
 *
 * Integration pattern in viewers:
 * ```kotlin
 * // 1. Check cache first (instant, no-suspend)
 * val ready = preloader?.get(pageIndex)
 * if (ready != null) { showBitmap(ready) } else { showCoilFallback(url) }
 *
 * // 2. Advance preloader on each page change (in a LaunchedEffect)
 * LaunchedEffect(currentPage) { preloader?.preload(currentPage, pageUrls) }
 * ```
 *
 * @param fetcher     Suspend function that downloads (or reads from disk) raw image bytes
 *                    for the given URL.  Returns `null` on failure.
 * @param windowSize  Number of pages to preload *ahead of* the current page.
 *                    One page behind is also kept.  Total cache = windowSize * 2 + 1.
 */
class PagePreloader(
    private val fetcher: suspend (url: String) -> ByteArray?,
    val windowSize: Int = 3,
) {
    private val cache = mutableMapOf<Int, ImageBitmap>()

    /**
     * Evicts pages outside the keep window, then preloads any missing pages in
     * `[currentPage - 1 .. currentPage + windowSize]`.
     *
     * Fetch + decode operations run in parallel on [Dispatchers.IO].
     */
    suspend fun preload(currentPage: Int, pageUrls: List<String>) {
        val keepRange = maxOf(0, currentPage - windowSize)..minOf(pageUrls.lastIndex, currentPage + windowSize)

        // Evict pages that have scrolled out of the window
        cache.keys.toList().forEach { if (it !in keepRange) cache.remove(it) }

        // Fetch and decode missing pages in parallel, then update cache from calling context
        val results = coroutineScope {
            keepRange
                .filter { it !in cache }
                .map { index ->
                    async(Dispatchers.IO) {
                        val bytes = fetcher(pageUrls[index]) ?: return@async null
                        index to SkiaImageDecoder.decode(bytes)
                    }
                }
                .awaitAll()
        }
        results.filterNotNull().forEach { (index, bitmap) -> cache[index] = bitmap }
    }

    /** Returns the cached [ImageBitmap] for [pageIndex], or `null` if not yet preloaded. */
    fun get(pageIndex: Int): ImageBitmap? = cache[pageIndex]

    /** Clears all cached bitmaps. */
    fun clear() = cache.clear()

    /** Number of currently cached pages (exposed for testing). */
    fun cacheSize(): Int = cache.size
}
