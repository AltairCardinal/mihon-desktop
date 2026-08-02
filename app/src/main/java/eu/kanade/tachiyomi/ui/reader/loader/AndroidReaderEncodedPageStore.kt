package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.cache.ChapterCache
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.storage.ByteBudgetEncodedPageStoreIndex
import mihon.domain.reader.storage.EncodedPageEvictionResult
import mihon.domain.reader.storage.EncodedPageStoreDiagnostics
import mihon.domain.reader.storage.EncodedPageStoreEntry
import mihon.domain.reader.storage.EncodedPageStoreLifecycleResult
import mihon.domain.reader.storage.EncodedPageStoreWriteResult
import mihon.domain.reader.storage.ReaderEncodedPageStore
import java.io.IOException

internal class AndroidReaderEncodedPageStore(
    private val chapterCache: ChapterCache,
    private val maxBytes: Long = ChapterCache.DEFAULT_MAX_SIZE_BYTES,
) : ReaderEncodedPageStore {
    private val stateLock = Any()
    private val index = ByteBudgetEncodedPageStoreIndex(maxBytes)
    private var sessionOpen = false
    private var sessionEnded = false
    private var sessionEpoch = 0L
    private var inFlightWrites = 0

    override suspend fun beginSession(retainedRefs: Set<EncodedPageRef>): EncodedPageStoreLifecycleResult =
        synchronized(stateLock) {
            check(!sessionEnded) { "Encoded page store session has ended" }
            check(!sessionOpen) { "Encoded page store session is already open" }
            check(inFlightWrites == 0) { "Cannot restart encoded page store while writes are active" }
            val available = mutableListOf<EncodedPageStoreEntry>()
            val missing = mutableSetOf<EncodedPageRef>()
            retainedRefs.forEach { ref ->
                if (chapterCache.isImageInCache(ref.value)) {
                    available += EncodedPageStoreEntry(ref, chapterCache.getImageFile(ref.value).length())
                } else {
                    missing += ref
                }
            }
            val preview = ByteBudgetEncodedPageStoreIndex(maxBytes).beginSession(available, missing)
            preview.evictedRefs.forEach(::removePhysicalOrThrow)
            val result = index.beginSession(
                availableEntries = available,
                missingRefs = missing,
            )
            check(result.evictedRefs == preview.evictedRefs) {
                "Encoded page startup eviction plan changed after physical deletion"
            }
            sessionEpoch++
            sessionOpen = true
            result
        }

    override suspend fun contains(ref: EncodedPageRef): Boolean = synchronized(stateLock) {
        ensureSessionLocked()
        val exists = chapterCache.isImageInCache(ref.value)
        index.recordLookup(
            ref = ref,
            exists = exists,
            byteCount = if (exists) chapterCache.getImageFile(ref.value).length() else null,
        )
    }

    override suspend fun store(
        ref: EncodedPageRef,
        writer: suspend () -> Long,
    ): EncodedPageStoreWriteResult {
        val writeEpoch = synchronized(stateLock) {
            ensureSessionLocked()
            inFlightWrites++
            sessionEpoch
        }
        val entry = try {
            EncodedPageStoreEntry(ref, writer())
        } catch (error: Throwable) {
            synchronized(stateLock) {
                inFlightWrites--
                cleanupAfterFailedWrite(ref, error)
            }
            throw error
        }
        return synchronized(stateLock) {
            inFlightWrites--
            if (!sessionOpen || sessionEnded || sessionEpoch != writeEpoch) {
                removePhysicalOrThrow(ref)
                error("Encoded page store session changed during write")
            }
            if (!chapterCache.isImageInCache(ref.value)) {
                val error = IOException("Encoded page writer did not commit a physical entry: ${ref.value}")
                cleanupAfterFailedWrite(ref, error)
                throw error
            }
            val physicalEntry = entry.copy(byteCount = chapterCache.getImageFile(ref.value).length())
            val physicallyEvictedRefs = reconcilePhysicallyMissingRefs()
            when (val plan = index.planCommit(physicalEntry)) {
                is EncodedPageStoreWriteResult.Stored -> {
                    try {
                        plan.evictedRefs.forEach { evicted ->
                            removePhysicalOrThrow(evicted)
                            check(index.evict(evicted) is EncodedPageEvictionResult.Evicted) {
                                "Planned encoded page eviction was missing from the logical index: $evicted"
                            }
                        }
                        val committed = index.commit(physicalEntry)
                        check(committed is EncodedPageStoreWriteResult.Stored && committed.evictedRefs.isEmpty()) {
                            "Encoded page commit changed after physical evictions"
                        }
                        plan.copy(evictedRefs = physicallyEvictedRefs + plan.evictedRefs)
                    } catch (error: Throwable) {
                        cleanupAfterFailedWrite(ref, error)
                        throw error
                    }
                }
                is EncodedPageStoreWriteResult.RejectedQuota -> {
                    removePhysicalOrThrow(ref)
                    if (ref in index.diagnostics().refs) index.evict(ref)
                    plan
                }
            }
        }
    }

    override suspend fun evict(ref: EncodedPageRef): EncodedPageEvictionResult = synchronized(stateLock) {
        ensureSessionLocked()
        val physicalEntry = if (chapterCache.isImageInCache(ref.value)) {
            EncodedPageStoreEntry(ref, chapterCache.getImageFile(ref.value).length())
        } else {
            null
        }
        if (physicalEntry != null) {
            index.recordLookup(ref, exists = true, byteCount = physicalEntry.byteCount)
            removePhysicalOrThrow(ref)
        }
        val result = index.evict(ref)
        result
    }

    override fun diagnostics(): EncodedPageStoreDiagnostics = synchronized(stateLock) { index.diagnostics() }

    override fun endSession(): EncodedPageStoreDiagnostics = synchronized(stateLock) {
        sessionOpen = false
        sessionEnded = true
        sessionEpoch++
        index.endSession()
    }

    private fun ensureSessionLocked() {
        check(!sessionEnded) { "Encoded page store session has ended" }
        if (!sessionOpen) {
            index.beginSession(emptyList(), emptySet())
            sessionEpoch++
            sessionOpen = true
        }
    }

    private fun cleanupAfterFailedWrite(ref: EncodedPageRef, primaryError: Throwable) {
        try {
            if (chapterCache.isImageInCache(ref.value) || chapterCache.getImageFile(ref.value).exists()) {
                removePhysicalOrThrow(ref)
            }
            if (sessionOpen && ref in index.diagnostics().refs) index.evict(ref)
        } catch (cleanupError: Throwable) {
            primaryError.addSuppressed(cleanupError)
        }
    }

    private fun reconcilePhysicallyMissingRefs(): Set<EncodedPageRef> = buildSet {
        index.diagnostics().refs.forEach { trackedRef ->
            if (!chapterCache.isImageInCache(trackedRef.value) &&
                index.evict(trackedRef) is EncodedPageEvictionResult.Evicted
            ) {
                add(trackedRef)
            }
        }
    }

    private fun removePhysicalOrThrow(ref: EncodedPageRef) {
        chapterCache.removeImageFromCache(ref.value)
        if (chapterCache.isImageInCache(ref.value) || chapterCache.getImageFile(ref.value).exists()) {
            throw IOException("Encoded page remains after physical eviction: ${ref.value}")
        }
    }
}
