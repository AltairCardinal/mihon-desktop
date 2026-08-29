package mihon.desktop.reader

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow
import mihon.domain.reader.PageCacheSnapshot
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId

/**
 * Compatibility adapter retained while the three Compose presentations move to stable image leases.
 *
 * It does not own content I/O, scheduling, decoding, or a decoded cache. All work is delegated to the
 * runtime's unique [DesktopReaderPageImagePipeline]. The adapter only pins leases exposed through the
 * legacy bitmap API so an image cannot be disposed while an existing presentation is still drawing it.
 */
class PagePreloader internal constructor(
    private val pageImagePipeline: DesktopReaderPageImagePipeline,
    val windowSize: Int = 3,
    private val maxDecodedWidth: Int = 2048,
    private val maxDecodedHeight: Int = 2048,
) : AutoCloseable {
    private val lock = Any()
    private val keysByPageIndex = mutableMapOf<Int, ReaderPageDecodeKey>()
    private val retainedLeases = mutableMapOf<Int, DesktopReaderImageAssetLease>()
    private var legacyGeneration = 0L
    private var closed = false

    val cacheRevision: StateFlow<Long> = pageImagePipeline.cacheRevision
    val cacheGeneration: StateFlow<Long> = cacheRevision

    init {
        require(windowSize >= 0) { "windowSize must be non-negative" }
        require(maxDecodedWidth > 0 && maxDecodedHeight > 0) { "decoded bounds must be positive" }
    }

    suspend fun preloadEncoded(
        currentPage: Int,
        encodedPageRefs: List<EncodedPageRef?>,
        pageIds: List<ReaderPageId>? = null,
        observer: ReaderPageIoObserver? = null,
        sessionGeneration: Long? = null,
    ) {
        require(pageIds == null || pageIds.size == encodedPageRefs.size) { "pageIds must match sources" }
        checkOpen()
        val generation = sessionGeneration ?: synchronized(lock) { ++legacyGeneration }
        if (!pageImagePipeline.beginGeneration(generation)) return
        val encodedPageRef = encodedPageRefs.getOrNull(currentPage)
        if (encodedPageRef == null) {
            releasePinnedLeases()
            return
        }
        val pageId = pageIds?.get(currentPage) ?: ReaderPageId(LEGACY_CHAPTER_ID, currentPage)
        val key = ReaderPageDecodeKey(
            contentKey = ReaderPageContentOpenRequest(pageId, generation, encodedPageRef),
            purpose = PageDecodePurpose.FULL_PAGE,
            maxWidth = maxDecodedWidth,
            maxHeight = maxDecodedHeight,
        )
        val lease = pageImagePipeline.acquire(key)
        val leasesToClose = synchronized(lock) {
            if (closed) {
                listOfNotNull(lease)
            } else {
                val previous = retainedLeases.values.toList()
                retainedLeases.clear()
                keysByPageIndex[currentPage] = key
                if (lease != null) retainedLeases[currentPage] = lease
                previous
            }
        }
        leasesToClose.forEach(DesktopReaderImageAssetLease::close)
        reconcileWithPipeline()
        @Suppress("UNUSED_VARIABLE")
        val presentationObserver = observer
    }

    fun get(pageIndex: Int): ImageBitmap? = getCachedPage(pageIndex)?.bitmap

    fun getCachedPage(pageIndex: Int): PreloadedPageBitmap? {
        checkOpen()
        reconcileWithPipeline()
        val existing = synchronized(lock) { retainedLeases[pageIndex] }
        if (existing != null) return existing.toPreloadedPageBitmap()
        val key = synchronized(lock) { keysByPageIndex[pageIndex] } ?: return null
        val acquired = pageImagePipeline.acquireCached(key) ?: return null
        val acceptedLease = synchronized(lock) {
            if (!closed && keysByPageIndex[pageIndex] == key) {
                retainedLeases.put(pageIndex, acquired)?.close()
                acquired
            } else {
                null
            }
        }
        if (acceptedLease == null) {
            acquired.close()
            return null
        }
        return acceptedLease.toPreloadedPageBitmap()
    }

    fun clear() {
        checkOpen()
        releasePinnedLeases()
        pageImagePipeline.clear()
    }

    fun cacheSize(): Int = cacheSnapshot().keys.size

    fun cacheSnapshot(): PageCacheSnapshot {
        checkOpen()
        reconcileWithPipeline()
        val snapshot = pageImagePipeline.snapshot().cache
        return PageCacheSnapshot(
            keys = snapshot.keys.mapTo(linkedSetOf(), ReaderPageDecodeKey::pageIndex),
            usedBytes = snapshot.usedBytes,
            maxBytes = snapshot.maxBytes,
        )
    }

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                true
            }
        }
        if (!shouldClose) return
        releasePinnedLeases(allowClosed = true)
    }

    private fun reconcileWithPipeline() {
        val cacheKeys = pageImagePipeline.snapshot().cache.keys.toSet()
        synchronized(lock) {
            keysByPageIndex.entries.removeAll { (pageIndex, key) ->
                key !in cacheKeys && pageIndex !in retainedLeases
            }
        }
    }

    private fun releasePinnedLeases(allowClosed: Boolean = false) {
        if (!allowClosed) checkOpen()
        val leases = synchronized(lock) {
            keysByPageIndex.clear()
            retainedLeases.values.toList().also { retainedLeases.clear() }
        }
        leases.forEach(DesktopReaderImageAssetLease::close)
    }

    private fun checkOpen() = synchronized(lock) {
        check(!closed) { "Page preloader adapter is closed" }
    }

    private fun DesktopReaderImageAssetLease.toPreloadedPageBitmap(): PreloadedPageBitmap =
        PreloadedPageBitmap(
            bitmap = asset.bitmap,
            sourceWidth = asset.sourceWidth,
            sourceHeight = asset.sourceHeight,
        )

    companion object {
        const val DEFAULT_CACHE_BYTES: Long = DesktopReaderPageImagePipeline.DEFAULT_CACHE_BYTES
        const val DEFAULT_LARGE_IMAGE_PIXELS: Long = 16_000_000L
        const val DEFAULT_CONCURRENT_REQUESTS: Int = 3
        private val LEGACY_CHAPTER_ID = ReaderChapterId(0L)
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
