package mihon.desktop.reader

import androidx.compose.ui.graphics.ImageBitmap
import mihon.domain.reader.ReaderPageDecodeKey

/** One independently closeable ownership reference to a decoded Desktop reader image. */
internal interface DesktopReaderImageAssetLease : AutoCloseable {
    val asset: DesktopReaderImageAsset

    /** Retains the same asset for another owner without transferring this lease. */
    fun retain(): DesktopReaderImageAssetLease
}

/**
 * Stable decoded image shared by presentation, transform, and cache consumers.
 *
 * The asset itself is the decoder's initial lease. Every additional owner must call [retain] and
 * close the returned lease. The disposer runs exactly once after the final lease is released.
 */
internal class DesktopReaderImageAsset(
    val bitmap: ImageBitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val estimatedBytes: Long,
    val sampled: Boolean,
    private val disposer: () -> Unit = {},
) : DesktopReaderImageAssetLease {
    private val lock = Any()
    private var referenceCount = 1
    private var initialLeaseClosed = false
    private var disposed = false

    override val asset: DesktopReaderImageAsset
        get() = this

    init {
        require(sourceWidth > 0 && sourceHeight > 0) { "source dimensions must be positive" }
        require(estimatedBytes >= 0) { "estimatedBytes must be non-negative" }
    }

    override fun retain(): DesktopReaderImageAssetLease = synchronized(lock) {
        check(!initialLeaseClosed) { "Desktop reader image asset lease is closed" }
        retainLocked()
    }

    override fun close() {
        val shouldDispose = synchronized(lock) {
            if (initialLeaseClosed) {
                false
            } else {
                initialLeaseClosed = true
                releaseLocked()
            }
        }
        if (shouldDispose) disposer()
    }

    private fun retainFromRetainedLease(): DesktopReaderImageAssetLease = synchronized(lock) {
        retainLocked()
    }

    private fun releaseFromRetainedLease() {
        val shouldDispose = synchronized(lock) { releaseLocked() }
        if (shouldDispose) disposer()
    }

    private fun retainLocked(): DesktopReaderImageAssetLease {
        check(!disposed) { "Desktop reader image asset is disposed" }
        referenceCount++
        return RetainedLease(this)
    }

    private fun releaseLocked(): Boolean {
        check(referenceCount > 0) { "Desktop reader image asset reference count underflow" }
        referenceCount--
        if (referenceCount != 0 || disposed) return false
        disposed = true
        return true
    }

    private class RetainedLease(
        override val asset: DesktopReaderImageAsset,
    ) : DesktopReaderImageAssetLease {
        private val lock = Any()
        private var closed = false

        override fun retain(): DesktopReaderImageAssetLease = synchronized(lock) {
            check(!closed) { "Desktop reader image asset lease is closed" }
            asset.retainFromRetainedLease()
        }

        override fun close() {
            val shouldRelease = synchronized(lock) {
                if (closed) {
                    false
                } else {
                    closed = true
                    true
                }
            }
            if (shouldRelease) asset.releaseFromRetainedLease()
        }
    }
}

/** Immutable access-order view of the decoded image cache. [keys] are least-to-most recently used. */
internal data class DesktopReaderImageCacheSnapshot(
    val keys: List<ReaderPageDecodeKey>,
    val entryCount: Int,
    val usedBytes: Long,
    val maxEntries: Int,
    val maxBytes: Long,
) {
    init {
        require(maxEntries >= 0) { "maxEntries must be non-negative" }
        require(maxBytes >= 0) { "maxBytes must be non-negative" }
        require(entryCount == keys.size) { "entryCount must match keys" }
        require(entryCount in 0..maxEntries) { "entryCount must be within the cache limit" }
        require(usedBytes in 0..maxBytes) { "usedBytes must be within the cache budget" }
    }
}

/**
 * Access-ordered decoded image cache bounded by both entry count and estimated bytes.
 *
 * The cache owns exactly one retained lease per entry. A caller keeps ownership of the lease passed
 * to [commit], and an acquired caller lease remains valid even after replacement, eviction, or clear.
 */
internal class DesktopReaderImageCache(
    private val maxEntries: Int,
    private val maxBytes: Long,
) : AutoCloseable {
    private data class Entry(val lease: DesktopReaderImageAssetLease)

    private val lock = Any()
    private val entries = LinkedHashMap<ReaderPageDecodeKey, Entry>(16, 0.75f, true)
    private var usedBytes = 0L
    private var closed = false

    init {
        require(maxEntries >= 0) { "maxEntries must be non-negative" }
        require(maxBytes >= 0) { "maxBytes must be non-negative" }
    }

    fun acquire(key: ReaderPageDecodeKey): DesktopReaderImageAssetLease? = synchronized(lock) {
        check(!closed) { "Desktop reader image cache is closed" }
        entries[key]?.lease?.retain()
    }

    /**
     * Retains [callerLease] for the cache without consuming or closing the caller's ownership.
     * Returns false when the asset cannot fit either configured limit.
     */
    fun commit(
        key: ReaderPageDecodeKey,
        callerLease: DesktopReaderImageAssetLease,
    ): Boolean {
        val releasedCacheLeases = mutableListOf<DesktopReaderImageAssetLease>()
        synchronized(lock) {
            check(!closed) { "Desktop reader image cache is closed" }
            val estimatedBytes = callerLease.asset.estimatedBytes
            if (maxEntries == 0 || estimatedBytes > maxBytes) return false

            val cacheLease = callerLease.retain()
            try {
                entries.remove(key)?.let { previous ->
                    usedBytes -= previous.lease.asset.estimatedBytes
                    releasedCacheLeases += previous.lease
                }
                while (
                    entries.size >= maxEntries ||
                    usedBytes > maxBytes - estimatedBytes
                ) {
                    val leastRecentlyUsedKey = entries.keys.first()
                    val evicted = checkNotNull(entries.remove(leastRecentlyUsedKey))
                    usedBytes -= evicted.lease.asset.estimatedBytes
                    releasedCacheLeases += evicted.lease
                }
                entries[key] = Entry(cacheLease)
                usedBytes += estimatedBytes
            } catch (error: Throwable) {
                cacheLease.close()
                throw error
            }
        }
        releasedCacheLeases.forEach(DesktopReaderImageAssetLease::close)
        return true
    }

    fun clear() {
        val releasedCacheLeases = synchronized(lock) { drainEntriesLocked() }
        releasedCacheLeases.forEach(DesktopReaderImageAssetLease::close)
    }

    fun snapshot(): DesktopReaderImageCacheSnapshot = synchronized(lock) {
        DesktopReaderImageCacheSnapshot(
            keys = entries.keys.toList(),
            entryCount = entries.size,
            usedBytes = usedBytes,
            maxEntries = maxEntries,
            maxBytes = maxBytes,
        )
    }

    override fun close() {
        val releasedCacheLeases = synchronized(lock) {
            if (closed) return
            closed = true
            drainEntriesLocked()
        }
        releasedCacheLeases.forEach(DesktopReaderImageAssetLease::close)
    }

    private fun drainEntriesLocked(): List<DesktopReaderImageAssetLease> {
        if (entries.isEmpty()) return emptyList()
        val leases = entries.values.map(Entry::lease)
        entries.clear()
        usedBytes = 0L
        return leases
    }
}
