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
import mihon.domain.reader.session.ReaderPageId
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
    private val regionDecoder: mihon.domain.reader.RegionDecoder<ByteArray, ImageBitmap> = SkiaRegionPageDecoder(),
) : DesktopReaderPageImageDecoder {
    override suspend fun decode(
        encoded: ByteArray,
        key: ReaderPageDecodeKey,
    ): DesktopReaderImageAssetLease? {
        if (key.purpose == PageDecodePurpose.REGION_TILE) return decodeRegion(encoded, key)
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

    private suspend fun decodeRegion(
        encoded: ByteArray,
        key: ReaderPageDecodeKey,
    ): DesktopReaderImageAssetLease? {
        val sourceSize = SkiaImageDecoder.peekSize(encoded) ?: return null
        return when (
            val result = regionDecoder.decodeRegion(
                encoded,
                PageDecodeRequest(
                    pageIndex = key.pageIndex,
                    generation = key.generation,
                    maxWidth = key.maxWidth,
                    maxHeight = key.maxHeight,
                    region = key.region,
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
    val tileCache: DesktopReaderImageCacheSnapshot,
    val memory: DesktopReaderImageMemorySnapshot,
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
    internal val memoryAuthority: DesktopReaderImageMemoryAuthority = DesktopReaderImageMemoryAuthority(
        if (maxBytes == DEFAULT_CACHE_BYTES) DEFAULT_TOTAL_IMAGE_BYTES else maxBytes,
    ),
    maxConcurrentDecodes: Int = DEFAULT_CONCURRENT_DECODES,
) : AutoCloseable {
    private data class PageGenerationIdentity(
        val pageId: ReaderPageId,
        val generation: Long,
    )

    private class Entry {
        lateinit var deferred: Deferred<DesktopReaderImageAssetLease?>
        var pendingAcquires: Int = 0
        var decodedLease: DesktopReaderImageAssetLease? = null
        var fullCacheClaimed: Boolean = false
    }

    private class ContentSessionEntry(
        val content: DesktopReaderSharedPageContent,
        var references: Int = 0,
    )

    private val lock = Any()
    private val mutableCacheRevision = MutableStateFlow(0L)
    private val cache = DesktopReaderImageCache(
        maxEntries = maxEntries,
        maxBytes = memoryAuthority.maxBytes,
        memoryAuthority = memoryAuthority,
        ownsMemoryAuthority = false,
        onAuthorityEviction = { mutableCacheRevision.value++ },
    )
    private val tileCache = DesktopReaderImageCache(
        maxEntries = DEFAULT_TILE_CACHE_ENTRIES,
        maxBytes = memoryAuthority.maxBytes,
        memoryAuthority = memoryAuthority,
        ownsMemoryAuthority = false,
    )
    private val decodePermits = Semaphore(maxConcurrentDecodes)
    private val entries = mutableMapOf<ReaderPageDecodeKey, Entry>()
    private val contentSessions = mutableMapOf<ReaderPageContentOpenRequest, ContentSessionEntry>()
    private val minimumPageAttempts = mutableMapOf<PageGenerationIdentity, Long>()
    private var minimumGeneration = 0L
    private var clearing = false
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
        val entry = retainContentSessionLocked(contentKey, "Animation")
        DesktopReaderAnimationContentSession(
            contentKey = contentKey,
            sharedContent = entry.content,
            pipeline = this,
        )
    }

    internal fun openRegionSession(
        contentKey: ReaderPageContentOpenRequest,
    ): DesktopReaderRegionContentSession = synchronized(lock) {
        val entry = retainContentSessionLocked(contentKey, "Region")
        DesktopReaderRegionContentSession(
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

    internal suspend fun acquireRegionTile(
        session: DesktopReaderRegionContentSession,
        key: ReaderPageDecodeKey,
    ): DesktopReaderImageAssetLease? {
        require(key.purpose == PageDecodePurpose.REGION_TILE) {
            "Region sessions only accept region-tile requests"
        }
        require(key.contentKey == session.contentKey) {
            "Region tile and source session identities must match"
        }
        return acquireWithLoader(key) {
            session.withContent { encoded -> decodeContent(key, encoded) }
        }
    }

    /** Caches a region only after its mounted presentation has accepted ownership. */
    internal fun commitRegionTile(
        key: ReaderPageDecodeKey,
        acceptedLease: DesktopReaderImageAssetLease,
    ): Boolean {
        require(key.purpose == PageDecodePurpose.REGION_TILE) {
            "Only accepted region tiles may enter the tile cache"
        }
        val current = synchronized(lock) {
            !closed && !clearing && isCurrentAttemptLocked(key.contentKey)
        }
        if (!current) return false
        if (!tileCache.commit(key, acceptedLease)) return false
        val stillCurrent = synchronized(lock) {
            !closed && !clearing && isCurrentAttemptLocked(key.contentKey)
        }
        if (stillCurrent) return true
        tileCache.removeWhere { candidate -> candidate == key }
        return false
    }

    private suspend fun acquireWithLoader(
        key: ReaderPageDecodeKey,
        loader: suspend () -> DesktopReaderImageAssetLease?,
    ): DesktopReaderImageAssetLease? {
        val entry = synchronized(lock) {
            check(!closed) { "Desktop reader page image pipeline is closed" }
            if (clearing || !isCurrentAttemptLocked(key.contentKey)) return null
            cacheFor(key)?.acquire(key)?.let { return it }
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
        var shouldCommitFull = false
        var callerAcquireFailure: Throwable? = null
        val callerLease = synchronized(lock) {
            entry.pendingAcquires--
            val accepted = !closed &&
                !clearing &&
                isCurrentAttemptLocked(key.contentKey) &&
                entries[key] === entry
            val lease = if (accepted) {
                try {
                    decoded.retain()
                } catch (error: Throwable) {
                    callerAcquireFailure = error
                    null
                }
            } else {
                null
            }
            if (
                lease != null &&
                key.purpose == PageDecodePurpose.FULL_PAGE &&
                decoded.asset.animationMetadata == null &&
                !entry.fullCacheClaimed
            ) {
                entry.fullCacheClaimed = true
                shouldCommitFull = true
            }
            entryLeaseToClose = removeIfUnusedLocked(key, entry)
            lease
        }
        callerAcquireFailure?.let { error ->
            try {
                entryLeaseToClose?.close()
            } catch (closeError: Throwable) {
                if (closeError !== error) error.addSuppressed(closeError)
            }
            throw error
        }
        if (callerLease == null) {
            entryLeaseToClose?.close()
            throw CancellationException("Desktop reader page image request became stale")
        }
        var failure: Throwable? = null
        if (shouldCommitFull) {
            try {
                if (cache.commit(key, callerLease)) {
                    val stillCurrent = synchronized(lock) {
                        !closed && !clearing && isCurrentAttemptLocked(key.contentKey)
                    }
                    if (stillCurrent) {
                        mutableCacheRevision.value++
                    } else {
                        cache.removeWhere { candidate -> candidate == key }
                    }
                }
            } catch (error: Throwable) {
                failure = error
            }
        }
        entryLeaseToClose?.let { lease ->
            try {
                lease.close()
            } catch (closeError: Throwable) {
                val first = failure
                if (first == null) {
                    failure = closeError
                } else if (closeError !== first) {
                    first.addSuppressed(closeError)
                }
            }
        }
        if (failure != null) {
            try {
                callerLease.close()
            } catch (closeError: Throwable) {
                val first = checkNotNull(failure)
                if (closeError !== first) first.addSuppressed(closeError)
            }
        }
        failure?.let { throw it }
        return callerLease
    }

    internal fun acquireCached(key: ReaderPageDecodeKey): DesktopReaderImageAssetLease? = synchronized(lock) {
        check(!closed) { "Desktop reader page image pipeline is closed" }
        if (clearing || !isCurrentAttemptLocked(key.contentKey)) return null
        cacheFor(key)?.acquire(key)
    }

    /** Retains the most recently used current FULL_PAGE asset for every cached logical page. */
    internal fun retainCachedFullPageAssets(): Map<Int, DesktopReaderImageAssetLease> {
        val keys = synchronized(lock) {
            check(!closed) { "Desktop reader page image pipeline is closed" }
            if (clearing) return emptyMap()
            cache.snapshot().keys.filter { key ->
                key.purpose == PageDecodePurpose.FULL_PAGE && isCurrentAttemptLocked(key.contentKey)
            }
        }
        val retained = linkedMapOf<Int, DesktopReaderImageAssetLease>()
        try {
            keys.forEach { key ->
                val lease = acquireCached(key) ?: return@forEach
                retained.put(key.pageIndex, lease)?.close()
            }
        } catch (error: Throwable) {
            try {
                runResourceActions(retained.values.map { lease -> lease::close })
            } catch (closeError: Throwable) {
                if (closeError !== error) error.addSuppressed(closeError)
            }
            throw error
        }
        return retained
    }

    /** Advances Retry identity for one logical page without disturbing another page or generation. */
    internal fun beginPageAttempt(
        pageId: ReaderPageId,
        generation: Long,
        attemptGeneration: Long,
    ): Boolean {
        require(generation >= 0L) { "generation must be non-negative" }
        require(attemptGeneration >= 0L) { "attemptGeneration must be non-negative" }
        val detached = synchronized(lock) {
            check(!closed) { "Desktop reader page image pipeline is closed" }
            if (generation < minimumGeneration) return false
            val identity = PageGenerationIdentity(pageId, generation)
            val previousAttempt = minimumPageAttempts[identity] ?: 0L
            if (attemptGeneration < previousAttempt) return false
            if (attemptGeneration == previousAttempt) return true
            minimumPageAttempts[identity] = attemptGeneration

            val staleEntries = entries.filterKeys { key ->
                key.contentKey.isOlderAttempt(pageId, generation, attemptGeneration)
            }
            staleEntries.keys.forEach(entries::remove)
            val staleSessions = contentSessions.filterKeys { contentKey ->
                contentKey.isOlderAttempt(pageId, generation, attemptGeneration)
            }
            staleSessions.keys.forEach(contentSessions::remove)
            staleEntries.values.toList() to staleSessions.values.map(ContentSessionEntry::content)
        }

        val staleKey: (ReaderPageDecodeKey) -> Boolean = { key ->
            key.contentKey.isOlderAttempt(pageId, generation, attemptGeneration)
        }
        val hadFullCacheEntries = cache.snapshot().keys.any(staleKey)
        runResourceActions(
            buildList {
                add { cache.removeWhere(staleKey) }
                add { tileCache.removeWhere(staleKey) }
                if (hadFullCacheEntries) {
                    add {
                        synchronized(lock) {
                            if (!closed) mutableCacheRevision.value++
                        }
                    }
                }
                detached.first.forEach { entry -> add { entry.deferred.cancel() } }
                add { releaseDetachedCompleted(detached.first) }
                detached.second.forEach { content -> add(content::close) }
            },
        )
        return true
    }

    /** Advances the accepted generation and rejects all older cache and in-flight results. */
    internal fun beginGeneration(generation: Long): Boolean {
        require(generation >= 0L) { "generation must be non-negative" }
        val stale = synchronized(lock) {
            check(!closed) { "Desktop reader page image pipeline is closed" }
            if (generation < minimumGeneration) return false
            if (generation == minimumGeneration) return true
            minimumGeneration = generation
            minimumPageAttempts.keys.removeAll { identity -> identity.generation < generation }
            val stale = entries.filterKeys { it.generation < generation }
            stale.keys.forEach(entries::remove)
            mutableCacheRevision.value++
            val staleSessions = contentSessions.filterKeys { it.generation < generation }
            staleSessions.keys.forEach(contentSessions::remove)
            stale.values.toList() to staleSessions.values.map(ContentSessionEntry::content)
        }
        runResourceActions(
            buildList {
                add { cache.removeWhere { key -> key.generation < generation } }
                add { tileCache.removeWhere { key -> key.generation < generation } }
                stale.first.forEach { entry -> add { entry.deferred.cancel() } }
                add { releaseDetachedCompleted(stale.first) }
                stale.second.forEach { content -> add(content::close) }
            },
        )
        return true
    }

    internal fun clear() {
        val detachedEntries = synchronized(lock) {
            check(!closed) { "Desktop reader page image pipeline is closed" }
            clearing = true
            val detached = entries.values.toList()
            entries.clear()
            mutableCacheRevision.value++
            detached
        }
        try {
            runResourceActions(
                buildList {
                    detachedEntries.forEach { entry -> add { entry.deferred.cancel() } }
                    add { releaseDetachedCompleted(detachedEntries) }
                    add(cache::clear)
                    add(tileCache::clear)
                },
            )
        } finally {
            synchronized(lock) { clearing = false }
        }
    }

    internal fun snapshot(): DesktopReaderPageImagePipelineSnapshot = synchronized(lock) {
        DesktopReaderPageImagePipelineSnapshot(
            minimumGeneration = minimumGeneration,
            cache = cache.snapshot(),
            tileCache = tileCache.snapshot(),
            memory = memoryAuthority.snapshot(),
            inFlightCount = entries.size,
            closed = closed,
        )
    }

    override fun close() {
        val detached = synchronized(lock) {
            if (closed) return
            closed = true
            val detachedEntries = entries.values.toList()
            val detachedSessions = contentSessions.values.map(ContentSessionEntry::content)
            entries.clear()
            contentSessions.clear()
            mutableCacheRevision.value++
            detachedEntries to detachedSessions
        }
        runResourceActions(
            buildList {
                detached.first.forEach { entry -> add { entry.deferred.cancel() } }
                add { releaseDetachedCompleted(detached.first) }
                detached.second.forEach { content -> add(content::close) }
                add(cache::close)
                add(tileCache::close)
                add(memoryAuthority::close)
            },
        )
    }

    private fun createEntry(
        key: ReaderPageDecodeKey,
        loader: suspend () -> DesktopReaderImageAssetLease?,
    ): Entry {
        val entry = Entry()
        entry.deferred = scope.async(start = CoroutineStart.LAZY) {
            val decoded = loader()
            try {
                decoded?.asset?.attachMemoryAuthority(
                    authority = memoryAuthority,
                    kind = key.purpose.toImageMemoryKind(),
                    retention = DesktopReaderImageMemoryRetention.IN_FLIGHT,
                )
            } catch (error: Throwable) {
                try {
                    decoded?.close()
                } catch (closeError: Throwable) {
                    if (closeError !== error) error.addSuppressed(closeError)
                }
                if (synchronized(lock) { closed }) {
                    throw CancellationException("Desktop reader page image pipeline closed").apply {
                        initCause(error)
                    }
                }
                throw error
            }
            synchronized(lock) {
                entry.decodedLease = decoded
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
        val leases = detachedEntries.mapNotNull { entry ->
            synchronized(lock) {
                if (entry.deferred.isCompleted && entry.pendingAcquires == 0) {
                    entry.decodedLease.also { entry.decodedLease = null }
                } else {
                    null
                }
            }
        }
        runResourceActions(leases.map { lease -> lease::close })
    }

    private fun retainContentSessionLocked(
        contentKey: ReaderPageContentOpenRequest,
        purpose: String,
    ): ContentSessionEntry {
        check(!closed) { "Desktop reader page image pipeline is closed" }
        check(isCurrentAttemptLocked(contentKey)) { "$purpose session identity is stale" }
        return contentSessions.getOrPut(contentKey) {
            ContentSessionEntry(
                DesktopReaderSharedPageContent(
                    contentKey = contentKey,
                    scope = scope,
                    pageContentOwner = pageContentOwner,
                ),
            )
        }.also { it.references++ }
    }

    private fun cacheFor(key: ReaderPageDecodeKey): DesktopReaderImageCache? = when (key.purpose) {
        PageDecodePurpose.FULL_PAGE -> cache
        PageDecodePurpose.REGION_TILE -> tileCache
        PageDecodePurpose.ANIMATION_FRAME -> null
    }

    private fun isCurrentAttemptLocked(contentKey: ReaderPageContentOpenRequest): Boolean {
        if (contentKey.generation < minimumGeneration) return false
        val minimumAttempt = minimumPageAttempts[
            PageGenerationIdentity(contentKey.pageId, contentKey.generation),
        ] ?: 0L
        return contentKey.attemptGeneration == minimumAttempt
    }

    private fun ReaderPageContentOpenRequest.isOlderAttempt(
        pageId: ReaderPageId,
        generation: Long,
        attemptGeneration: Long,
    ): Boolean = this.pageId == pageId &&
        this.generation == generation &&
        this.attemptGeneration < attemptGeneration

    internal fun releaseAnimationSession(session: DesktopReaderAnimationContentSession) {
        releaseContentSession(session.contentKey, session.sharedContent)
    }

    internal fun releaseRegionSession(session: DesktopReaderRegionContentSession) {
        releaseContentSession(session.contentKey, session.sharedContent)
    }

    private fun releaseContentSession(
        contentKey: ReaderPageContentOpenRequest,
        sharedContent: DesktopReaderSharedPageContent,
    ) {
        val contentToClose = synchronized(lock) {
            val entry = contentSessions[contentKey]
            if (entry == null || entry.content !== sharedContent) return
            check(entry.references > 0) { "Content session reference count underflow" }
            entry.references--
            if (entry.references == 0) {
                contentSessions.remove(contentKey)
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
        const val DEFAULT_TILE_CACHE_ENTRIES = 8
        const val DEFAULT_TILE_CACHE_BYTES = 64L * 1024L * 1024L
        const val DEFAULT_CONCURRENT_DECODES = 3
        const val DEFAULT_TOTAL_IMAGE_BYTES = DesktopReaderImageMemoryAuthority.DEFAULT_MAX_BYTES
    }
}

private fun PageDecodePurpose.toImageMemoryKind(): DesktopReaderImageMemoryKind = when (this) {
    PageDecodePurpose.FULL_PAGE -> DesktopReaderImageMemoryKind.FULL
    PageDecodePurpose.ANIMATION_FRAME -> DesktopReaderImageMemoryKind.FRAME
    PageDecodePurpose.REGION_TILE -> DesktopReaderImageMemoryKind.TILE
}

/**
 * One mounted animation's encoded-content lease. Frame requests still use the runtime pipeline's
 * single-flight/cache; this session only prevents reopening the encoded source for every frame.
 */
internal class DesktopReaderAnimationContentSession(
    val contentKey: ReaderPageContentOpenRequest,
    internal val sharedContent: DesktopReaderSharedPageContent,
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

/** One mounted large-static presentation's encoded source shared by its preview and region tiles. */
internal class DesktopReaderRegionContentSession(
    val contentKey: ReaderPageContentOpenRequest,
    internal val sharedContent: DesktopReaderSharedPageContent,
    private val pipeline: DesktopReaderPageImagePipeline,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    suspend fun pin() {
        withContent { Unit }
    }

    suspend fun acquireTile(key: ReaderPageDecodeKey): DesktopReaderImageAssetLease? {
        synchronized(lock) {
            check(!closed) { "Desktop reader region content session is closed" }
        }
        return pipeline.acquireRegionTile(this, key)
    }

    suspend fun <T> withContent(block: suspend (ByteArray) -> T): T {
        synchronized(lock) {
            check(!closed) { "Desktop reader region content session is closed" }
        }
        return sharedContent.withContent(block)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        pipeline.releaseRegionSession(this)
    }
}

/** Pipeline-owned source lease shared by every mounted presentation of the same animated page. */
internal class DesktopReaderSharedPageContent(
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
            if (closed) throw CancellationException("Desktop reader shared content is closed")
            content.start()
        }
        val lease = content.await() ?: throw CancellationException("Reader page content is unavailable")
        synchronized(lock) {
            if (closed) throw CancellationException("Desktop reader shared content is closed")
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
