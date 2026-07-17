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
import java.util.IdentityHashMap

fun interface DesktopBrowserOpener {
    fun open(uri: URI, completion: DesktopBrowserLoginTicket): Boolean
}

interface DesktopBrowserLoginTicket {
    fun complete(session: AuthenticatedSession): Boolean
    fun cancel(): Boolean
}

class DesktopBrowserLoginCompletion {
    internal fun register(onTerminal: (DesktopBrowserLoginTicket) -> Unit = {}): RegisteredBrowserLogin {
        val result = CompletableDeferred<BrowserLoginResult>()
        val ticket = ControlledBrowserLoginTicket(result, onTerminal)
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
        private val onTerminal: (DesktopBrowserLoginTicket) -> Unit,
    ) : DesktopBrowserLoginTicket {
        override fun complete(session: AuthenticatedSession): Boolean = finish(BrowserLoginResult.Completed(session))

        override fun cancel(): Boolean = finish(BrowserLoginResult.Cancelled)

        private fun finish(terminal: BrowserLoginResult): Boolean {
            val completed = result.complete(terminal)
            if (completed) onTerminal(this)
            return completed
        }

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
    private val onTicketRegistered: (DesktopBrowserLoginTicket) -> Boolean = { true },
    private val onTicketTerminal: (DesktopBrowserLoginTicket) -> Unit = {},
) : BrowserLoginAdapter {
    override suspend fun open(request: SourceLoginRequest): BrowserOpenResult {
        val registered = completion.register(onTicketTerminal)
        if (!onTicketRegistered(registered.ticket)) {
            registered.ticket.cancel()
            return BrowserOpenResult.Unavailable
        }
        val opened = runCatching { browserOpener.open(request.url.toUri(), registered.ticket) }.getOrDefault(false)
        if (!opened) {
            registered.ticket.cancel()
            return BrowserOpenResult.Unavailable
        }
        return BrowserOpenResult.Opened(registered.session)
    }
}

class DesktopChallengeBrowserLoginBridge(
    private val completion: DesktopBrowserLoginCompletion = DesktopBrowserLoginCompletion(),
    browserOpener: DesktopBrowserOpener? = null,
) {
    private val browserOpener = browserOpener ?: SystemDesktopBrowserOpener
    private val pending = IdentityHashMap<CloudflareChallenge, DesktopBrowserLoginTicket>()

    fun adapterFor(challenge: CloudflareChallenge): BrowserLoginAdapter = DesktopBrowserLoginAdapter(
        browserOpener = browserOpener,
        completion = completion,
        onTicketRegistered = { ticket ->
            synchronized(pending) {
                if (pending.containsKey(challenge)) false else true.also { pending[challenge] = ticket }
            }
        },
        onTicketTerminal = { ticket ->
            synchronized(pending) {
                if (pending[challenge] === ticket) pending.remove(challenge)
            }
        },
    )

    fun complete(challenge: CloudflareChallenge, session: AuthenticatedSession): Boolean =
        synchronized(pending) { pending[challenge] }?.complete(session) ?: false

    fun cancel(challenge: CloudflareChallenge): Boolean =
        synchronized(pending) { pending[challenge] }?.cancel() ?: false

    override fun toString(): String =
        "DesktopChallengeBrowserLoginBridge(pending=${synchronized(pending) { pending.size }})"
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
