package mihon.desktop.ui.browse

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.source.CatalogueSource
import mihon.desktop.network.DesktopBrowserLoginTicket
import mihon.desktop.network.DesktopSourceLoginAdapter
import mihon.desktop.network.DesktopSourceLoginEndpoint
import mihon.desktop.network.DesktopSourceLoginSessionFactory
import tachiyomi.domain.source.service.AuthenticatedSession
import tachiyomi.domain.source.service.SourceLoginState
import tachiyomi.i18n.MR

data class DesktopSourceLoginCopy(
    val title: String,
    val description: String,
    val cookieHeaderLabel: String,
    val cookieHeaderPlaceholder: String,
    val invalidHeader: String,
    val browserUnavailable: String,
    val timedOut: String,
    val invalidCookies: String,
    val commitFailed: String,
    val submit: String,
    val cancel: String,
    val close: String,
) {
    fun feedback(value: DesktopSourceLoginFeedback): String = when (value) {
        DesktopSourceLoginFeedback.InvalidHeader -> invalidHeader
        DesktopSourceLoginFeedback.BrowserUnavailable -> browserUnavailable
        DesktopSourceLoginFeedback.TimedOut -> timedOut
        DesktopSourceLoginFeedback.InvalidCookies -> invalidCookies
        DesktopSourceLoginFeedback.CommitFailed -> commitFailed
    }
}

internal fun desktopSourceLoginCopy(text: (StringResource) -> String) = DesktopSourceLoginCopy(
    text(MR.strings.login),
    text(MR.strings.desktop_source_login_description),
    text(MR.strings.desktop_source_login_cookie_header),
    text(MR.strings.desktop_source_login_cookie_placeholder),
    text(MR.strings.desktop_source_login_invalid_header),
    text(MR.strings.desktop_source_login_browser_unavailable),
    text(MR.strings.desktop_source_login_timed_out),
    text(MR.strings.desktop_source_login_invalid_cookies),
    text(MR.strings.desktop_source_login_commit_failed),
    text(MR.strings.action_ok),
    text(MR.strings.action_cancel),
    text(MR.strings.action_close),
)

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
        onAttemptAccepted: (DesktopSourceLoginAttempt) -> Unit = {},
    ): SourceLoginState {
        val request = intent.request
        val endpoint = DesktopSourceLoginAdapter.parseEndpoint(intent.url) ?: return SourceLoginState.BrowserUnavailable
        if (!claim(attempt, endpoint, request)) return SourceLoginState.BrowserUnavailable
        return try {
            onAttemptAccepted(attempt)
            val login = factory.create(
                onTicketRegistered = { ticket -> register(attempt, ticket) },
                onTicketTerminal = { ticket -> markTerminal(attempt, ticket) },
            )
            val result = login.login(DesktopSourceLoginAdapter.request(endpoint, timeoutMillis))
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
        val session = DesktopSourceCookieHeaderParser.parse(header, snapshot.endpoint) ?: return false
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

    private fun claim(
        attempt: DesktopSourceLoginAttempt,
        endpoint: DesktopSourceLoginEndpoint,
        request: tachiyomi.domain.source.service.SourcePageRequest,
    ): Boolean =
        synchronized(lock) {
            if (activeAttempt != null) return@synchronized false
            activeAttempt = ActiveAttempt(attempt, endpoint, request)
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
        val endpoint: DesktopSourceLoginEndpoint,
        val request: tachiyomi.domain.source.service.SourcePageRequest,
        val ticket: DesktopBrowserLoginTicket? = null,
        val cancelled: Boolean = false,
        val terminal: Boolean = false,
    )
}

class DesktopSourceLoginAttempt internal constructor() {
    override fun toString(): String = "DesktopSourceLoginAttempt(<opaque>)"
}

enum class DesktopSourceLoginFeedback {
    InvalidHeader,
    BrowserUnavailable,
    TimedOut,
    InvalidCookies,
    CommitFailed,
}

data class DesktopSourceLoginUiState(
    val attempt: DesktopSourceLoginAttempt,
    val host: String,
    val cookieHeader: String = "",
    val feedback: DesktopSourceLoginFeedback? = null,
    val terminal: Boolean = false,
) {
    override fun toString(): String =
        "DesktopSourceLoginUiState(attempt=$attempt, host=$host, cookieHeader=<redacted>, feedback=$feedback, terminal=$terminal)"
}

class DesktopSourceLoginUiActions(
    private val submitCookies: (DesktopSourceLoginAttempt, String) -> Boolean,
    private val cancel: (DesktopSourceLoginAttempt) -> Boolean,
) {
    fun open(attempt: DesktopSourceLoginAttempt, url: String): DesktopSourceLoginUiState =
        DesktopSourceLoginUiState(attempt, DesktopSourceLoginAdapter.parseEndpoint(url)?.host.orEmpty())

    fun editHeader(state: DesktopSourceLoginUiState, header: String): DesktopSourceLoginUiState =
        state.copy(cookieHeader = header, feedback = null)

    fun submit(state: DesktopSourceLoginUiState): DesktopSourceLoginUiState =
        if (submitCookies(state.attempt, state.cookieHeader)) {
            state.copy(feedback = null)
        } else {
            state.copy(feedback = DesktopSourceLoginFeedback.InvalidHeader)
        }

    fun cancel(state: DesktopSourceLoginUiState): DesktopSourceLoginUiState? =
        if (cancel(state.attempt)) null else state

    fun complete(
        state: DesktopSourceLoginUiState,
        completedAttempt: DesktopSourceLoginAttempt,
        result: SourceLoginState?,
    ): DesktopSourceLoginUiState? {
        if (state.attempt !== completedAttempt) return state
        return when (result) {
            is SourceLoginState.Authenticated, SourceLoginState.Cancelled -> null
            SourceLoginState.BrowserUnavailable -> state.terminal(DesktopSourceLoginFeedback.BrowserUnavailable)
            SourceLoginState.TimedOut -> state.terminal(DesktopSourceLoginFeedback.TimedOut)
            is SourceLoginState.InvalidCookies -> state.terminal(DesktopSourceLoginFeedback.InvalidCookies)
            SourceLoginState.CommitFailed -> state.terminal(DesktopSourceLoginFeedback.CommitFailed)
            else -> state
        }
    }

    private fun DesktopSourceLoginUiState.terminal(feedback: DesktopSourceLoginFeedback) =
        copy(feedback = feedback, terminal = true)
}

internal object DesktopSourceCookieHeaderParser {
    fun parse(header: String, endpoint: DesktopSourceLoginEndpoint): AuthenticatedSession? =
        DesktopSourceLoginAdapter.parseCookieHeader(header, endpoint)
}
