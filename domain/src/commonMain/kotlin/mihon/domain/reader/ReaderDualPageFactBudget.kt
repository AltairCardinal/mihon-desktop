package mihon.domain.reader

import mihon.domain.reader.session.ReaderPageId

/** One explicit location's optional requests. All state changes use the existing presentation owner. */
class ReaderDualPageFactBudget {
    private val lock = Any()
    private val attempted = mutableSetOf<ReaderPageId>()
    private val active = mutableSetOf<ReaderPageId>()
    private var startedAt: Long? = null
    private var newlyReadBytes = 0L
    private var closed = false
    private val meteredPages = mutableSetOf<ReaderPageId>()

    fun reserve(candidates: List<ReaderPageId>, nowMillis: Long): List<ReaderPageId> = synchronized(lock) {
        require(nowMillis >= 0)
        if (closed) return@synchronized emptyList()
        val start = startedAt ?: nowMillis.also { startedAt = it }
        if (nowMillis - start >= ReaderDualPageFactLimits.MAX_WAIT_MILLIS ||
            newlyReadBytes >= ReaderDualPageFactLimits.MAX_NEW_BYTES
        ) {
            return@synchronized emptyList()
        }
        val capacity = minOf(
            ReaderDualPageFactLimits.MAX_CONCURRENT_REQUESTS - active.size,
            ReaderDualPageFactLimits.MAX_ADDITIONAL_PAGES - attempted.size,
        ).coerceAtLeast(0)
        candidates.distinct().filter { it !in attempted }.take(capacity).also {
            attempted.addAll(it)
            active.addAll(it)
        }
    }

    fun complete(pageId: ReaderPageId, newlyReadBytes: Long, cacheHit: Boolean) = synchronized(lock) {
        require(newlyReadBytes >= 0)
        if (!active.remove(pageId)) return@synchronized
        if (!cacheHit && pageId !in meteredPages) {
            this.newlyReadBytes = minOf(
                ReaderDualPageFactLimits.MAX_NEW_BYTES,
                this.newlyReadBytes + newlyReadBytes.coerceAtMost(ReaderDualPageFactLimits.MAX_NEW_BYTES),
            )
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        active.clear()
    }

    /** Reserve before calling the native source, so even simultaneous streams cannot overshoot. */
    fun reserveRead(pageId: ReaderPageId, requestedBytes: Long, nowMillis: Long): Long = synchronized(lock) {
        require(requestedBytes >= 0)
        if (closed || pageId !in active || remainingWaitMillis(nowMillis) == 0L) return@synchronized 0L
        val granted = minOf(requestedBytes, ReaderDualPageFactLimits.MAX_NEW_BYTES - newlyReadBytes)
        newlyReadBytes += granted
        meteredPages += pageId
        granted
    }

    fun returnUnreadBytes(pageId: ReaderPageId, byteCount: Long) = synchronized(lock) {
        require(byteCount >= 0)
        if (pageId in meteredPages) newlyReadBytes = (newlyReadBytes - byteCount).coerceAtLeast(0)
    }

    fun remainingWaitMillis(nowMillis: Long): Long = synchronized(lock) {
        if (closed) return@synchronized 0L
        val elapsed = startedAt?.let { (nowMillis - it).coerceAtLeast(0) } ?: 0
        (ReaderDualPageFactLimits.MAX_WAIT_MILLIS - elapsed).coerceAtLeast(0)
    }

    fun canRead(pageId: ReaderPageId, nowMillis: Long): Boolean = synchronized(lock) {
        !closed && pageId in active && newlyReadBytes < ReaderDualPageFactLimits.MAX_NEW_BYTES &&
            remainingWaitMillis(nowMillis) > 0
    }
}
