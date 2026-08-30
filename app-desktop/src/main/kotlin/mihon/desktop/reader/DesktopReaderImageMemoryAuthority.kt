package mihon.desktop.reader

import java.util.concurrent.atomic.AtomicBoolean
import mihon.domain.reader.ReaderPageDecodeKey

internal enum class DesktopReaderImageMemoryKind {
    FULL,
    TILE,
    FRAME,
    DERIVED,
}

internal enum class DesktopReaderImageMemoryRetention {
    ACTIVE,
    IN_FLIGHT,
    CACHE,
}

internal data class DesktopReaderImageMemorySnapshot(
    val maxBytes: Long,
    val residentBytes: Long,
    val cacheBytes: Long,
    val pinnedBytes: Long,
    val inFlightBytes: Long,
    val overBudgetBytes: Long,
    val byKind: Map<DesktopReaderImageMemoryKind, Long>,
    val revision: Long,
)

internal interface DesktopReaderImageMemoryLease : AutoCloseable {
    fun retain(retention: DesktopReaderImageMemoryRetention): DesktopReaderImageMemoryLease
}

/**
 * Runtime-wide soft authority for unique native image allocations.
 *
 * Active and in-flight allocations are never forcibly released. Pressure removes globally oldest
 * cache retentions first; resident bytes can therefore exceed the soft budget while a draw or
 * presentation lease remains pinned.
 */
