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
import java.util.concurrent.atomic.AtomicReference

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
    private val terminalReference = AtomicReference<ChallengeRecoveryTerminal?>(null)
    private val activeAction = AtomicReference<Job?>(null)
    private val mutableState = MutableStateFlow<ChallengeRecoveryState>(ChallengeRecoveryState.AwaitingUserAction)
    internal val actionMutex = Mutex()

    val state: StateFlow<ChallengeRecoveryState> = mutableState.asStateFlow()
    val terminal: ChallengeRecoveryTerminal?
        get() = terminalReference.get()

    internal fun updateState(state: ChallengeRecoveryState) {
        if (terminal == null) mutableState.value = state
    }

    internal fun remainingMillis(): Long =
        TimeUnit.NANOSECONDS.toMillis((deadlineNanos - nanoTime()).coerceAtLeast(0))

    internal fun ensureActive(): Boolean {
        if (terminal != null) return false
        if (remainingMillis() > 0) return true
        complete(ChallengeRecoveryTerminal.TimedOut, ChallengeRecoveryState.TimedOut)
        return false
    }

    internal fun complete(
        terminal: ChallengeRecoveryTerminal,
        state: ChallengeRecoveryState,
    ): Boolean {
        if (!terminalReference.compareAndSet(null, terminal)) return false
        if (terminal == ChallengeRecoveryTerminal.Recovered) resolved = true
        mutableState.value = state
        latch.countDown()
        if (terminal == ChallengeRecoveryTerminal.TimedOut) cancelActiveAction()
        return true
    }

    internal fun stateAfterTerminal(): ChallengeRecoveryState? = when (terminal) {
        ChallengeRecoveryTerminal.Recovered -> mutableState.value
        ChallengeRecoveryTerminal.Cancelled -> ChallengeRecoveryState.Cancelled
        ChallengeRecoveryTerminal.TimedOut -> ChallengeRecoveryState.TimedOut
        null -> null
    }

    internal fun registerAction(job: Job?) {
        activeAction.set(job)
    }

    internal fun clearAction(job: Job?) {
        activeAction.compareAndSet(job, null)
    }

    internal fun cancelActiveAction() {
        activeAction.getAndSet(null)?.cancel()
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
            complete(ChallengeRecoveryTerminal.TimedOut, ChallengeRecoveryState.TimedOut)
        }
        return checkNotNull(terminalReference.get())
    }
}
