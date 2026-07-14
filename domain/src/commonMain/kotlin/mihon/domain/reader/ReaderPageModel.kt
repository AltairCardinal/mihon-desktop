package mihon.domain.reader

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
    data class Error(val error: AppError) : ReaderChapterState
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
        to?.let { ReaderNavigationCommand.RetryChapter(it.id) }
            ?: ReaderNavigationCommand.ChapterBoundary(direction)
}

/** A decode request that does not expose Bitmap, ImageBitmap, or Skia types. */
data class PageDecodeRequest(
    val pageIndex: Int,
    val maxWidth: Int,
    val maxHeight: Int,
    val region: PixelBounds? = null,
)

sealed interface PageDecodeResult<out T> {
    data class Success<T>(
        val value: T,
        val width: Int,
        val height: Int,
        val estimatedBytes: Long,
        val isSampled: Boolean = false,
    ) : PageDecodeResult<T>

    data class Failure(val error: AppError) : PageDecodeResult<Nothing>
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
    val availableBytes: Long get() = (maxBytes - usedBytes).coerceAtLeast(0)
}

/** Byte-budgeted cache contract; platform implementations own the decoded value type. */
interface PageCache<T> {
    fun get(pageIndex: Int): T?
    fun put(pageIndex: Int, value: T, estimatedBytes: Long): Boolean
    fun remove(pageIndex: Int): T?
    fun clear()
    fun snapshot(): PageCacheSnapshot
}

enum class PreloadPriority { CURRENT, FORWARD, BACKWARD }

data class PreloadRequest(val pageIndex: Int, val priority: PreloadPriority)

data class PreloadPlan(
    val generation: Long,
    val requests: List<PreloadRequest>,
    val keepPageIndices: Set<Int>,
    val cancelPageIndices: Set<Int>,
    val evictPageIndices: Set<Int>,
)

/**
 * Pure preload-window coordinator. A generation change invalidates late results from older moves.
 */
class ReaderPreloadPlanner(
    private val windowSize: Int,
    private val backwardWindowSize: Int = windowSize,
) {
    private var generation = 0L
    private var previousKeep: Set<Int> = emptySet()

    init {
        require(windowSize >= 0) { "windowSize must be non-negative" }
        require(backwardWindowSize >= 0) { "backwardWindowSize must be non-negative" }
    }

    fun moveTo(currentPage: Int, pageCount: Int): PreloadPlan {
        generation++
        if (pageCount <= 0) {
            val stale = previousKeep
            previousKeep = emptySet()
            return PreloadPlan(generation, emptyList(), emptySet(), stale, stale)
        }
        val current = currentPage.coerceIn(0, pageCount - 1)
        val start = (current - backwardWindowSize).coerceAtLeast(0)
        val end = (current + windowSize).coerceAtMost(pageCount - 1)
        val keep = (start..end).toSet()
        val requests = buildList {
            add(PreloadRequest(current, PreloadPriority.CURRENT))
            for (index in (current + 1)..end) add(PreloadRequest(index, PreloadPriority.FORWARD))
            for (index in (current - 1) downTo start) add(PreloadRequest(index, PreloadPriority.BACKWARD))
        }
        val stale = previousKeep - keep
        previousKeep = keep
        return PreloadPlan(generation, requests, keep, stale, stale)
    }
}
