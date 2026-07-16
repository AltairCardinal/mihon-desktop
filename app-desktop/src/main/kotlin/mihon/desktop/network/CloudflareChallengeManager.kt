package mihon.desktop.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.source.service.AuthenticatedCookie
import tachiyomi.domain.source.service.AuthenticatedSession
import tachiyomi.domain.source.service.AuthenticatedSessionCommitter
import tachiyomi.domain.source.service.BrowserLoginAdapter
import tachiyomi.domain.source.service.BrowserLoginResult
import tachiyomi.domain.source.service.BrowserLoginSession
import tachiyomi.domain.source.service.BrowserOpenResult
import tachiyomi.domain.source.service.SourceLoginRequest
import tachiyomi.domain.source.service.SourceLoginSession
import tachiyomi.domain.source.service.SourceLoginState
import java.util.concurrent.ConcurrentLinkedQueue

class CloudflareChallengeManager(
    private val browserAdapter: BrowserLoginAdapter = UnavailableBrowserLoginAdapter,
    private val committer: AuthenticatedSessionCommitter? = null,
    private val flareSolverrClient: FlareSolverrClient? = null,
) {
    private val _challenges = MutableSharedFlow<CloudflareChallenge>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val challenges: SharedFlow<CloudflareChallenge> = _challenges

    private val recentChallenges = ConcurrentLinkedQueue<CloudflareChallenge>()

    internal fun publish(request: SourceLoginRequest): CloudflareChallenge =
        CloudflareChallenge(request).also(::emit)

    fun emit(challenge: CloudflareChallenge) {
        recentChallenges.add(challenge)
        _challenges.tryEmit(challenge)
    }

    suspend fun recover(
        challenge: CloudflareChallenge,
        intent: ChallengeRecoveryIntent,
    ): ChallengeRecoveryState {
        if (intent == ChallengeRecoveryIntent.Cancel) {
            challenge.complete(ChallengeRecoveryTerminal.Cancelled, ChallengeRecoveryState.Cancelled)
            challenge.cancelActiveAction()
            return challenge.stateAfterTerminal() ?: ChallengeRecoveryState.Cancelled
        }

        return challenge.actionMutex.withLock {
            challenge.stateAfterTerminal()?.let { return@withLock it }
            if (!challenge.ensureActive()) return@withLock ChallengeRecoveryState.TimedOut
            val actionJob = currentCoroutineContext()[Job]
            challenge.registerAction(actionJob)
            try {
                when (intent) {
                    ChallengeRecoveryIntent.Cancel -> error("cancel is handled before action serialization")
                    ChallengeRecoveryIntent.Retry -> {
                        challenge.updateState(ChallengeRecoveryState.AwaitingUserAction)
                        ChallengeRecoveryState.AwaitingUserAction
                    }
                    ChallengeRecoveryIntent.OpenBrowser -> recoverWithSession(
                        challenge = challenge,
                        action = ChallengeRecoveryAction.Browser,
                        adapter = browserAdapter,
                        invalidFailure = ChallengeRecoveryFailure.InvalidCookies,
                    )
                    is ChallengeRecoveryIntent.SubmitManualCookies -> recoverWithSession(
                        challenge = challenge,
                        action = ChallengeRecoveryAction.ManualCookies,
                        adapter = completedSessionAdapter(intent.session),
                        invalidFailure = ChallengeRecoveryFailure.InvalidCookies,
                    )
                    ChallengeRecoveryIntent.UseFlareSolverr -> recoverWithSolver(challenge)
                }
            } finally {
                challenge.clearAction(actionJob)
            }
        }
    }

    private suspend fun recoverWithSolver(challenge: CloudflareChallenge): ChallengeRecoveryState {
        val solver = flareSolverrClient
            ?: return challenge.fail(ChallengeRecoveryFailure.SolverUnavailable)
        challenge.updateState(ChallengeRecoveryState.Running(ChallengeRecoveryAction.FlareSolverr))
        val solved = try {
            solver.solve(challenge.request.url.toString())
        } catch (error: CancellationException) {
            challenge.complete(ChallengeRecoveryTerminal.Cancelled, ChallengeRecoveryState.Cancelled)
            throw error
        } ?: return challenge.fail(ChallengeRecoveryFailure.SolverFailed)

        if (!challenge.ensureActive()) return ChallengeRecoveryState.TimedOut
        if (solved.cookies.isEmpty()) return challenge.fail(ChallengeRecoveryFailure.SolverFailed)
        val session = AuthenticatedSession(
            solved.cookies.map { cookie ->
                AuthenticatedCookie(
                    name = cookie.name,
                    value = cookie.value,
                    domain = cookie.domain.trimStart('.'),
                    hostOnly = cookie.hostOnly,
                    path = cookie.path,
                    expiresAt = cookie.expiresAt,
                    secure = cookie.secure,
                    httpOnly = cookie.httpOnly,
                )
            },
        )
        return recoverWithSession(
            challenge = challenge,
            action = ChallengeRecoveryAction.FlareSolverr,
            adapter = completedSessionAdapter(session),
            invalidFailure = ChallengeRecoveryFailure.SolverFailed,
        )
    }

    private suspend fun recoverWithSession(
        challenge: CloudflareChallenge,
        action: ChallengeRecoveryAction,
        adapter: BrowserLoginAdapter,
        invalidFailure: ChallengeRecoveryFailure,
    ): ChallengeRecoveryState {
        val delegate = committer ?: MissingAuthenticatedSessionCommitter
        if (!challenge.ensureActive()) return ChallengeRecoveryState.TimedOut
        challenge.updateState(ChallengeRecoveryState.Running(action))
        val request = challenge.request.copy(timeoutMillis = challenge.remainingMillis().coerceAtLeast(1))
        val guardedCommitter = AuthenticatedSessionCommitter { loginRequest, session ->
            if (!challenge.ensureActive()) throw ChallengeExpiredException()
            delegate.commit(loginRequest, session)
        }
        val result = try {
            SourceLoginSession(adapter, guardedCommitter).login(request)
        } catch (error: CancellationException) {
            challenge.complete(ChallengeRecoveryTerminal.Cancelled, ChallengeRecoveryState.Cancelled)
            throw error
        }
        return when (result) {
            is SourceLoginState.Authenticated -> {
                val recovered = ChallengeRecoveryState.Recovered(result.cookieNames, result.cookieCount)
                if (challenge.complete(ChallengeRecoveryTerminal.Recovered, recovered)) {
                    recovered
                } else {
                    challenge.stateAfterTerminal() ?: ChallengeRecoveryState.TimedOut
                }
            }
            SourceLoginState.BrowserUnavailable -> challenge.fail(ChallengeRecoveryFailure.BrowserUnavailable)
            is SourceLoginState.InvalidCookies -> challenge.fail(invalidFailure)
            SourceLoginState.CommitFailed -> {
                if (challenge.terminal == ChallengeRecoveryTerminal.TimedOut) {
                    ChallengeRecoveryState.TimedOut
                } else {
                    challenge.fail(ChallengeRecoveryFailure.CommitFailed)
                }
            }
            SourceLoginState.Cancelled -> {
                challenge.complete(ChallengeRecoveryTerminal.Cancelled, ChallengeRecoveryState.Cancelled)
                ChallengeRecoveryState.Cancelled
            }
            SourceLoginState.TimedOut -> {
                challenge.complete(ChallengeRecoveryTerminal.TimedOut, ChallengeRecoveryState.TimedOut)
                ChallengeRecoveryState.TimedOut
            }
            SourceLoginState.Idle,
            SourceLoginState.OpeningBrowser,
            SourceLoginState.AwaitingCookies,
            -> error("Source login returned a non-terminal state")
        }
    }

    /** For tests and the Task 5C UI adapter: poll one pending recovery request. */
    internal fun tryReceive(): CloudflareChallenge? = recentChallenges.poll()
}

private fun CloudflareChallenge.fail(reason: ChallengeRecoveryFailure): ChallengeRecoveryState {
    val failure = ChallengeRecoveryState.RecoverableFailure(reason)
    updateState(failure)
    return failure
}

private fun completedSessionAdapter(session: AuthenticatedSession): BrowserLoginAdapter = BrowserLoginAdapter {
    BrowserOpenResult.Opened(
        object : BrowserLoginSession {
            override suspend fun awaitResult(): BrowserLoginResult = BrowserLoginResult.Completed(session)
            override fun cancel() = Unit
        },
    )
}

private object UnavailableBrowserLoginAdapter : BrowserLoginAdapter {
    override suspend fun open(request: SourceLoginRequest): BrowserOpenResult = BrowserOpenResult.Unavailable
}

private object MissingAuthenticatedSessionCommitter : AuthenticatedSessionCommitter {
    override suspend fun commit(request: SourceLoginRequest, session: AuthenticatedSession) {
        error("authenticated session committer is unavailable")
    }
}

private class ChallengeExpiredException : IllegalStateException("challenge recovery expired before commit")