internal class DesktopReaderImageMemoryAuthority(
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) : AutoCloseable {
    private class Allocation(
        val id: Long,
        val kind: DesktopReaderImageMemoryKind,
        val estimatedBytes: Long,
        val disposer: () -> Unit,
        var activeRetentions: Int = 0,
        var inFlightRetentions: Int = 0,
        var cacheRetentions: Int = 0,
    )

    private class CacheRetention(
        val allocationId: Long,
        val onEvict: () -> Unit,
    )

    private data class ReleasedResource(
        val callback: (() -> Unit)? = null,
        val disposer: (() -> Unit)? = null,
    )

    private val lock = Any()
    private val allocations = mutableMapOf<Long, Allocation>()
    private val cacheRetentions = LinkedHashMap<ReaderPageDecodeKey, CacheRetention>(16, 0.75f, true)
    private var nextAllocationId = 1L
    private var revision = 0L
    private var closed = false

    init {
        require(maxBytes >= 0L) { "maxBytes must be non-negative" }
    }

    fun register(
        kind: DesktopReaderImageMemoryKind,
        estimatedBytes: Long,
        retention: DesktopReaderImageMemoryRetention,
        disposer: () -> Unit,
    ): DesktopReaderImageMemoryLease {
        require(estimatedBytes >= 0L) { "estimatedBytes must be non-negative" }
        require(retention != DesktopReaderImageMemoryRetention.CACHE) {
            "CACHE retention requires an identity and must use admitCache"
        }
        val result = synchronized(lock) {
            check(!closed) { "Desktop reader image memory authority is closed" }
            val allocation = Allocation(
                id = nextAllocationId++,
                kind = kind,
                estimatedBytes = estimatedBytes,
                disposer = disposer,
            )
            incrementRetentionLocked(allocation, retention)
            allocations[allocation.id] = allocation
            revision++
            AuthorityLease(this, allocation.id, retention) to rebalanceLocked()
        }
        try {
            releaseResources(result.second)
        } catch (error: Throwable) {
            try {
                result.first.close()
            } catch (closeError: Throwable) {
                if (closeError !== error) error.addSuppressed(closeError)
            }
            throw error
        }
        return result.first
    }

    fun admitCache(
        key: ReaderPageDecodeKey,
        lease: DesktopReaderImageMemoryLease,
        isCurrent: () -> Boolean = { true },
        onEvict: () -> Unit,
    ): Boolean {
        val allocationId = allocationIdFor(lease)
        val released = synchronized(lock) {
            if (closed || allocationId !in allocations || !isCurrent()) return false
            val resources = mutableListOf<ReleasedResource>()
            cacheRetentions.entries.firstOrNull { (retainedKey, _) -> retainedKey == key }
                ?.value
                ?.allocationId
                ?.let { previousAllocationId ->
                    removeCacheRetentionLocked(key, previousAllocationId, notify = false)?.let(resources::add)
                }
            val allocation = checkNotNull(allocations[allocationId])
            allocation.cacheRetentions++
            cacheRetentions[key] = CacheRetention(allocationId, onEvict)
            revision++
            resources += rebalanceLocked()
            resources
        }
        releaseResources(released)
        return synchronized(lock) { cacheRetentionMatchesLocked(key, allocationId) } && isCurrent()
    }

    fun retainCache(
        key: ReaderPageDecodeKey,
        allocationId: Long,
        isCurrent: () -> Boolean,
    ): DesktopReaderImageMemoryLease? = synchronized(lock) {
        if (closed) return null
        if (!cacheRetentionMatchesLocked(key, allocationId) || !isCurrent()) return null
        cacheRetentions[key]
        val allocation = allocations[allocationId] ?: return null
        incrementRetentionLocked(allocation, DesktopReaderImageMemoryRetention.ACTIVE)
        revision++
        AuthorityLease(this, allocationId, DesktopReaderImageMemoryRetention.ACTIVE)
    }

    fun touchCache(key: ReaderPageDecodeKey, allocationId: Long): Boolean = synchronized(lock) {
        if (closed || !cacheRetentionMatchesLocked(key, allocationId)) return false
        cacheRetentions[key]
        true
    }

    fun removeCache(key: ReaderPageDecodeKey, allocationId: Long): Boolean {
        val released = synchronized(lock) {
            removeCacheRetentionLocked(key, allocationId, notify = false) ?: return false
        }
        releaseResources(listOf(released))
        return true
    }

    fun snapshot(): DesktopReaderImageMemorySnapshot = synchronized(lock) {
        val residents = allocations.values
        val residentBytes = residents.sumOf(Allocation::estimatedBytes)
        val cacheBytes = residents.filter { it.cacheRetentions > 0 }.sumOf(Allocation::estimatedBytes)
        val pinnedBytes = residents.filter { it.activeRetentions > 0 }.sumOf(Allocation::estimatedBytes)
        val inFlightBytes = residents.filter { it.inFlightRetentions > 0 }.sumOf(Allocation::estimatedBytes)
        DesktopReaderImageMemorySnapshot(
            maxBytes = maxBytes,
            residentBytes = residentBytes,
            cacheBytes = cacheBytes,
            pinnedBytes = pinnedBytes,
            inFlightBytes = inFlightBytes,
            overBudgetBytes = (residentBytes - maxBytes).coerceAtLeast(0L),
            byKind = DesktopReaderImageMemoryKind.entries.associateWith { kind ->
                residents.filter { it.kind == kind }.sumOf(Allocation::estimatedBytes)
            },
            revision = revision,
        )
    }

    override fun close() {
        val released = synchronized(lock) {
            if (closed) return
            closed = true
            val resources = cacheRetentions.entries.toList().mapNotNull { (key, retention) ->
                removeCacheRetentionLocked(key, retention.allocationId, notify = true)
            }
            revision++
            resources
        }
        releaseResources(released)
    }

    private fun retain(
        allocationId: Long,
        retention: DesktopReaderImageMemoryRetention,
    ): DesktopReaderImageMemoryLease {
        require(retention != DesktopReaderImageMemoryRetention.CACHE) {
            "CACHE retention requires an identity and must use admitCache"
        }
        return synchronized(lock) {
            check(!closed) { "Desktop reader image memory authority is closed" }
            val allocation = checkNotNull(allocations[allocationId]) { "Image allocation is released" }
            incrementRetentionLocked(allocation, retention)
            revision++
            AuthorityLease(this, allocationId, retention)
        }
    }

    internal fun retainAllocation(
        allocationId: Long,
        retention: DesktopReaderImageMemoryRetention,
    ): DesktopReaderImageMemoryLease = retain(allocationId, retention)

    private fun release(allocationId: Long, retention: DesktopReaderImageMemoryRetention) {
        val released = synchronized(lock) {
            val allocation = allocations[allocationId] ?: return
            decrementRetentionLocked(allocation, retention)
            revision++
            buildList {
                releaseAllocationIfUnusedLocked(allocation)?.let(::add)
                addAll(rebalanceLocked())
            }
        }
        releaseResources(released)
    }

    private fun rebalanceLocked(): List<ReleasedResource> {
        val released = mutableListOf<ReleasedResource>()
        while (residentBytesLocked() > maxBytes) {
            val oldestKey = cacheRetentions.entries.firstOrNull { (_, retention) ->
                val allocation = allocations[retention.allocationId]
                allocation != null &&
                    allocation.activeRetentions == 0 &&
                    allocation.inFlightRetentions == 0
            }?.key ?: break
            val allocationId = checkNotNull(cacheRetentions[oldestKey]).allocationId
            removeCacheRetentionLocked(oldestKey, allocationId, notify = true)?.let(released::add)
        }
        return released
    }

    private fun removeCacheRetentionLocked(
        key: ReaderPageDecodeKey,
        allocationId: Long,
        notify: Boolean,
    ): ReleasedResource? {
        if (!cacheRetentionMatchesLocked(key, allocationId)) return null
        val retention = checkNotNull(cacheRetentions.remove(key))
        val allocation = allocations[retention.allocationId] ?: return null
        check(allocation.cacheRetentions > 0) { "Image cache retention underflow" }
        allocation.cacheRetentions--
        revision++
        val disposer = releaseAllocationIfUnusedLocked(allocation)?.disposer
        return ReleasedResource(
            callback = retention.onEvict.takeIf { notify },
            disposer = disposer,
        )
    }

    private fun releaseAllocationIfUnusedLocked(allocation: Allocation): ReleasedResource? {
        if (
            allocation.activeRetentions != 0 ||
            allocation.inFlightRetentions != 0 ||
            allocation.cacheRetentions != 0
        ) {
            return null
        }
        if (allocations.remove(allocation.id) == null) return null
        return ReleasedResource(disposer = allocation.disposer)
    }

    private fun residentBytesLocked(): Long = allocations.values.sumOf(Allocation::estimatedBytes)

    private fun cacheRetentionMatchesLocked(key: ReaderPageDecodeKey, allocationId: Long): Boolean =
        cacheRetentions.entries.any { (retainedKey, retention) ->
            retainedKey == key && retention.allocationId == allocationId
        }

    private fun incrementRetentionLocked(
        allocation: Allocation,
        retention: DesktopReaderImageMemoryRetention,
    ) {
        when (retention) {
            DesktopReaderImageMemoryRetention.ACTIVE -> allocation.activeRetentions++
            DesktopReaderImageMemoryRetention.IN_FLIGHT -> allocation.inFlightRetentions++
            DesktopReaderImageMemoryRetention.CACHE -> allocation.cacheRetentions++
        }
    }

    private fun decrementRetentionLocked(
        allocation: Allocation,
        retention: DesktopReaderImageMemoryRetention,
    ) {
        when (retention) {
            DesktopReaderImageMemoryRetention.ACTIVE -> {
                check(allocation.activeRetentions > 0) { "Active image retention underflow" }
                allocation.activeRetentions--
            }
            DesktopReaderImageMemoryRetention.IN_FLIGHT -> {
                check(allocation.inFlightRetentions > 0) { "In-flight image retention underflow" }
                allocation.inFlightRetentions--
            }
            DesktopReaderImageMemoryRetention.CACHE -> error("CACHE retention is released by key")
        }
    }

    internal fun allocationIdFor(lease: DesktopReaderImageMemoryLease): Long = when (lease) {
        is AuthorityLease -> {
            require(lease.owner === this) { "Image allocation belongs to another memory authority" }
            lease.allocationId
        }
        is DesktopReaderImageAssetLease -> lease.memoryAllocationId(this)
        else -> error("Unsupported image memory lease implementation")
    }

    private fun releaseResources(resources: List<ReleasedResource>) {
        runResourceActions(
            resources.flatMap { resource -> listOfNotNull(resource.callback, resource.disposer) },
        )
    }

    private class AuthorityLease(
        val owner: DesktopReaderImageMemoryAuthority,
        val allocationId: Long,
        private val retention: DesktopReaderImageMemoryRetention,
    ) : DesktopReaderImageMemoryLease {
        private val closed = AtomicBoolean()

        override fun retain(retention: DesktopReaderImageMemoryRetention): DesktopReaderImageMemoryLease {
            check(!closed.get()) { "Desktop reader image memory lease is closed" }
            return owner.retain(allocationId, retention)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) owner.release(allocationId, retention)
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 192L * 1024L * 1024L
    }
}

internal fun runResourceActions(actions: Iterable<() -> Unit>) {
    var failure: Throwable? = null
    actions.forEach { action ->
        try {
            action()
        } catch (error: Throwable) {
            val first = failure
            if (first == null) {
                failure = error
            } else if (error !== first) {
                first.addSuppressed(error)
            }
        }
    }
    failure?.let { throw it }
}
