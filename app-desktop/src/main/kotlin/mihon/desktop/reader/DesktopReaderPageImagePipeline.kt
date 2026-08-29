package mihon.desktop.reader

import androidx.compose.ui.graphics.ImageBitmap
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
import mihon.domain.reader.observability.ReaderIoEventType
import mihon.domain.reader.observability.ReaderIoPurpose
import mihon.domain.reader.observability.ReaderIoReporter

/** Platform decoder boundary owned exclusively by [DesktopReaderPageImagePipeline]. */
internal fun interface DesktopReaderPageImageDecoder {
    suspend fun decode(
        encoded: ByteArray,
        key: ReaderPageDecodeKey,
    ): DesktopReaderImageAssetLease?
}

/** Production full-page Skia adapter. Animation and true region decoding are added in RUA-04D. */
internal class SkiaDesktopReaderPageImageDecoder(
    private val decoder: PageDecoder<ByteArray, ImageBitmap> = SkiaPageDecoder(),
) : DesktopReaderPageImageDecoder {
    override suspend fun decode(
        encoded: ByteArray,
        key: ReaderPageDecodeKey,
    ): DesktopReaderImageAssetLease? {
        require(key.purpose == PageDecodePurpose.FULL_PAGE) {
            "The standard Desktop decoder only accepts full-page requests"
        }
        val sourceSize = SkiaImageDecoder.peekSize(encoded) ?: return null
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
                disposer = result.value.asSkiaBitmap()::close,
            )
        }
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
 * Presentation and transform consumers receive independently closeable leases. Encoded content is released
 * immediately after the decoder returns; the decoded cache never retains archive or source streams.
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

    private val lock = Any()
    private val cache = DesktopReaderImageCache(maxEntries, maxBytes)
    private val decodePermits = Semaphore(maxConcurrentDecodes)
    private val entries = mutableMapOf<ReaderPageDecodeKey, Entry>()
    private val mutableCacheRevision = MutableStateFlow(0L)
    private var minimumGeneration = 0L
    private var closed = false

    init {
        require(maxConcurrentDecodes > 0) { "maxConcurrentDecodes must be positive" }
    }

    internal val cacheRevision: StateFlow<Long> = mutableCacheRevision.asStateFlow()

    internal suspend fun acquire(key: ReaderPageDecodeKey): DesktopReaderImageAssetLease? {
        val entry = synchronized(lock) {
            check(!closed) { "Desktop reader page image pipeline is closed" }
            if (key.generation < minimumGeneration) return null
            cache.acquire(key)?.let { return it }
            entries.getOrPut(key) { createEntry(key) }.also {
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
        val staleEntries = synchronized(lock) {
            check(!closed) { "Desktop reader page image pipeline is closed" }
            if (generation < minimumGeneration) return false
            if (generation == minimumGeneration) return true
            minimumGeneration = generation
            val stale = entries.filterKeys { it.generation < generation }
            stale.keys.forEach(entries::remove)
            cache.clear()
            mutableCacheRevision.value++
            stale.values.toList()
        }
        staleEntries.forEach { entry -> entry.deferred.cancel() }
        releaseDetachedCompleted(staleEntries)
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
        val detachedEntries = synchronized(lock) {
            if (closed) return
            closed = true
            val detached = entries.values.toList()
            entries.clear()
            cache.close()
            mutableCacheRevision.value++
            detached
        }
        detachedEntries.forEach { entry -> entry.deferred.cancel() }
        releaseDetachedCompleted(detachedEntries)
    }

    private fun createEntry(key: ReaderPageDecodeKey): Entry {
        val entry = Entry()
        entry.deferred = scope.async(start = CoroutineStart.LAZY) {
            val decoded = load(key)
            synchronized(lock) {
                entry.decodedLease = decoded
                if (
                    decoded != null &&
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
        return decodePermits.withPermit {
            val contentLease = pageContentOwner.acquire(key.contentKey) ?: return@withPermit null
            try {
                ioReporter.report(
                    type = ReaderIoEventType.DECODE,
                    chapterId = key.contentKey.pageId.chapterId,
                    pageId = key.contentKey.pageId,
                    generation = key.generation,
                    purpose = ReaderIoPurpose.VISIBLE_DECODE,
                )
                decoder.decode(contentLease.content, key)
            } finally {
                contentLease.close()
            }
        }
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

    companion object {
        const val DEFAULT_CACHE_ENTRIES = 7
        const val DEFAULT_CACHE_BYTES = 128L * 1024L * 1024L
        const val DEFAULT_CONCURRENT_DECODES = 3
    }
}
