package mihon.domain.reader.content

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import mihon.domain.reader.session.EncodedPageRef
import mihon.domain.reader.session.ReaderEncodedPageProvenance
import mihon.domain.reader.session.ReaderPageId

/** Stable content identity used across platform open, decode, cache, and presentation adapters. */
data class ReaderPageContentOpenRequest(
    val pageId: ReaderPageId,
    val generation: Long,
    val encodedPageRef: EncodedPageRef,
    val attemptGeneration: Long = 0L,
    val encodedPageProvenance: ReaderEncodedPageProvenance? = null,
) {
    init {
        require(generation >= 0L) { "generation must be non-negative" }
        require(attemptGeneration >= 0L) { "attemptGeneration must be non-negative" }
    }

    /** Returns the next Retry identity while retaining the same logical page, session, and encoded ref. */
    fun nextAttempt(): ReaderPageContentOpenRequest {
        check(attemptGeneration < Long.MAX_VALUE) { "Reader page content attempt generation is exhausted" }
        return copy(attemptGeneration = attemptGeneration + 1L)
    }
}

/** Platform boundary that turns an opaque encoded-page reference into readable content. */
fun interface ReaderPageContentOpenPort<T> {
    suspend fun open(request: ReaderPageContentOpenRequest): T?
}

class ReaderPageContentLease<T> internal constructor(
    val request: ReaderPageContentOpenRequest,
    val content: T,
    private val release: () -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    override fun close() {
        val shouldRelease = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                true
            }
        }
        if (shouldRelease) release()
    }
}

data class ReaderPageContentOpenSnapshot(
    val activeLeaseCounts: Map<ReaderPageContentOpenRequest, Int>,
)

/**
 * Owns one in-flight/completed platform open while callers hold overlapping leases for the same content identity.
 * Platform resources stay behind [ReaderPageContentOpenPort]; shared reader code only sees opaque references.
 */
class ReaderPageContentOpenCoordinator<T>(
    private val scope: CoroutineScope,
    private val port: ReaderPageContentOpenPort<T>,
) : AutoCloseable {
    private class Entry<T>(
        val content: Deferred<T?>,
        var pendingAcquires: Int = 0,
        var activeLeases: Int = 0,
    )

    private val lock = Any()
    private val entries = mutableMapOf<ReaderPageContentOpenRequest, Entry<T>>()
    private var closed = false

    suspend fun acquire(request: ReaderPageContentOpenRequest): ReaderPageContentLease<T>? {
        val entry = synchronized(lock) {
            check(!closed) { "Reader page content owner is closed" }
            entries.getOrPut(request) {
                createEntry(request)
            }.also {
                it.pendingAcquires++
                it.content.start()
            }
        }
        val content = try {
            entry.content.await()
        } catch (error: Throwable) {
            releasePending(request, entry)
            throw error
        }
        if (content == null) {
            releasePending(request, entry)
            return null
        }
        val acquired = synchronized(lock) {
            entry.pendingAcquires--
            if (closed || entries[request] !== entry) {
                removeIfUnused(request, entry)
                false
            } else {
                entry.activeLeases++
                true
            }
        }
        if (!acquired) throw CancellationException("Reader page content owner closed during acquire")
        return ReaderPageContentLease(request, content) { releaseLease(request, entry) }
    }

    fun snapshot(): ReaderPageContentOpenSnapshot = synchronized(lock) {
        ReaderPageContentOpenSnapshot(
            entries.mapNotNull { (request, entry) ->
                entry.activeLeases.takeIf { it > 0 }?.let { request to it }
            }.toMap(),
        )
    }

    override fun close() {
        val activeEntries = synchronized(lock) {
            if (closed) return
            closed = true
            entries.values.toSet().also { entries.clear() }
        }
        activeEntries.forEach { it.content.cancel() }
    }

    private fun releasePending(request: ReaderPageContentOpenRequest, entry: Entry<T>) = synchronized(lock) {
        entry.pendingAcquires--
        removeIfUnused(request, entry)
    }

    private fun releaseLease(request: ReaderPageContentOpenRequest, entry: Entry<T>) = synchronized(lock) {
        if (entry.activeLeases > 0) entry.activeLeases--
        removeIfUnused(request, entry)
    }

    private fun removeIfUnused(request: ReaderPageContentOpenRequest, entry: Entry<T>) {
        if (
            entry.content.isCompleted &&
            entry.pendingAcquires == 0 &&
            entry.activeLeases == 0 &&
            entries[request] === entry
        ) {
            entries.remove(request)
        }
    }

    private fun createEntry(request: ReaderPageContentOpenRequest): Entry<T> {
        lateinit var entry: Entry<T>
        entry = Entry(scope.async(start = CoroutineStart.LAZY) { port.open(request) })
        entry.content.invokeOnCompletion {
            synchronized(lock) { removeIfUnused(request, entry) }
        }
        return entry
    }
}
