package mihon.presentation.sync

import dev.icerock.moko.resources.StringResource
import mihon.data.sync.runtime.SyncPanelRunSource
import mihon.data.sync.runtime.SyncPanelState
import mihon.data.sync.runtime.SyncProgressDirection
import mihon.data.sync.runtime.SyncProgressFact
import mihon.data.sync.runtime.SyncProgressHold
import mihon.data.sync.runtime.SyncProgressStage
import mihon.data.sync.runtime.SyncRunPhase
import mihon.data.sync.runtime.SyncRunState
import tachiyomi.i18n.MR

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
)

/** Bounded, panel-owned observations; safe counts always come from production facts. */
class SyncProgressDisplaySession {
    companion object {
        const val ACTION_DELAY = 800L
        const val STABILITY_WINDOW = 2_000L
        const val NUMBER_INTERVAL = 1_000L
    }
    private data class Identity(val space: String, val generation: Long, val run: String)
    private data class ActionKey(
        val scope: String?,
        val direction: SyncProgressDirection?,
        val stage: SyncProgressStage?,
        val phase: SyncRunPhase?,
        val merging: Boolean?,
    )
    private var identity: Identity? = null
    private var attempt: Long? = null
    private var actionKey: ActionKey? = null
    private var actionSince = 0L
    private val changes = ArrayDeque<Long>()
    private var locked = false
    private var etaSince: Long? = null
    private var lastFact: SyncProgressFact? = null
    private var factSince = 0L
    private var lastHold: SyncProgressHold? = null
    private var lastState: SyncRunState? = null
    private var numberSince = Long.MIN_VALUE
    private var displayedConfirmed: Long? = null
    private var displayedFraction: Float? = null
    private var displayedEta: Long? = null
    private val stages = mutableMapOf<SyncProgressStage, Pair<Long, Long?>>()

