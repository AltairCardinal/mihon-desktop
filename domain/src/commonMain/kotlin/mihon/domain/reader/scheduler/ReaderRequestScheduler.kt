package mihon.domain.reader.scheduler

import mihon.domain.reader.session.ReaderChapterId
import mihon.domain.reader.session.ReaderPageId

enum class ReaderRequestPriority {
    P0_INTERACTIVE,
    P1_NEARBY,
    P2_CURRENT_BACKGROUND,
    P3_ADJACENT_METADATA,
    P4_ADJACENT_BACKGROUND,
}

enum class ReaderRequestKind(
    val priority: ReaderRequestPriority,
    internal val tieBreakRank: Int = 0,
) {
    EXPLICIT_RETRY(ReaderRequestPriority.P0_INTERACTIVE, tieBreakRank = 0),
    INTERACTIVE_VISIBLE(ReaderRequestPriority.P0_INTERACTIVE, tieBreakRank = 1),
    NEARBY(ReaderRequestPriority.P1_NEARBY),
    CURRENT_BACKGROUND(ReaderRequestPriority.P2_CURRENT_BACKGROUND),
    ADJACENT_METADATA(ReaderRequestPriority.P3_ADJACENT_METADATA),
    ADJACENT_BACKGROUND(ReaderRequestPriority.P4_ADJACENT_BACKGROUND),
}

data class ReaderRequestKey(
    val pageId: ReaderPageId,
    val generation: Long,
    val requestId: Long,
) {
    init {
        require(generation >= 0) { "generation must be non-negative" }
        require(requestId >= 0) { "requestId must be non-negative" }
    }

    val pageIndex: Int get() = pageId.sourcePageIndex
    val chapterId: ReaderChapterId get() = pageId.chapterId
}

data class ReaderScheduledRequest(
    val jobKey: ReaderRequestKey,
    val kind: ReaderRequestKind,
    val forceRefresh: Boolean = false,
) {
    val pageId: ReaderPageId get() = jobKey.pageId
    val pageIndex: Int get() = jobKey.pageIndex
    val generation: Long get() = jobKey.generation
    val priority: ReaderRequestPriority get() = kind.priority
}

data class ReaderSchedulerPolicy(
    val nearbyForward: Int,
    val nearbyBackward: Int = 0,
    val maxConcurrentRequests: Int,
) {
    init {
        require(nearbyForward >= 0) { "nearbyForward must be non-negative" }
        require(nearbyBackward >= 0) { "nearbyBackward must be non-negative" }
        require(maxConcurrentRequests > 0) { "maxConcurrentRequests must be positive" }
    }

    companion object {
        fun originalMihon() = ReaderSchedulerPolicy(
            nearbyForward = 4,
            nearbyBackward = 0,
            maxConcurrentRequests = 1,
        )
    }
}

data class ReaderSchedulePlan(
    val generation: Long,
    val requests: List<ReaderScheduledRequest>,
    val keepPageIds: Set<ReaderPageId>,
    val cancelRequests: Set<ReaderRequestKey>,
    val discardRequests: Set<ReaderRequestKey>,
    val evictPageIds: Set<ReaderPageId>,
) {
    val keepPageIndices: Set<Int> get() = keepPageIds.mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex)
    val evictPageIndices: Set<Int> get() = evictPageIds.mapTo(mutableSetOf(), ReaderPageId::sourcePageIndex)
}

data class ReaderEnqueueResult(
    val request: ReaderScheduledRequest?,
    val cancelRequests: Set<ReaderRequestKey> = emptySet(),
    val replacedRequests: Set<ReaderRequestKey> = emptySet(),
) {
    val scheduled: Boolean get() = request != null
}

data class ReaderSchedulerSnapshot(
    val generation: Long,
    val pendingRequests: List<ReaderScheduledRequest>,
    val activeRequests: List<ReaderScheduledRequest>,
    val maxConcurrentRequests: Int,
)

/**
 * Canonical reader request policy and queue state.
 *
 * Platforms own coroutine/job execution, but request ordering, bounded concurrency, generation
 * replacement, preemption, retry force, and stale-result acceptance are decided here.
 */
