package mihon.domain.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mihon.domain.error.AppError

/** Platform-neutral page metadata consumed by both reader front ends. */
data class ReaderPageModel(
    val index: Int,
    val url: String = "",
    val imageUrl: String? = null,
    val size: ReaderPageSize? = null,
)

data class ReaderPageSize(val width: Int, val height: Int)

/** Platform-neutral chapter identity; platform loaders own streams and decoded pixels. */
data class ReaderChapterModel(
    val id: Long,
    val url: String,
    val name: String,
    val chapterNumber: Double,
)

sealed interface ReaderChapterState {
    data object Wait : ReaderChapterState
    data object Loading : ReaderChapterState
    data class Loaded(val pages: List<ReaderPageModel>) : ReaderChapterState
    data class Error(
        val error: AppError,
        val retryTargetChapterId: Long,
    ) : ReaderChapterState {
        fun retryCommand(): ReaderNavigationCommand = ReaderNavigationCommand.RetryChapter(retryTargetChapterId)
    }
}

enum class ReaderTransitionDirection { PREVIOUS, NEXT }

data class ReaderChapterTransitionModel(
    val direction: ReaderTransitionDirection,
    val from: ReaderChapterModel,
    val to: ReaderChapterModel?,
    val missingChapterCount: Int = 0,
    val state: ReaderChapterState = ReaderChapterState.Wait,
) {
    init {
        require(missingChapterCount >= 0) { "missingChapterCount must be non-negative" }
    }

    fun retryCommand(): ReaderNavigationCommand =
        (state as? ReaderChapterState.Error)?.retryCommand()
            ?: to?.let { ReaderNavigationCommand.RetryChapter(it.id) }
            ?: ReaderNavigationCommand.ChapterBoundary(direction)
}

/** A decode request that does not expose Bitmap, ImageBitmap, or Skia types. */
data class PageDecodeRequest(
    val pageIndex: Int,
    val generation: Long,
    val maxWidth: Int,
    val maxHeight: Int,
    val region: PixelBounds? = null,
) {
    init {
        require(generation >= 0) { "generation must be non-negative" }
    }
}

sealed interface PageDecodeResult<out T> {
    val generation: Long

    data class Success<T>(
        override val generation: Long,
        val value: T,
        val width: Int,
        val height: Int,
        val estimatedBytes: Long,
        val isSampled: Boolean = false,
    ) : PageDecodeResult<T> {
        init {
            require(generation >= 0) { "generation must be non-negative" }
        }
    }

    data class Failure(
        override val generation: Long,
        val error: AppError,
    ) : PageDecodeResult<Nothing> {
        init {
            require(generation >= 0) { "generation must be non-negative" }
        }
    }
}

/** Platform adapter for bounded full-page decoding. */
interface PageDecoder<S, T> {
    suspend fun decode(encoded: S, request: PageDecodeRequest): PageDecodeResult<T>
}

/** Platform adapter for tile/region decoding of large pages. */
interface RegionDecoder<S, T> {
    suspend fun decodeRegion(encoded: S, request: PageDecodeRequest): PageDecodeResult<T>
}

/** Shared cache intent; platform adapters map it to Coil, Skia, or another cache implementation. */
data class PageDecodeCachePolicy(
    val decodedMemoryCache: Boolean,
    val decodedDiskCache: Boolean,
) {
    companion object {
        /** Region/tile readers own their bounded cache and must not retain another full decoded page. */
        val TILED_READER = PageDecodeCachePolicy(
            decodedMemoryCache = false,
            decodedDiskCache = false,
        )
    }
}

data class PageCacheSnapshot(
    val keys: Set<Int>,
    val usedBytes: Long,
    val maxBytes: Long,
) {
    init {
        require(maxBytes >= 0) { "maxBytes must be non-negative" }
        require(usedBytes in 0..maxBytes) { "usedBytes must be within the cache budget" }
    }

    val availableBytes: Long get() = maxBytes - usedBytes
}

data class PageCacheWrite<T>(
    val pageIndex: Int,
    val generation: Long,
    val value: T,
    val estimatedBytes: Long,
) {
    init {
        require(generation >= 0) { "generation must be non-negative" }
    }
}

enum class PageCacheCommitResult { STORED, REJECTED_STALE_GENERATION, REJECTED_OVERSIZED }

/** Generation-aware byte-budgeted cache contract; platform implementations own the decoded value type. */
interface PageCache<T> {
    val generation: Long?
    val revision: StateFlow<Long>

    fun get(pageIndex: Int): T?
    fun beginGeneration(generation: Long, evictPageIndices: Set<Int>): Boolean
    fun commit(write: PageCacheWrite<T>): PageCacheCommitResult
    fun remove(pageIndex: Int): T?
    fun clear()
    fun snapshot(): PageCacheSnapshot
}

