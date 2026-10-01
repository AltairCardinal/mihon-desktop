package mihon.presentation.sync

import dev.icerock.moko.resources.StringResource
import mihon.data.sync.runtime.SyncPanelRunSource
import mihon.data.sync.runtime.SyncPanelState
import mihon.data.sync.runtime.SyncProgressDirection
import mihon.data.sync.runtime.SyncProgressFact
import mihon.data.sync.runtime.SyncProgressHold
import mihon.data.sync.runtime.SyncProgressStage
import mihon.data.sync.runtime.SyncProgressTimeline
import mihon.data.sync.runtime.SyncRunState
import tachiyomi.i18n.MR
import kotlin.math.ceil

internal const val SYNC_ROUND_RATE_WINDOW_MILLIS = 300_000L

data class SyncProgressPresentation(
    val status: StringResource,
    val action: StringResource,
    val confirmed: Long?,
    val fraction: Float?,
    val wholeEta: Long?,
    val estimating: Boolean,
    val idleSeconds: Long?,
    val active: Boolean,
    val fact: SyncProgressFact?,
    val stages: Map<SyncProgressStage, Pair<Long, Long?>>,
    val nextDeadlineMillis: Long?,
    val elapsedSeconds: Long = 0,
)

/** Panel-owned observations; count and denominator always come from the same durable run. */
class SyncProgressDisplaySession {
    companion object {
        const val NUMBER_INTERVAL = 1_000L
    }
    private data class Identity(val space: String, val generation: Long, val run: String)
    private var identity: Identity? = null
    private var initialized = false
    private var attempt: Long? = null
    private var elapsedOriginMillis = 0L
    private var elapsedBaselineSeconds = 0L
    private val roundRate = SyncRoundConfirmationRate()
    private var previousFact: SyncProgressFact? = null
    private val stages = mutableMapOf<SyncProgressStage, Pair<Long, Long?>>()

    fun project(state: SyncPanelState, monotonicMillis: Long): SyncProgressPresentation {
        val run = state.run
        val nextIdentity = run?.let { Identity(it.spaceId, it.generation, it.runId) }
            ?: if (state.busy) Identity("", 0, "counting") else null
        val fact = state.progress?.takeIf {
            run != null && (it.scope == run.runId || it.scope.startsWith("${run.runId}:"))
        }
        if (!initialized || identity != nextIdentity) {
            elapsedOriginMillis = monotonicMillis
            elapsedBaselineSeconds = ((state.nowMillis - (run?.createdAt ?: state.nowMillis)).coerceAtLeast(0)) / 1000
            stages.clear()
        }
        if (identity != nextIdentity || attempt != run?.attemptId) roundRate.reset()
        if (previousFact?.scope != fact?.scope || previousFact?.direction != fact?.direction) stages.clear()
        val terminal = run?.state in TERMINAL_STATES
        val historical = state.showingHistoricalResult
        val active = run?.state == SyncRunState.RUNNING && (fact == null || fact.hold == SyncProgressHold.ACTIVE)
        val fraction = run?.plannedItems?.takeIf { it > 0 && run.confirmedItems in 0..it }
            ?.let { run.confirmedItems.toDouble().div(it).toFloat() }
        val wholeRemaining = roundRate.estimate(run?.plannedItems, run?.confirmedItems, active, monotonicMillis)
        val status = when {
            terminal -> when (run?.state) {
                SyncRunState.SUCCEEDED -> MR.strings.sync_last_succeeded
                SyncRunState.PARTIAL -> if (historical) {
                    MR.strings.sync_last_partial
                } else {
                    MR.strings.sync_terminal_partial
                }
                SyncRunState.FAILED -> MR.strings.sync_last_failed
                SyncRunState.BLOCKED -> if (historical) MR.strings.sync_last_blocked else MR.strings.sync_blocked
                else -> MR.strings.sync_last_cancelled
            }
            run?.state == SyncRunState.PAUSED_USER -> MR.strings.sync_paused
            run?.state == SyncRunState.WAITING_NETWORK -> MR.strings.sync_waiting_network
            run?.state == SyncRunState.WAITING_SYSTEM -> MR.strings.sync_waiting_system
            run?.state == SyncRunState.WAITING_RETRY -> if (run.stopReason == "rate_limit") {
                MR.strings.sync_waiting_rate_limit
            } else {
                MR.strings.sync_waiting_retry
            }
            fact?.hold == SyncProgressHold.PAUSING -> MR.strings.sync_pausing_save
            fact?.hold == SyncProgressHold.PAUSED -> MR.strings.sync_paused
            fact?.hold == SyncProgressHold.OFFLINE -> MR.strings.sync_waiting_network
            fact?.hold == SyncProgressHold.WAITING -> MR.strings.sync_waiting_system
            fact?.hold == SyncProgressHold.RECOVERING -> MR.strings.sync_recovering_progress
            run?.state == SyncRunState.QUEUED || run == null -> MR.strings.sync_wait_start
            else -> MR.strings.sync_busy
        }
        val action = when {
            terminal -> if (historical) MR.strings.sync_confirmed_retained else status
            fact?.stage == SyncProgressStage.PREPARING -> MR.strings.sync_stage_prepare
            fact?.stage == SyncProgressStage.TRANSFERRING -> if (fact.direction == SyncProgressDirection.UPLOAD) {
                MR.strings.sync_phase_uploading
            } else {
                MR.strings.sync_phase_downloading
            }
            fact?.direction == SyncProgressDirection.UPLOAD -> MR.strings.sync_checking_github_saved
            fact?.mergingReceivedData == true -> MR.strings.sync_merging_received_data
            else -> status
        }
        if (fact != null && !terminal) stages[fact.stage] = fact.completedItems to fact.totalItems
        identity = nextIdentity
        initialized = true
        attempt = run?.attemptId
        previousFact = fact
        val elapsed = if (terminal && run != null) {
            (run.updatedAt - run.createdAt).coerceAtLeast(0) / 1000
        } else {
            elapsedBaselineSeconds + (monotonicMillis - elapsedOriginMillis).coerceAtLeast(0) / 1000
        }
        return SyncProgressPresentation(
            status, action, run?.confirmedItems, fraction, if (active) wholeRemaining else null,
            false, null, active, fact, stages.toMap(),
            if (!terminal && (run != null || state.busy)) monotonicMillis + NUMBER_INTERVAL else null,
            elapsed,
        )
    }
}

