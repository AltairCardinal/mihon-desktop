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
    private var ticket: DesktopBrowserLoginTicket? = null
    private var loginUrl: HttpUrl? = null

    suspend fun login(
        source: CatalogueSource,
        intent: DesktopSourceRecoveryIntent.OpenLogin,
    ): SourceLoginState {
        val request = intent.request
        val url = intent.url.toHttpUrlOrNull() ?: return SourceLoginState.BrowserUnavailable
        loginUrl = url
        val login = factory.create(
            onTicketRegistered = { candidate -> true.also { ticket = candidate } },
            onTicketTerminal = { candidate -> if (ticket === candidate) ticket = null },
        )
        val result = login.login(SourceLoginRequest(url, timeoutMillis = timeoutMillis))
        if (result is SourceLoginState.Authenticated) coordinator.retryAfterLogin(source, request)
        return result
    }

    fun submitCookies(header: String): Boolean {
        val session = loginUrl?.let { DesktopSourceCookieHeaderParser.parse(header, it) } ?: return false
        return ticket?.complete(session) ?: false
    }
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
            if (!cookieName.matches(name) || value.isBlank() || !names.add(name)) return null
            AuthenticatedCookie(name, value, url.host, true, "/", null, url.isHttps, false)
        }
        return AuthenticatedSession(cookies)
    }
}
