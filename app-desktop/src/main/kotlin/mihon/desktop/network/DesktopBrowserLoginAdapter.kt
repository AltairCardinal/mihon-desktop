package mihon.desktop.network

import eu.kanade.tachiyomi.network.DesktopCookieJar
import kotlinx.coroutines.CompletableDeferred
import okhttp3.Cookie
import tachiyomi.domain.source.service.AuthenticatedCookie
import tachiyomi.domain.source.service.AuthenticatedSession
import tachiyomi.domain.source.service.AuthenticatedSessionCommitter
import tachiyomi.domain.source.service.BrowserLoginAdapter
import tachiyomi.domain.source.service.BrowserLoginResult
import tachiyomi.domain.source.service.BrowserLoginSession
import tachiyomi.domain.source.service.BrowserOpenResult
import tachiyomi.domain.source.service.SourceLoginRequest
import java.awt.Desktop
import java.net.URI
import java.util.UUID

fun interface DesktopBrowserOpener {
    fun open(uri: URI): Boolean
}

class DesktopBrowserLoginCompletion {
    private val pending = linkedMapOf<String, CompletableDeferred<BrowserLoginResult>>()

    internal fun register(sessionId: String): BrowserLoginSession {
        val result = CompletableDeferred<BrowserLoginResult>()
        synchronized(pending) {
            check(pending.put(sessionId, result) == null) { "Duplicate browser login session" }
        }
        return ControlledBrowserLoginSession(sessionId, result, this)
    }

    fun complete(sessionId: String, session: AuthenticatedSession): Boolean =
        take(sessionId)?.complete(BrowserLoginResult.Completed(session)) == true

    fun cancel(sessionId: String): Boolean =
        take(sessionId)?.complete(BrowserLoginResult.Cancelled) == true

    fun pendingSessionIds(): Set<String> = synchronized(pending) { pending.keys.toSet() }

    private fun take(sessionId: String): CompletableDeferred<BrowserLoginResult>? =
        synchronized(pending) { pending.remove(sessionId) }

    private class ControlledBrowserLoginSession(
        private val sessionId: String,
        private val result: CompletableDeferred<BrowserLoginResult>,
        private val completion: DesktopBrowserLoginCompletion,
    ) : BrowserLoginSession {
        override suspend fun awaitResult(): BrowserLoginResult = result.await()

        override fun cancel() {
            completion.cancel(sessionId)
        }
    }
}

class DesktopBrowserLoginAdapter(
    private val browserOpener: DesktopBrowserOpener = SystemDesktopBrowserOpener,
    private val completion: DesktopBrowserLoginCompletion,
) : BrowserLoginAdapter {
    override suspend fun open(request: SourceLoginRequest): BrowserOpenResult {
        val opened = runCatching { browserOpener.open(request.url.toUri()) }.getOrDefault(false)
        if (!opened) return BrowserOpenResult.Unavailable
        val sessionId = UUID.randomUUID().toString()
        return BrowserOpenResult.Opened(completion.register(sessionId))
    }
}

class DesktopAuthenticatedSessionCommitter(
    private val cookieJar: DesktopCookieJar,
) : AuthenticatedSessionCommitter {
    override suspend fun commit(request: SourceLoginRequest, session: AuthenticatedSession) {
        cookieJar.commitAuthenticatedSession(request.url, session.cookies.map(AuthenticatedCookie::toOkHttpCookie))
    }
}

private object SystemDesktopBrowserOpener : DesktopBrowserOpener {
    override fun open(uri: URI): Boolean {
        if (!Desktop.isDesktopSupported()) return false
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.BROWSE)) return false
        desktop.browse(uri)
        return true
    }
}

private fun AuthenticatedCookie.toOkHttpCookie(): Cookie = Cookie.Builder()
    .name(name)
    .value(value)
    .apply {
        if (hostOnly) hostOnlyDomain(domain.trimStart('.')) else domain(domain.trimStart('.'))
    }
    .path(path)
    .apply { expiresAt?.let(::expiresAt) }
    .apply { if (secure) secure() }
    .apply { if (httpOnly) httpOnly() }
    .build()
