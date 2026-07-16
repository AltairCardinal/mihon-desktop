package tachiyomi.domain.source.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl

data class SourceLoginRequest(
    val url: HttpUrl,
    val requiredCookieNames: Set<String> = emptySet(),
    val timeoutMillis: Long,
) {
    init {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
    }
}

data class AuthenticatedCookie(
    val name: String,
    val value: String,
    val domain: String,
    val hostOnly: Boolean,
    val path: String,
    val expiresAt: Long?,
    val secure: Boolean,
    val httpOnly: Boolean,
) {
    override fun toString(): String =
        "AuthenticatedCookie(name=$name, domain=$domain, hostOnly=$hostOnly, path=$path, value=<redacted>)"
}

data class AuthenticatedSession(
    val cookies: List<AuthenticatedCookie>,
) {
    val cookieNames: Set<String> = cookies.mapTo(linkedSetOf()) { it.name }

    override fun toString(): String =
        "AuthenticatedSession(cookieNames=${cookieNames.sorted()}, cookieCount=${cookies.size})"
}

sealed interface BrowserOpenResult {
    data class Opened(val session: BrowserLoginSession) : BrowserOpenResult
    data object Unavailable : BrowserOpenResult
}

sealed interface BrowserLoginResult {
    data class Completed(val session: AuthenticatedSession) : BrowserLoginResult
    data object Cancelled : BrowserLoginResult
}

fun interface BrowserLoginAdapter {
    suspend fun open(request: SourceLoginRequest): BrowserOpenResult
}

interface BrowserLoginSession {
    suspend fun awaitResult(): BrowserLoginResult
    fun cancel()
}

fun interface AuthenticatedSessionCommitter {
    suspend fun commit(request: SourceLoginRequest, session: AuthenticatedSession)
}

sealed interface SourceLoginState {
    data object Idle : SourceLoginState
    data object OpeningBrowser : SourceLoginState
    data object AwaitingCookies : SourceLoginState
    data class Authenticated(val cookieNames: Set<String>, val cookieCount: Int) : SourceLoginState
    data class InvalidCookies(
        val missingRequiredCookieNames: Set<String>,
        val rejectedCookieNames: Set<String>,
    ) : SourceLoginState
    data object Cancelled : SourceLoginState
    data object TimedOut : SourceLoginState
    data object BrowserUnavailable : SourceLoginState
    data object CommitFailed : SourceLoginState
}

class SourceLoginSession(
    private val browserAdapter: BrowserLoginAdapter,
    private val committer: AuthenticatedSessionCommitter,
) {
    private val mutableState = MutableStateFlow<SourceLoginState>(SourceLoginState.Idle)
    val state: StateFlow<SourceLoginState> = mutableState.asStateFlow()

    suspend fun login(request: SourceLoginRequest): SourceLoginState {
        mutableState.value = SourceLoginState.OpeningBrowser
        var browserSession: BrowserLoginSession? = null
        val browserPhase = try {
            withTimeout(request.timeoutMillis) {
                when (val opened = browserAdapter.open(request)) {
                    BrowserOpenResult.Unavailable -> BrowserPhase.Unavailable
                    is BrowserOpenResult.Opened -> {
                        browserSession = opened.session
                        mutableState.value = SourceLoginState.AwaitingCookies
                        when (val result = opened.session.awaitResult()) {
                            BrowserLoginResult.Cancelled -> BrowserPhase.Cancelled
                            is BrowserLoginResult.Completed -> BrowserPhase.Completed(result.session)
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            browserSession?.cancel()
            return finish(SourceLoginState.TimedOut)
        } catch (error: CancellationException) {
            browserSession?.cancel()
            finish(SourceLoginState.Cancelled)
            throw error
        }
        return when (browserPhase) {
            BrowserPhase.Unavailable -> finish(SourceLoginState.BrowserUnavailable)
            BrowserPhase.Cancelled -> finish(SourceLoginState.Cancelled)
            is BrowserPhase.Completed -> commitValidated(request, browserPhase.session)
        }
    }

    private suspend fun commitValidated(
        request: SourceLoginRequest,
        session: AuthenticatedSession,
    ): SourceLoginState {
        val missing = request.requiredCookieNames - session.cookieNames
        val rejected = session.cookies
            .filterNot { it.matches(request.url) }
            .mapTo(sortedSetOf()) { it.name }
        if (missing.isNotEmpty() || rejected.isNotEmpty()) {
            return finish(SourceLoginState.InvalidCookies(missing.toSortedSet(), rejected))
        }

        return withContext(NonCancellable) {
            try {
                committer.commit(request, session)
                finish(SourceLoginState.Authenticated(session.cookieNames, session.cookies.size))
            } catch (_: Exception) {
                finish(SourceLoginState.CommitFailed)
            }
        }
    }

    private fun finish(terminal: SourceLoginState): SourceLoginState {
        mutableState.value = terminal
        return terminal
    }
}

private sealed interface BrowserPhase {
    data object Unavailable : BrowserPhase
    data object Cancelled : BrowserPhase
    data class Completed(val session: AuthenticatedSession) : BrowserPhase
}

private fun AuthenticatedCookie.matches(requestUrl: HttpUrl): Boolean {
    val host = requestUrl.host.lowercase().trimEnd('.')
    val cookieDomain = domain.lowercase().trim().trimStart('.').trimEnd('.')
    if (cookieDomain.isBlank()) return false
    return if (hostOnly) {
        host == cookieDomain
    } else {
        isRegistrableDomain(cookieDomain) && (host == cookieDomain || host.endsWith(".$cookieDomain"))
    }
}

private fun isRegistrableDomain(domain: String): Boolean =
    try {
        HttpUrl.Builder()
            .scheme("https")
            .host(domain)
            .build()
            .topPrivateDomain() != null
    } catch (_: IllegalArgumentException) {
        false
    }
