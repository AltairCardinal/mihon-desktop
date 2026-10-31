package mihon.domain.reader

import mihon.domain.reader.session.ReaderPageId

/**
 * The purpose a page was admitted for.
 *
 * `REQUIRED` is the canonical certification's own demand, `OPTIONAL` is the pairing's
 * own preparation of the visible pair and `SPECULATIVE` is the publisher's guess about
 * what the scan will need next.
 */
enum class ReaderDualPageFactRole {
    REQUIRED,
    OPTIONAL,
    SPECULATIVE,
}

/** Shared physical accounting window across generation/rebind budget shells. */
internal class FactBudgetWindow(
    val lock: Any = Any(),
    var startedAt: Long? = null,
    var newlyReadBytes: Long = 0L,
)

/** One explicit location's optional requests. All state changes use the existing presentation owner. */
class ReaderDualPageFactBudget internal constructor(
    private val window: FactBudgetWindow,
) {
    constructor() : this(FactBudgetWindow())

    private val lock: Any = window.lock
    private val attempted = mutableSetOf<ReaderPageId>()
    private val optionalAttempted = mutableSetOf<ReaderPageId>()
    private val requiredAttempted = mutableSetOf<ReaderPageId>()
    private val active = mutableSetOf<ReaderPageId>()
    private val admissionRoles = mutableMapOf<ReaderPageId, ReaderDualPageFactRole>()
    private var startedAt: Long? = window.startedAt
    private var newlyReadBytes: Long = window.newlyReadBytes
    private var closed = false
    private val meteredPages = mutableSetOf<ReaderPageId>()
    private val meteredReservedBytes = mutableMapOf<ReaderPageId, Long>()

    fun reserve(
        candidates: List<ReaderPageId>,
        nowMillis: Long,
        required: Boolean = false,
        speculative: Boolean = false,
    ): List<ReaderPageId> = synchronized(window.lock) {
        require(nowMillis >= 0)
        if (closed) return@synchronized emptyList()
        val start = window.startedAt ?: nowMillis.also {
            window.startedAt = it
            startedAt = it
        }
        if (nowMillis - start >= ReaderDualPageFactLimits.MAX_WAIT_MILLIS ||
            window.newlyReadBytes >= ReaderDualPageFactLimits.MAX_NEW_BYTES
        ) {
            return@synchronized emptyList()
        }
        val maxConcurrency = if (required) {
            ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS
        } else {
            ReaderDualPageFactLimits.MAX_CONCURRENT_REQUESTS
        }
        val remainingConcurrency = (maxConcurrency - active.size).coerceAtLeast(0)
        if (remainingConcurrency == 0) return@synchronized emptyList()

        if (required) {
            val capacity = remainingConcurrency
            candidates.distinct().filter {
                it !in active && if (speculative) it !in attempted else it !in requiredAttempted
            }.take(capacity).also {
                if (!speculative) requiredAttempted.addAll(it)
                attempted.addAll(it)
                active.addAll(it)
                it.forEach { pageId ->
                    recordAdmission(
                        pageId,
                        if (speculative) ReaderDualPageFactRole.SPECULATIVE else ReaderDualPageFactRole.REQUIRED,
                    )
                }
            }
        } else {
            val capacity = minOf(
                remainingConcurrency,
                (ReaderDualPageFactLimits.MAX_ADDITIONAL_PAGES - optionalAttempted.size).coerceAtLeast(0),
            )
            candidates.distinct().filter { it !in attempted && it !in active }.take(capacity).also {
                optionalAttempted.addAll(it)
                attempted.addAll(it)
                active.addAll(it)
                it.forEach { pageId -> recordAdmission(pageId, ReaderDualPageFactRole.OPTIONAL) }
            }
        }
    }

    /** One admitted capability and the demand class that won the capacity. */
    data class ReservedCapability(val pageId: ReaderPageId, val speculative: Boolean)

    /**
     * Real and speculative demand share one capacity allocation.
     *
     * Two sequential `reserve` calls share `remainingConcurrency`, so real demand fills the shared
     * pool first and speculation is admitted zero times per round, collapsing batch prefetch into
     * one page per round. Here real demand is admitted first and speculation may only consume the
     * capacity real demand left over, inside a single pass over the shared limits.
     */
    fun reserveClassified(
        real: List<ReaderPageId>,
        speculative: List<ReaderPageId>,
        nowMillis: Long,
    ): List<ReservedCapability> = synchronized(window.lock) {
        require(nowMillis >= 0)
        if (closed) return@synchronized emptyList()
        val start = window.startedAt ?: nowMillis.also {
            window.startedAt = it
            startedAt = it
        }
        if (nowMillis - start >= ReaderDualPageFactLimits.MAX_WAIT_MILLIS ||
            window.newlyReadBytes >= ReaderDualPageFactLimits.MAX_NEW_BYTES
        ) {
            return@synchronized emptyList()
        }
        var capacity = (ReaderDualPageFactLimits.REQUIRED_CONCURRENT_REQUESTS - active.size).coerceAtLeast(0)
        if (capacity == 0) return@synchronized emptyList()

        val admitted = mutableListOf<ReservedCapability>()
        val claimed = mutableSetOf<ReaderPageId>()
        real.distinct().filter { it !in active && it !in claimed && it !in requiredAttempted }
            .take(capacity)
            .forEach { pageId ->
                requiredAttempted += pageId
                attempted += pageId
                active += pageId
                claimed += pageId
                capacity--
                recordAdmission(pageId, ReaderDualPageFactRole.REQUIRED)
                admitted += ReservedCapability(pageId, speculative = false)
            }
        if (capacity > 0) {
            // A speculative exit consumes the one speculative attempt, but it must leave the
            // requiredAttempted slot available for one real recovery. Once real demand is admitted,
            // requiredAttempted prevents it from being retried indefinitely. In-place promotion of
            // work that is already running remains the pipeline's `promotePairing` path.
            speculative.distinct()
                .filter { it !in active && it !in claimed && it !in attempted }
                .take(capacity)
                .forEach { pageId ->
                    attempted += pageId
                    active += pageId
                    claimed += pageId
                    capacity--
                    recordAdmission(pageId, ReaderDualPageFactRole.SPECULATIVE)
                    admitted += ReservedCapability(pageId, speculative = true)
                }
        }
        admitted
    }

    /**
     * Releases one required attempt without recording a failure.
     *
     * A deferred page is not a failed page: its analysis stays pending and the publisher
     * may admit it again once the blocking condition is gone. The one shot attempt rule
     * keeps its meaning for a page that really failed, because that failure is recorded
     * on the analysis itself and not deferred.
     */
    fun defer(pageId: ReaderPageId): Boolean = synchronized(window.lock) {
        val wasActive = active.remove(pageId)
        // The attempt is released even when the slot was already returned: a page
        // that owes its samples must stay admissible, and the slot of a deferred
        // page can be freed by the publisher before the capability reports.
        requiredAttempted.remove(pageId)
        // A deferred page is not a failed page, so it must not spend the shared
        // attempt set either: it has to be admissible again once the blocking
        // condition is gone.
        optionalAttempted.remove(pageId)
        attempted.remove(pageId)
        wasActive
    }

    /** Whether the page still occupies a logical slot of this budget. */
    fun holds(pageId: ReaderPageId): Boolean = synchronized(window.lock) { pageId in active }

    /**
     * The pages this budget admitted for the canonical certification's real demand.
     *
     * The record is the purpose of each admitted page along the request chain: a page
     * that is not in this set was admitted as speculation or as an optional fact, so it
     * was never demanded by the certification itself.
     */
    fun requiredPages(): Set<ReaderPageId> = synchronized(window.lock) { requiredAttempted.toSet() }

    /** The pages this budget admitted as optional facts. */
    fun optionalPages(): Set<ReaderPageId> = synchronized(window.lock) { optionalAttempted.toSet() }

    /** The pages this budget admitted as speculation only. */
    fun speculativePages(): Set<ReaderPageId> = synchronized(window.lock) {
        attempted.filterTo(mutableSetOf<ReaderPageId>()) {
            it !in requiredAttempted && it !in optionalAttempted
        }
    }

    /**
     * The role every admitted page was admitted under.
     *
     * This is the purpose record of the request chain: a page the certification
     * demanded is `REQUIRED`, a page the pairing prepares on its own is `OPTIONAL`
     * and a page the publisher only guessed is `SPECULATIVE`. The record is not
     * cleared when a page is deferred, because a deferred page was still admitted
     * for that purpose, and the canonical role wins over a weaker one.
     */
    fun admissionRoles(): Map<ReaderPageId, ReaderDualPageFactRole> =
        synchronized(window.lock) { admissionRoles.toMap() }

    private fun recordAdmission(pageId: ReaderPageId, role: ReaderDualPageFactRole) {
        if (role == ReaderDualPageFactRole.REQUIRED || pageId !in admissionRoles) {
            admissionRoles[pageId] = role
        }
    }

    fun complete(pageId: ReaderPageId, newlyReadBytes: Long, cacheHit: Boolean) = synchronized(window.lock) {
        require(newlyReadBytes >= 0)
        if (!active.remove(pageId)) return@synchronized
        if (!cacheHit && pageId !in meteredPages) {
            window.newlyReadBytes = minOf(
                ReaderDualPageFactLimits.MAX_NEW_BYTES,
                window.newlyReadBytes + newlyReadBytes.coerceAtMost(ReaderDualPageFactLimits.MAX_NEW_BYTES),
            )
            this.newlyReadBytes = window.newlyReadBytes
        }
        meteredReservedBytes.remove(pageId)
    }

    fun close() = synchronized(window.lock) {
        closed = true
        active.clear()
    }

    /** Reserve before calling the native source, so even simultaneous streams cannot overshoot. */
    fun reserveRead(pageId: ReaderPageId, requestedBytes: Long, nowMillis: Long): Long = synchronized(window.lock) {
        require(requestedBytes >= 0)
        if (closed || pageId !in active || remainingWaitMillis(nowMillis) == 0L) return@synchronized 0L
        val granted = minOf(requestedBytes, ReaderDualPageFactLimits.MAX_NEW_BYTES - window.newlyReadBytes)
        window.newlyReadBytes += granted
        this.newlyReadBytes = window.newlyReadBytes
        meteredPages += pageId
        meteredReservedBytes[pageId] = (meteredReservedBytes[pageId] ?: 0L) + granted
        granted
    }

    fun returnUnreadBytes(pageId: ReaderPageId, byteCount: Long) = synchronized(window.lock) {
        require(byteCount >= 0)
        if (pageId in meteredPages) {
            val reserved = meteredReservedBytes[pageId] ?: 0L
            val refundable = minOf(byteCount, reserved)
            if (refundable > 0L) {
                meteredReservedBytes[pageId] = reserved - refundable
                window.newlyReadBytes = (window.newlyReadBytes - refundable).coerceAtLeast(0L)
                this.newlyReadBytes = window.newlyReadBytes
            }
        }
    }

    fun remainingWaitMillis(nowMillis: Long): Long = synchronized(window.lock) {
        if (closed) return@synchronized 0L
        val elapsed = window.startedAt?.let { (nowMillis - it).coerceAtLeast(0) } ?: 0
        (ReaderDualPageFactLimits.MAX_WAIT_MILLIS - elapsed).coerceAtLeast(0)
    }

    fun canRead(pageId: ReaderPageId, nowMillis: Long): Boolean = synchronized(window.lock) {
        !closed && pageId in active && window.newlyReadBytes < ReaderDualPageFactLimits.MAX_NEW_BYTES &&
            remainingWaitMillis(nowMillis) > 0
    }

    fun rebind(
        unchanged: Set<ReaderPageId>,
        failedPages: Set<ReaderPageId>,
    ): ReaderDualPageFactBudget = synchronized(window.lock) {
        val oldActive = active.toSet()
        close()
        val next = ReaderDualPageFactBudget(window)
        next.startedAt = window.startedAt
        next.newlyReadBytes = window.newlyReadBytes
        for (pageId in unchanged) {
            val wasActive = pageId in oldActive
            if (pageId in failedPages || (!wasActive && pageId in attempted)) {
                if (pageId in attempted) next.attempted += pageId
                if (pageId in requiredAttempted) next.requiredAttempted += pageId
                if (pageId in optionalAttempted) next.optionalAttempted += pageId
                admissionRoles[pageId]?.let { next.admissionRoles[pageId] = it }
            }
        }
        next
    }
}
