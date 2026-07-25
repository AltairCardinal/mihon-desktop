package mihon.desktop.tracking

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID

enum class DesktopTrackerOAuthProvider(
    val trackerId: Long,
    val callbackHost: String,
    internal val credentialParameter: String,
) {
    MY_ANIME_LIST(1L, "myanimelist-auth", "code"),
    ANI_LIST(2L, "anilist-auth", "access_token"),
    SHIKIMORI(4L, "shikimori-auth", "code"),
    BANGUMI(5L, "bangumi-auth", "code"),
    ;

    val redirectUri: String get() = "mihon://$callbackHost"

    companion object {
        fun fromTrackerId(id: Long) = entries.firstOrNull { it.trackerId == id }
        internal fun fromCallbackHost(host: String) = entries.firstOrNull { it.callbackHost == host }
    }
}

class DesktopTrackerOAuthCallbackException internal constructor(
    val reason: Reason,
) : IllegalStateException(
    when (reason) {
        Reason.PROVIDER_ERROR -> "OAuth provider rejected the callback"
        Reason.MALFORMED_CALLBACK -> "OAuth provider returned an incomplete callback"
        Reason.CANCELLED -> "OAuth login was cancelled"
    },
) {
    enum class Reason {
        PROVIDER_ERROR,
        MALFORMED_CALLBACK,
        CANCELLED,
    }
}

class DesktopTrackerOAuthCallbackBroker internal constructor(
    private val stateFactory: () -> String = ::secureOAuthState,
) {
    private val lock = Any()
    private var pending: Pending? = null
    private var lastDeliveredProvider: DesktopTrackerOAuthProvider? = null

    fun begin(
        provider: DesktopTrackerOAuthProvider,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): Session {
        require(!timeout.isZero && !timeout.isNegative) { "OAuth callback timeout must be positive" }
        val value = synchronized(lock) {
            check(pending == null) { "Another tracker OAuth login is already pending" }
            val state = stateFactory()
            require(state.isNotBlank()) { "OAuth callback state must not be blank" }
            val value = Pending(
                id = UUID.randomUUID().toString(),
                provider = provider,
                state = state,
                result = CompletableDeferred(),
            )
            pending = value
            value
        }
        return Session(
            broker = this,
            id = value.id,
            provider = value.provider,
            state = value.state,
            result = value.result,
            timeout = timeout,
        )
    }

    fun handle(rawUri: String): HandleResult {
        val uri = runCatching { URI(rawUri) }.getOrNull()
        val host = uri?.host?.lowercase()
        val queryParameters = parseParameters(uri?.rawQuery)
        val fragmentParameters = parseParameters(uri?.rawFragment)
        val parameterNames = queryParameters.keys + fragmentParameters.keys
        val hasPendingSession = synchronized(lock) { pending != null }
        val callbackParameterShape =
            parameterNames.any { it == "code" || it == "access_token" || it == "error" } &&
                ("state" in parameterNames || hasPendingSession)
        val oauthShaped = host?.endsWith("-auth") == true ||
            OAUTH_URI_SHAPE.containsMatchIn(rawUri) ||
            (
                uri?.scheme?.lowercase() == CALLBACK_SCHEME &&
                    callbackParameterShape
                )
        if (!oauthShaped) return HandleResult.NotOAuth
        if (uri == null || uri.scheme?.lowercase() != CALLBACK_SCHEME || host == null) {
            return HandleResult.Consumed(Outcome.REJECTED_HOST)
        }
        val provider = DesktopTrackerOAuthProvider.fromCallbackHost(host)
            ?: return HandleResult.Consumed(Outcome.REJECTED_HOST)
        val parameters = when (provider) {
            DesktopTrackerOAuthProvider.ANI_LIST -> fragmentParameters
            else -> queryParameters
        }
        return synchronized(lock) {
            val current = pending
                ?: return@synchronized HandleResult.Consumed(
                    if (lastDeliveredProvider == provider) Outcome.DUPLICATE else Outcome.STALE,
                )
            if (provider != current.provider) {
                return@synchronized HandleResult.Consumed(Outcome.REJECTED_PROVIDER)
            }
            if (parameters["state"] != current.state) {
                return@synchronized HandleResult.Consumed(Outcome.REJECTED_STATE)
            }
            if (parameters["error"] != null) {
                pending = null
                current.result.completeExceptionally(
                    DesktopTrackerOAuthCallbackException(
                        DesktopTrackerOAuthCallbackException.Reason.PROVIDER_ERROR,
                    ),
                )
                return@synchronized HandleResult.Consumed(Outcome.PROVIDER_ERROR)
            }
            val credential = parameters[provider.credentialParameter].orEmpty()
            if (credential.isBlank()) {
                pending = null
                current.result.completeExceptionally(
                    DesktopTrackerOAuthCallbackException(
                        DesktopTrackerOAuthCallbackException.Reason.MALFORMED_CALLBACK,
                    ),
                )
                return@synchronized HandleResult.Consumed(Outcome.MALFORMED)
            }
            pending = null
            lastDeliveredProvider = provider
            current.result.complete(credential)
            HandleResult.Consumed(Outcome.DELIVERED)
        }
    }

    private fun release(id: String, cancel: Boolean) {
        synchronized(lock) {
            val current = pending?.takeIf { it.id == id } ?: return
            pending = null
            if (cancel) {
                current.result.completeExceptionally(
                    DesktopTrackerOAuthCallbackException(
                        DesktopTrackerOAuthCallbackException.Reason.CANCELLED,
                    ),
                )
            }
        }
    }

    class Session internal constructor(
        private val broker: DesktopTrackerOAuthCallbackBroker,
        private val id: String,
        val provider: DesktopTrackerOAuthProvider,
        val state: String,
        private val result: CompletableDeferred<String>,
        private val timeout: Duration,
    ) : AutoCloseable {
        val redirectUri: String get() = provider.redirectUri

        suspend fun awaitCredential(): String = try {
            withTimeout(timeout.toMillis()) { result.await() }
        } finally {
            broker.release(id, cancel = false)
        }

        override fun close() {
            broker.release(id, cancel = true)
        }
    }

    sealed interface HandleResult {
        val outcome: Outcome?

        data object NotOAuth : HandleResult {
            override val outcome: Outcome? = null
        }

        data class Consumed(override val outcome: Outcome) : HandleResult
    }

    enum class Outcome {
        DELIVERED,
        REJECTED_HOST,
        REJECTED_PROVIDER,
        REJECTED_STATE,
        PROVIDER_ERROR,
        MALFORMED,
        STALE,
        DUPLICATE,
    }

    private class Pending(
        val id: String,
        val provider: DesktopTrackerOAuthProvider,
        val state: String,
        val result: CompletableDeferred<String>,
    )

    companion object {
        private const val CALLBACK_SCHEME = "mihon"
        private val DEFAULT_TIMEOUT: Duration = Duration.ofMinutes(2)
        private val OAUTH_URI_SHAPE = Regex("""(?i)^[a-z][a-z0-9+.-]*://[^/?#]*-auth[^/?#]*(?:[/?#]|$)""")
    }
}

private fun parseParameters(raw: String?): Map<String, String> {
    if (raw.isNullOrEmpty()) return emptyMap()
    return raw.split('&').mapNotNull { field ->
        val separator = field.indexOf('=')
        if (separator < 0) return@mapNotNull null
        decode(field.substring(0, separator)) to decode(field.substring(separator + 1))
    }.toMap()
}

private fun decode(value: String): String = runCatching {
    URLDecoder.decode(value, StandardCharsets.UTF_8)
}.getOrDefault("")

private fun secureOAuthState(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
