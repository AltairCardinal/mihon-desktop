package mihon.data.sync.runtime

import kotlin.math.ceil

enum class SyncProgressStage { PREPARING, TRANSFERRING, CONFIRMING }
enum class SyncProgressDirection { UPLOAD, DOWNLOAD }
enum class SyncProgressHold { ACTIVE, PAUSING, PAUSED, OFFLINE, WAITING, RECOVERING }

/** A display fact for one work scope. Null totals and ETAs mean that they are not known. */
data class SyncProgressFact(
    val scope: String,
    val stage: SyncProgressStage,
    val direction: SyncProgressDirection,
    val completedItems: Long,
    val totalItems: Long?,
    val effectiveBytes: Long,
    val networkBytes: Long,
    val totalBytes: Long?,
    val elapsedSeconds: Long,
    val hold: SyncProgressHold,
    val stageEtaSeconds: Long?,
    val wholeEtaSeconds: Long?,
    val additionalWork: Boolean = false,
    val activeBodyBytes: Long? = null,
    val activeBodyTotal: Long? = null,
    /** ETA for this HTTP body only; it says nothing about the rest of the sync round. */
    val activeBodyEtaSeconds: Long? = null,
    val importCompletedItems: Long? = null,
    val importTotalItems: Long? = null,
    /** Null means prior confirmations have not been safely reconstructed. */
    val confirmedThisRun: Long? = null,
)

/**
 * In-memory observations only. The caller owns safe persistence and supplies event, batch, and
 * body identities scoped to a space/generation/work round. Time is monotonic nanoseconds.
 */
class SyncProgressTimeline {
    companion object {
        const val RATE_WINDOW_NANOS = 10_000_000_000L
        const val MIN_SAMPLE_SPAN_NANOS = 3_000_000_000L
        const val MIN_INCREMENT_SAMPLES = 3
        const val STALE_AFTER_NANOS = 10_000_000_000L
    }

    private data class Lane(val stage: SyncProgressStage, val direction: SyncProgressDirection)

    private class Counts(var totalItems: Long?, var totalBytes: Long?) {
        val items = mutableSetOf<String>()

        // A caller may mix batch and event APIs only for disjoint logical events.
        val batches = mutableMapOf<String, Long>()
        val bodyOffsets = mutableMapOf<String, Long>()

        // Retained only for this scope so repeated callbacks remain idempotent; scope changes clear it.
        val attemptOffsets = mutableMapOf<Pair<String, String>, Long>()
        var batchItems = 0L
        var effectiveBytes = 0L
        var networkBytes = 0L
        val completedItems: Long get() = items.size.toLong() + batchItems
    }

    private data class Sample(val nanos: Long, val amount: Long)

    private class RateWindow {
        val samples = ArrayDeque<Sample>()
        var activeNanos = 0L
        var enteredAtNanos: Long? = null
        var lastProgressNanos: Long? = null
    }

    private var scope: String? = null
    private var lane: Lane? = null
    private val counters = mutableMapOf<Lane, Counts>()
    private val rates = mutableMapOf<Lane, RateWindow>()
    private val futureEstimates = mutableMapOf<SyncProgressStage, Long>()
    private var firstNanos: Long? = null
    private var lastNanos = Long.MIN_VALUE
    private var hold = SyncProgressHold.ACTIVE
    private var additionalWork = false
    private var activeRequestKey: String? = null
    private var activeBodyBytes: Long? = null
    private var activeBodyTotal: Long? = null
    private var activeBodyRate: RateWindow? = null
    private var importId: String? = null
    private var importTotal: Long? = null
    private var importCompleted = 0L
    private val importPages = mutableMapOf<String, Long>()
    private val confirmedBatches = mutableMapOf<Pair<SyncProgressDirection, String>, Long>()
    private val confirmedEvents = mutableSetOf<Pair<SyncProgressDirection, String>>()
    private var confirmedRunItems = 0L