/** Executable common reference policy used to keep platform cache adapters semantically aligned. */
class ByteBudgetPageCache<T>(
    private val maxBytes: Long,
) : PageCache<T> {
    private data class Entry<T>(val value: T, val estimatedBytes: Long)

    private val entries = LinkedHashMap<Int, Entry<T>>()
    private var usedBytes = 0L
    private val mutableRevision = MutableStateFlow(0L)

    override var generation: Long? = null
        private set

    override val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    init {
        require(maxBytes >= 0) { "maxBytes must be non-negative" }
    }

    override fun get(pageIndex: Int): T? {
        val entry = entries.remove(pageIndex) ?: return null
        entries[pageIndex] = entry
        return entry.value
    }

    override fun beginGeneration(generation: Long, evictPageIndices: Set<Int>): Boolean {
        require(generation >= 0) { "generation must be non-negative" }
        if (this.generation?.let { generation <= it } == true) return false
        this.generation = generation
        var changed = false
        evictPageIndices.forEach { pageIndex ->
            entries.remove(pageIndex)?.let { entry ->
                usedBytes -= entry.estimatedBytes
                changed = true
            }
        }
        if (changed) publishRevision()
        return true
    }

    override fun commit(write: PageCacheWrite<T>): PageCacheCommitResult {
        require(write.estimatedBytes >= 0) { "estimatedBytes must be non-negative" }
        if (generation == null || write.generation != generation) {
            return PageCacheCommitResult.REJECTED_STALE_GENERATION
        }
        if (write.estimatedBytes > maxBytes) return PageCacheCommitResult.REJECTED_OVERSIZED

        entries.remove(write.pageIndex)?.let { usedBytes -= it.estimatedBytes }
        while (usedBytes + write.estimatedBytes > maxBytes && entries.isNotEmpty()) {
            val leastRecentlyUsedKey = entries.keys.first()
            val evicted = entries.remove(leastRecentlyUsedKey) ?: continue
            usedBytes -= evicted.estimatedBytes
        }
        entries[write.pageIndex] = Entry(write.value, write.estimatedBytes)
        usedBytes += write.estimatedBytes
        publishRevision()
        return PageCacheCommitResult.STORED
    }

    override fun remove(pageIndex: Int): T? {
        val removed = entries.remove(pageIndex) ?: return null
        usedBytes -= removed.estimatedBytes
        publishRevision()
        return removed.value
    }

    override fun clear() {
        if (entries.isEmpty()) return
        entries.clear()
        usedBytes = 0L
        publishRevision()
    }

    override fun snapshot(): PageCacheSnapshot = PageCacheSnapshot(
        keys = entries.keys.toSet(),
        usedBytes = usedBytes,
        maxBytes = maxBytes,
    )

    private fun publishRevision() {
        mutableRevision.value++
    }
}

enum class PreloadPriority { CURRENT, FORWARD, BACKWARD }

data class PreloadJobKey(val pageIndex: Int, val generation: Long)

data class PreloadRequest(
    val pageIndex: Int,
    val priority: PreloadPriority,
    val generation: Long,
) {
    init {
        require(generation >= 0) { "generation must be non-negative" }
    }

    val jobKey: PreloadJobKey get() = PreloadJobKey(pageIndex, generation)
}

data class PreloadPlan(
    val generation: Long,
    val requests: List<PreloadRequest>,
    val keepPageIndices: Set<Int>,
    val cancelRequests: Set<PreloadJobKey>,
    val evictPageIndices: Set<Int>,
) {
    val cancelPageIndices: Set<Int> get() = cancelRequests.mapTo(mutableSetOf(), PreloadJobKey::pageIndex)
}

/**
 * Pure preload-window coordinator. A generation change invalidates late results from older moves.
 */
class ReaderPreloadPlanner(
    private val windowSize: Int,
    private val backwardWindowSize: Int = windowSize,
) {
    private var generation = 0L
    private var previousKeep: Set<Int> = emptySet()
    private var previousRequests: Set<PreloadJobKey> = emptySet()

    init {
        require(windowSize >= 0) { "windowSize must be non-negative" }
        require(backwardWindowSize >= 0) { "backwardWindowSize must be non-negative" }
    }

    fun moveTo(currentPage: Int, pageCount: Int): PreloadPlan {
        generation++
        if (pageCount <= 0) {
            val cancel = previousRequests
            val evict = previousKeep
            previousKeep = emptySet()
            previousRequests = emptySet()
            return PreloadPlan(generation, emptyList(), emptySet(), cancel, evict)
        }
        val current = currentPage.coerceIn(0, pageCount - 1)
        val start = (current - backwardWindowSize).coerceAtLeast(0)
        val end = (current + windowSize).coerceAtMost(pageCount - 1)
        val keep = (start..end).toSet()
        val requests = buildList {
            add(PreloadRequest(current, PreloadPriority.CURRENT, generation))
            for (index in (current + 1)..end) add(PreloadRequest(index, PreloadPriority.FORWARD, generation))
            for (index in (current - 1) downTo start) add(PreloadRequest(index, PreloadPriority.BACKWARD, generation))
        }
        val cancel = previousRequests
        val evict = previousKeep
        previousKeep = keep
        previousRequests = requests.mapTo(mutableSetOf(), PreloadRequest::jobKey)
        return PreloadPlan(generation, requests, keep, cancel, evict)
    }

    fun accepts(generation: Long): Boolean = generation == this.generation
}
