package mihon.desktop.network

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import tachiyomi.domain.source.service.AuthenticatedSession
import tachiyomi.domain.source.service.SourceLoginRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

sealed interface ChallengeRecoveryIntent {
    data object OpenBrowser : ChallengeRecoveryIntent
    data class SubmitManualCookies(
        val session: AuthenticatedSession,
    ) : ChallengeRecoveryIntent
    data object UseFlareSolverr : ChallengeRecoveryIntent
    data object Cancel : ChallengeRecoveryIntent
    data object Retry : ChallengeRecoveryIntent
}

enum class ChallengeRecoveryFailure {
    BrowserUnavailable,
    SolverUnavailable,
    SolverFailed,
    InvalidCookies,
    CommitFailed,
}

enum class ChallengeRecoveryAction {
    Browser,
    ManualCookies,
    FlareSolverr,
}

sealed interface ChallengeRecoveryState {
    data object AwaitingUserAction : ChallengeRecoveryState
    data class Running(val action: ChallengeRecoveryAction) : ChallengeRecoveryState
    data class RecoverableFailure(val reason: ChallengeRecoveryFailure) : ChallengeRecoveryState
    data class Recovered(val cookieNames: Set<String>, val cookieCount: Int) : ChallengeRecoveryState
    data object Cancelled : ChallengeRecoveryState
    data object TimedOut : ChallengeRecoveryState
}

enum class ChallengeRecoveryTerminal {
    Recovered,
    Failed,
    Cancelled,
    TimedOut,
}