    fun begin(
        scope: String,
        stage: SyncProgressStage,
        direction: SyncProgressDirection,
        totalItems: Long?,
        totalBytes: Long? = null,
        atNanos: Long,
        additionalWork: Boolean = false,
    ) {
        require(
            scope.isNotBlank() && (totalItems == null || totalItems >= 0) && (totalBytes == null || totalBytes >= 0),
        )
        observeTime(atNanos)
        if (this.scope != scope) {
            counters.clear()
            futureEstimates.clear()
            resetRate()
            this.scope = scope
            this.additionalWork = additionalWork
            clearActiveBody()
            importId = null
            importTotal = null
            importCompleted = 0L
            importPages.clear()
        }
        val nextLane = Lane(stage, direction)
        if (lane != nextLane) {
            leaveLane(atNanos)
            if (lane?.direction != null && lane?.direction != direction) resetRate()
            clearActiveBody()
        }
        lane = nextLane
        if (hold == SyncProgressHold.ACTIVE) {
            val rate = rates.getOrPut(nextLane, ::RateWindow)
            if (rate.enteredAtNanos == null) rate.enteredAtNanos = atNanos
        }
        val previous = counters[nextLane]
        if (previous == null) {
            counters[nextLane] = Counts(totalItems, totalBytes)
        } else {
            require(
                (totalItems == null || previous.totalItems == null || previous.totalItems == totalItems) &&
                    (totalBytes == null || previous.totalBytes == null || previous.totalBytes == totalBytes),
            ) {
                "Known totals are fixed within a work scope; start a new scope for added work"
            }
            if (totalItems != null) previous.totalItems = totalItems
            if (totalBytes != null) previous.totalBytes = totalBytes
        }
    }

    fun prepared(eventKey: String, atNanos: Long) = completeItem(SyncProgressStage.PREPARING, eventKey, atNanos)

    fun transferred(eventKey: String, atNanos: Long) = completeItem(SyncProgressStage.TRANSFERRING, eventKey, atNanos)

    fun confirmed(eventKey: String, atNanos: Long) = completeItem(SyncProgressStage.CONFIRMING, eventKey, atNanos)

    fun prepareBatch(batchKey: String, itemCount: Long, atNanos: Long) =
        completeBatch(SyncProgressStage.PREPARING, batchKey, itemCount, atNanos)

    /** An imported page is visible only after its journal transaction commits. */
    fun importPage(importId: String, pageKey: String, total: Long, committedCount: Long, atNanos: Long) {
        require(importId.isNotBlank() && pageKey.isNotBlank() && total >= 0 && committedCount > 0)
        current(SyncProgressStage.PREPARING, atNanos)
        require(this.importId == null || this.importId == importId)
        require(importTotal == null || importTotal == total)
        val previous = importPages.putIfAbsent(pageKey, committedCount)
        require(previous == null || previous == committedCount)
        if (previous == null) {
            require(importCompleted + committedCount <= total)
            importCompleted += committedCount
        }
        this.importId = importId
        importTotal = total
    }

    fun transferBatch(batchKey: String, itemCount: Long, atNanos: Long) =
        completeBatch(SyncProgressStage.TRANSFERRING, batchKey, itemCount, atNanos)

    /** An atomic or transactional confirmation advances only after the real confirmation succeeds. */
    fun confirmBatch(batchKey: String, itemCount: Long, atNanos: Long) =
        completeBatch(SyncProgressStage.CONFIRMING, batchKey, itemCount, atNanos)

    private fun completeBatch(stage: SyncProgressStage, batchKey: String, itemCount: Long, atNanos: Long) {
        require(itemCount >= 0)
        val counts = current(stage, atNanos)
        val previous = counts.batches.putIfAbsent(batchKey, itemCount)
        require(previous == null || previous == itemCount)
        if (previous == null) {
            counts.batchItems += itemCount
            if (stage == SyncProgressStage.CONFIRMING &&
                confirmedBatches.putIfAbsent(checkNotNull(lane).direction to batchKey, itemCount) == null
            ) {
                confirmedRunItems += itemCount
            }
            if (stage != SyncProgressStage.TRANSFERRING || counts.totalBytes == null) {
                recordIncrement(itemCount, atNanos)
            }
        }
    }

    /** Offsets are cumulative within an attempt. Work keys identify the same logical body across retries. */
    fun bodyProgress(
        workKey: String,
        attemptKey: String,
        byteOffset: Long,
        atNanos: Long,
        bodyTotal: Long? = null,
    ) {
        require(byteOffset >= 0)
        val counts = current(SyncProgressStage.TRANSFERRING, atNanos)
        beginActiveBody(attemptKey, atNanos)
        activeBodyBytes = byteOffset
        activeBodyTotal = bodyTotal?.takeIf { it > 0L }
        val attempt = workKey to attemptKey
        val priorAttemptOffset = counts.attemptOffsets[attempt] ?: 0L
        require(byteOffset >= priorAttemptOffset) { "Body offset must be monotonic within an attempt" }
        counts.attemptOffsets[attempt] = byteOffset
        val networkIncrement = byteOffset - priorAttemptOffset
        counts.networkBytes += networkIncrement
        recordBodyIncrement(networkIncrement, atNanos)

        val priorEffectiveOffset = counts.bodyOffsets[workKey] ?: 0L
        if (byteOffset > priorEffectiveOffset) {
            counts.bodyOffsets[workKey] = byteOffset
            val available = counts.totalBytes?.let { (it - counts.effectiveBytes).coerceAtLeast(0) } ?: Long.MAX_VALUE
            val increment = (byteOffset - priorEffectiveOffset).coerceAtMost(available)
            counts.effectiveBytes += increment
            if (counts.totalBytes != null) recordIncrement(increment, atNanos)
        }
    }