class ReaderRequestScheduler(
    private val policy: ReaderSchedulerPolicy,
) {
    private data class Entry(
        val request: ReaderScheduledRequest,
        val sequence: Long,
    )

    private val pending = mutableMapOf<ReaderRequestKey, Entry>()
    private val active = mutableMapOf<ReaderRequestKey, Entry>()
    private val currentRequestByPage = mutableMapOf<ReaderPageId, ReaderRequestKey>()
    private var generation = 0L
    private var nextRequestId = 0L
    private var nextSequence = 0L
    private var previousKeep: Set<ReaderPageId> = emptySet()
    private var lastChapterId: ReaderChapterId? = null
    private var lastVisiblePages: List<Int> = emptyList()
    private var lastAnchorPage = 0
    private var lastPageCount = 0

    fun moveTo(
        chapterId: ReaderChapterId,
        currentPage: Int,
        pageCount: Int,
    ): ReaderSchedulePlan = moveTo(
        chapterId = chapterId,
        visiblePageIndices = listOf(currentPage),
        anchorPage = currentPage,
        pageCount = pageCount,
    )

    fun moveTo(
        chapterId: ReaderChapterId,
        visiblePageIndices: List<Int>,
        anchorPage: Int,
        pageCount: Int,
    ): ReaderSchedulePlan = replaceWindow(
        chapterId = chapterId,
        visiblePageIndices = visiblePageIndices,
        anchorPage = anchorPage,
        pageCount = pageCount,
        retryPageId = null,
    )

    fun retry(pageId: ReaderPageId, pageCount: Int = lastPageCount): ReaderSchedulePlan {
        val pageIndex = pageId.sourcePageIndex
        val effectivePageCount = maxOf(pageCount, pageIndex + 1)
        val isCurrentChapter = pageId.chapterId == lastChapterId && lastPageCount > 0
        val visiblePages = lastVisiblePages.takeIf { isCurrentChapter && it.isNotEmpty() } ?: listOf(pageIndex)
        val anchorPage = lastAnchorPage.takeIf { isCurrentChapter } ?: pageIndex
        return replaceWindow(
            chapterId = pageId.chapterId,
            visiblePageIndices = visiblePages,
            anchorPage = anchorPage,
            pageCount = effectivePageCount,
            retryPageId = pageId,
        )
    }

    fun enqueue(
        pageId: ReaderPageId,
        kind: ReaderRequestKind,
        forceRefresh: Boolean = false,
    ): ReaderEnqueueResult {
        val replaced = mutableSetOf<ReaderRequestKey>()
        val cancelled = mutableSetOf<ReaderRequestKey>()
        val existingKey = currentRequestByPage[pageId]
        if (existingKey != null) {
            pending[existingKey]?.let { existing ->
                val shouldReplace = compareKinds(kind, existing.request.kind) < 0 ||
                    (forceRefresh && !existing.request.forceRefresh)
                if (!shouldReplace) return ReaderEnqueueResult(request = null)
                pending.remove(existingKey)
                currentRequestByPage.remove(pageId, existingKey)
                replaced += existingKey
            }
            active[existingKey]?.let { existing ->
                val shouldReplace = compareKinds(kind, existing.request.kind) < 0 ||
                    (forceRefresh && !existing.request.forceRefresh)
                if (!shouldReplace) return ReaderEnqueueResult(request = null)
                active.remove(existingKey)
                currentRequestByPage.remove(pageId, existingKey)
                cancelled += existingKey
            }
        }

        if (active.size >= policy.maxConcurrentRequests) {
            val worstActive = active.values.maxWithOrNull(entryComparator)
            if (worstActive != null && compareKinds(kind, worstActive.request.kind) < 0) {
                active.remove(worstActive.request.jobKey)
                currentRequestByPage.remove(worstActive.request.pageId, worstActive.request.jobKey)
                cancelled += worstActive.request.jobKey
                addPending(
                    pageId = worstActive.request.pageId,
                    kind = worstActive.request.kind,
                    forceRefresh = worstActive.request.forceRefresh,
                )
            }
        }

        val request = addPending(pageId, kind, forceRefresh)
        return ReaderEnqueueResult(request, cancelled, replaced)
    }

    fun pollNext(): ReaderScheduledRequest? {
        if (active.size >= policy.maxConcurrentRequests) return null
        val next = pending.values.minWithOrNull(entryComparator) ?: return null
        pending.remove(next.request.jobKey)
        active[next.request.jobKey] = next
        return next.request
    }

    fun complete(jobKey: ReaderRequestKey): Boolean {
        val removed = active.remove(jobKey) ?: return false
        currentRequestByPage.remove(removed.request.pageId, jobKey)
        return true
    }

    fun cancelPending(jobKey: ReaderRequestKey): Boolean {
        val removed = pending.remove(jobKey) ?: return false
        currentRequestByPage.remove(removed.request.pageId, jobKey)
        return true
    }

    fun accepts(jobKey: ReaderRequestKey): Boolean =
        jobKey.generation == generation && active.containsKey(jobKey)

    fun acceptsGeneration(generation: Long): Boolean = generation == this.generation

    fun hasCurrentReplacement(pageId: ReaderPageId, excluding: ReaderRequestKey): Boolean {
        val current = currentRequestByPage[pageId] ?: return false
        return current != excluding && current.generation == generation &&
            (pending.containsKey(current) || active.containsKey(current))
    }

    fun snapshot(): ReaderSchedulerSnapshot = ReaderSchedulerSnapshot(
        generation = generation,
        pendingRequests = pending.values.sortedWith(entryComparator).map(Entry::request),
        activeRequests = active.values.sortedBy(Entry::sequence).map(Entry::request),
        maxConcurrentRequests = policy.maxConcurrentRequests,
    )

    private fun replaceWindow(
        chapterId: ReaderChapterId,
        visiblePageIndices: List<Int>,
        anchorPage: Int,
        pageCount: Int,
        retryPageId: ReaderPageId?,
    ): ReaderSchedulePlan {
        val cancelRequests = active.keys.toSet()
        val discardRequests = pending.keys.toSet()
        val evictPageIndices = previousKeep
        pending.clear()
        active.clear()
        currentRequestByPage.clear()
        generation++

        if (pageCount <= 0) {
            previousKeep = emptySet()
            lastChapterId = chapterId
            lastVisiblePages = emptyList()
            lastAnchorPage = 0
            lastPageCount = 0
            return ReaderSchedulePlan(
                generation,
                emptyList(),
                emptySet(),
                cancelRequests,
                discardRequests,
                evictPageIndices,
            )
        }

        val anchor = anchorPage.coerceIn(0, pageCount - 1)
        val visible = visiblePageIndices.map { it.coerceIn(0, pageCount - 1) }.distinct()
            .ifEmpty { listOf(anchor) }
        val start = (anchor - policy.nearbyBackward).coerceAtLeast(0)
        val end = (anchor + policy.nearbyForward).coerceAtMost(pageCount - 1)
        val keep = ((start..end).toSet() + visible + listOfNotNull(retryPageId?.sourcePageIndex))
            .filter { it in 0 until pageCount }
            .mapTo(mutableSetOf()) { ReaderPageId(chapterId, it) }
        val requestedPages = mutableSetOf<ReaderPageId>()
        val requests = buildList {
            retryPageId?.takeIf {
                it.chapterId == chapterId && it.sourcePageIndex in 0 until pageCount
            }?.let { retry ->
                add(addPending(retry, ReaderRequestKind.EXPLICIT_RETRY, forceRefresh = true))
                requestedPages += retry
            }
            visible.forEach { pageIndex ->
                val pageId = ReaderPageId(chapterId, pageIndex)
                if (requestedPages.add(pageId)) {
                    add(addPending(pageId, ReaderRequestKind.INTERACTIVE_VISIBLE, forceRefresh = false))
                }
            }
            for (pageIndex in (anchor + 1)..end) {
                val pageId = ReaderPageId(chapterId, pageIndex)
                if (requestedPages.add(pageId)) {
                    add(addPending(pageId, ReaderRequestKind.NEARBY, forceRefresh = false))
                }
            }
            for (pageIndex in (anchor - 1) downTo start) {
                val pageId = ReaderPageId(chapterId, pageIndex)
                if (requestedPages.add(pageId)) {
                    add(addPending(pageId, ReaderRequestKind.NEARBY, forceRefresh = false))
                }
            }
        }
        previousKeep = keep
        lastChapterId = chapterId
        lastVisiblePages = visible
        lastAnchorPage = anchor
        lastPageCount = pageCount
        return ReaderSchedulePlan(
            generation,
            requests,
            keep,
            cancelRequests,
            discardRequests,
            evictPageIndices,
        )
    }

    private fun addPending(
        pageId: ReaderPageId,
        kind: ReaderRequestKind,
        forceRefresh: Boolean,
    ): ReaderScheduledRequest {
        val request = ReaderScheduledRequest(
            jobKey = ReaderRequestKey(pageId, generation, nextRequestId++),
            kind = kind,
            forceRefresh = forceRefresh,
        )
        val entry = Entry(request, nextSequence++)
        pending[request.jobKey] = entry
        currentRequestByPage[pageId] = request.jobKey
        return request
    }

    private fun compareKinds(first: ReaderRequestKind, second: ReaderRequestKind): Int {
        val priority = first.priority.ordinal.compareTo(second.priority.ordinal)
        return if (priority != 0) priority else first.tieBreakRank.compareTo(second.tieBreakRank)
    }

    private val entryComparator = Comparator<Entry> { first, second ->
        val kind = compareKinds(first.request.kind, second.request.kind)
        if (kind != 0) kind else first.sequence.compareTo(second.sequence)
    }
}
