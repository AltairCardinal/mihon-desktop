package mihon.domain.sync.auth

import kotlinx.coroutines.CancellationException

/** Public GitHub device-flow endpoints. A client id is supplied by the application. */
data class GitHubAuthEndpoints(
    val deviceCodeUrl: String = "https://github.com/login/device/code",
    val accessTokenUrl: String = "https://github.com/login/oauth/access_token",
    val apiBaseUrl: String = "https://api.github.com",
)

data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
) {
    override fun toString(): String =
        "GitHubDeviceCode(userCode=<redacted>, verificationUri=<redacted>, expiresInSeconds=$expiresInSeconds, intervalSeconds=$intervalSeconds)"
}

data class GitHubAccessToken(
    val accessToken: String,
    val refreshToken: String?,
    val tokenType: String,
    val scope: Set<String>,
    val accessTokenExpiresAtMillis: Long?,
    val refreshTokenExpiresAtMillis: Long?,
) {
    override fun toString(): String =
        "GitHubAccessToken(tokenType=$tokenType, scope=$scope, accessTokenExpiresAtMillis=$accessTokenExpiresAtMillis, refreshTokenExpiresAtMillis=$refreshTokenExpiresAtMillis, accessToken=<redacted>, refreshToken=<redacted>)"
}

data class GitHubStoredCredential(
    val credential: GitHubAccessToken,
    val revision: Long,
) {
    override fun toString(): String = "GitHubStoredCredential(credential=<redacted>, revision=$revision)"
}

enum class GitHubAuthFailureReason {
    HTTP,
    MALFORMED_RESPONSE,
    ACCESS_DENIED,
    PERMISSION_DENIED,
    EXPIRED,
    RATE_LIMITED,
    REVOKED,
}

data class GitHubAuthFailure(
    val reason: GitHubAuthFailureReason,
    val message: String,
    val retryable: Boolean,
    val retryAfterMillis: Long? = null,
)

/** A safe, actionable refresh failure; never retains the remote payload or an unsafe cause. */
class GitHubAuthException(val failure: GitHubAuthFailure) : IllegalStateException(failure.message)

sealed interface GitHubDeviceAuthResult {
    data class Authorized(val token: GitHubAccessToken) : GitHubDeviceAuthResult
    data class Failed(val failure: GitHubAuthFailure) : GitHubDeviceAuthResult
}

fun interface GitHubAuthWaiter {
    suspend fun waitMillis(millis: Long)
}

interface GitHubCredentialStore {
    suspend fun read(): GitHubStoredCredential?
    suspend fun replace(expectedRevision: Long?, value: GitHubAccessToken): GitHubStoredCredential
}

/**
 * Implementations must let [CancellationException] pass through unchanged. The exception is
 * deliberately not represented as a failed authorization result.
 */
interface GitHubAuthPort {
    suspend fun authorize(
        clientId: String,
        onDeviceCode: suspend (GitHubDeviceCode) -> Unit,
    ): GitHubDeviceAuthResult

    suspend fun refresh(
        clientId: String,
        current: GitHubStoredCredential,
    ): Result<GitHubStoredCredential>
}
