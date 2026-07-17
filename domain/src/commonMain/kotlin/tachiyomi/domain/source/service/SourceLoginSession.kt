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

/**
 * Atomically commits a validated session using local, finite work only.
 *
 * Implementations must not perform network calls and must eventually return the real persisted success or failure.
 * The caller may deliberately finish a claimed commit despite cancellation, so cancellation is not a timeout signal.
 */
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
        val nowMillis = System.currentTimeMillis()
        val invalidDomainCookies = session.cookies.filterNot { it.matchesDomain(request.url) }
        val missing = request.requiredCookieNames - session.cookieNames
        val satisfiedRequired = session.cookies
            .filter {
                it.name in request.requiredCookieNames &&
                    it.value.isNotBlank() &&
                    it.isDeliverableTo(request.url, nowMillis)
            }
            .mapTo(hashSetOf()) { it.name }
        val unsatisfiedRequired = request.requiredCookieNames - missing - satisfiedRequired
        val rejected = invalidDomainCookies
            .mapTo(sortedSetOf()) { it.name }
            .apply {
                addAll(unsatisfiedRequired)
                addAll(session.conflictingCanonicalCookieNames())
            }
        if (missing.isNotEmpty() || rejected.isNotEmpty()) {
            return finish(SourceLoginState.InvalidCookies(missing.toSortedSet(), rejected))
        }

        val normalizedSession = session.normalizedForCommit(request, satisfiedRequired, nowMillis)

        return withContext(NonCancellable) {
            try {
                committer.commit(request, normalizedSession)
                finish(SourceLoginState.Authenticated(normalizedSession.cookieNames, normalizedSession.cookies.size))
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

private fun AuthenticatedCookie.matchesDomain(requestUrl: HttpUrl): Boolean {
    val host = requestUrl.host.lowercase().trimEnd('.')
    val cookieDomain = domain.lowercase().trim().trimStart('.').trimEnd('.')
    if (cookieDomain.isBlank()) return false
    return if (hostOnly) {
        host == cookieDomain
    } else {
        isRegistrableDomain(cookieDomain) && (host == cookieDomain || host.endsWith(".$cookieDomain"))
    }
}

private fun AuthenticatedCookie.isDeliverableTo(requestUrl: HttpUrl, nowMillis: Long): Boolean =
    matchesDomain(requestUrl) &&
        pathMatches(requestUrl.encodedPath, path.ifBlank { "/" }) &&
        (!secure || requestUrl.isHttps) &&
        expiresAt?.let { it > nowMillis } != false

private fun pathMatches(requestPath: String, cookiePath: String): Boolean {
    if (!cookiePath.startsWith('/')) return false
    return requestPath == cookiePath ||
        (requestPath.startsWith(cookiePath) && (cookiePath.endsWith('/') || requestPath[cookiePath.length] == '/'))
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

private fun AuthenticatedSession.normalizedForCommit(
    request: SourceLoginRequest,
    satisfiedRequired: Set<String>,
    nowMillis: Long,
): AuthenticatedSession {
    val candidates = cookies
        .asSequence()
        .filterNot { it.name in satisfiedRequired && it.name in request.requiredCookieNames && it.value.isBlank() }
        .map { cookie ->
            cookie.copy(
                domain = cookie.domain.lowercase().trim().trimStart('.').trimEnd('.'),
                path = cookie.path.ifBlank { "/" },
            )
        }
        .groupBy { CanonicalCookieIdentity(it.name, it.domain, it.path) }
        .values
        .map { duplicates ->
            val deliverableRequired = duplicates.filter {
                it.name in request.requiredCookieNames &&
                    it.value.isNotBlank() &&
                    it.isDeliverableTo(request.url, nowMillis)
            }
            checkNotNull((deliverableRequired.ifEmpty { duplicates }).maxWithOrNull(AUTHENTICATED_COOKIE_PREFERENCE))
        }
        .sortedWith(compareBy({ it.name }, { it.domain }, { it.path }))
    return AuthenticatedSession(candidates)
}

private fun AuthenticatedSession.conflictingCanonicalCookieNames(): Set<String> = cookies
    .map { cookie ->
        cookie.copy(
            domain = cookie.domain.lowercase().trim().trimStart('.').trimEnd('.'),
            path = cookie.path.ifBlank { "/" },
        )
    }
    .groupBy { CanonicalCookieIdentity(it.name, it.domain, it.path) }
    .filterValues { duplicates -> duplicates.mapNotNull { it.value.takeIf(String::isNotBlank) }.toSet().size > 1 }
    .values
    .mapTo(sortedSetOf()) { it.first().name }

private data class CanonicalCookieIdentity(
    val name: String,
    val domain: String,
    val path: String,
)

private val AUTHENTICATED_COOKIE_PREFERENCE = compareBy<AuthenticatedCookie>(
    { it.value.isNotBlank() },
    { it.hostOnly },
    { it.secure },
    { it.httpOnly },
    { it.expiresAt ?: Long.MAX_VALUE },
)
