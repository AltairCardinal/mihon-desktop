package mihon.domain.reader.storage

import mihon.domain.reader.session.EncodedPageRef

data class EncodedPageStoreEntry(
    val ref: EncodedPageRef,
    val byteCount: Long,
) {
    init {
        require(byteCount >= 0) { "byteCount must be non-negative" }
    }
}

data class EncodedPageStoreLifecycleResult(
    val availableRefs: Set<EncodedPageRef>,
    val missingRefs: Set<EncodedPageRef>,
    val evictedRefs: Set<EncodedPageRef>,
)

sealed interface EncodedPageStoreWriteResult {
    data class Stored(
        val entry: EncodedPageStoreEntry,
        val evictedRefs: Set<EncodedPageRef>,
    ) : EncodedPageStoreWriteResult

    data class RejectedQuota(
        val entry: EncodedPageStoreEntry,
        val maxBytes: Long,
    ) : EncodedPageStoreWriteResult
}

sealed interface EncodedPageEvictionResult {
    data class Evicted(val entry: EncodedPageStoreEntry) : EncodedPageEvictionResult
    data object Missing : EncodedPageEvictionResult
}

data class EncodedPageStoreDiagnostics(
    val refs: Set<EncodedPageRef>,
    val usedBytes: Long,
    val maxBytes: Long,
    val hitCount: Long,
    val missCount: Long,
    val writeCount: Long,
    val evictionCount: Long,
    val isSessionOpen: Boolean,
) {
    init {
        require(maxBytes >= 0) { "maxBytes must be non-negative" }
        require(usedBytes in 0..maxBytes) { "usedBytes must be within maxBytes" }
        require(hitCount >= 0 && missCount >= 0 && writeCount >= 0 && evictionCount >= 0) {
            "diagnostic counters must be non-negative"
        }
    }
}

interface ReaderEncodedPageStore {
    suspend fun beginSession(retainedRefs: Set<EncodedPageRef>): EncodedPageStoreLifecycleResult
    suspend fun contains(ref: EncodedPageRef): Boolean
    suspend fun store(
        ref: EncodedPageRef,
        writer: suspend () -> Long,
    ): EncodedPageStoreWriteResult
    suspend fun evict(ref: EncodedPageRef): EncodedPageEvictionResult
    fun diagnostics(): EncodedPageStoreDiagnostics
    fun endSession(): EncodedPageStoreDiagnostics
}

/** Platform-neutral LRU/quota index. Encoded bytes and physical existence remain platform-owned. */
class ByteBudgetEncodedPageStoreIndex(
    private val maxBytes: Long,
) {
    private val entries = LinkedHashMap<EncodedPageRef, EncodedPageStoreEntry>()
    private var usedBytes = 0L
    private var hitCount = 0L
    private var missCount = 0L
    private var writeCount = 0L
    private var evictionCount = 0L
    private var isSessionOpen = false

    init {
        require(maxBytes >= 0) { "maxBytes must be non-negative" }
    }

    fun beginSession(
        availableEntries: List<EncodedPageStoreEntry>,
        missingRefs: Set<EncodedPageRef>,
    ): EncodedPageStoreLifecycleResult {
        entries.clear()
        usedBytes = 0
        hitCount = availableEntries.size.toLong()
        missCount = missingRefs.size.toLong()
        writeCount = 0
        evictionCount = 0
        isSessionOpen = true
        availableEntries.forEach(::restore)
        val evictedRefs = trimToBudget()
        return EncodedPageStoreLifecycleResult(
            availableRefs = entries.keys.toSet(),
            missingRefs = missingRefs,
            evictedRefs = evictedRefs,
        )
    }

    fun recordLookup(
        ref: EncodedPageRef,
        exists: Boolean,
        byteCount: Long? = null,
    ): Boolean {
        ensureSession()
        if (!exists) {
            missCount++
            entries.remove(ref)?.let { usedBytes -= it.byteCount }
            return false
        }
        hitCount++
        val tracked = entries.remove(ref)
        when {
            tracked != null -> entries[ref] = tracked
            byteCount != null && byteCount >= 0 && usedBytes + byteCount <= maxBytes -> {
                val entry = EncodedPageStoreEntry(ref, byteCount)
                entries[ref] = entry
                usedBytes += byteCount
            }
        }
        return true
    }

    fun commit(entry: EncodedPageStoreEntry): EncodedPageStoreWriteResult {
        val plan = planCommit(entry)
        if (plan is EncodedPageStoreWriteResult.RejectedQuota) return plan
        entries.remove(entry.ref)?.let { usedBytes -= it.byteCount }
        entries[entry.ref] = entry
        usedBytes += entry.byteCount
        val evictedRefs = (plan as EncodedPageStoreWriteResult.Stored).evictedRefs
        evictedRefs.forEach { ref ->
            entries.remove(ref)?.let { evicted ->
                usedBytes -= evicted.byteCount
                evictionCount++
            }
        }
        writeCount++
        return plan
    }

    fun planCommit(entry: EncodedPageStoreEntry): EncodedPageStoreWriteResult {
        ensureSession()
        if (entry.byteCount > maxBytes) {
            return EncodedPageStoreWriteResult.RejectedQuota(entry, maxBytes)
        }
        val plannedEntries = LinkedHashMap(entries)
        var plannedBytes = usedBytes
        plannedEntries.remove(entry.ref)?.let { plannedBytes -= it.byteCount }
        plannedEntries[entry.ref] = entry
        plannedBytes += entry.byteCount
        val evictedRefs = mutableSetOf<EncodedPageRef>()
        while (plannedBytes > maxBytes && plannedEntries.isNotEmpty()) {
            val oldest = plannedEntries.keys.first()
            val removed = plannedEntries.remove(oldest) ?: continue
            plannedBytes -= removed.byteCount
            evictedRefs += removed.ref
        }
        return EncodedPageStoreWriteResult.Stored(entry, evictedRefs)
    }

    fun evict(ref: EncodedPageRef): EncodedPageEvictionResult {
        ensureSession()
        val entry = entries.remove(ref) ?: return EncodedPageEvictionResult.Missing
        usedBytes -= entry.byteCount
        evictionCount++
        return EncodedPageEvictionResult.Evicted(entry)
    }

    fun diagnostics(): EncodedPageStoreDiagnostics = EncodedPageStoreDiagnostics(
        refs = entries.keys.toSet(),
        usedBytes = usedBytes,
        maxBytes = maxBytes,
        hitCount = hitCount,
        missCount = missCount,
        writeCount = writeCount,
        evictionCount = evictionCount,
        isSessionOpen = isSessionOpen,
    )

    fun endSession(): EncodedPageStoreDiagnostics {
        isSessionOpen = false
        return diagnostics()
    }

    private fun restore(entry: EncodedPageStoreEntry) {
        entries.remove(entry.ref)?.let { usedBytes -= it.byteCount }
        entries[entry.ref] = entry
        usedBytes += entry.byteCount
    }

    private fun trimToBudget(): Set<EncodedPageRef> {
        val evicted = mutableSetOf<EncodedPageRef>()
        while (usedBytes > maxBytes && entries.isNotEmpty()) {
            val oldest = entries.keys.first()
            val removed = entries.remove(oldest) ?: continue
            usedBytes -= removed.byteCount
            evictionCount++
            evicted += removed.ref
        }
        return evicted
    }

    private fun ensureSession() {
        check(isSessionOpen) { "Encoded page store session is not open" }
    }
}
