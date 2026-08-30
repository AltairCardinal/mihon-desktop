package mihon.desktop.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.PageDecodeRequest
import mihon.domain.reader.PageDecodeResult
import mihon.domain.reader.PageDecoder
import mihon.domain.reader.ReaderPageDecodeKey
import mihon.domain.reader.content.ReaderPageContentOpenRequest
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoPurpose
import mihon.domain.reader.observability.ReaderIoReporter
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.Canvas as SkiaCanvas
import org.jetbrains.skia.Codec as SkiaCodec
import org.jetbrains.skia.Data as SkiaData
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect as SkiaRect

/** Platform decoder boundary owned exclusively by [DesktopReaderPageImagePipeline]. */
internal fun interface DesktopReaderPageImageDecoder {
    suspend fun decode(
        encoded: ByteArray,
        key: ReaderPageDecodeKey,
    ): DesktopReaderImageAssetLease?
}

/** Production Skia adapter for ordinary full pages and purpose-keyed animation frames. */
internal class SkiaDesktopReaderPageImageDecoder(
    private val decoder: PageDecoder<ByteArray, ImageBitmap> = SkiaPageDecoder(),
) : DesktopReaderPageImageDecoder {
    override suspend fun decode(
        encoded: ByteArray,
        key: ReaderPageDecodeKey,
    ): DesktopReaderImageAssetLease? {
        require(key.purpose != PageDecodePurpose.REGION_TILE) {
            "The standard Desktop decoder does not accept region requests"
        }
        if (key.purpose == PageDecodePurpose.ANIMATION_FRAME) return decodeAnimationFrame(encoded, key)
        val sourceSize = SkiaImageDecoder.peekSize(encoded) ?: return null
        val animationMetadata = inspectAnimationMetadata(encoded)
        return when (
            val result = decoder.decode(
                encoded,
                PageDecodeRequest(
                    pageIndex = key.pageIndex,
                    generation = key.generation,
                    maxWidth = key.maxWidth,
                    maxHeight = key.maxHeight,
                ),
            )
        ) {
            is PageDecodeResult.Failure -> null
            is PageDecodeResult.Success -> DesktopReaderImageAsset(
                bitmap = result.value,
                sourceWidth = sourceSize.first,
                sourceHeight = sourceSize.second,
                estimatedBytes = result.estimatedBytes,
                sampled = result.isSampled,
                animationMetadata = animationMetadata,
                disposer = result.value.asSkiaBitmap()::close,
            )
        }
    }

    private fun decodeAnimationFrame(
        encoded: ByteArray,
        key: ReaderPageDecodeKey,
    ): DesktopReaderImageAssetLease? = try {
        SkiaData.makeFromBytes(encoded).use { data ->
            SkiaCodec.makeFromData(data).use { codec ->
                val frameIndex = requireNotNull(key.frameIndex)
                require(frameIndex < codec.frameCount) { "Animation frame exceeds source frame count" }
                val decoded = SkiaBitmap()
                check(decoded.allocPixels(codec.imageInfo)) { "Unable to allocate animation frame bitmap" }
                try {
                    codec.readPixels(decoded, frameIndex)
                    val sampleSize = boundedSampleSize(codec.width, codec.height, key.maxWidth, key.maxHeight)
                    val output = if (sampleSize == 1) {
                        decoded
                    } else {
                        val targetWidth = ceilDiv(codec.width, sampleSize)
                        val targetHeight = ceilDiv(codec.height, sampleSize)
                        val target = SkiaBitmap()
                        try {
                            check(target.allocN32Pixels(targetWidth, targetHeight)) {
                                "Unable to allocate sampled animation frame bitmap"
                            }
                            SkiaCanvas(target).use { canvas ->
                                canvas.clear(0x00000000)
                                SkiaImage.makeFromBitmap(decoded).use { image ->
                                    canvas.drawImageRect(
                                        image,
                                        SkiaRect.makeWH(codec.width.toFloat(), codec.height.toFloat()),
                                        SkiaRect.makeWH(targetWidth.toFloat(), targetHeight.toFloat()),
                                    )
                                }
                            }
                            decoded.close()
                            target
                        } catch (error: Throwable) {
                            target.close()
                            throw error
                        }
                    }
                    try {
                        DesktopReaderImageAsset(
                            bitmap = output.asComposeImageBitmap(),
                            sourceWidth = codec.width,
                            sourceHeight = codec.height,
                            estimatedBytes = output.width.toLong() * output.height * BYTES_PER_PIXEL,
                            sampled = sampleSize > 1,
                            animationMetadata = animationMetadata(codec),
                            disposer = output::close,
                        )
                    } catch (error: Throwable) {
                        output.close()
                        throw error
                    }
                } catch (error: Throwable) {
                    if (!decoded.isClosed) decoded.close()
                    throw error
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun inspectAnimationMetadata(encoded: ByteArray): DesktopReaderAnimationMetadata? = try {
        SkiaData.makeFromBytes(encoded).use { data ->
            SkiaCodec.makeFromData(data).use(::animationMetadata)
        }
    } catch (_: Exception) {
        null
    }

    private fun animationMetadata(codec: SkiaCodec): DesktopReaderAnimationMetadata? {
        if (codec.frameCount < 2) return null
        val bounds = mihon.domain.reader.PixelBounds(0, 0, codec.width, codec.height)
        return DesktopReaderAnimationMetadata(
            frames = codec.framesInfo.map { frame ->
                DesktopReaderAnimationFrameMetadata(
                    durationMillis = frame.duration.toLong().coerceAtLeast(0L),
                    bounds = bounds,
                )
            },
            repeatCount = codec.repetitionCount.takeIf { it >= 0 },
        )
    }

    private fun boundedSampleSize(
        width: Int,
        height: Int,
        maxWidth: Int,
        maxHeight: Int,
    ): Int = maxOf(
        ceilDiv(width, maxWidth),
        ceilDiv(height, maxHeight),
        1,
    )

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()

    private companion object {
        const val BYTES_PER_PIXEL = 4L
    }
}

internal data class DesktopReaderPageImagePipelineSnapshot(
    val minimumGeneration: Long,
    val cache: DesktopReaderImageCacheSnapshot,
    val inFlightCount: Int,
    val closed: Boolean,
)

/**
 * Unique Desktop owner for encoded-content acquisition, full-page decode single-flight, and decoded caching.
 *
 * Presentation and transform consumers receive independently closeable leases. Static encoded content is
 * released immediately after decode; animated content is pinned by one bounded, reference-counted session
 * shared by mounted presentations and released with the final animation lease.
 */
class DesktopReaderPageImagePipeline internal constructor(
    private val scope: CoroutineScope,
    private val pageContentOwner: DesktopReaderPageContentOwner,
    private val ioReporter: ReaderIoReporter,
    private val decoder: DesktopReaderPageImageDecoder = SkiaDesktopReaderPageImageDecoder(),
    maxEntries: Int = DEFAULT_CACHE_ENTRIES,
    maxBytes: Long = DEFAULT_CACHE_BYTES,
    maxConcurrentDecodes: Int = DEFAULT_CONCURRENT_DECODES,
) : AutoCloseable {
    private class Entry {
        lateinit var deferred: Deferred<DesktopReaderImageAssetLease?>
        var pendingAcquires: Int = 0
        var decodedLease: DesktopReaderImageAssetLease? = null
    }

    private class AnimationSessionEntry(
        val content: DesktopReaderSharedAnimationContent,
        var references: Int = 0,
    )

    private val lock = Any()
    private val cache = DesktopReaderImageCache(maxEntries, maxBytes)
    private val decodePermits = Semaphore(maxConcurrentDecodes)
    private val entries = mutableMapOf<ReaderPageDecodeKey, Entry>()
    private val animationSessions = mutableMapOf<ReaderPageContentOpenRequest, AnimationSessionEntry>()
    private val mutableCacheRevision = MutableStateFlow(0L)
    private var minimumGeneration = 0L
    private var closed = false

    init {
        require(maxConcurrentDecodes > 0) { "maxConcurrentDecodes must be positive" }
    }

    internal val cacheRevision: StateFlow<Long> = mutableCacheRevision.asStateFlow()

    internal suspend fun acquire(key: ReaderPageDecodeKey): DesktopReaderImageAssetLease? =
        acquireWithLoader(key) { load(key) }

    internal fun openAnimationSession(
        contentKey: ReaderPageContentOpenRequest,
    ): DesktopReaderAnimationContentSession = synchronized(lock) {
        check(!closed) { "Desktop reader page image pipeline is closed" }
        check(contentKey.generation >= minimumGeneration) { "Animation session generation is stale" }
        val entry = animationSessions.getOrPut(contentKey) {
            AnimationSessionEntry(
                DesktopReaderSharedAnimationContent(
                    contentKey = contentKey,
                    scope = scope,
                    pageContentOwner = pageContentOwner,
                ),
            )
        }
        entry.references++
        DesktopReaderAnimationContentSession(
            contentKey = contentKey,
            sharedContent = entry.content,
            pipeline = this,
        )
    }

    internal suspend fun acquireAnimationFrame(
        session: DesktopReaderAnimationContentSession,
        key: ReaderPageDecodeKey,
    ): DesktopReaderImageAssetLease? {
        require(key.purpose == PageDecodePurpose.ANIMATION_FRAME) {
            "Animation sessions only accept animation-frame requests"
        }
        require(key.contentKey == session.contentKey) {
            "Animation frame and source session identities must match"
        }
        return acquireWithLoader(key) {
            session.withContent { encoded -> decodeContent(key, encoded) }
        }
    }

    private suspend fun acquireWithLoader(
        key: ReaderPageDecodeKey,
        loader: suspend () -> DesktopReaderImageAssetLease?,
    ): DesktopReaderImageAssetLease? {
        val entry = synchronized(lock) {
            check(!closed) { "Desktop reader page image pipeline is closed" }
            if (key.generation < minimumGeneration) return null
            cache.acquire(key)?.let { return it }
            entries.getOrPut(key) { createEntry(key, loader) }.also {
                it.pendingAcquires++
                it.deferred.start()
            }
        }
        val decoded = try {
            entry.deferred.await()
        } catch (error: Throwable) {
            releasePending(key, entry)
            throw error
        }
        if (decoded == null) {
            releasePending(key, entry)
            return null
        }

        var entryLeaseToClose: DesktopReaderImageAssetLease? = null
        val callerLease = synchronized(lock) {
            entry.pendingAcquires--
            val accepted = !closed &&
                key.generation >= minimumGeneration &&
                entries[key] === entry
            val lease = if (accepted) decoded.retain() else null
            entryLeaseToClose = removeIfUnusedLocked(key, entry)
            lease
        }
        entryLeaseToClose?.close()
        if (callerLease == null) {
            throw CancellationException("Desktop reader page image request became stale")
        }
        return callerLease
    }

    internal fun acquireCached(key: ReaderPageDecodeKey): DesktopReaderImageAssetLease? = synchronized(lock) {
        check(!closed) { "Desktop reader page image pipeline is closed" }
        if (key.generation < minimumGeneration) return null
        cache.acquire(key)
    }

    /** Advances the accepted generation and rejects all older cache and in-flight results. */
    internal fun beginGeneration(generation: Long): Boolean {
        require(generation >= 0L) { "generation must be non-negative" }
        val stale = synchronized(lock) {
            check(!closed) { "Desktop reader page image pipeline is closed" }
            if (generation < minimumGeneration) return false
            if (generation == minimumGeneration) return true
            minimumGeneration = generation
            val stale = entries.filterKeys { it.generation < generation }
            stale.keys.forEach(entries::remove)
            cache.clear()
            mutableCacheRevision.value++
            val staleSessions = animationSessions.filterKeys { it.generation < generation }
            staleSessions.keys.forEach(animationSessions::remove)
            stale.values.toList() to staleSessions.values.map(AnimationSessionEntry::content)
        }
        stale.first.forEach { entry -> entry.deferred.cancel() }
        releaseDetachedCompleted(stale.first)
        stale.second.forEach(DesktopReaderSharedAnimationContent::close)
        return true
    }

    internal fun clear() {
        val detachedEntries = synchronized(lock) {
            check(!closed) { "Desktop reader page image pipeline is closed" }
            val detached = entries.values.toList()
            entries.clear()
            cache.clear()
            mutableCacheRevision.value++
            detached
        }
        detachedEntries.forEach { entry -> entry.deferred.cancel() }
        releaseDetachedCompleted(detachedEntries)
    }

    internal fun snapshot(): DesktopReaderPageImagePipelineSnapshot = synchronized(lock) {
        DesktopReaderPageImagePipelineSnapshot(
            minimumGeneration = minimumGeneration,
            cache = cache.snapshot(),
            inFlightCount = entries.size,
            closed = closed,
        )
    }

    override fun close() {
        val detached = synchronized(lock) {
            if (closed) return
            closed = true
            val detachedEntries = entries.values.toList()
            val detachedSessions = animationSessions.values.map(AnimationSessionEntry::content)
            entries.clear()
            animationSessions.clear()
            cache.close()
            mutableCacheRevision.value++
            detachedEntries to detachedSessions
        }
        detached.first.forEach { entry -> entry.deferred.cancel() }
        releaseDetachedCompleted(detached.first)
        detached.second.forEach(DesktopReaderSharedAnimationContent::close)
    }

    private fun createEntry(
        key: ReaderPageDecodeKey,
        loader: suspend () -> DesktopReaderImageAssetLease?,
    ): Entry {
        val entry = Entry()
        entry.deferred = scope.async(start = CoroutineStart.LAZY) {
            val decoded = loader()
            synchronized(lock) {
                entry.decodedLease = decoded
                if (
                    decoded != null &&
                    key.purpose == PageDecodePurpose.FULL_PAGE &&
                    decoded.asset.animationMetadata == null &&
                    !closed &&
                    key.generation >= minimumGeneration &&
                    entries[key] === entry &&
                    cache.commit(key, decoded)
                ) {
                    mutableCacheRevision.value++
                }
            }
            decoded
        }
        entry.deferred.invokeOnCompletion {
            var leaseToClose: DesktopReaderImageAssetLease? = null
            synchronized(lock) {
                leaseToClose = removeIfUnusedLocked(key, entry)
            }
            leaseToClose?.close()
        }
        return entry
    }

    private suspend fun load(key: ReaderPageDecodeKey): DesktopReaderImageAssetLease? {
        val contentLease = pageContentOwner.acquire(key.contentKey) ?: return null
        var contentOwnershipTransferred = false
        return try {
            decodeContent(key, contentLease.content)?.also { decoded ->
                if (decoded.asset.animationMetadata != null) {
                    decoded.asset.addFinalDisposer(contentLease::close)
                    contentOwnershipTransferred = true
                }
            }
        } finally {
            if (!contentOwnershipTransferred) contentLease.close()
        }
    }

    private suspend fun decodeContent(
        key: ReaderPageDecodeKey,
        encoded: ByteArray,
    ): DesktopReaderImageAssetLease? = decodePermits.withPermit {
        ioReporter.report(
            type = ReaderIoEventType.DECODE,
            chapterId = key.contentKey.pageId.chapterId,
            pageId = key.contentKey.pageId,
            generation = key.generation,
            purpose = ReaderIoPurpose.VISIBLE_DECODE,
        )
        decoder.decode(encoded, key)
    }

    private fun releasePending(key: ReaderPageDecodeKey, entry: Entry) {
        var leaseToClose: DesktopReaderImageAssetLease? = null
        synchronized(lock) {
            if (entry.pendingAcquires > 0) entry.pendingAcquires--
            leaseToClose = removeIfUnusedLocked(key, entry)
        }
        leaseToClose?.close()
    }

    private fun removeIfUnusedLocked(
        key: ReaderPageDecodeKey,
        entry: Entry,
    ): DesktopReaderImageAssetLease? {
        if (!entry.deferred.isCompleted || entry.pendingAcquires != 0) return null
        if (entries[key] === entry) entries.remove(key)
        return entry.decodedLease.also { entry.decodedLease = null }
    }

    private fun releaseDetachedCompleted(detachedEntries: List<Entry>) {
        detachedEntries.forEach { entry ->
            val lease = synchronized(lock) {
                if (entry.deferred.isCompleted && entry.pendingAcquires == 0) {
                    entry.decodedLease.also { entry.decodedLease = null }
                } else {
                    null
                }
            }
            lease?.close()
        }
    }

    internal fun releaseAnimationSession(session: DesktopReaderAnimationContentSession) {
        val contentToClose = synchronized(lock) {
            val entry = animationSessions[session.contentKey]
            if (entry == null || entry.content !== session.sharedContent) return
            check(entry.references > 0) { "Animation session reference count underflow" }
            entry.references--
            if (entry.references == 0) {
                animationSessions.remove(session.contentKey)
                entry.content
            } else {
                null
            }
        }
        contentToClose?.close()
    }

    companion object {
        const val DEFAULT_CACHE_ENTRIES = 7
        const val DEFAULT_CACHE_BYTES = 128L * 1024L * 1024L
        const val DEFAULT_CONCURRENT_DECODES = 3
    }
}

/**
 * One mounted animation's encoded-content lease. Frame requests still use the runtime pipeline's
 * single-flight/cache; this session only prevents reopening the encoded source for every frame.
 */
internal class DesktopReaderAnimationContentSession(
    val contentKey: ReaderPageContentOpenRequest,
    internal val sharedContent: DesktopReaderSharedAnimationContent,
    private val pipeline: DesktopReaderPageImagePipeline,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    suspend fun acquireFrame(key: ReaderPageDecodeKey): DesktopReaderImageAssetLease? {
        synchronized(lock) {
            check(!closed) { "Desktop reader animation content session is closed" }
        }
        return pipeline.acquireAnimationFrame(this, key)
    }

    suspend fun <T> withContent(block: suspend (ByteArray) -> T): T = sharedContent.withContent(block)

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        pipeline.releaseAnimationSession(this)
    }
}

/** Pipeline-owned source lease shared by every mounted presentation of the same animated page. */
internal class DesktopReaderSharedAnimationContent(
    val contentKey: ReaderPageContentOpenRequest,
    scope: CoroutineScope,
    pageContentOwner: DesktopReaderPageContentOwner,
) : AutoCloseable {
    private val lock = Any()
    private val content = scope.async(start = CoroutineStart.LAZY) {
        pageContentOwner.acquire(contentKey)
    }
    private var closed = false
    private var contentLeaseClosed = false

    init {
        content.invokeOnCompletion { closeCompletedContentIfNeeded() }
    }

    suspend fun <T> withContent(block: suspend (ByteArray) -> T): T {
        synchronized(lock) {
            if (closed) throw CancellationException("Desktop reader animation content is closed")
            content.start()
        }
        val lease = content.await() ?: throw CancellationException("Animated page content is unavailable")
        synchronized(lock) {
            if (closed) throw CancellationException("Desktop reader animation content is closed")
        }
        return block(lease.content)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        content.cancel()
        closeCompletedContentIfNeeded()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun closeCompletedContentIfNeeded() {
        val lease = synchronized(lock) {
            if (!closed || contentLeaseClosed || !content.isCompleted) return
            val completed = runCatching { content.getCompleted() }.getOrNull() ?: return
            contentLeaseClosed = true
            completed
        }
        lease.close()
    }
}