    /** Request identity alone cannot establish logical work across retries; count traffic only. */
    fun networkProgress(requestKey: String, byteOffset: Long, atNanos: Long, bodyTotal: Long? = null) {
        require(byteOffset >= 0)
        val counts = current(SyncProgressStage.TRANSFERRING, atNanos)
        beginActiveBody(requestKey, atNanos)
        activeBodyBytes = byteOffset
        activeBodyTotal = bodyTotal?.takeIf { it > 0L }
        val attempt = requestKey to requestKey
        val previous = counts.attemptOffsets[attempt] ?: 0L
        require(byteOffset >= previous)
        counts.attemptOffsets[attempt] = byteOffset
        val increment = byteOffset - previous
        counts.networkBytes += increment
        recordBodyIncrement(increment, atNanos)
    }

    fun finishBody(requestKey: String, atNanos: Long) {
        observeTime(atNanos)
        if (activeRequestKey == requestKey) clearActiveBody()
    }

    fun setHold(value: SyncProgressHold, atNanos: Long) {
        observeTime(atNanos)
        if (hold != value) {
            leaveLane(atNanos)
            hold = value
            resetRate()
            clearActiveBody()
            if (value ==
                SyncProgressHold.ACTIVE
            ) {
                lane?.let { rates.getOrPut(it, ::RateWindow).enteredAtNanos = atNanos }
            }
        }
    }

    /** Supply only evidence-backed future estimates; omission keeps whole-run ETA unknown. */
    fun setFutureStageEstimate(stage: SyncProgressStage, remainingSeconds: Long?) {
        require(remainingSeconds == null || remainingSeconds > 0)
        if (remainingSeconds == null) futureEstimates.remove(stage) else futureEstimates[stage] = remainingSeconds
    }

    fun snapshot(atNanos: Long): SyncProgressFact {
        observeTime(atNanos)
        val currentLane = checkNotNull(lane) { "Call begin before snapshot" }
        val counts = checkNotNull(counters[currentLane])
        val transferCounts = counters[Lane(SyncProgressStage.TRANSFERRING, currentLane.direction)]
        val useBytes = currentLane.stage == SyncProgressStage.TRANSFERRING && counts.totalBytes != null
        val totalWork = if (useBytes) counts.totalBytes else counts.totalItems
        val doneWork = if (useBytes) counts.effectiveBytes else counts.completedItems
        val remaining = totalWork?.let { (it - doneWork).coerceAtLeast(0) }
        val stageEta = if (remaining == null || remaining == 0L || hold != SyncProgressHold.ACTIVE) {
            null
        } else {
            estimatedSeconds(remaining, atNanos)
        }
        val later = SyncProgressStage.entries.drop(currentLane.stage.ordinal + 1)
        // Confirmation can lead back to preparation and transfer for another batch in this round.
        val wholeEta = if (currentLane.stage != SyncProgressStage.CONFIRMING &&
            stageEta != null && later.all(futureEstimates::containsKey)
        ) {
            stageEta + later.sumOf { futureEstimates.getValue(it) }
        } else {
            null
        }
        val bodyEta = if (hold == SyncProgressHold.ACTIVE && currentLane.stage == SyncProgressStage.TRANSFERRING) {
            val bodyRemaining = activeBodyTotal?.let { total ->
                (total - (activeBodyBytes ?: 0L)).coerceAtLeast(0L)
            }
            if (bodyRemaining != null && bodyRemaining > 0L) {
                activeBodyRate?.let { estimatedSeconds(it, bodyRemaining, atNanos) }
            } else {
                null
            }
        } else {
            null
        }
        return SyncProgressFact(
            scope = checkNotNull(scope),
            stage = currentLane.stage,
            direction = currentLane.direction,
            completedItems = counts.completedItems,
            totalItems = counts.totalItems,
            effectiveBytes = transferCounts?.effectiveBytes ?: counts.effectiveBytes,
            networkBytes = transferCounts?.networkBytes ?: counts.networkBytes,
            totalBytes = counts.totalBytes,
            elapsedSeconds = ((atNanos - checkNotNull(firstNanos)).coerceAtLeast(0) / 1_000_000_000L),
            hold = hold,
            stageEtaSeconds = stageEta,
            wholeEtaSeconds = wholeEta,
            additionalWork = additionalWork,
            activeBodyBytes = activeBodyBytes,
            activeBodyTotal = activeBodyTotal,
            activeBodyEtaSeconds = bodyEta,
            importCompletedItems = importTotal?.let { importCompleted },
            importTotalItems = importTotal,
            confirmedThisRun = confirmedRunItems,
        )
    }