    fun project(state: SyncPanelState, monotonicMillis: Long): SyncProgressPresentation {
        val run = state.run
        val nextIdentity = run?.let { Identity(it.spaceId, it.generation, it.runId) }
        val fact = state.progress?.takeIf {
            run != null &&
                (it.scope == run.runId || it.scope.startsWith("${run.runId}:"))
        }
        val key = ActionKey(fact?.scope, fact?.direction, fact?.stage, run?.phase, fact?.mergingReceivedData)
        val identityChanged = identity != nextIdentity
        val attemptChanged = attempt != run?.attemptId
        val keyChanged = actionKey != key
        val holdChanged = lastHold != fact?.hold || lastState != run?.state
        if (identityChanged || attemptChanged) {
            changes.clear()
            locked = false
            actionSince = monotonicMillis
        }
        if (identityChanged || attemptChanged || keyChanged || holdChanged) {
            displayedFraction = null
            displayedEta = null
            etaSince = null
            actionSince = monotonicMillis
            numberSince = Long.MIN_VALUE
        }
        if (identityChanged || attemptChanged || holdChanged || actionKey?.scope != key.scope ||
            actionKey?.direction != key.direction
        ) {
            stages.clear()
        }
        if (keyChanged && !identityChanged && !attemptChanged) {
            changes.addLast(monotonicMillis)
            while (changes.size > 3) changes.removeFirst()
            if (changes.size == 3 && monotonicMillis - changes.first() <= STABILITY_WINDOW) locked = true
        }
        if (locked && monotonicMillis - actionSince >= STABILITY_WINDOW) {
            locked = false
            changes.clear()
        }
        if (fact?.secondsWithoutProgress != lastFact?.secondsWithoutProgress || keyChanged || holdChanged) {
            factSince = monotonicMillis
        }
        lastFact = fact
        val terminal = run?.state in TERMINAL_STATES
        val historical = state.showingHistoricalResult
        val active = run?.state == SyncRunState.RUNNING && (fact == null || fact.hold == SyncProgressHold.ACTIVE)
        val age = fact?.secondsWithoutProgress?.let { it + (monotonicMillis - factSince).coerceAtLeast(0) / 1000 }
        val etaValid = active && fact?.wholeEtaSeconds?.let { it >= 0 } == true && age != null && age < 10
        if (!etaValid) {
            etaSince = null
            displayedEta = null
        } else if (etaSince == null) {
            etaSince = monotonicMillis
        }
        val stable = !locked && monotonicMillis - actionSince >= ACTION_DELAY
        val candidateFraction = if (active && stable && fact?.stage == SyncProgressStage.TRANSFERRING) {
            fact.totalBytes?.takeIf { it > 0 && fact.effectiveBytes in 0..it }
                ?.let { fact.effectiveBytes.toFloat() / it }
        } else {
            null
        }
        // Invalidated values bypass number throttling; no previous scope denominator survives.
        if (candidateFraction == null) displayedFraction = null
        val etaReady = etaValid && monotonicMillis - requireNotNull(etaSince) >= STABILITY_WINDOW
        val confirmed = if (terminal) {
            run?.confirmedItems
        } else {
            fact?.confirmedThisRun
                ?: run?.confirmedItems?.takeIf { it > 0 }
        }
        if (terminal || holdChanged || identityChanged || numberSince == Long.MIN_VALUE ||
            monotonicMillis - numberSince >= NUMBER_INTERVAL
        ) {
            displayedConfirmed = confirmed
            displayedFraction = candidateFraction
            displayedEta = if (etaReady) fact?.wholeEtaSeconds else null
            numberSince = monotonicMillis
        }
        // Stability promotion must happen at the boundary even when no new network event arrives.
        if (candidateFraction != null && displayedFraction == null) displayedFraction = candidateFraction
        if (etaReady && displayedEta == null) displayedEta = fact?.wholeEtaSeconds
        if (fact != null && !terminal) stages[fact.stage] = fact.completedItems to fact.totalItems
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
            run?.state == SyncRunState.WAITING_RETRY -> if (run.stopReason ==
                "rate_limit"
            ) {
                MR.strings.sync_waiting_rate_limit
            } else {
                MR.strings.sync_waiting_retry
            }
            fact?.hold == SyncProgressHold.PAUSING -> MR.strings.sync_pausing_save
            fact?.hold == SyncProgressHold.RECOVERING -> MR.strings.sync_recovering_progress
            run?.state == SyncRunState.QUEUED || run == null -> MR.strings.sync_wait_start
            else -> MR.strings.sync_busy
        }
        val action = when {
            terminal -> if (historical) MR.strings.sync_confirmed_retained else status
            run?.state == SyncRunState.PAUSED_USER -> MR.strings.sync_paused
            run?.state == SyncRunState.WAITING_NETWORK -> MR.strings.sync_waiting_network
            run?.state == SyncRunState.WAITING_SYSTEM -> MR.strings.sync_waiting_system
            run?.state == SyncRunState.WAITING_RETRY -> if (run.stopReason ==
                "rate_limit"
            ) {
                MR.strings.sync_waiting_rate_limit
            } else {
                MR.strings.sync_waiting_retry
            }
            fact?.hold == SyncProgressHold.PAUSING -> MR.strings.sync_pausing_save
            fact?.hold == SyncProgressHold.RECOVERING -> MR.strings.sync_recovering_progress
            fact == null || !stable || locked -> if (fact?.stage == SyncProgressStage.PREPARING ||
                fact == null
            ) {
                MR.strings.sync_stage_prepare
            } else {
                MR.strings.sync_exchanging_data
            }
            fact.stage == SyncProgressStage.PREPARING -> MR.strings.sync_stage_prepare
            fact.stage == SyncProgressStage.TRANSFERRING -> if (fact.direction ==
                SyncProgressDirection.UPLOAD
            ) {
                MR.strings.sync_phase_uploading
            } else {
                MR.strings.sync_phase_downloading
            }
            fact.direction == SyncProgressDirection.UPLOAD -> MR.strings.sync_checking_github_saved
            fact.mergingReceivedData -> MR.strings.sync_merging_received_data
            else -> MR.strings.sync_receiving_and_verifying
        }
        val deadlines = buildList {
            if (active && !stable) add(actionSince + if (locked) STABILITY_WINDOW else ACTION_DELAY)
            if (etaValid && !etaReady) add(requireNotNull(etaSince) + STABILITY_WINDOW)
            if (active && age != null &&
                age < 10
            ) {
                add(factSince + (10 - requireNotNull(fact?.secondsWithoutProgress)) * 1000)
            }
            if (active && age != null &&
                age < 60
            ) {
                add(factSince + (60 - requireNotNull(fact?.secondsWithoutProgress)) * 1000)
            }
            if (active && (
                    confirmed != displayedConfirmed || candidateFraction != displayedFraction ||
                        (etaReady && fact?.wholeEtaSeconds != displayedEta)
                    )
            ) {
                add(numberSince + NUMBER_INTERVAL)
            }
        }.filter { it > monotonicMillis }
        identity = nextIdentity
        attempt = run?.attemptId
        actionKey = key
        lastHold = fact?.hold
        lastState = run?.state
        return SyncProgressPresentation(
            status, action, displayedConfirmed, displayedFraction, displayedEta,
            etaValid && !etaReady, if (active) age else null, active, fact, stages.toMap(), deadlines.minOrNull(),
        )
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
