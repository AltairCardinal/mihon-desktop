package mihon.desktop.ui.browse

import eu.kanade.tachiyomi.source.CatalogueSource
import mihon.desktop.network.DesktopBrowserLoginTicket
import mihon.desktop.network.DesktopSourceLoginSessionFactory
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.domain.source.service.AuthenticatedCookie
import tachiyomi.domain.source.service.AuthenticatedSession
import tachiyomi.domain.source.service.SourceLoginRequest
import tachiyomi.domain.source.service.SourceLoginState

class DesktopSourceLoginController(
    private val factory: DesktopSourceLoginSessionFactory,
    private val coordinator: SourceBrowseQueryCoordinator,
    private val timeoutMillis: Long = 120_000,
) {
    private val lock = Any()
    private var activeAttempt: ActiveAttempt? = null

    fun newAttempt(): DesktopSourceLoginAttempt = DesktopSourceLoginAttempt()

    suspend fun login(
        source: CatalogueSource,
        intent: DesktopSourceRecoveryIntent.OpenLogin,
    ): SourceLoginState = login(source, intent, newAttempt())

    suspend fun login(
        source: CatalogueSource,
        intent: DesktopSourceRecoveryIntent.OpenLogin,
        attempt: DesktopSourceLoginAttempt,
    ): SourceLoginState {
        val request = intent.request
        val url = intent.url.toHttpUrlOrNull() ?: return SourceLoginState.BrowserUnavailable
        if (!claim(attempt, url, request)) return SourceLoginState.BrowserUnavailable
        return try {
            val login = factory.create(
                onTicketRegistered = { ticket -> register(attempt, ticket) },
                onTicketTerminal = { ticket -> markTerminal(attempt, ticket) },
            )
            val result = login.login(SourceLoginRequest(url, timeoutMillis = timeoutMillis))
            if (result is SourceLoginState.Authenticated) coordinator.retryAfterLogin(source, request)
            result
        } finally {
            synchronized(lock) {
                if (activeAttempt?.identity === attempt) activeAttempt = null
            }
        }
    }

    fun submitCookies(attempt: DesktopSourceLoginAttempt, header: String): Boolean {
        val snapshot = synchronized(lock) {
            activeAttempt?.takeIf { it.identity === attempt && !it.terminal && !it.cancelled }
        } ?: return false
        val session = DesktopSourceCookieHeaderParser.parse(header, snapshot.url) ?: return false
        val ticket = synchronized(lock) {
            activeAttempt?.takeIf {
                it.identity === attempt && it.ticket === snapshot.ticket && !it.terminal && !it.cancelled
            }?.ticket
        } ?: return false
        return ticket.complete(session)
    }

    fun cancel(attempt: DesktopSourceLoginAttempt): Boolean {
        val ticket = synchronized(lock) {
            val current = activeAttempt?.takeIf { it.identity === attempt } ?: return false
            if (current.cancelled) return true
            activeAttempt = current.copy(cancelled = true)
            current.ticket
        }
        ticket?.cancel()
        return true
    }

    private fun claim(attempt: DesktopSourceLoginAttempt, url: HttpUrl, request: tachiyomi.domain.source.service.SourcePageRequest): Boolean =
        synchronized(lock) {
            if (activeAttempt != null) return@synchronized false
            activeAttempt = ActiveAttempt(attempt, url, request)
            true
        }

    private fun register(attempt: DesktopSourceLoginAttempt, ticket: DesktopBrowserLoginTicket): Boolean =
        synchronized(lock) {
            val current = activeAttempt?.takeIf { it.identity === attempt && it.ticket == null && !it.cancelled }
                ?: return@synchronized false
            activeAttempt = current.copy(ticket = ticket)
            true
        }

    private fun markTerminal(attempt: DesktopSourceLoginAttempt, ticket: DesktopBrowserLoginTicket) {
        synchronized(lock) {
            val current = activeAttempt
            if (current?.identity === attempt && current.ticket === ticket) {
                activeAttempt = current.copy(terminal = true)
            }
        }
    }

    override fun toString(): String =
        "DesktopSourceLoginController(active=${synchronized(lock) { activeAttempt != null }})"

    private data class ActiveAttempt(
        val identity: DesktopSourceLoginAttempt,
        val url: HttpUrl,
        val request: tachiyomi.domain.source.service.SourcePageRequest,
        val ticket: DesktopBrowserLoginTicket? = null,
        val cancelled: Boolean = false,
        val terminal: Boolean = false,
    )
}

class DesktopSourceLoginAttempt internal constructor() {
    override fun toString(): String = "DesktopSourceLoginAttempt(<opaque>)"
}

internal object DesktopSourceCookieHeaderParser {
    private val cookieName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

    fun parse(header: String, url: HttpUrl): AuthenticatedSession? {
        if (header.isBlank()) return null
        val names = hashSetOf<String>()
        val cookies = header.split(';').map { raw ->
            val pair = raw.trim()
            val separator = pair.indexOf('=')
            if (separator <= 0) return null
            val name = pair.substring(0, separator).trim()
            val value = pair.substring(separator + 1).trim()
            if (!cookieName.matches(name) || value.isBlank() || !value.all(::isCookieOctet) || !names.add(name)) {
                return null
            }
            AuthenticatedCookie(name, value, url.host, true, "/", null, url.isHttps, false)
        }
        return AuthenticatedSession(cookies)
    }

    private fun isCookieOctet(char: Char): Boolean = char.code == 0x21 ||
        char.code in 0x23..0x2B ||
        char.code in 0x2D..0x3A ||
        char.code in 0x3C..0x5B ||
        char.code in 0x5D..0x7E
}