/** Whole-plan ETA observes only committed run confirmations, across all work directions and stages. */
private class SyncRoundConfirmationRate {
    companion object {
        const val MAX_SAMPLE_POINTS = 9
        const val MAX_STALE_MILLIS = 120_000L
    }
    private data class Sample(val millis: Long, val confirmed: Long)
    private val samples = ArrayDeque<Sample>()
    private var lastIncrementMillis: Long? = null

    fun reset() {
        samples.clear()
        lastIncrementMillis = null
    }

    fun estimate(total: Long?, confirmed: Long?, active: Boolean, now: Long): Long? {
        if (!active || total == null || confirmed == null || total <= 0 || confirmed !in 0..total) {
            reset()
            return null
        }
        if (samples.lastOrNull()?.let { confirmed < it.confirmed || now < it.millis } == true) reset()
        val cadence = if (samples.size > 1) {
            (samples.last().millis - samples.first().millis) / (samples.size - 1)
        } else {
            0L
        }
        val staleAfter = maxOf(SyncProgressTimeline.STALE_AFTER_NANOS / 1_000_000, cadence * 2)
            .coerceAtMost(MAX_STALE_MILLIS)
        if (samples.size > SyncProgressTimeline.MIN_INCREMENT_SAMPLES &&
            lastIncrementMillis?.let { now - it >= staleAfter } == true
        ) {
            reset()
        }
        if (samples.isEmpty()) {
            samples.addLast(Sample(now, confirmed))
        } else if (confirmed > samples.last().confirmed) {
            samples.addLast(Sample(now, confirmed))
            lastIncrementMillis = now
        }
        while (samples.size > MAX_SAMPLE_POINTS ||
            (samples.size > 1 && samples.first().millis < now - SYNC_ROUND_RATE_WINDOW_MILLIS)
        ) {
            samples.removeFirst()
        }
        val latest = samples.last()
        val first = samples.first()
        if (samples.size <= SyncProgressTimeline.MIN_INCREMENT_SAMPLES ||
            latest.millis - first.millis < SyncProgressTimeline.MIN_SAMPLE_SPAN_NANOS / 1_000_000 ||
            lastIncrementMillis == null ||
            now - requireNotNull(lastIncrementMillis) >= staleAfter
        ) {
            return null
        }
        val rate = (latest.confirmed - first.confirmed).toDouble() / (latest.millis - first.millis)
        return ceil((total - confirmed) / rate / 1000)
            .takeIf { it.isFinite() && it >= 0 && it <= Long.MAX_VALUE.toDouble() }?.toLong()
    }
}

internal val TERMINAL_STATES = setOf(
    SyncRunState.SUCCEEDED,
    SyncRunState.PARTIAL,
    SyncRunState.FAILED,
    SyncRunState.BLOCKED,
    SyncRunState.CANCELLED,
)

/** ACTIVE BLOCKED and unresolved PARTIAL still belong to the current recovery task. */
internal val SyncPanelState.showingHistoricalResult: Boolean
    get() {
        val selectedRun = run ?: return false
        return when (runSource) {
            SyncPanelRunSource.ACTIVE -> false
            SyncPanelRunSource.LATEST -> true
            null -> selectedRun.state in setOf(SyncRunState.SUCCEEDED, SyncRunState.FAILED, SyncRunState.CANCELLED)
        }
    }