    private fun completeItem(stage: SyncProgressStage, eventKey: String, atNanos: Long) {
        val counts = current(stage, atNanos)
        if (stage == SyncProgressStage.CONFIRMING &&
            counts.items.add(eventKey) && confirmedEvents.add(checkNotNull(lane).direction to eventKey)
        ) {
            confirmedRunItems++
            recordIncrement(1, atNanos)
            return
        }
        if (counts.items.add(eventKey) &&
            (stage != SyncProgressStage.TRANSFERRING || counts.totalBytes == null)
        ) {
            recordIncrement(1, atNanos)
        }
    }

    private fun current(stage: SyncProgressStage, atNanos: Long): Counts {
        observeTime(atNanos)
        val currentLane = checkNotNull(lane) { "Call begin before progress" }
        require(currentLane.stage == stage) { "Progress belongs to the active stage" }
        return checkNotNull(counters[currentLane])
    }

    private fun observeTime(atNanos: Long) {
        require(atNanos >= lastNanos) { "Monotonic time cannot move backwards" }
        if (firstNanos == null) firstNanos = atNanos
        lastNanos = atNanos
    }

    private fun recordIncrement(amount: Long, atNanos: Long) {
        if (amount <= 0) return
        val rate = rates.getOrPut(checkNotNull(lane), ::RateWindow)
        val activeNow = activeTime(rate, atNanos)
        rate.lastProgressNanos = atNanos
        rate.samples.addLast(Sample(activeNow, amount))
        while (rate.samples.isNotEmpty() &&
            activeNow - rate.samples.first().nanos > RATE_WINDOW_NANOS
        ) {
            rate.samples.removeFirst()
        }
    }

    private fun estimatedSeconds(remaining: Long, atNanos: Long): Long? {
        val rate = rates[checkNotNull(lane)] ?: return null
        return estimatedSeconds(rate, remaining, atNanos)
    }

    private fun estimatedSeconds(rate: RateWindow, remaining: Long, atNanos: Long): Long? {
        if (rate.lastProgressNanos == null ||
            atNanos - checkNotNull(rate.lastProgressNanos) >= STALE_AFTER_NANOS
        ) {
            return null
        }
        val activeNow = activeTime(rate, atNanos)
        val window = rate.samples.filter { activeNow - it.nanos <= RATE_WINDOW_NANOS }
        if (window.size < MIN_INCREMENT_SAMPLES) return null
        val span = window.last().nanos - window.first().nanos
        if (span < MIN_SAMPLE_SPAN_NANOS) return null
        val work = window.drop(1).sumOf { it.amount }
        if (work <= 0) return null
        return ceil(remaining.toDouble() * span / work / 1_000_000_000.0).toLong().coerceAtLeast(1)
    }

    private fun resetRate() {
        rates.clear()
    }

    private fun leaveLane(atNanos: Long) {
        val rate = lane?.let(rates::get) ?: return
        rate.enteredAtNanos?.let { rate.activeNanos += (atNanos - it).coerceAtLeast(0) }
        rate.enteredAtNanos = null
    }

    private fun activeTime(rate: RateWindow, atNanos: Long): Long =
        rate.activeNanos + (rate.enteredAtNanos?.let { (atNanos - it).coerceAtLeast(0) } ?: 0L)

    private fun clearActiveBody() {
        activeRequestKey = null
        activeBodyBytes = null
        activeBodyTotal = null
        activeBodyRate = null
    }

    private fun beginActiveBody(requestKey: String, atNanos: Long) {
        if (activeRequestKey != requestKey) {
            clearActiveBody()
            activeRequestKey = requestKey
            activeBodyRate = RateWindow().apply { enteredAtNanos = atNanos }
        }
    }

    private fun recordBodyIncrement(amount: Long, atNanos: Long) {
        if (amount <= 0L) return
        val rate = activeBodyRate ?: return
        val activeNow = activeTime(rate, atNanos)
        rate.lastProgressNanos = atNanos
        rate.samples.addLast(Sample(activeNow, amount))
        while (rate.samples.isNotEmpty() && activeNow - rate.samples.first().nanos > RATE_WINDOW_NANOS) {
            rate.samples.removeFirst()
        }
    }
}
