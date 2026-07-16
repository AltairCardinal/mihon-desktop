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

fun interface DesktopBrowserOpener {
    fun open(uri: URI, completion: DesktopBrowserLoginTicket): Boolean
}

interface DesktopBrowserLoginTicket {
    fun complete(session: AuthenticatedSession): Boolean
    fun cancel(): Boolean
}

class DesktopBrowserLoginCompletion {
    internal fun register(): RegisteredBrowserLogin {
        val result = CompletableDeferred<BrowserLoginResult>()
        val ticket = ControlledBrowserLoginTicket(result)
        return RegisteredBrowserLogin(ticket, ControlledBrowserLoginSession(result, ticket))
    }

    private class ControlledBrowserLoginSession(
        private val result: CompletableDeferred<BrowserLoginResult>,
        private val ticket: DesktopBrowserLoginTicket,
    ) : BrowserLoginSession {
        override suspend fun awaitResult(): BrowserLoginResult = result.await()

        override fun cancel() {
            ticket.cancel()
        }
    }

    private class ControlledBrowserLoginTicket(
        private val result: CompletableDeferred<BrowserLoginResult>,
    ) : DesktopBrowserLoginTicket {
        override fun complete(session: AuthenticatedSession): Boolean =
            result.complete(BrowserLoginResult.Completed(session))

        override fun cancel(): Boolean = result.complete(BrowserLoginResult.Cancelled)

        override fun toString(): String = "DesktopBrowserLoginTicket(<opaque>)"
    }
}

internal data class RegisteredBrowserLogin(
    val ticket: DesktopBrowserLoginTicket,
    val session: BrowserLoginSession,
)

class DesktopBrowserLoginAdapter(
    private val browserOpener: DesktopBrowserOpener = SystemDesktopBrowserOpener,
    private val completion: DesktopBrowserLoginCompletion,
) : BrowserLoginAdapter {
    override suspend fun open(request: SourceLoginRequest): BrowserOpenResult {
        val registered = completion.register()
        val opened = runCatching { browserOpener.open(request.url.toUri(), registered.ticket) }.getOrDefault(false)
        if (!opened) {
            registered.ticket.cancel()
            return BrowserOpenResult.Unavailable
        }
        return BrowserOpenResult.Opened(registered.session)
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
    override fun open(uri: URI, completion: DesktopBrowserLoginTicket): Boolean {
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
