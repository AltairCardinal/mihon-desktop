package mihon.desktop.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

fun interface AuthenticatedCookieLookup {
    fun loadForRequest(url: HttpUrl): List<AuthenticatedCookie>
}

class CloudflareChallengeManager(
    private val browserAdapter: BrowserLoginAdapter = UnavailableBrowserLoginAdapter,
    private val committer: AuthenticatedSessionCommitter? = null,
    private val flareSolverrClient: FlareSolverrClient? = null,
    private val commitDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val authenticatedCookieLookup: AuthenticatedCookieLookup = AuthenticatedCookieLookup { emptyList() },
    private val browserAdapterProvider: ((CloudflareChallenge) -> BrowserLoginAdapter)? = null,
    private val committerProvider: (() -> AuthenticatedSessionCommitter)? = null,
    private val flareSolverrClientProvider: (() -> FlareSolverrClient?)? = null,
) {
    private val _challenges = MutableSharedFlow<CloudflareChallenge>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val challenges: SharedFlow<CloudflareChallenge> = _challenges

    private val recentChallenge = AtomicReference<CloudflareChallenge?>()
    private val solverUserAgents = ConcurrentHashMap<String, SolverUserAgentBinding>()
    private val hostCommitLocks = List(COMMIT_LOCK_STRIPES) { Mutex() }
    internal val commitLockCount: Int
        get() = hostCommitLocks.size

    internal fun solverUserAgentFor(url: HttpUrl): String? {
        val host = url.host.lowercase()
        repeat(BINDING_LOOKUP_RETRIES) {
            val binding = solverUserAgents[host] ?: return null
            val applicableCredentials = binding.credentials.filter { it.appliesTo(url) }
            if (applicableCredentials.isEmpty()) return null
            val actualCookies = runCatching { authenticatedCookieLookup.loadForRequest(url) }.getOrDefault(emptyList())
            if (solverUserAgents[host] !== binding) return@repeat
            val invalidCredentials = applicableCredentials.filterNot { credential ->
                credential.isBackedBy(actualCookies, currentTimeMillis())
            }.toSet()
            if (invalidCredentials.isEmpty()) return binding.userAgent

            val remainingCredentials = binding.credentials.filterNot(invalidCredentials::contains)
            val updated = if (remainingCredentials.isEmpty()) {
                solverUserAgents.remove(host, binding)
            } else {
                solverUserAgents.replace(host, binding, binding.copy(credentials = remainingCredentials))
            }
            if (!updated) return@repeat
        }
        return null
    }

    internal fun solverUserAgentForOutboundRequest(
        url: HttpUrl,
        cookieHeaders: List<String>,
    ): String? {
        val outboundCookies = parseOutboundCookies(cookieHeaders) ?: return null
        val host = url.host.lowercase()
        repeat(BINDING_LOOKUP_RETRIES) {
            val binding = solverUserAgents[host] ?: return null
            val applicableCredentials = binding.credentials.filter { it.appliesTo(url) }
            if (applicableCredentials.isEmpty()) return null

            val unmatchedClearances = outboundCookies
                .filter { it.name == CF_CLEARANCE_COOKIE_NAME }
                .toMutableList()
            val nowMillis = currentTimeMillis()
            val invalidCredentials = applicableCredentials.filter { credential ->
                if (credential.isExpired(nowMillis)) {
                    true
                } else {
                    val outboundIndex = unmatchedClearances.indexOfFirst(credential::matchesOutbound)
                    if (outboundIndex < 0) {
                        true
                    } else {
                        unmatchedClearances.removeAt(outboundIndex)
                        false
                    }
                }
            }.toSet()

            if (solverUserAgents[host] !== binding) return@repeat
            if (invalidCredentials.isEmpty()) {
                return binding.userAgent.takeIf { unmatchedClearances.isEmpty() }
            }

            val remainingCredentials = binding.credentials.filterNot(invalidCredentials::contains)
            val updated = if (remainingCredentials.isEmpty()) {
                solverUserAgents.remove(host, binding)
            } else {
                solverUserAgents.replace(host, binding, binding.copy(credentials = remainingCredentials))
            }
            if (!updated) return@repeat
            return null
        }
        return null
    }

    internal fun publish(request: SourceLoginRequest): CloudflareChallenge =
        CloudflareChallenge(request).also(::emit)

    fun emit(challenge: CloudflareChallenge) {
        recentChallenge.set(challenge)
        _challenges.tryEmit(challenge)
    }

    suspend fun recover(
        challenge: CloudflareChallenge,
        intent: ChallengeRecoveryIntent,
    ): ChallengeRecoveryState {
        if (intent == ChallengeRecoveryIntent.Cancel) {
            val cancellation = challenge.cancelOrAwaitCommit()
            withContext(Dispatchers.IO) { cancellation.awaitTerminal() }
            return checkNotNull(cancellation.stateAfterTerminal())
        }

        return challenge.actionMutex.withLock {
            if (intent == ChallengeRecoveryIntent.Retry) {
                return@withLock challenge.retry()
            }
            challenge.stateAfterTerminal()?.let { return@withLock it }
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
                        adapter = browserAdapterProvider?.invoke(challenge) ?: browserAdapter,
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
        val solver = flareSolverrClient ?: flareSolverrClientProvider?.invoke()
            ?: return challenge.fail(ChallengeRecoveryFailure.SolverUnavailable)
        val solved = try {
            withContext(Dispatchers.IO) {
                withTimeout(challenge.remainingMillis().coerceAtLeast(1)) {
                    solver.solve(challenge.request.url.toString())
                }
            }
        } catch (error: TimeoutCancellationException) {
            challenge.completeFromActiveAction(ChallengeRecoveryTerminal.TimedOut, ChallengeRecoveryState.TimedOut)
            throw error
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
        val request = challenge.request.copy(timeoutMillis = challenge.remainingMillis().coerceAtLeast(1))
        val guardedCommitter = AuthenticatedSessionCommitter { loginRequest, session ->
            // Claim and the final result bracket one local atomic commit. Once claimed, cancellation
            // cannot manufacture a timeout while persistence is still able to complete later.
            if (!challenge.claimCommit()) throw ChallengeExpiredException()
            try {
                val host = loginRequest.url.host.lowercase()
                hostCommitLock(host).withLock {
                    withContext(commitDispatcher) {
                        (committer ?: committerProvider?.invoke() ?: MissingAuthenticatedSessionCommitter)
                            .commit(loginRequest, session)
                    }
                    if (solverUserAgent == null) {
                        solverUserAgents.remove(host)
                    } else {
                        val clearances = session.cookies.filter {
                            it.name == CF_CLEARANCE_COOKIE_NAME && it.value.isNotBlank()
                        }
                        check(clearances.isNotEmpty())
                        solverUserAgents[host] = SolverUserAgentBinding(
                            userAgent = solverUserAgent,
                            credentials = clearances.map { it.toBoundCredential() },
                        )
                    }
                }
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
                challenge.completeFromActiveAction(
                    ChallengeRecoveryTerminal.Cancelled,
                    ChallengeRecoveryState.Cancelled,
                )
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
    internal fun tryReceive(): CloudflareChallenge? = recentChallenge.getAndSet(null)

    private fun hostCommitLock(host: String): Mutex =
        hostCommitLocks[Math.floorMod(host.lowercase().hashCode(), hostCommitLocks.size)]

    private companion object {
        const val COMMIT_LOCK_STRIPES = 64
        const val BINDING_LOOKUP_RETRIES = 3
    }
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

private data class SolverUserAgentBinding(
    val userAgent: String,
    val credentials: List<BoundClearanceCredential>,
)

private data class ClearanceCookieIdentity(
    val name: String,
    val domain: String,
    val path: String,
)

private data class BoundClearanceCredential(
    val identity: ClearanceCookieIdentity,
    val hostOnly: Boolean,
    val secure: Boolean,
    val valueFingerprint: String,
    val expiresAt: Long?,
) {
    fun isExpired(nowMillis: Long): Boolean = expiresAt?.let { it <= nowMillis } == true

    fun appliesTo(url: HttpUrl): Boolean {
        val host = url.host.lowercase().trimEnd('.')
        val domainMatches = if (hostOnly) {
            host == identity.domain
        } else {
            host == identity.domain || host.endsWith(".${identity.domain}")
        }
        return domainMatches &&
            pathMatches(url.encodedPath, identity.path) &&
            (!secure || url.isHttps)
    }

    fun isBackedBy(actualCookies: List<AuthenticatedCookie>, nowMillis: Long): Boolean =
        !isExpired(nowMillis) && actualCookies.any { cookie ->
            cookie.value.isNotBlank() &&
                cookie.toIdentity() == identity &&
                cookie.hostOnly == hostOnly &&
                cookie.secure == secure &&
                cookie.value.fingerprint() == valueFingerprint &&
                cookie.expiresAt?.let { it > nowMillis } != false
        }

    fun matchesOutbound(cookie: OutboundCookie): Boolean =
        identity.name == cookie.name && valueFingerprint == cookie.valueFingerprint
}

private data class OutboundCookie(
    val name: String,
    val valueFingerprint: String,
)

private fun AuthenticatedCookie.toBoundCredential() = BoundClearanceCredential(
    identity = toIdentity(),
    hostOnly = hostOnly,
    secure = secure,
    valueFingerprint = value.fingerprint(),
    expiresAt = expiresAt,
)

private fun AuthenticatedCookie.toIdentity() = ClearanceCookieIdentity(
    name = name,
    domain = domain.lowercase().trim().trimStart('.').trimEnd('.'),
    path = path.ifBlank { "/" },
)

private fun pathMatches(requestPath: String, cookiePath: String): Boolean =
    requestPath == cookiePath ||
        (requestPath.startsWith(cookiePath) && (cookiePath.endsWith('/') || requestPath[cookiePath.length] == '/'))

private fun parseOutboundCookies(cookieHeaders: List<String>): List<OutboundCookie>? {
    if (cookieHeaders.size != 1) return emptyList<OutboundCookie>().takeIf { cookieHeaders.isEmpty() }
    val header = cookieHeaders.single()
    if (header.isEmpty()) return null
    return header.split("; ").map { pair ->
        val separator = pair.indexOf('=')
        if (separator <= 0) return null
        val name = pair.substring(0, separator)
        val value = pair.substring(separator + 1)
        if (!name.all(Char::isCookieNameToken) || !value.all(Char::isCookieOctet)) return null
        OutboundCookie(
            name = name,
            valueFingerprint = value.fingerprint(),
        )
    }
}

private fun Char.isCookieNameToken(): Boolean =
    this in '0'..'9' ||
        this in 'A'..'Z' ||
        this in 'a'..'z' ||
        this in "!#$%&'*+-.^_`|~"

private fun Char.isCookieOctet(): Boolean =
    this == '\u0021' ||
        this in '\u0023'..'\u002B' ||
        this in '\u002D'..'\u003A' ||
        this in '\u003C'..'\u005B' ||
        this in '\u005D'..'\u007E'

private fun String.fingerprint(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
