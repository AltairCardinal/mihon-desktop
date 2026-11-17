package mihon.presentation.sync

import dev.icerock.moko.resources.StringResource
import mihon.data.sync.runtime.SyncBatchProgress
import mihon.data.sync.runtime.SyncPanelRunSource
import mihon.data.sync.runtime.SyncPanelState
import mihon.data.sync.runtime.SyncProgressDirection
import mihon.data.sync.runtime.SyncProgressFact
import mihon.data.sync.runtime.SyncProgressHold
import mihon.data.sync.runtime.SyncProgressStage
import mihon.data.sync.runtime.SyncProgressTimeline
import mihon.data.sync.runtime.SyncRunState
import mihon.domain.sync.runtime.SyncOperationProgress
import mihon.domain.sync.runtime.SyncOperationStage
import mihon.domain.sync.runtime.SyncRunStatus
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
    val attemptConfirmed: Long? = null,
    val retainedConfirmed: Long = 0,
    val elapsedKnown: Boolean = true,
    val batchProgress: SyncBatchProgress? = null,
    val processedItems: Long? = confirmed,
    val itemFraction: Float? = null,
    val operation: SyncOperationProgress? = null,
    val complete: Boolean = false,
    val itemEta: Long? = null,
    val operationFailed: Boolean = false,
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
    private var elapsedAttemptOrigin: Long? = null
    private var elapsedAdvancing = false
    private var excludedPauseMillis = 0L
    private val roundRate = SyncRoundConfirmationRate()
    private var previousFact: SyncProgressFact? = null
    private val stages = mutableMapOf<SyncProgressStage, Pair<Long, Long?>>()

    fun project(state: SyncPanelState, monotonicMillis: Long): SyncProgressPresentation {
        val run = state.run
        val operation = state.operation?.takeIf {
            !state.syncStartRequested && (it.running || (it.runId == run?.runId && it.outcome != SyncRunStatus.SKIPPED))
        }
        val operationFailed = run?.state == SyncRunState.SUCCEEDED && operation?.running == false &&
            operation.outcome in setOf(SyncRunStatus.FAILED, SyncRunStatus.PARTIAL)
        val complete = !state.syncStartRequested && run?.state == SyncRunState.SUCCEEDED &&
            !state.busy && operation?.running != true &&
            (
                operation == null || (
                    operation.runId == run.runId && operation.durableSuccess &&
                        operation.outcome == SyncRunStatus.SUCCESS
                    )
                )
        val nextIdentity = operation?.let { Identity("", 0, "operation:${it.id}") }
            ?: run?.let { Identity(it.spaceId, it.generation, it.runId) }
            ?: if (state.busy) Identity("", 0, "counting") else null
        val fact = state.progress?.takeIf {
            !state.syncStartRequested && run != null && (it.scope == run.runId || it.scope.startsWith("${run.runId}:"))
        }
        val counting = state.syncStartRequested || run?.plannedItems == null
        val attemptOrigin = run?.attemptStartedAt
        val elapsedKnown = attemptOrigin != null
        val pausedAfterAttempt = ((run?.pausedMillis ?: 0) - (run?.attemptPausedMillis ?: 0)).coerceAtLeast(0)
        val paused = run?.state == SyncRunState.PAUSED_USER
        val advancing = run?.state == SyncRunState.RUNNING && elapsedKnown &&
            (fact?.hold in setOf(SyncProgressHold.ACTIVE, SyncProgressHold.PAUSING) || (fact == null && state.busy))
        if (!initialized || identity != nextIdentity || attempt != run?.attemptId ||
            elapsedAttemptOrigin != attemptOrigin || elapsedAdvancing != advancing ||
            excludedPauseMillis != (run?.pausedMillis ?: 0L)
        ) {
            elapsedOriginMillis = monotonicMillis
            val elapsedUntil = if (paused) {
                run!!.pausedAt ?: run.updatedAt
            } else if (advancing) {
                maxOf(
                    state.nowMillis,
                    run?.updatedAt ?: 0,
                )
            } else {
                run?.updatedAt ?: 0L
            }
            elapsedBaselineSeconds = (
                elapsedUntil - (attemptOrigin ?: elapsedUntil) - pausedAfterAttempt
                ).coerceAtLeast(0) / 1000
        }
        if (!initialized || identity != nextIdentity) stages.clear()
        elapsedAttemptOrigin = attemptOrigin
        elapsedAdvancing = advancing
        excludedPauseMillis = run?.pausedMillis ?: 0L
        if (identity != nextIdentity || attempt != run?.attemptId) roundRate.reset()
        if (previousFact?.scope != fact?.scope || previousFact?.direction != fact?.direction) stages.clear()
        val terminal = !state.syncStartRequested && run?.state in TERMINAL_STATES &&
            (run?.state != SyncRunState.SUCCEEDED || complete)
        val historical = state.showingHistoricalResult
        val active = state.syncStartRequested || (
            operation?.running == true &&
                (
                    operation.runId != run?.runId || run?.state !in setOf(
                        SyncRunState.PAUSED_USER,
                        SyncRunState.WAITING_SYSTEM,
                        SyncRunState.WAITING_NETWORK,
                        SyncRunState.WAITING_RETRY,
                    )
                    )
            ) || (
            operation == null && run?.state == SyncRunState.RUNNING &&
                (fact == null || fact.hold == SyncProgressHold.ACTIVE)
            )
        val batchProgress = fact?.batchProgress?.takeIf {
            !terminal && it.plannedRunItems == run?.plannedItems
        }
        val processedItems = run?.let {
            maxOf(it.confirmedItems, batchProgress?.processedRunItems ?: 0L)
                .coerceIn(0L, it.plannedItems ?: Long.MAX_VALUE)
        }
        val itemFraction = run?.plannedItems?.takeIf { it > 0 && run.confirmedItems in 0..it }
            ?.let { requireNotNull(processedItems).toDouble().div(it).toFloat() }
        val fraction = when {
            state.syncStartRequested -> null
            complete -> 1f
            operation != null -> if (operation.fraction >= 1.0) {
                operation.copy(
                    running = true,
                    fraction = operation.segmentBase +
                        (1.0 - operation.segmentBase) * SyncOperationStage.PERSISTING.end,
                ).displayFraction()
            } else {
                operation.displayFraction()
            }
            run?.plannedItems != null -> when (run.phase) {
                mihon.data.sync.runtime.SyncRunPhase.CHECKING -> 0.02f
                mihon.data.sync.runtime.SyncRunPhase.IMPORTING -> 0.06f
                mihon.data.sync.runtime.SyncRunPhase.COMPLETE -> 0.94f
                else -> 0.20f + 0.65f * (batchProgress?.workflowFraction?.toFloat() ?: itemFraction ?: 0f)
            }
            else -> null
        }
        val wholeRemaining = roundRate.estimate(run?.plannedItems, run?.confirmedItems, active, monotonicMillis)
        val operationFeedback = state.operationFeedback()
        val status = when {
            operationFeedback != null -> operationFeedback
            operationFailed -> if (operation?.outcome == SyncRunStatus.PARTIAL) {
                MR.strings.sync_terminal_partial
            } else {
                MR.strings.sync_last_failed
            }
            run?.state == SyncRunState.SUCCEEDED && !complete -> MR.strings.sync_busy
            operation?.running == true && operation.runId != run?.runId -> MR.strings.sync_busy
            state.hasInvalidReceivedData -> MR.strings.sync_invalid_saved_data
            run?.state in setOf(SyncRunState.FAILED, SyncRunState.PARTIAL) &&
                state.problem == mihon.domain.sync.runtime.SyncRunProblem.NETWORK ->
                MR.strings.sync_connection_unavailable
            state.waitingForLocalRestoration -> if (
                state.terminalSummary?.takeIf { it.runId == run?.runId }?.sourceUnavailableFields?.let { it > 0 } ==
                true
            ) {
                MR.strings.sync_source_restore_pending
            } else {
                MR.strings.sync_local_restore_pending
            }
            terminal -> when (run?.state) {
                SyncRunState.SUCCEEDED -> MR.strings.sync_result_completed
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
            operationFeedback != null -> operationFeedback
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
        val elapsed = if (counting || !elapsedKnown) {
            0L
        } else if (terminal && run != null) {
            (run.updatedAt - requireNotNull(attemptOrigin) - pausedAfterAttempt).coerceAtLeast(0) / 1000
        } else if (!advancing) {
            elapsedBaselineSeconds
        } else {
            elapsedBaselineSeconds + (monotonicMillis - elapsedOriginMillis).coerceAtLeast(0) / 1000
        }
        return SyncProgressPresentation(
            status, action, run?.confirmedItems, fraction, null,
            false, null, active, fact, stages.toMap(),
            if (!counting && !terminal && advancing &&
                (run != null || state.busy)
            ) {
                monotonicMillis + NUMBER_INTERVAL
            } else {
                null
            },
            elapsed,
            run?.takeIf { it.attemptStartedAt != null }?.let {
                (it.confirmedItems - it.attemptConfirmedBaseline).coerceAtLeast(0)
            },
            run?.let { if (it.attemptStartedAt != null) it.attemptConfirmedBaseline else it.confirmedItems } ?: 0L,
            elapsedKnown,
            batchProgress,
            processedItems,
            itemFraction,
            operation,
            complete,
            if (active) wholeRemaining else null,
            operationFailed,
        )
    }
}

/** Keep successful activity feedback until the matching final database projection reaches the panel. */
internal fun SyncPanelState.operationFeedback(): StringResource? {
    if (syncStartRequested) return MR.strings.sync_start_preparing
    val current = operation ?: return null
    if (current.runId == run?.runId && current.durableSuccess &&
        current.outcome !in setOf(SyncRunStatus.FAILED, SyncRunStatus.PARTIAL, SyncRunStatus.SKIPPED) &&
        run?.state in setOf(SyncRunState.RUNNING, SyncRunState.SUCCEEDED) &&
        (current.running || busy || run?.state != SyncRunState.SUCCEEDED)
    ) {
        return MR.strings.sync_operation_finishing
    }
    if (!current.running) return null
    return when (current.stage) {
        SyncOperationStage.CHECKING, SyncOperationStage.AUTHORIZATION -> MR.strings.sync_checking_connection
        SyncOperationStage.BASELINE -> MR.strings.sync_phase_importing
        SyncOperationStage.DISCOVERY -> MR.strings.sync_phase_checking
        SyncOperationStage.PLAN -> MR.strings.sync_stage_prepare
        SyncOperationStage.HEAD -> MR.strings.sync_operation_head
        SyncOperationStage.INTEGRITY -> MR.strings.sync_operation_integrity
        SyncOperationStage.PERSISTING -> MR.strings.sync_operation_closing
        SyncOperationStage.ITEMS -> null
    }
}

/** Received data awaiting local application is separate from a user decision or a transport failure. */
internal val SyncPanelState.waitingForLocalRestoration: Boolean
    get() = run?.state == SyncRunState.PARTIAL && run?.stopReason == "projection_pending" &&
        problem == null && pendingTotal == 0L && !hasInvalidReceivedData

internal val SyncPanelState.hasInvalidReceivedData: Boolean
    get() = run?.state == SyncRunState.PARTIAL &&
        terminalSummary?.takeIf { it.runId == run?.runId }?.invalidEvents?.let { it > 0 } == true

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
