package mihon.desktop.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
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
import java.util.concurrent.ConcurrentHashMap
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
    private val solverUserAgents = ConcurrentHashMap<String, String>()

    internal fun solverUserAgentFor(url: HttpUrl): String? = solverUserAgents[url.host.lowercase()]

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
            if (challenge.cancelOrAwaitCommit()) {
                withContext(Dispatchers.IO) { challenge.awaitTerminal() }
            }
            return challenge.stateAfterTerminal() ?: ChallengeRecoveryState.Cancelled
        }

        return challenge.actionMutex.withLock {
            challenge.stateAfterTerminal()?.let { return@withLock it }
            if (intent == ChallengeRecoveryIntent.Retry) {
                return@withLock challenge.transition(ChallengeRecoveryState.AwaitingUserAction)
            }
            val actionJob = currentCoroutineContext()[Job]
            val action = when (intent) {
                ChallengeRecoveryIntent.OpenBrowser -> ChallengeRecoveryAction.Browser
                is ChallengeRecoveryIntent.SubmitManualCookies -> ChallengeRecoveryAction.ManualCookies
                ChallengeRecoveryIntent.UseFlareSolverr -> ChallengeRecoveryAction.FlareSolverr
                ChallengeRecoveryIntent.Cancel,
                ChallengeRecoveryIntent.Retry,
                -> error("non-action intent reached action registration")
            }
            challenge.beginAction(actionJob, ChallengeRecoveryState.Running(action))?.let { return@withLock it }
            try {
                when (intent) {
                    ChallengeRecoveryIntent.Cancel,
                    ChallengeRecoveryIntent.Retry,
                    -> error("non-action intent reached action execution")
                    ChallengeRecoveryIntent.OpenBrowser -> recoverWithSession(
                        challenge = challenge,
                        adapter = browserAdapter,
                        invalidFailure = ChallengeRecoveryFailure.InvalidCookies,
                    )
                    is ChallengeRecoveryIntent.SubmitManualCookies -> recoverWithSession(
                        challenge = challenge,
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
        val solved = try {
            withContext(Dispatchers.IO) {
                withTimeout(challenge.remainingMillis().coerceAtLeast(1)) {
                    solver.solve(challenge.request.url.toString())
                }
            }
        } catch (_: TimeoutCancellationException) {
            challenge.completeFromActiveAction(ChallengeRecoveryTerminal.TimedOut, ChallengeRecoveryState.TimedOut)
            return ChallengeRecoveryState.TimedOut
        } catch (error: CancellationException) {
            challenge.cancelOrAwaitCommit()
            throw error
        } ?: return challenge.fail(ChallengeRecoveryFailure.SolverFailed)

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
            adapter = completedSessionAdapter(session),
            invalidFailure = ChallengeRecoveryFailure.SolverFailed,
            solverUserAgent = solved.userAgent,
        )
    }

    private suspend fun recoverWithSession(
        challenge: CloudflareChallenge,
        adapter: BrowserLoginAdapter,
        invalidFailure: ChallengeRecoveryFailure,
        solverUserAgent: String? = null,
    ): ChallengeRecoveryState {
        val delegate = committer ?: MissingAuthenticatedSessionCommitter
        val request = challenge.request.copy(timeoutMillis = challenge.remainingMillis().coerceAtLeast(1))
        val guardedCommitter = AuthenticatedSessionCommitter { loginRequest, session ->
            if (!challenge.claimCommit()) throw ChallengeExpiredException()
            try {
                delegate.commit(loginRequest, session)
                solverUserAgent?.let { solverUserAgents[loginRequest.url.host.lowercase()] = it }
                challenge.complete(
                    ChallengeRecoveryTerminal.Recovered,
                    ChallengeRecoveryState.Recovered(session.cookieNames, session.cookies.size),
                )
            } catch (error: Exception) {
                challenge.complete(
                    ChallengeRecoveryTerminal.Failed,
                    ChallengeRecoveryState.RecoverableFailure(ChallengeRecoveryFailure.CommitFailed),
                )
                throw error
            }
        }
        val result = try {
            SourceLoginSession(adapter, guardedCommitter).login(request)
        } catch (error: CancellationException) {
            challenge.cancelOrAwaitCommit()
            throw error
        }
        return when (result) {
            is SourceLoginState.Authenticated -> checkNotNull(challenge.stateAfterTerminal())
            SourceLoginState.BrowserUnavailable -> challenge.fail(ChallengeRecoveryFailure.BrowserUnavailable)
            is SourceLoginState.InvalidCookies -> challenge.fail(invalidFailure)
            SourceLoginState.CommitFailed ->
                challenge.stateAfterTerminal() ?: challenge.fail(ChallengeRecoveryFailure.CommitFailed)
            SourceLoginState.Cancelled -> {
                challenge.complete(ChallengeRecoveryTerminal.Cancelled, ChallengeRecoveryState.Cancelled)
                challenge.stateAfterTerminal() ?: ChallengeRecoveryState.Cancelled
            }
            SourceLoginState.TimedOut -> {
                challenge.completeFromActiveAction(
                    ChallengeRecoveryTerminal.TimedOut,
                    ChallengeRecoveryState.TimedOut,
                )
                challenge.stateAfterTerminal() ?: ChallengeRecoveryState.TimedOut
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
    return transition(failure)
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
