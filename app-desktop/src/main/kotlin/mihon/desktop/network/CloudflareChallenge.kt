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
) {
    val url: String = request.url.toString()

    // Kept until Task 5C moves the existing dialog to recovery intents.
    val latch: CountDownLatch = CountDownLatch(1)
    @Volatile var resolved: Boolean = false

    private val deadlineNanos = nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.timeoutMillis)
    private val lifecycleLock = Any()
    private var terminalValue: ChallengeRecoveryTerminal? = null
    private var activeAction: Job? = null
    private var commitClaimed = false
    private val mutableState = MutableStateFlow<ChallengeRecoveryState>(ChallengeRecoveryState.AwaitingUserAction)
    internal val actionMutex = Mutex()

    val state: StateFlow<ChallengeRecoveryState> = mutableState.asStateFlow()
    val terminal: ChallengeRecoveryTerminal?
        get() = synchronized(lifecycleLock) { terminalValue }

    internal fun beginAction(job: Job?, state: ChallengeRecoveryState.Running): ChallengeRecoveryState? {
        var jobToCancel: Job? = null
        val result = synchronized(lifecycleLock) {
            terminalValue?.let { return@synchronized mutableState.value }
            if (remainingMillis() <= 0) {
                jobToCancel = activeAction
                finishLocked(ChallengeRecoveryTerminal.TimedOut, ChallengeRecoveryState.TimedOut)
                return@synchronized ChallengeRecoveryState.TimedOut
            }
            activeAction = job
            mutableState.value = state
            null
        }
        jobToCancel?.cancel()
        return result
    }

    internal fun remainingMillis(): Long =
        TimeUnit.NANOSECONDS.toMillis((deadlineNanos - nanoTime()).coerceAtLeast(0))

    internal fun transition(state: ChallengeRecoveryState): ChallengeRecoveryState {
        var jobToCancel: Job? = null
        val result = synchronized(lifecycleLock) {
            terminalValue?.let { return@synchronized mutableState.value }
            if (!commitClaimed && remainingMillis() <= 0) {
                jobToCancel = activeAction
                finishLocked(ChallengeRecoveryTerminal.TimedOut, ChallengeRecoveryState.TimedOut)
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
            if (terminalValue != null) return@synchronized false
            if (remainingMillis() <= 0) {
                finishLocked(ChallengeRecoveryTerminal.TimedOut, ChallengeRecoveryState.TimedOut)
                return@synchronized false
            }
            commitClaimed = true
            true
        }
    }

    internal fun complete(
        terminal: ChallengeRecoveryTerminal,
        state: ChallengeRecoveryState,
    ): Boolean {
        var jobToCancel: Job? = null
        val completed = synchronized(lifecycleLock) {
            if (terminalValue != null) return@synchronized false
            if (terminal == ChallengeRecoveryTerminal.Cancelled || terminal == ChallengeRecoveryTerminal.TimedOut) {
                jobToCancel = activeAction
            }
            finishLocked(terminal, state)
            true
        }
        jobToCancel?.cancel()
        return completed
    }

    internal fun completeFromActiveAction(
        terminal: ChallengeRecoveryTerminal,
        state: ChallengeRecoveryState,
    ): Boolean = synchronized(lifecycleLock) {
        if (terminalValue != null) return@synchronized false
        finishLocked(terminal, state)
        true
    }

    internal fun cancelOrAwaitCommit(): Boolean {
        var jobToCancel: Job? = null
        val waitForCommit = synchronized(lifecycleLock) {
            if (terminalValue != null) return@synchronized false
            if (commitClaimed) return@synchronized true
            jobToCancel = activeAction
            finishLocked(ChallengeRecoveryTerminal.Cancelled, ChallengeRecoveryState.Cancelled)
            false
        }
        jobToCancel?.cancel()
        return waitForCommit
    }

    internal fun stateAfterTerminal(): ChallengeRecoveryState? = synchronized(lifecycleLock) {
        terminalValue?.let { mutableState.value }
    }

    internal fun clearAction(job: Job?) {
        synchronized(lifecycleLock) {
            if (activeAction == job && !commitClaimed) activeAction = null
        }
    }

    internal fun awaitTerminal(): ChallengeRecoveryTerminal {
        terminal?.let { return it }
        val completed = latch.await(remainingMillis(), TimeUnit.MILLISECONDS)
        terminal?.let { return it }
        if (completed && resolved) {
            complete(
                ChallengeRecoveryTerminal.Recovered,
                ChallengeRecoveryState.Recovered(emptySet(), 0),
            )
        } else if (completed) {
            complete(ChallengeRecoveryTerminal.Cancelled, ChallengeRecoveryState.Cancelled)
        } else {
            var jobToCancel: Job? = null
            val waitForCommit = synchronized(lifecycleLock) {
                if (terminalValue != null) return@synchronized false
                if (commitClaimed) return@synchronized true
                jobToCancel = activeAction
                finishLocked(ChallengeRecoveryTerminal.TimedOut, ChallengeRecoveryState.TimedOut)
                false
            }
            jobToCancel?.cancel()
            if (waitForCommit) latch.await()
        }
        return checkNotNull(terminal)
    }

    private fun finishLocked(
        terminal: ChallengeRecoveryTerminal,
        state: ChallengeRecoveryState,
    ) {
        terminalValue = terminal
        commitClaimed = false
        activeAction = null
        if (terminal == ChallengeRecoveryTerminal.Recovered) resolved = true
        mutableState.value = state
        latch.countDown()
    }
}