class CloudflareChallenge internal constructor(
    val request: SourceLoginRequest,
    private val nanoTime: () -> Long = System::nanoTime,
    private val afterAttemptCompletionObserved: () -> Unit = {},
) {
    val url: String = request.url.toString()

    // Kept until Task 5C moves the existing dialog to recovery intents.
    val latch: CountDownLatch
        get() = synchronized(lifecycleLock) { currentAttempt.latch }
    @Volatile var resolved: Boolean = false

    private val lifecycleLock = Any()
    private var currentAttempt = newAttempt()
    private val mutableState = MutableStateFlow<ChallengeRecoveryState>(ChallengeRecoveryState.AwaitingUserAction)
    internal val actionMutex = Mutex()

    val state: StateFlow<ChallengeRecoveryState> = mutableState.asStateFlow()
    val terminal: ChallengeRecoveryTerminal?
        get() = synchronized(lifecycleLock) { currentAttempt.terminal }

    internal fun beginAction(job: Job?, state: ChallengeRecoveryState.Running): ChallengeRecoveryState? {
        var jobToCancel: Job? = null
        val result = synchronized(lifecycleLock) {
            val attempt = currentAttempt
            attempt.terminal?.let { return@synchronized mutableState.value }
            if (remainingMillis(attempt) <= 0) {
                jobToCancel = attempt.activeAction
                finishLocked(attempt, ChallengeRecoveryTerminal.TimedOut, ChallengeRecoveryState.TimedOut)
                return@synchronized ChallengeRecoveryState.TimedOut
            }
            attempt.activeAction = job
            mutableState.value = state
            null
        }
        jobToCancel?.cancel()
        return result
    }

    internal fun remainingMillis(): Long = synchronized(lifecycleLock) { remainingMillis(currentAttempt) }

    internal fun transition(state: ChallengeRecoveryState): ChallengeRecoveryState {
        var jobToCancel: Job? = null
        val result = synchronized(lifecycleLock) {
            val attempt = currentAttempt
            attempt.terminal?.let { return@synchronized mutableState.value }
            if (!attempt.commitClaimed && remainingMillis(attempt) <= 0) {
                jobToCancel = attempt.activeAction
                finishLocked(attempt, ChallengeRecoveryTerminal.TimedOut, ChallengeRecoveryState.TimedOut)
                ChallengeRecoveryState.TimedOut
            } else {
                mutableState.value = state
                state
            }
        }
        jobToCancel?.cancel()
        return result
    }

    internal fun claimCommit(): Boolean {
        return synchronized(lifecycleLock) {
            val attempt = currentAttempt
            if (attempt.terminal != null) return@synchronized false
            if (remainingMillis(attempt) <= 0) {
                finishLocked(attempt, ChallengeRecoveryTerminal.TimedOut, ChallengeRecoveryState.TimedOut)
                return@synchronized false
            }
            attempt.commitClaimed = true
            true
        }
    }

    internal fun complete(
        terminal: ChallengeRecoveryTerminal,
        state: ChallengeRecoveryState,
    ): Boolean {
        var jobToCancel: Job? = null
        val completed = synchronized(lifecycleLock) {
            val attempt = currentAttempt
            if (attempt.terminal != null) return@synchronized false
            if (terminal == ChallengeRecoveryTerminal.Cancelled || terminal == ChallengeRecoveryTerminal.TimedOut) {
                jobToCancel = attempt.activeAction
            }
            finishLocked(attempt, terminal, state)
            true
        }
        jobToCancel?.cancel()
        return completed
    }

    internal fun completeFromActiveAction(
        terminal: ChallengeRecoveryTerminal,
        state: ChallengeRecoveryState,
    ): Boolean = synchronized(lifecycleLock) {
        val attempt = currentAttempt
        if (attempt.terminal != null) return@synchronized false
        finishLocked(attempt, terminal, state)
        true
    }

    internal fun cancelOrAwaitCommit(): Boolean {
        var jobToCancel: Job? = null
        val waitForCommit = synchronized(lifecycleLock) {
            val attempt = currentAttempt
            if (attempt.terminal != null) return@synchronized false
            if (attempt.commitClaimed) return@synchronized true
            jobToCancel = attempt.activeAction
            finishLocked(attempt, ChallengeRecoveryTerminal.Cancelled, ChallengeRecoveryState.Cancelled)
            false
        }
        jobToCancel?.cancel()
        return waitForCommit
    }

    internal fun stateAfterTerminal(): ChallengeRecoveryState? = synchronized(lifecycleLock) {
        currentAttempt.terminal?.let { mutableState.value }
    }

    internal fun retry(): ChallengeRecoveryState = synchronized(lifecycleLock) {
        when (currentAttempt.terminal) {
            null -> {
                if (mutableState.value is ChallengeRecoveryState.RecoverableFailure) {
                    mutableState.value = ChallengeRecoveryState.AwaitingUserAction
                }
            }
            ChallengeRecoveryTerminal.Failed -> {
                if (
                    mutableState.value ==
                    ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.CommitFailed)
                ) {
                    currentAttempt = newAttempt()
                    resolved = false
                    mutableState.value = ChallengeRecoveryState.AwaitingUserAction
                }
            }
            ChallengeRecoveryTerminal.Recovered,
            ChallengeRecoveryTerminal.Cancelled,
            ChallengeRecoveryTerminal.TimedOut,
            -> Unit
        }
        mutableState.value
    }

    internal fun clearAction(job: Job?) {
        synchronized(lifecycleLock) {
            val attempt = currentAttempt
            if (attempt.activeAction == job && !attempt.commitClaimed) attempt.activeAction = null
        }
    }

    internal fun awaitTerminal(): ChallengeRecoveryTerminal {
        val attempt = synchronized(lifecycleLock) { currentAttempt }
        attempt.terminal?.let { return it }
        val completed = attempt.latch.await(remainingMillis(attempt), TimeUnit.MILLISECONDS)
        if (completed) afterAttemptCompletionObserved()
        attempt.terminal?.let { return it }
        if (completed && resolved && synchronized(lifecycleLock) { currentAttempt === attempt }) {
            complete(
                ChallengeRecoveryTerminal.Recovered,
                ChallengeRecoveryState.Recovered(emptySet(), 0),
            )
        } else if (completed && synchronized(lifecycleLock) { currentAttempt === attempt }) {
            complete(ChallengeRecoveryTerminal.Cancelled, ChallengeRecoveryState.Cancelled)
        } else {
            var jobToCancel: Job? = null
            val waitForCommit = synchronized(lifecycleLock) {
                if (attempt.terminal != null) return@synchronized false
                if (attempt.commitClaimed) return@synchronized true
                if (currentAttempt !== attempt) return@synchronized false
                jobToCancel = attempt.activeAction
                finishLocked(attempt, ChallengeRecoveryTerminal.TimedOut, ChallengeRecoveryState.TimedOut)
                false
            }
            jobToCancel?.cancel()
            if (waitForCommit) {
                attempt.latch.await()
                afterAttemptCompletionObserved()
            }
        }
        return checkNotNull(attempt.terminal)
    }

    private fun finishLocked(
        attempt: ChallengeAttempt,
        terminal: ChallengeRecoveryTerminal,
        state: ChallengeRecoveryState,
    ) {
        attempt.terminal = terminal
        attempt.commitClaimed = false
        attempt.activeAction = null
        if (currentAttempt === attempt) {
            if (terminal == ChallengeRecoveryTerminal.Recovered) resolved = true
            mutableState.value = state
        }
        attempt.latch.countDown()
    }

    private fun remainingMillis(attempt: ChallengeAttempt): Long =
        TimeUnit.NANOSECONDS.toMillis((attempt.deadlineNanos - nanoTime()).coerceAtLeast(0))

    private fun newAttempt() = ChallengeAttempt(
        deadlineNanos = nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.timeoutMillis),
    )
}

private class ChallengeAttempt(
    val deadlineNanos: Long,
    val latch: CountDownLatch = CountDownLatch(1),
    @Volatile var terminal: ChallengeRecoveryTerminal? = null,
    var activeAction: Job? = null,
    var commitClaimed: Boolean = false,
)
