package mihon.desktop.reader

import androidx.compose.ui.graphics.ImageBitmap
import mihon.domain.reader.PageDecodePurpose
import mihon.domain.reader.ReaderPageDecodeKey

/** One independently closeable ownership reference to a decoded Desktop reader image. */
internal interface DesktopReaderImageAssetLease : DesktopReaderImageMemoryLease {
    val asset: DesktopReaderImageAsset

    /** Retains the same asset for another owner without transferring this lease. */
    fun retain(): DesktopReaderImageAssetLease = retain(DesktopReaderImageMemoryRetention.ACTIVE)

    override fun retain(retention: DesktopReaderImageMemoryRetention): DesktopReaderImageAssetLease

    fun memoryAllocationId(authority: DesktopReaderImageMemoryAuthority): Long
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
    val animationMetadata: DesktopReaderAnimationMetadata? = null,
    private val disposer: () -> Unit = {},
) : DesktopReaderImageAssetLease {
    private val lock = Any()
    private val finalDisposers = mutableListOf(disposer)
    private var referenceCount = 1
    private var initialLeaseClosed = false
    private var disposed = false
    private var resourcesDisposed = false
    private var memoryAuthority: DesktopReaderImageMemoryAuthority? = null
    private var initialMemoryLease: DesktopReaderImageMemoryLease? = null
    private var memoryAllocationId: Long? = null
    private var memoryRegistrationInProgress = false

    override val asset: DesktopReaderImageAsset
        get() = this

    init {
        require(sourceWidth > 0 && sourceHeight > 0) { "source dimensions must be positive" }
        require(estimatedBytes >= 0) { "estimatedBytes must be non-negative" }
    }

    override fun retain(retention: DesktopReaderImageMemoryRetention): DesktopReaderImageAssetLease = synchronized(lock) {
        check(!initialLeaseClosed) { "Desktop reader image asset lease is closed" }
        retainLocked(retention)
    }

    override fun close() {
        var memoryLeaseToClose: DesktopReaderImageMemoryLease? = null
        val shouldDispose = synchronized(lock) {
            if (initialLeaseClosed) {
                false
            } else {
                initialLeaseClosed = true
                memoryLeaseToClose = initialMemoryLease
                releaseLocked(hasMemoryLease = memoryLeaseToClose != null)
            }
        }
        memoryLeaseToClose?.close()
        if (shouldDispose) disposeResources()
    }

    override fun memoryAllocationId(authority: DesktopReaderImageMemoryAuthority): Long = synchronized(lock) {
        require(memoryAuthority === authority) { "Image asset belongs to another memory authority" }
        checkNotNull(memoryAllocationId) { "Image asset has no memory allocation" }
    }

    internal fun attachMemoryAuthority(
        authority: DesktopReaderImageMemoryAuthority,
        kind: DesktopReaderImageMemoryKind,
        retention: DesktopReaderImageMemoryRetention,
    ) {
        synchronized(lock) {
            memoryAuthority?.let { existing ->
                require(existing === authority) { "Image asset is already registered with another memory authority" }
                return
            }
            check(referenceCount == 1 && !initialLeaseClosed && !disposed) {
                "Image asset must be registered before it is shared"
            }
            check(!memoryRegistrationInProgress) { "Image asset memory registration is already in progress" }
            memoryRegistrationInProgress = true
        }
        try {
            val allocation = authority.register(
                kind = kind,
                estimatedBytes = estimatedBytes,
                retention = retention,
                disposer = ::disposeResources,
            )
            synchronized(lock) {
                memoryAuthority = authority
                initialMemoryLease = allocation
                memoryAllocationId = authority.allocationIdFor(allocation)
                memoryRegistrationInProgress = false
            }
        } catch (error: Throwable) {
            synchronized(lock) { memoryRegistrationInProgress = false }
            throw error
        }
    }

    /** Adds a resource acquired by the pipeline before this asset becomes observable. */
    internal fun addFinalDisposer(finalDisposer: () -> Unit) = synchronized(lock) {
        check(!resourcesDisposed) { "Desktop reader image asset is disposed" }
        finalDisposers += finalDisposer
    }

    private fun retainFromRetainedLease(
        retention: DesktopReaderImageMemoryRetention,
    ): DesktopReaderImageAssetLease = synchronized(lock) {
        retainLocked(retention)
    }

    private fun releaseFromRetainedLease(memoryLease: DesktopReaderImageMemoryLease?) {
        val shouldDispose = synchronized(lock) { releaseLocked(hasMemoryLease = memoryLease != null) }
        memoryLease?.close()
        if (shouldDispose) disposeResources()
    }

    private fun retainLocked(retention: DesktopReaderImageMemoryRetention): DesktopReaderImageAssetLease {
        check(!disposed) { "Desktop reader image asset is disposed" }
        require(retention != DesktopReaderImageMemoryRetention.CACHE) {
            "Image cache retention must be admitted through the memory authority"
        }
        val retainedMemoryLease = memoryAuthority?.retainAllocation(checkNotNull(memoryAllocationId), retention)
        referenceCount++
        return RetainedLease(this, retainedMemoryLease)
    }

    internal fun retainForCacheAcquire(
        memoryLease: DesktopReaderImageMemoryLease,
    ): DesktopReaderImageAssetLease? {
        val retained = synchronized(lock) {
            if (disposed || memoryAuthority == null) return@synchronized null
            referenceCount++
            RetainedLease(this, memoryLease)
        }
        if (retained == null) memoryLease.close()
        return retained
    }

    private fun releaseLocked(hasMemoryLease: Boolean): Boolean {
        check(referenceCount > 0) { "Desktop reader image asset reference count underflow" }
        referenceCount--
        if (referenceCount != 0 || disposed) return false
        if (hasMemoryLease || memoryAuthority != null) return false
        disposed = true
        return true
    }

    private fun disposeResources() {
        val resources = synchronized(lock) {
            if (resourcesDisposed) return
            resourcesDisposed = true
            disposed = true
            finalDisposers.toList().also { finalDisposers.clear() }
        }
        runResourceActions(resources)
    }

    private class RetainedLease(
        override val asset: DesktopReaderImageAsset,
        private val memoryLease: DesktopReaderImageMemoryLease?,
    ) : DesktopReaderImageAssetLease {
        private val lock = Any()
        private var closed = false

        override fun retain(retention: DesktopReaderImageMemoryRetention): DesktopReaderImageAssetLease =
            synchronized(lock) {
                check(!closed) { "Desktop reader image asset lease is closed" }
                asset.retainFromRetainedLease(retention)
            }

        override fun memoryAllocationId(authority: DesktopReaderImageMemoryAuthority): Long =
            asset.memoryAllocationId(authority)

        override fun close() {
            val shouldRelease = synchronized(lock) {
                if (closed) {
                    false
                } else {
                    closed = true
                    true
                }
            }
            if (shouldRelease) asset.releaseFromRetainedLease(memoryLease)
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
 * The cache owns one authority retention per entry. A caller keeps ownership of the lease passed to
 * [commit], and an acquired caller lease remains valid even after replacement, eviction, or clear.
 */
internal class DesktopReaderImageCache(
    private val maxEntries: Int,
    private val maxBytes: Long,
    private val memoryAuthority: DesktopReaderImageMemoryAuthority = DesktopReaderImageMemoryAuthority(maxBytes),
    private val ownsMemoryAuthority: Boolean = true,
    private val onAuthorityEviction: () -> Unit = {},
) : AutoCloseable {
    private data class Entry(
        val asset: DesktopReaderImageAsset,
        val allocationId: Long,
        var admitted: Boolean = false,
    )

    private data class RemovedEntry(
        val key: ReaderPageDecodeKey,
        val entry: Entry,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<ReaderPageDecodeKey, Entry>(16, 0.75f, true)
    private var usedBytes = 0L
    private var closed = false

    init {
        require(maxEntries >= 0) { "maxEntries must be non-negative" }
        require(maxBytes >= 0) { "maxBytes must be non-negative" }
    }

    fun acquire(key: ReaderPageDecodeKey): DesktopReaderImageAssetLease? {
        val entry = synchronized(lock) {
            check(!closed) { "Desktop reader image cache is closed" }
            entries[key]?.takeIf(Entry::admitted)
        } ?: return null
        val memoryLease = memoryAuthority.retainCache(key, entry.allocationId) {
            isCurrentEntry(key, entry.allocationId)
        } ?: run {
            evictFromAuthority(key, entry.allocationId)
            return null
        }
        return entry.asset.retainForCacheAcquire(memoryLease) ?: run {
            evictFromAuthority(key, entry.allocationId)
            memoryAuthority.removeCache(key, entry.allocationId)
            null
        }
    }

    /**
     * Retains [callerLease] for the cache without consuming or closing the caller's ownership.
     * Returns false when the asset cannot fit either configured limit.
     */
    fun commit(
        key: ReaderPageDecodeKey,
        callerLease: DesktopReaderImageAssetLease,
    ): Boolean {
        val estimatedBytes = callerLease.asset.estimatedBytes
        synchronized(lock) {
            check(!closed) { "Desktop reader image cache is closed" }
            if (maxEntries == 0 || estimatedBytes > maxBytes) return false
        }
        callerLease.asset.attachMemoryAuthority(
            authority = memoryAuthority,
            kind = key.purpose.toMemoryKind(),
            retention = DesktopReaderImageMemoryRetention.ACTIVE,
        )
        val allocationId = callerLease.memoryAllocationId(memoryAuthority)
        val removedEntries = mutableListOf<RemovedEntry>()
        synchronized(lock) {
            check(!closed) { "Desktop reader image cache is closed" }

            entries.remove(key)?.let { previous ->
                usedBytes -= previous.asset.estimatedBytes
                removedEntries += RemovedEntry(key, previous)
            }
            while (
                entries.size >= maxEntries ||
                usedBytes > maxBytes - estimatedBytes
            ) {
                val leastRecentlyUsedKey = entries.keys.first()
                val evicted = checkNotNull(entries.remove(leastRecentlyUsedKey))
                usedBytes -= evicted.asset.estimatedBytes
                removedEntries += RemovedEntry(leastRecentlyUsedKey, evicted)
            }
            entries[key] = Entry(callerLease.asset, allocationId)
            usedBytes += estimatedBytes
        }
        var failure: Throwable? = null
        fun recordFailure(error: Throwable) {
            val first = failure
            if (first == null) {
                failure = error
            } else if (error !== first) {
                first.addSuppressed(error)
            }
        }
        try {
            runResourceActions(
                removedEntries.map { removed ->
                    {
                        memoryAuthority.removeCache(removed.key, removed.entry.allocationId)
                        Unit
                    }
                },
            )
        } catch (error: Throwable) {
            recordFailure(error)
        }
        var admitted = false
        if (isCurrentEntry(key, allocationId)) {
            admitted = try {
                memoryAuthority.admitCache(
                    key = key,
                    lease = callerLease,
                    isCurrent = { isCurrentEntry(key, allocationId) },
                    onEvict = { evictFromAuthority(key, allocationId) },
                )
            } catch (error: Throwable) {
                recordFailure(error)
                memoryAuthority.touchCache(key, allocationId)
            }
        }
        if (admitted) {
            admitted = synchronized(lock) {
                val current = entries.entries.firstOrNull { (retainedKey, retainedEntry) ->
                    retainedKey == key && retainedEntry.allocationId == allocationId
                }?.value
                if (closed || current == null) {
                    false
                } else {
                    current.admitted = true
                    true
                }
            }
            if (!admitted) {
                try {
                    memoryAuthority.removeCache(key, allocationId)
                } catch (error: Throwable) {
                    recordFailure(error)
                }
            }
        }
        if (!admitted) evictFromAuthority(key, allocationId, notify = false)
        failure?.let { throw it }
        return admitted
    }

    fun clear() {
        val removedEntries = synchronized(lock) { drainEntriesLocked() }
        removeAuthorityRetentions(removedEntries)
    }

    /** Removes only matching identities while preserving leases acquired by draw callers. */
    fun removeWhere(predicate: (ReaderPageDecodeKey) -> Boolean): Int {
        val removedEntries = synchronized(lock) {
            if (closed) return 0
            val matching = entries.entries
                .filter { (key, _) -> predicate(key) }
                .map { (key, entry) ->
                    checkNotNull(entries.remove(key))
                    usedBytes -= entry.asset.estimatedBytes
                    RemovedEntry(key, entry)
                }
            matching
        }
        removeAuthorityRetentions(removedEntries)
        return removedEntries.size
    }

    fun snapshot(): DesktopReaderImageCacheSnapshot = synchronized(lock) {
        val admittedEntries = entries.filterValues(Entry::admitted)
        DesktopReaderImageCacheSnapshot(
            keys = admittedEntries.keys.toList(),
            entryCount = admittedEntries.size,
            usedBytes = admittedEntries.values.sumOf { entry -> entry.asset.estimatedBytes },
            maxEntries = maxEntries,
            maxBytes = maxBytes,
        )
    }

    override fun close() {
        val removedEntries = synchronized(lock) {
            if (closed) return
            closed = true
            drainEntriesLocked()
        }
        val actions = buildList<() -> Unit> {
            removedEntries.forEach { removed ->
                add {
                    memoryAuthority.removeCache(removed.key, removed.entry.allocationId)
                    Unit
                }
            }
            if (ownsMemoryAuthority) add(memoryAuthority::close)
        }
        runResourceActions(actions)
    }

    private fun drainEntriesLocked(): List<RemovedEntry> {
        if (entries.isEmpty()) return emptyList()
        val removedEntries = entries.map { (key, entry) -> RemovedEntry(key, entry) }
        entries.clear()
        usedBytes = 0L
        return removedEntries
    }

    private fun removeAuthorityRetentions(removedEntries: List<RemovedEntry>) {
        runResourceActions(
            removedEntries.map { removed ->
                {
                    memoryAuthority.removeCache(removed.key, removed.entry.allocationId)
                    Unit
                }
            },
        )
    }

    private fun isCurrentEntry(key: ReaderPageDecodeKey, allocationId: Long): Boolean = synchronized(lock) {
        if (closed) return false
        entries.entries.any { (retainedKey, entry) ->
            retainedKey == key && entry.allocationId == allocationId
        }
    }

    private fun evictFromAuthority(
        key: ReaderPageDecodeKey,
        allocationId: Long,
        notify: Boolean = true,
    ) {
        val removed = synchronized(lock) {
            val entry = entries.entries.firstOrNull { (retainedKey, retainedEntry) ->
                retainedKey == key && retainedEntry.allocationId == allocationId
            }?.value ?: return
            entries.remove(key)
            usedBytes -= entry.asset.estimatedBytes
            entry.admitted
        }
        if (removed && notify) onAuthorityEviction()
    }
}

private fun PageDecodePurpose.toMemoryKind(): DesktopReaderImageMemoryKind = when (this) {
    PageDecodePurpose.FULL_PAGE -> DesktopReaderImageMemoryKind.FULL
    PageDecodePurpose.ANIMATION_FRAME -> DesktopReaderImageMemoryKind.FRAME
    PageDecodePurpose.REGION_TILE -> DesktopReaderImageMemoryKind.TILE
}
